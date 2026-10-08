package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Tomcat reads, framing, async lifecycle and request isolation; synthetic credentials only. */
class Msg91BroadcastReportHttpIntegrationTest {
    private static final String PATH="/api/v1/notifications/provider-reports/msg91/email";
    private static final String SERVICE="synthetic-service",WEBHOOK="w".repeat(40);
    private static Tomcat tomcat;
    private static Path temporary;
    private static URI uri;
    private static HttpClient client;
    private static BroadcastLiveRepository ledger;
    private static final BlockingQueue<String> seen=new LinkedBlockingQueue<>();
    private static volatile CountDownLatch bodyReceivers;

    @BeforeAll static void start() throws Exception {
        ledger=mock(BroadcastLiveRepository.class);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            seen.add(call.getArgument(1));return true;
        }).when(ledger).report(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString());
        // The send kill switch remains OFF; configured receipts still work.
        var controller=new Msg91BroadcastReportController(SERVICE,
                new LiveBroadcastConfiguration(false,"EMAIL",true,"1","a".repeat(64),WEBHOOK),ledger,new Transactions());
        temporary=Files.createTempDirectory("ims-email-report-tomcat-");tomcat=new Tomcat();tomcat.setBaseDir(temporary.toString());tomcat.setPort(0);
        tomcat.getConnector().setProperty("address","127.0.0.1");tomcat.getConnector().setProperty("connectionTimeout","10000");
        tomcat.getEngine().setBackgroundProcessorDelay(1);
        var context=tomcat.addContext("",temporary.toString());
        var servlet=Tomcat.addServlet(context,"email-reports",new HttpServlet(){
            @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException {
                try {
                    controller.report(request.getHeader("X-Notification-Service-Token"),request.getHeader("X-MSG91-Webhook-Token"),request,response);
                    var receivers=bodyReceivers;if(receivers!=null && request.isAsyncStarted())receivers.countDown();
                } catch(ResponseStatusException failure){response.sendError(failure.getStatusCode().value(),failure.getReason());}
            }
        });
        servlet.setAsyncSupported(true);context.addServletMappingDecoded(PATH,"email-reports");tomcat.start();
        uri=URI.create("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+PATH);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void stop() throws Exception {
        if(client!=null)client.close();if(tomcat!=null){tomcat.stop();tomcat.destroy();}
        if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    @BeforeEach void clear(){seen.clear();clearInvocations(ledger);}
    private static String body(String id){return """
        {"correlationId":"ims%s","providerMessageId":"%s","destination":"guardian@synthetic.invalid",
         "sender":"sender@synthetic.invalid","status":"DELIVERED","occurredAt":"2026-10-08T12:00:00Z"}
        """.formatted("b".repeat(48),id);}
    private static HttpResponse<String> post(byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                .header("X-Notification-Service-Token",SERVICE).header("X-MSG91-Webhook-Token",WEBHOOK)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(String body) throws Exception{return post(body.getBytes(StandardCharsets.UTF_8));}
    private static Socket chunked(String chunk,boolean complete) throws Exception {
        Socket socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",uri.getPort()),2000);socket.setSoTimeout(9000);
        String headers="POST "+PATH+" HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nX-Notification-Service-Token: "+SERVICE+
                "\r\nX-MSG91-Webhook-Token: "+WEBHOOK+"\r\nConnection: close\r\n\r\n";
        byte[] bytes=chunk.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write((Integer.toHexString(bytes.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
        if(complete)socket.getOutputStream().write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();return socket;
    }
    private static String statusLine(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
    }

    @Test void fixedAndUnknownLengthExactly32768BytesAreAcceptedWithOriginalAcknowledgement() throws Exception {
        String valid=body("exact-limit"),padded=valid+" ".repeat(32768-valid.getBytes(StandardCharsets.UTF_8).length);
        var result=post(padded);assertThat(result.statusCode()).isEqualTo(202);assertThat(result.body()).isEqualTo("{\"accepted\":true}");
        assertThat(seen.poll(1,TimeUnit.SECONDS)).isEqualTo("exact-limit");
        try(Socket complete=chunked(padded,true)){assertThat(statusLine(complete)).contains("202");}
        assertThat(seen.poll(1,TimeUnit.SECONDS)).isEqualTo("exact-limit");assertThat(seen).isEmpty();
        verify(ledger,times(2)).report(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void fixedAndUnknownLength32769BytesAreRejectedBeforeParsingIncludingIncompleteFraming() throws Exception {
        assertThat(post(" ".repeat(32769)).statusCode()).isEqualTo(413);
        for(boolean complete:new boolean[]{true,false})try(Socket oversized=chunked(" ".repeat(32769),complete)) {
            assertThat(statusLine(oversized)).contains("413");
        }
        assertThat(seen).isEmpty();verifyNoInteractions(ledger);
    }
    @Test void malformedDuplicateTrailingAndInvalidUtf8BodiesNeverReachLedger() throws Exception {
        String valid=body("never-recorded");
        for(String invalid:List.of("{",valid+" {}",valid+" null",valid+" trailing",
                valid.replace("\"status\":\"DELIVERED\"","\"status\":\"FAILED\",\"status\":\"DELIVERED\""),
                valid.replace("\"providerMessageId\":","\"providerMessageId\":\"wrong\",\"providerMessageI\\u0064\":"))) {
            var response=post(invalid);assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("never-recorded","guardian@",SERVICE,WEBHOOK);
        }
        byte[] malformed=valid.getBytes(StandardCharsets.UTF_8);int offset=valid.indexOf("guardian");malformed[offset]=(byte)0xc3;malformed[offset+1]=(byte)0x28;
        assertThat(post(malformed).statusCode()).isEqualTo(400);
        assertThat(post(valid.getBytes(StandardCharsets.UTF_16)).statusCode()).isEqualTo(400);
        byte[] utf32=valid.getBytes(java.nio.charset.Charset.forName("UTF-32BE"));
        assertThat(post(java.nio.ByteBuffer.allocate(utf32.length+4).putInt(0xFEFF).put(utf32).array()).statusCode()).isEqualTo(400);
        assertThat(seen).isEmpty();verifyNoInteractions(ledger);
    }
    @Test void trickledBodyTimesOutAndLateDataCannotReachFollowingRequest() throws Exception {
        long started=System.nanoTime();
        try(Socket stalled=chunked("{\"correlationId\"",false)) {
            assertThat(statusLine(stalled)).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(8));
            try{stalled.getOutputStream().write("1\r\nX\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));stalled.getOutputStream().flush();}catch(IOException disconnected){}
        }
        assertThat(seen).isEmpty();verifyNoInteractions(ledger);
        assertThat(post(body("after-timeout")).statusCode()).isEqualTo(202);
        assertThat(seen.poll(1,TimeUnit.SECONDS)).isEqualTo("after-timeout");assertThat(seen).isEmpty();
    }
    @Test void onlyTwoActiveEmailBodiesAreAllowedAndDisconnectReleasesBoth() throws Exception {
        bodyReceivers=new CountDownLatch(2);
        try(Socket first=chunked("{",false);Socket second=chunked("{",false)) {
            assertThat(bodyReceivers.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(post(body("capacity-denied")).statusCode()).isEqualTo(503);verifyNoInteractions(ledger);
        } finally {bodyReceivers=null;}
        int status=0;
        for(int attempt=0;attempt<10 && status!=202;attempt++) {
            status=post(body("after-disconnect")).statusCode();if(status!=202)Thread.sleep(100);
        }
        assertThat(status).isEqualTo(202);assertThat(seen.poll(1,TimeUnit.SECONDS)).isEqualTo("after-disconnect");assertThat(seen).isEmpty();
    }
    @Test void persistenceFailureKeepsSanitized503AndReleasesReaderForFollowingReceipt() throws Exception {
        doThrow(new IllegalStateException("guardian@synthetic.invalid "+SERVICE+" "+WEBHOOK)).when(ledger)
                .report(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString());
        try {
            var result=post(body("failed-receipt"));assertThat(result.statusCode()).isEqualTo(503);
            assertThat(result.body()).doesNotContain("guardian@",SERVICE,WEBHOOK,"failed-receipt");
        } finally {
            doAnswer(call->{seen.add(call.getArgument(1));return true;}).when(ledger)
                    .report(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString());
        }
        assertThat(post(body("healthy-receipt")).statusCode()).isEqualTo(202);
        assertThat(seen.poll(1,TimeUnit.SECONDS)).isEqualTo("healthy-receipt");assertThat(seen).isEmpty();
    }
    private static final class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){}
    }
}
