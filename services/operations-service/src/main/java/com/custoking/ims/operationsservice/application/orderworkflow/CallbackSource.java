package com.custoking.ims.operationsservice.application.orderworkflow;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The callbacks an order still owes (design D39), for its Upcoming actions. The call log service implements it.
 */
public interface CallbackSource {

    /**
     * The open callbacks of one order: per contacted entity, the latest "Callback Requested" call, when no later call to
     * that entity exists. Ordered by {@code nextFollowUpAt}, earliest first.
     */
    List<OpenCallback> openCallbacks(long instanceId);

    /**
     * @param callId         the call log entry that asked for the callback
     * @param contacted      the SOP entity: {@code SCHOOL}, {@code DESIGNER}, {@code PRINTING_PRESS},
     *                       {@code MANUFACTURING_FACTORY} or {@code INTERNAL_APPROVER}
     * @param contactLabel   who was called (a contact, vendor or designer name, or typed), or null
     * @param stepOrder      the stage the call was logged on, or null
     * @param nextFollowUpAt when to call back (required for Callback Requested)
     */
    public record OpenCallback(long callId, String contacted, String contactLabel, Integer stepOrder, OffsetDateTime nextFollowUpAt) {
        public OpenCallback {
            if (contacted == null || contacted.isBlank()) throw new IllegalArgumentException("A callback needs the contacted entity");
        }
    }
}
