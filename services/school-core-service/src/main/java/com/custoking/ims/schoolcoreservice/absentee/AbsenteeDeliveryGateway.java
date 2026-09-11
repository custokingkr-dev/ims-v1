package com.custoking.ims.schoolcoreservice.absentee;

/**
 * The "real send" boundary. Implementations must not throw for expected failures (peer down, policy
 * suppression, dead-letter) — they return a {@link DeliveryOutcome} so the worker's state handling
 * is uniform and a transient failure is always retried with backoff rather than crashing the tick.
 */
@FunctionalInterface
public interface AbsenteeDeliveryGateway {

    DeliveryOutcome deliver(AbsenteeDeliveryRequest request);
}
