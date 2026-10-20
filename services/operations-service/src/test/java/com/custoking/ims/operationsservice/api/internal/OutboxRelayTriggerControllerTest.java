package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.outbox.OutboxRelay;
import com.custoking.ims.operationsservice.application.QuotationDocumentService;
import com.custoking.ims.operationsservice.persistence.TrackerStatusOutbox;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class OutboxRelayTriggerControllerTest {
    @Test
    void relay_returnsPublishedCount() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(3);
        QuotationDocumentService documents = mock(QuotationDocumentService.class);
        when(documents.cleanup()).thenReturn(2);
        assertThat(new OutboxRelayTriggerController(relay, documents, mock(TrackerStatusOutbox.class)).relay())
                .containsEntry("published", 3).containsEntry("quotationDocumentsChecked", 2);
        verify(documents).cleanup();
    }

    @Test
    void relay_drainsTheTrackerStatusOutbox() {
        TrackerStatusOutbox statuses = mock(TrackerStatusOutbox.class);
        when(statuses.drain(OutboxRelayTriggerController.TRACKER_STATUSES_PER_TRIGGER)).thenReturn(4);
        assertThat(new OutboxRelayTriggerController(mock(OutboxRelay.class), mock(QuotationDocumentService.class), statuses).relay())
                .containsEntry("trackerStatusesDelivered", 4);
        verify(statuses).drain(OutboxRelayTriggerController.TRACKER_STATUSES_PER_TRIGGER);
    }
}
