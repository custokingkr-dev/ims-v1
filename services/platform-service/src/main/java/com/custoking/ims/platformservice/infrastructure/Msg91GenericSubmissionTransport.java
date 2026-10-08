package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.NotificationDeliveryRequest;
import com.custoking.ims.platformservice.application.NotificationSubmissionResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Dormant fixed-endpoint single-use submission path. Production entry remains live-disabled. */
final class Msg91GenericSubmissionTransport {
    static final URI SMS_ENDPOINT=URI.create("https://control.msg91.com/api/v5/flow");
    private final Msg91BroadcastLiveProvider.Transport transport;
    private final Msg91SubmissionCodec codec;
    private final Semaphore capacity=new Semaphore(2);
    Msg91GenericSubmissionTransport() { this(new Msg91BroadcastLiveProvider.SingleRequestTransport()); }
    // Offline transport injection cannot remove the public provider's live-disable guard.
    Msg91GenericSubmissionTransport(Msg91BroadcastLiveProvider.Transport transport) {
        this.transport=transport;this.codec=new Msg91SubmissionCodec();
    }
    NotificationSubmissionResult submit(NotificationDeliveryRequest request,String body,String authKey) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Provider submission cannot run in a transaction");
        if(!"SMS".equals(request.channel())) return Msg91SubmissionCodec.unknown(request,"PROVIDER_CHANNEL_RECEIPT_UNVERIFIED");
        if(body==null || body.getBytes(StandardCharsets.UTF_8).length>65_536 || authKey==null || !authKey.matches("[A-Za-z0-9_-]{16,256}"))
            return Msg91SubmissionCodec.unknown(request,"PROVIDER_PREPARATION_INVALID");
        try {
            var json=Msg91WireJson.read(body);var payload=Msg91WireJson.read(request.payload());
            String destination=payload.path("destination").asString("").replaceAll("[^0-9]","");
            if(!json.path("CRQID").isString() || !NotificationSubmissionResult.correlationId(request.eventId()).equals(json.path("CRQID").asString())
                || !json.path("recipients").isArray() || json.path("recipients").size()!=1
                || !destination.matches("[0-9]{10,15}")
                || !destination.equals(json.path("recipients").path(0).path("mobiles").asString("")))
                return Msg91SubmissionCodec.unknown(request,"PROVIDER_PREPARATION_BINDING_INVALID");
        } catch(RuntimeException malformed) { return Msg91SubmissionCodec.unknown(request,"PROVIDER_PREPARATION_INVALID"); }
        if(!capacity.tryAcquire()) return Msg91SubmissionCodec.unknown(request,"PROVIDER_CAPACITY_UNAVAILABLE");
        try {
            HttpRequest wire=HttpRequest.newBuilder(SMS_ENDPOINT).timeout(Duration.ofSeconds(10))
                .header("accept","application/json").header("content-type","application/json").header("authkey",authKey)
                .POST(Msg91BroadcastLiveProvider.singleUseBody(body)).build();
            var response=transport.post(wire);
            return response==null?Msg91SubmissionCodec.unknown(request,"PROVIDER_RESPONSE_INVALID"):
                codec.decode(request,response.statusCode(),response.body());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();return Msg91SubmissionCodec.unknown(request,"PROVIDER_INTERRUPTED");
        } catch(Exception uncertain) { return Msg91SubmissionCodec.unknown(request,"PROVIDER_RESULT_UNCONFIRMED"); }
        finally { capacity.release(); }
    }
}
