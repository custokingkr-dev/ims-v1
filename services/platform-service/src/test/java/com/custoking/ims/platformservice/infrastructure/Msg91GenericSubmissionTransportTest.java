package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class Msg91GenericSubmissionTransportTest {
    final NotificationDeliveryRequest request=new NotificationDeliveryRequest("one","fee-reminder.v1","SMS","GUARDIAN","one","{\"destination\":\"919999999999\"}");
    final String accepted="{\"type\":\"success\",\"message\":\"5762846b4f8d285d378b4567\"}";
    String body() { return "{\"template_id\":\"synthetic\",\"CRQID\":\""+NotificationSubmissionResult.correlationId("one")+"\",\"recipients\":[{\"mobiles\":\"919999999999\"}]}"; }
    @Test void exactDocumentedAcceptanceIsBoundToRequestAndCorrelationNotDelivery() {
        var calls=new AtomicInteger();
        var transport=new Msg91GenericSubmissionTransport(wire->{
            calls.incrementAndGet();assertThat(wire.uri()).isEqualTo(Msg91GenericSubmissionTransport.SMS_ENDPOINT);
            assertThat(wire.timeout()).contains(Duration.ofSeconds(10));return new Msg91BroadcastLiveProvider.Reply(200,accepted);
        });
        var result=transport.submit(request,body(),"synthetic_auth_key");
        assertThat(result.status()).isEqualTo(NotificationSubmissionResult.Status.ACCEPTED);
        assertThat(result.providerRequestId()).isEqualTo("5762846b4f8d285d378b4567");assertThat(result.binds(request)).isTrue();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void narrowDocumentedRejectionAndEveryAmbiguousResponseAreDistinct() {
        var codec=new Msg91SubmissionCodec();
        assertThat(codec.decode(request,200,"{\"type\":\"error\",\"message\":\"flow id missing\"}").status()).isEqualTo(NotificationSubmissionResult.Status.REJECTED);
        for(int http:new int[]{408,409,425,429,500,502,503,301,401})
            assertThat(codec.decode(request,http,accepted).status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        for(String body:new String[]{"{","{}","{\"type\":\"success\",\"message\":\"queued\"}","{\"type\":\"success\"}",
            "{\"type\":\"error\",\"message\":\"unknown error\"}",accepted.substring(0,accepted.length()-1)+",\"CRQID\":\"different\"}",
            accepted.substring(0,accepted.length()-1)+",\"errors\":[\"partial acceptance\"]}","x".repeat(16_385)})
            assertThat(codec.decode(request,200,body).status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
    }
    @Test void requestRoutingTamperCannotReachTransport() {
        var calls=new AtomicInteger();var transport=new Msg91GenericSubmissionTransport(wire->{calls.incrementAndGet();return new Msg91BroadcastLiveProvider.Reply(200,accepted);});
        assertThat(transport.submit(request,body().replace("919999999999","918888888888"),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        assertThat(transport.submit(request,body().replace(NotificationSubmissionResult.correlationId("one"),"spoofed"),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        assertThat(calls.get()).isZero();
    }
    @Test void duplicateOrTrailingAcknowledgementsNeverBecomeAcceptance() {
        var codec=new Msg91SubmissionCodec();
        for(String response:new String[]{
            "{\"type\":\"error\",\"type\":\"success\",\"message\":\"5762846b4f8d285d378b4567\"}",
            "{\"type\":\"success\",\"message\":\"uncertain\",\"mess\\u0061ge\":\"5762846b4f8d285d378b4567\"}",
            accepted+" {}",accepted+" null",accepted+" trailing",
            "{\"type\":\"error\",\"type\":\"error\",\"message\":\"flow id missing\"}"}) {
            var result=codec.decode(request,200,response);
            assertThat(result.status()).as("ambiguous acknowledgement").isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
            assertThat(result.providerRequestId()).isNull();
        }
    }
    @Test void ambiguousPreparedRoutingCannotReachTransport() {
        var calls=new AtomicInteger();
        var transport=new Msg91GenericSubmissionTransport(wire->{calls.incrementAndGet();return new Msg91BroadcastLiveProvider.Reply(200,accepted);});
        String correlation=NotificationSubmissionResult.correlationId("one");
        for(String prepared:new String[]{
            body().replace("\"CRQID\":\""+correlation+"\"","\"CRQID\":\"other\",\"CRQID\":\""+correlation+"\""),
            body().replace("\"mobiles\":\"919999999999\"","\"mobiles\":\"918888888888\",\"mobil\\u0065s\":\"919999999999\""),
            body()+" {}",body()+" null"}) {
            assertThat(transport.submit(request,prepared,"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        }
        var ambiguousRequest=new NotificationDeliveryRequest(request.eventId(),request.template(),request.channel(),
            request.recipientType(),request.recipientId(),"{\"destination\":\"918888888888\",\"destination\":\"919999999999\"}");
        assertThat(transport.submit(ambiguousRequest,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
        assertThat(calls.get()).isZero();
    }
    @Test void noncanonicalPersistedDestinationsCannotBecomeAcceptedReservations() {
        assertAll(java.util.stream.Stream.of(
            "{\"destination\":919999999999}",
            "{\"destination\":\"+919999999999\"}",
            "{\"destination\":\"91 9999-999999\"}",
            "{\"destination\":\"91x9999999999\"}")
            .map(payload -> () -> assertRoutingRejected(payload,body())));
    }
    @Test void routingAliasesCannotEnterTheCanonicalSubmissionContract() {
        assertAll(java.util.stream.Stream.of("mobile","phone","to","recipientMobile")
            .flatMap(alias -> java.util.stream.Stream.of("null","\"919999999999\"","\"918888888888\"")
                .map(value -> () -> assertRoutingRejected(
                    "{\"destination\":\"919999999999\",\""+alias+"\":"+value+"}",body()))));
    }
    @Test void nonnullPassthroughCannotEnterTheCanonicalSubmissionContract() {
        assertAll(java.util.stream.Stream.of("{}","false","\"ignored\"","[]")
            .map(value -> () -> assertRoutingRejected(
                "{\"destination\":\"919999999999\",\"msg91Body\":"+value+"}",body())));
    }
    @Test void wireRoutingRequiresAnExactStringRecipient() {
        assertRoutingRejected(request.payload(),body().replace(
            "\"mobiles\":\"919999999999\"","\"mobiles\":919999999999"));
    }
    @Test void canonicalBoundaryDestinationsAndNullPassthroughRemainAdmitted() {
        for(String destination:new String[]{"9199999999","919999999999999"}) {
            var canonical=new NotificationDeliveryRequest(request.eventId(),request.template(),request.channel(),
                request.recipientType(),request.recipientId(),
                "{\"destination\":\""+destination+"\",\"msg91Body\":null}");
            var calls=new AtomicInteger();
            var transport=new Msg91GenericSubmissionTransport(wire->{calls.incrementAndGet();return new Msg91BroadcastLiveProvider.Reply(200,accepted);});
            var result=transport.submit(canonical,body().replace("919999999999",destination),"synthetic_auth_key");
            assertThat(result.status()).isEqualTo(NotificationSubmissionResult.Status.ACCEPTED);
            assertThat(result.binds(canonical)).isTrue();assertThat(calls.get()).isEqualTo(1);
        }
    }
    private void assertRoutingRejected(String payload,String prepared) {
        var canonical=new NotificationDeliveryRequest(request.eventId(),request.template(),request.channel(),
            request.recipientType(),request.recipientId(),payload);
        var calls=new AtomicInteger();
        var transport=new Msg91GenericSubmissionTransport(wire->{calls.incrementAndGet();return new Msg91BroadcastLiveProvider.Reply(200,accepted);});
        var result=transport.submit(canonical,prepared,"synthetic_auth_key");
        assertAll(() -> assertThat(result.status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN),
            () -> assertThat(calls.get()).isZero());
    }
    @Test void realFakeHttpAcceptanceMalformedAndRejectionAreDecodedWithoutRetries() throws Exception {
        try(var fixture=new FakeHttp(0,200,accepted)) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.ACCEPTED);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
        try(var fixture=new FakeHttp(0,200,"malformed")) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
        try(var fixture=new FakeHttp(0,400,"{\"type\":\"error\",\"message\":\"flow id missing\"}")) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.REJECTED);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }
    @Test void realFakeHttpRespondingAfterServerAcceptedRequestIsUnknownAndNotRetried() throws Exception {
        try(var fixture=new FakeHttp(700,200,accepted)) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
            assertThat(fixture.received.await(2,TimeUnit.SECONDS)).isTrue();assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }
    @Test void realFakeHttpOversizeAndRedirectAreUnknownAndNeverFollowed() throws Exception {
        try(var fixture=new FakeHttp(0,200,"x".repeat(16_385))) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
        try(var fixture=new FakeHttp(0,302,accepted)) {
            assertThat(fixture.transport().submit(request,body(),"synthetic_auth_key").status()).isEqualTo(NotificationSubmissionResult.Status.UNKNOWN);
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }
    final class FakeHttp implements AutoCloseable {
        final HttpServer server;final ExecutorService threads=Executors.newFixedThreadPool(1);
        final AtomicInteger calls=new AtomicInteger();final CountDownLatch received=new CountDownLatch(1);
        final long delay;
        FakeHttp(long delay,int status,String reply) throws Exception {
            this.delay=delay;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
            server.createContext("/",exchange->{
                calls.incrementAndGet();exchange.getRequestBody().readNBytes(65_537);received.countDown();
                try { if(delay>0)Thread.sleep(delay);byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unexpected");
                    exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                finally { exchange.close(); }
            });server.start();
        }
        Msg91GenericSubmissionTransport transport() {
            var actual=new Msg91BroadcastLiveProvider.SingleRequestTransport();
            return new Msg91GenericSubmissionTransport(wire->actual.post(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                .timeout(delay>0?Duration.ofMillis(200):Duration.ofSeconds(3))
                .POST(wire.bodyPublisher().orElseThrow()).build()));
        }
        public void close() {server.stop(0);threads.shutdownNow();}
    }
}
