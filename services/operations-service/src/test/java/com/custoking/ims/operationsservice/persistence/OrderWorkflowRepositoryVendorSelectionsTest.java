package com.custoking.ims.operationsservice.persistence;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reading an order's stored vendor selections. Every call on an order reads its tracker row, so a selection the engine
 * cannot use (written by the notebook tracker during a rollout, before V21's check) must select nothing rather than fail
 * the whole order with a 500.
 */
class OrderWorkflowRepositoryVendorSelectionsTest {
    private final OrderWorkflowRepository repo = new OrderWorkflowRepository(null, JsonMapper.builder().build(), "workflow");

    @Test
    void directoryIdsAreReadPerCategoryInTheOrderStored() {
        Map<String, List<Long>> read = repo.vendorSelections("CK-1", "{\"OFFSET_PRINTERS\": [7, 4], \"BINDERS\": [], \"BOARD_SUPPLIERS\": [3000000000]}");
        assertEquals(Map.of("OFFSET_PRINTERS", List.of(7L, 4L), "BINDERS", List.of(), "BOARD_SUPPLIERS", List.of(3_000_000_000L)), read);
        assertEquals(List.of("OFFSET_PRINTERS", "BINDERS", "BOARD_SUPPLIERS"), List.copyOf(read.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> read.put("FLAPS", List.of()));
        assertEquals(Map.of(), repo.vendorSelections("CK-1", "{}"));
        assertEquals(Map.of(), repo.vendorSelections("CK-1", null));
        assertEquals(Map.of(), repo.vendorSelections("CK-1", " "));
    }

    @Test
    void anythingThatIsNotAVendorIdSelectsNothingInsteadOfFailingTheOrder() {
        // The notebook tracker's own format, as an old revision writes it after V19 remapped the column.
        assertEquals(Map.of("board", List.of(), "offset", List.of()),
                repo.vendorSelections("CK-2", "{\"board\": [\"board_shree\"], \"offset\": [\"offset_metro\", \"offset_cityline\"]}"));
        assertEquals(Map.of("OFFSET_PRINTERS", List.of(4L), "BINDERS", List.of()),
                repo.vendorSelections("CK-3", "{\"OFFSET_PRINTERS\": [\"7\", 4, 1.5, null, true], \"BINDERS\": 9}"));
        assertEquals(Map.of(), repo.vendorSelections("CK-4", "[1, 2]"));
        assertEquals(Map.of(), repo.vendorSelections("CK-5", "null"));
        assertEquals(Map.of(), repo.vendorSelections("CK-6", "{not json"));
    }
}
