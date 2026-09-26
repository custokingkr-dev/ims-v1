package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationDeliveryBroadcastDedupeTest {
    @Test void terminalInboxReplayDoesNotAttemptProviderAgain() {
        var inbox = mock(NotificationInboxRepository.class); var processor = mock(NotificationInboxProcessor.class);
        var mapper = new ObjectMapper(); var service = new NotificationDeliveryCommandService(inbox,processor,mapper,"logging",true);
        var event = new NotificationInboxEvent(); event.setEventId("broadcast:stable:1:SMS"); event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
        when(inbox.findById(event.getEventId())).thenReturn(Optional.of(event));
        var command = new NotificationDeliveryCommandService.DeliverNowCommand(event.getEventId(),"notification.requested.v1",event.getEventId(),"SCHOOL_BROADCAST","stable",mapper.createObjectNode());
        var answer = service.deliverNow(command); service.deliverNow(command);
        assertThat(answer.dryRun()).isTrue(); verifyNoInteractions(processor); verify(inbox,never()).save(any());
    }
}
