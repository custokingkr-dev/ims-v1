package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeeMoneySemanticsTest {
    private final FeeReadRepository repository = new FeeReadRepository(mock(org.springframework.jdbc.core.simple.JdbcClient.class),
            mock(com.custoking.ims.schoolcoreservice.outbox.OutboxWriter.class), mock(FeeReceiptRepository.class), mock(FeeDocumentRenderer.class));

    @Test void monetaryRoundingUsesDecimalHalfUpWithoutBinaryMidpointDrift() {
        assertThat((Long)ReflectionTestUtils.invokeMethod(repository, "rupeesToPaise", "1.005")).isEqualTo(101L);
        assertThat((Long)ReflectionTestUtils.invokeMethod(repository, "rupeesToPaise", "1.004")).isEqualTo(100L);
        assertThat((Long)ReflectionTestUtils.invokeMethod(repository, "percentageAmount", 100L, 2.5)).isEqualTo(3L);
        assertThat((Long)ReflectionTestUtils.invokeMethod(repository, "percentageAmount", 999999999999999L, 10.0)).isEqualTo(100000000000000L);
    }
    @Test void nonfiniteNegativeAndOverflowMoneyIsRejected() {
        for (String input : new String[]{"NaN", "Infinity", "-0.01", "999999999999999999999999"})
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(repository, "rupeesToPaise", input)).isInstanceOf(IllegalArgumentException.class);
    }
}
