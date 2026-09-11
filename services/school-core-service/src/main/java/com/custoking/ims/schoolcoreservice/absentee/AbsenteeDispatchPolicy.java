package com.custoking.ims.schoolcoreservice.absentee;

/**
 * Re-evaluates guardian consent and preferences for one student immediately before a delivery
 * attempt. {@code attendance/V9} says dispatch must re-evaluate or suppress once the queue-time
 * evidence expires; the worker always re-evaluates, on every attempt including retries.
 *
 * <p>Implementations run inside the worker's transaction, which is scoped to the row's school, so
 * their RLS-protected reads resolve to that tenant only.
 */
@FunctionalInterface
public interface AbsenteeDispatchPolicy {

    DispatchDecision evaluate(long schoolId, long studentId, String channel, String sourceEventId);
}
