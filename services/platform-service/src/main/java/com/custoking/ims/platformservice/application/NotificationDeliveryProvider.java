package com.custoking.ims.platformservice.application;

public interface NotificationDeliveryProvider {

    void deliver(NotificationDeliveryRequest request);

    /** Backward-compatible adapters must never infer acceptance from a void return. */
    default NotificationSubmissionResult submit(NotificationDeliveryRequest request) {
        try { deliver(request); }
        catch(RuntimeException uncertain) {
            return NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.UNKNOWN,null,"PROVIDER_RESULT_UNCONFIRMED");
        }
        return NotificationSubmissionResult.of(request,NotificationSubmissionResult.Status.UNKNOWN,null,"PROVIDER_RECEIPT_UNAVAILABLE");
    }
}
