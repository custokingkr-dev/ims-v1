package com.custoking.ims.commandcenter;

import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.repo.CatalogOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReorderPredictionService")
class ReorderPredictionServiceTest {

    @Mock CatalogOrderRepository catalogOrderRepository;

    @InjectMocks ReorderPredictionService service;

    private static final Long SCHOOL_ID = 5L;

    private CatalogOrderEntity order(String category, LocalDate date) {
        var o = new CatalogOrderEntity();
        o.setCategory(category);
        o.setStatus("APPROVED");
        o.setCreatedAt(date.atStartOfDay().atOffset(ZoneOffset.UTC));
        return o;
    }

    @Test
    @DisplayName("getSignals returns empty list for null schoolId without querying DB")
    void getSignals_nullSchool_returnsEmpty() {
        var result = service.getSignals(null);

        assertThat(result.items()).isEmpty();
        assertThat(result.alertCount()).isZero();
        verifyNoInteractions(catalogOrderRepository);
    }

    @Test
    @DisplayName("single order with no interval shows alertLevel OK when recent")
    void getSignals_singleRecentOrder_alertLevelOk() {
        when(catalogOrderRepository.findBySchool_IdAndStatusIn(eq(SCHOOL_ID), anyList()))
                .thenReturn(List.of(order("NOTEBOOKS", LocalDate.now().minusDays(30))));

        var result = service.getSignals(SCHOOL_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().alertLevel()).isEqualTo("OK");
        assertThat(result.items().getFirst().avgIntervalDays()).isNull();
    }

    @Test
    @DisplayName("single order older than 180 days shows alertLevel YELLOW")
    void getSignals_singleOldOrder_alertLevelYellow() {
        when(catalogOrderRepository.findBySchool_IdAndStatusIn(eq(SCHOOL_ID), anyList()))
                .thenReturn(List.of(order("STATIONERY", LocalDate.now().minusDays(200))));

        var result = service.getSignals(SCHOOL_ID);

        assertThat(result.items().getFirst().alertLevel()).isEqualTo("YELLOW");
        assertThat(result.alertCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("category overdue vs historical interval is marked RED")
    void getSignals_overdueCategory_markedRed() {
        LocalDate today = LocalDate.now();
        // avgInterval = 50 days; daysSinceLast = 150 → 150 > 50 * 1.2 = 60 → RED
        when(catalogOrderRepository.findBySchool_IdAndStatusIn(eq(SCHOOL_ID), anyList()))
                .thenReturn(List.of(
                        order("UNIFORMS", today.minusDays(200)),
                        order("UNIFORMS", today.minusDays(150))));

        var result = service.getSignals(SCHOOL_ID);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().alertLevel()).isEqualTo("RED");
        assertThat(result.items().getFirst().avgIntervalDays()).isEqualTo(50);
        assertThat(result.alertCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("RED and YELLOW categories are sorted before OK; RED items appear first")
    void getSignals_sortsByAlertLevelThenDays() {
        LocalDate today = LocalDate.now();
        // UNIFORMS: RED (interval 50d, daysSinceLast 150)
        // NOTEBOOKS: OK (interval 90d, daysSinceLast 10)
        // STATIONERY: single old order → YELLOW (daysSinceLast 200)
        when(catalogOrderRepository.findBySchool_IdAndStatusIn(eq(SCHOOL_ID), anyList()))
                .thenReturn(List.of(
                        order("UNIFORMS",   today.minusDays(200)),
                        order("UNIFORMS",   today.minusDays(150)),
                        order("NOTEBOOKS",  today.minusDays(180)),
                        order("NOTEBOOKS",  today.minusDays(10)),
                        order("STATIONERY", today.minusDays(200))));

        var result = service.getSignals(SCHOOL_ID);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).alertLevel()).isEqualTo("RED");
        assertThat(result.items().get(1).alertLevel()).isEqualTo("YELLOW");
        assertThat(result.items().get(2).alertLevel()).isEqualTo("OK");
        assertThat(result.alertCount()).isEqualTo(2);
    }
}
