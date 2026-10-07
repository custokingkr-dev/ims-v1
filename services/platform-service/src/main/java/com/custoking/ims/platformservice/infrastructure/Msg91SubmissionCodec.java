package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.NotificationDeliveryRequest;
import com.custoking.ims.platformservice.application.NotificationSubmissionResult;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Narrow documented SMS FLOW response, never a delivery report or vendor deduplication promise. */
final class Msg91SubmissionCodec {
    private final ObjectMapper mapper;
    Msg91SubmissionCodec(ObjectMapper mapper) { this.mapper=mapper; }
    NotificationSubmissionResult decode(NotificationDeliveryRequest request,int http,String body) {
        if(body==null || body.getBytes(StandardCharsets.UTF_8).length>16_384)
            return unknown(request,"PROVIDER_RESPONSE_INVALID");
        if(!"SMS".equals(request.channel())) return unknown(request,"PROVIDER_CHANNEL_RECEIPT_UNVERIFIED");
        if(http!=200 && http!=400) return unknown(request,"PROVIDER_HTTP_UNCONFIRMED");
        try {
            var response=mapper.readTree(body);
            if(!response.isObject() || response.size()<2 || response.size()>3
                || !response.path("type").isString() || !response.path("message").isString())
                return unknown(request,"PROVIDER_RESPONSE_INVALID");
            for(var field:response.properties()) if(!Set.of("type","message","CRQID").contains(field.getKey()))
                return unknown(request,"PROVIDER_RESPONSE_UNCONFIRMED");
            if(response.has("CRQID") && (!response.path("CRQID").isString()
                || !NotificationSubmissionResult.correlationId(request.eventId()).equals(response.path("CRQID").asString())))
                return unknown(request,"PROVIDER_CORRELATION_MISMATCH");
            String type=response.path("type").asString(),message=response.path("message").asString();
            if(http==200 && "success".equals(type) && message.matches("[0-9a-fA-F]{24}"))
                return NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.ACCEPTED,message.toLowerCase(java.util.Locale.ROOT),"PROVIDER_REQUEST_ACCEPTED");
            // This exact no-request error is published by MSG91. Other errors may describe
            // partial/uncertain processing and cannot be classified as definitive rejection.
            if("error".equals(type) && "flow id missing".equals(message))
                return NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.REJECTED,null,"PROVIDER_FLOW_ID_MISSING");
            return unknown(request,"PROVIDER_RESPONSE_UNCONFIRMED");
        } catch(RuntimeException malformed) { return unknown(request,"PROVIDER_RESPONSE_INVALID"); }
    }
    static NotificationSubmissionResult unknown(NotificationDeliveryRequest request,String reason) {
        return NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.UNKNOWN,null,reason);
    }
}
