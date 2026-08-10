package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTriggerControllerTest {
    @Test
    void relay_returnsPublishedCount() {
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.publishBatch()).thenReturn(7);
        assertThat(new OutboxRelayTriggerController(relay).relay()).containsEntry("published", 7);
        verify(relay).publishBatch();
    }
}
