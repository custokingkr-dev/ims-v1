package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import java.util.List;
import java.util.Map;

/**
 * What the gates of one stage look at (design §3.4). The service loads them under the order's lock; the design file
 * count is fetched from school-core before the lock is taken and the gates are checked again under it.
 *
 * @param data             the stage's saved data (fields and checklist index lists; the sign-off record may be present)
 * @param signedOff        the stage's sign-off is recorded and still valid ({@link StepDataRules#signedOff})
 * @param designState      the order's design state: {@code NOT_SENT}, {@code SENT}, {@code CHANGES_REQUESTED} or
 *                         {@code APPROVED}
 * @param approvedCdrLink  the CDR link the Head Approval bound to, or null
 * @param currentCdrLink   the CDR link saved in CDR Conversion now, or null
 * @param designFileCount  current design files of the order
 * @param vendorSelections the order's vendor selections, category code to vendor ids
 * @param orderedCount     the quantity placed on the order
 * @param categoryNames    vendor category code to its current name, for messages; codes missing here use the SOP name
 */
public record GateFacts(Map<String, Object> data, boolean signedOff, String designState, String approvedCdrLink,
                        String currentCdrLink, int designFileCount, Map<String, List<Long>> vendorSelections, long orderedCount,
                        Map<String, String> categoryNames) {

    public GateFacts {
        data = data == null ? Map.of() : data;
        vendorSelections = vendorSelections == null ? Map.of() : vendorSelections;
        categoryNames = categoryNames == null ? Map.of() : categoryNames;
    }

    /** The facts of design §3.4; category names default to the SOP names. */
    public GateFacts(Map<String, Object> data, boolean signedOff, String designState, String approvedCdrLink, String currentCdrLink,
                     int designFileCount, Map<String, List<Long>> vendorSelections, long orderedCount) {
        this(data, signedOff, designState, approvedCdrLink, currentCdrLink, designFileCount, vendorSelections, orderedCount, Map.of());
    }

    /** Facts for a stage with only saved data to look at (no design, vendors or counts). */
    public static GateFacts ofData(Map<String, Object> data) {
        return new GateFacts(data, StepDataRules.signedOff(data), null, null, null, 0, Map.of(), 0);
    }
}
