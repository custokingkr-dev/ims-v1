package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationInboxRetryService;
import com.custoking.ims.platformservice.application.ReportingEventInboxProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncWorkTriggerControllerTest {
    @Test
    void drain_runsBothDurableQueues() {
        ReportingEventInboxProcessor reporting = mock(ReportingEventInboxProcessor.class);
        NotificationInboxRetryService notifications = mock(NotificationInboxRetryService.class);
        when(reporting.processBatch()).thenReturn(11);
        when(notifications.retryFailedEvents()).thenReturn(2);

        assertThat(new AsyncWorkTriggerController(reporting, notifications).drain())
                .containsEntry("reportingProjected", 11)
                .containsEntry("notificationRetriesAttempted", 2);
    }
}
