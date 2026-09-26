package com.custoking.ims.platformservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationBroadcastDispatchPolicyTest {
    @Test
    void existingBroadcastCannotBeMarkedSentWhilePolicyEvidenceIsMissing() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> count = mock(JdbcClient.MappedQuerySpec.class);
        UUID id = UUID.randomUUID();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param("id", id)).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(count);
        when(count.single()).thenReturn(1L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new NotificationBroadcastCommandRepository(jdbc).send(id, 42L));
        assertEquals(NotificationBroadcastCommandRepository.DISPATCH_BLOCK_REASON, error.getMessage());
        verify(statement, never()).update();
    }
}
