package com.custoking.ims.billingservice.api.internal;

import com.custoking.ims.billingservice.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayTriggerControllerTest {
    @Test
    void relay_returnsPublishedCount() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(5);
        assertThat(new OutboxRelayTriggerController(relay).relay()).containsEntry("published", 5);
    }
}
