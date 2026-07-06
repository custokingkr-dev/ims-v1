INSERT INTO firefighting.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'FirefightingRequestUpserted:' || ffr.code,
       'firefighting-request.upserted.v1',
       'FirefightingRequest',
       ffr.code,
       ffr.school_id,
       jsonb_build_object(
           'code', ffr.code,
           'title', ffr.title,
           'category', ffr.category,
           'urgency', ffr.urgency,
           'status', ffr.status,
           'estimatedBudget', ffr.estimated_budget,
           'schoolId', ffr.school_id,
           'winnerVendor', ffr.winner_vendor,
           'winnerAmount', ffr.winner_amount,
           'createdAt', ffr.created_at,
           'bursarApprovedAt', ffr.bursar_approved_at,
           'principalApprovedAt', ffr.principal_approved_at,
           'rejectedReason', ffr.rejected_reason,
           'vendorPaidAt', ffr.vendor_paid_at,
           'vendorPaidBy', ffr.vendor_paid_by,
           'vendorPaymentNotes', ffr.vendor_payment_notes)
FROM firefighting.firefighting_requests ffr;
