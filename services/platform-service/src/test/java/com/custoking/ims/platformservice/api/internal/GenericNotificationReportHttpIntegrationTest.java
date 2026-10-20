package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.GenericNotificationReport;
import com.custoking.ims.platformservice.application.GenericNotificationReportService;
import com.custoking.ims.platformservice.application.NotificationSubmissionResult;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Tomcat nonblocking lifecycle, HTTP framing and timeout/request isolation. */
class GenericNotificationReportHttpIntegrationTest {
    private static final String PATH="/api/v1/internal/notifications/reports/reconcile",TOKEN="r".repeat(40);
    static Tomcat tomcat;static Path temporary;static URI uri;static HttpClient client;
    static GenericNotificationReportService service;
    static BlockingQueue<GenericNotificationReport> seen=new LinkedBlockingQueue<>();
    static volatile CountDownLatch bodyReceivers;
    @BeforeAll static void start() throws Exception {
        service=mock(GenericNotificationReportService.class);
        doAnswer(call->{seen.add(call.getArgument(0));return null;}).when(service).reconcile(any(),any());
        var controller=new GenericNotificationReportController(service,id->Optional.of("reports@synthetic.iam.gserviceaccount.com"),true,
                TOKEN,"s".repeat(40),"p".repeat(40),"reports@synthetic.iam.gserviceaccount.com","gateway@synthetic.iam.gserviceaccount.com");
        temporary=Files.createTempDirectory("ims-report-tomcat-");tomcat=new Tomcat();tomcat.setBaseDir(temporary.toString());tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");tomcat.getConnector().setProperty("connectionTimeout","10000");
        tomcat.getEngine().setBackgroundProcessorDelay(1);
        var context=tomcat.addContext("",temporary.toString());
        var servlet=Tomcat.addServlet(context,"reports",new HttpServlet(){@Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException {
            try{controller.reconcile(request.getHeader("Authorization"),request.getHeader("X-Notification-Report-Token"),request,response);
                var receivers=bodyReceivers;if(receivers!=null && request.isAsyncStarted())receivers.countDown();}
            catch(ResponseStatusException failure){response.setStatus(failure.getStatusCode().value());response.setContentType("application/json");response.getWriter().write("{\"error\":\"Report unavailable\"}");}
        }});servlet.setAsyncSupported(true);context.addServletMappingDecoded(PATH,"reports");tomcat.start();
        uri=URI.create("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+PATH);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void stop() throws Exception {
        if(client!=null)client.close();if(tomcat!=null){tomcat.stop();tomcat.destroy();}
        if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    @BeforeEach void clear(){seen.clear();clearInvocations(service);}
    private static String body(String event){return """
        {"version":1,"schoolId":10,"eventId":"%s","requestSha256":"%s","correlationId":"%s",
         "providerRequestId":"5762846b4f8d285d378b4567","status":"DELIVERED","occurredAt":"2026-10-07T12:00:00Z","evidenceSha256":"%s"}
        """.formatted(event,"a".repeat(64),NotificationSubmissionResult.correlationId(event),"b".repeat(64));}
    private static HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                .header("Authorization","Bearer signed-purpose").header("X-Notification-Report-Token",TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static Socket chunked(String chunk) throws Exception {
        Socket socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",uri.getPort()),2000);socket.setSoTimeout(9000);
        String headers="POST "+PATH+" HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nAuthorization: Bearer signed-purpose\r\nX-Notification-Report-Token: "+TOKEN+"\r\nConnection: close\r\n\r\n";
        byte[] bytes=chunk.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write((Integer.toHexString(bytes.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return socket;
    }
    @Test void actualControllerAcceptsOwnedBytesAndRejectsTrailingTokensDuplicateFieldsAndOversize() throws Exception {
        var accepted=post(body("valid-event"));assertThat(accepted.statusCode()).isEqualTo(202);assertThat(accepted.body()).isEqualTo("{\"accepted\":true}");
        assertThat(seen.poll(1,TimeUnit.SECONDS).eventId()).isEqualTo("valid-event");
        for(String invalid:List.of(body("invalid-event")+"{}",body("invalid-event").replace("\"version\":1","\"version\":1,\"version\":2")))
            assertThat(post(invalid).statusCode()).isEqualTo(400);
        assertThat(post(" ".repeat(8193)).statusCode()).isEqualTo(413);
        try(Socket oversized=chunked(" ".repeat(8193))) {
            var line=new BufferedReader(new InputStreamReader(oversized.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertThat(line).contains("413");
        }
        assertThat(seen).isEmpty();verify(service,times(1)).reconcile(any(),any());
    }
    @Test void trickledChunkTimesOutBeforeParseAndLateBodyCannotContaminateFollowingRequest() throws Exception {
        long started=System.nanoTime();
        try(Socket stalled=chunked("{\"version\"")) {
            var line=new BufferedReader(new InputStreamReader(stalled.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertThat(line).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(8));
            // Complete late framing after timeout if the peer still accepts writes; no callback
            // may parse or persist it after AsyncContext completion/recycling.
            try{stalled.getOutputStream().write("1\r\nX\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));stalled.getOutputStream().flush();}catch(IOException closed){}
        }
        assertThat(seen).isEmpty();verifyNoInteractions(service);
        var fresh=post(body("following-request"));assertThat(fresh.statusCode()).isEqualTo(202);
        assertThat(seen.poll(1,TimeUnit.SECONDS).eventId()).isEqualTo("following-request");assertThat(seen).isEmpty();verify(service,times(1)).reconcile(any(),any());
    }
    @Test void persistenceFailureIsSanitizedAndNextRequestCanUseTheSameContainer() throws Exception {
        doThrow(new IllegalStateException("private synthetic binding must not be reflected")).when(service).reconcile(any(),any());
        try {
            var failure=post(body("failed-request"));assertThat(failure.statusCode()).isEqualTo(503);
            assertThat(failure.body()).doesNotContain("private","failed-request","5762846");
        } finally {doAnswer(call->{seen.add(call.getArgument(0));return null;}).when(service).reconcile(any(),any());}
        assertThat(post(body("healthy-after-failure")).statusCode()).isEqualTo(202);
        assertThat(seen.poll(1,TimeUnit.SECONDS).eventId()).isEqualTo("healthy-after-failure");
    }
    @Test void twoActiveBodiesBoundConcurrencyAndDisconnectReleasesOwnedContexts() throws Exception {
        bodyReceivers=new CountDownLatch(2);
        try(Socket first=chunked("{");Socket second=chunked("{")) {
            assertThat(bodyReceivers.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(post(body("capacity-denied")).statusCode()).isEqualTo(503);verifyNoInteractions(service);
        } finally {bodyReceivers=null;}
        int status=0;
        for(int attempt=0;attempt<10 && status!=202;attempt++) {
            status=post(body("after-disconnect")).statusCode();if(status!=202)Thread.sleep(100);
        }
        assertThat(status).isEqualTo(202);assertThat(seen.poll(1,TimeUnit.SECONDS).eventId()).isEqualTo("after-disconnect");
        assertThat(seen).isEmpty();verify(service,times(1)).reconcile(any(),any());
    }
}
