package com.custoking.ims.commandcenter;

import com.custoking.ims.commandcenter.dto.ReorderSignalItem;
import com.custoking.ims.commandcenter.dto.ReorderSignalsResponse;
import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.repo.CatalogOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReorderPredictionService {

    static final double RED_MULTIPLIER = 1.2;
    static final double YELLOW_MULTIPLIER = 0.7;
    static final int SINGLE_ORDER_YELLOW_DAYS = 180;

    private final CatalogOrderRepository catalogOrderRepository;

    public ReorderPredictionService(CatalogOrderRepository catalogOrderRepository) {
        this.catalogOrderRepository = catalogOrderRepository;
    }

    public DashboardCommandCenterResponse.ReorderSection getSummary(Long schoolId) {
        if (schoolId == null) {
            return new DashboardCommandCenterResponse.ReorderSection(0);
        }
        long alertCount = computeSignals(schoolId).stream()
                .filter(s -> "RED".equals(s.alertLevel()) || "YELLOW".equals(s.alertLevel()))
                .count();
        return new DashboardCommandCenterResponse.ReorderSection((int) alertCount);
    }

    public ReorderSignalsResponse getSignals(Long schoolId) {
        if (schoolId == null) {
            return new ReorderSignalsResponse(0, List.of());
        }
        List<ReorderSignalItem> items = computeSignals(schoolId);
        long alertCount = items.stream()
                .filter(s -> "RED".equals(s.alertLevel()) || "YELLOW".equals(s.alertLevel()))
                .count();
        return new ReorderSignalsResponse((int) alertCount, items);
    }

    private List<ReorderSignalItem> computeSignals(Long schoolId) {
        List<CatalogOrderEntity> orders = catalogOrderRepository
                .findBySchool_IdAndStatusIn(schoolId, List.of("APPROVED", "FULFILLED"));
        if (orders.isEmpty()) {
            return List.of();
        }

        Map<String, List<CatalogOrderEntity>> byCategory = orders.stream()
                .collect(Collectors.groupingBy(CatalogOrderEntity::getCategory));

        LocalDate today = LocalDate.now();
        List<ReorderSignalItem> signals = new ArrayList<>();

        for (Map.Entry<String, List<CatalogOrderEntity>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<CatalogOrderEntity> catOrders = new ArrayList<>(entry.getValue());
            catOrders.sort(Comparator.comparing(o -> o.getCreatedAt().toLocalDate()));

            LocalDate lastDate = catOrders.getLast().getCreatedAt().toLocalDate();
            int daysSinceLast = (int) Math.max(0, ChronoUnit.DAYS.between(lastDate, today));
            int count = catOrders.size();

            Integer avgInterval = null;
            LocalDate predictedNext = null;
            if (count >= 2) {
                LocalDate firstDate = catOrders.getFirst().getCreatedAt().toLocalDate();
                long span = ChronoUnit.DAYS.between(firstDate, lastDate);
                int computed = (int) (span / (count - 1));
                if (computed > 0) {
                    avgInterval = computed;
                    predictedNext = lastDate.plusDays(computed);
                }
            }

            String alertLevel;
            if (avgInterval != null) {
                if (daysSinceLast > avgInterval * RED_MULTIPLIER) {
                    alertLevel = "RED";
                } else if (daysSinceLast > avgInterval * YELLOW_MULTIPLIER) {
                    alertLevel = "YELLOW";
                } else {
                    alertLevel = "OK";
                }
            } else {
                alertLevel = daysSinceLast >= SINGLE_ORDER_YELLOW_DAYS ? "YELLOW" : "OK";
            }

            signals.add(new ReorderSignalItem(category, lastDate, daysSinceLast, avgInterval, predictedNext, alertLevel));
        }

        signals.sort(Comparator
                .<ReorderSignalItem, Integer>comparing(s -> alertOrder(s.alertLevel()))
                .thenComparing(s -> -s.daysSinceLastOrder()));

        return signals;
    }

    private int alertOrder(String level) {
        return switch (level) {
            case "RED" -> 0;
            case "YELLOW" -> 1;
            default -> 2;
        };
    }
}
