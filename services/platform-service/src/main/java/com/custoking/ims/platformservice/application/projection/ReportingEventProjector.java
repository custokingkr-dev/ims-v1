package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;

import java.util.Set;

/**
 * A pluggable projector for one or more reporting-inbox event types. Each future decoupling
 * sub-project (SP2-SP6) adds exactly one new {@code @Component} implementing this interface —
 * {@link com.custoking.ims.platformservice.application.ReportingEventInboxProcessor} discovers
 * all projector beans and routes events by {@link #handledEventTypes()} without needing any
 * edits to a shared switch.
 */
public interface ReportingEventProjector {

    /**
     * The reporting-event-envelope {@code eventType} values this projector handles.
     */
    Set<String> handledEventTypes();

    /**
     * Whether events of this projector's handled types should also create a
     * {@code command_center_feed} row (in addition to being projected).
     */
    boolean feedWorthy();

    /**
     * Project the given inbox event into this projector's read model(s).
     */
    void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event);
}
