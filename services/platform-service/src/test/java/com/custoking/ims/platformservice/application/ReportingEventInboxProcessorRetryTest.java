package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.projection.ReportingEventProjector;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportingEventInboxProcessorRetryTest {
    @Test
    void projectorFailure_isPersistedForDurableRetry() {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
        ReportingEventProjector projector = mock(ReportingEventProjector.class);
        var event = row("retry-event-1");
        when(projector.handledEventTypes()).thenReturn(Set.of("student.upserted.v1"));
        when(projector.feedWorthy()).thenReturn(false);
        when(inbox.findReceivedForProjection(50)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("transient projection failure")).when(projector).project(event);

        int processed = new ReportingEventInboxProcessor(inbox, commands, List.of(projector), 50)
                .processBatch();

        assertThat(processed).isZero();
        verify(inbox).markFailed("retry-event-1", "transient projection failure");
    }

    @Test
    void reclaimedFailedEvent_isMarkedProcessedAfterSuccessfulRetry() {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
        ReportingEventProjector projector = mock(ReportingEventProjector.class);
        var event = row("retry-event-2");
        when(projector.handledEventTypes()).thenReturn(Set.of("student.upserted.v1"));
        when(projector.feedWorthy()).thenReturn(false);
        when(inbox.findReceivedForProjection(50)).thenReturn(List.of(event));

        int processed = new ReportingEventInboxProcessor(inbox, commands, List.of(projector), 50)
                .processBatch();

        assertThat(processed).isEqualTo(1);
        verify(projector).project(event);
        verify(inbox).markProcessed("retry-event-2");
    }

    private ReportingEventInboxRepository.ReportingEventInboxProjectionRow row(String eventId) {
        return new ReportingEventInboxRepository.ReportingEventInboxProjectionRow(
                eventId, "student.upserted.v1", "Student", "student-1", 900000000L, 1L,
                OffsetDateTime.now(), OffsetDateTime.now(), "{}", null, null);
    }
}
