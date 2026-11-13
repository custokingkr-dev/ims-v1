package com.custoking.ims.operationsservice.application.orderworkflow;

import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.GroupLink;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.Source;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The invariants of the records shared between work packages (design §3.4, §5.2). */
class SeamRecordsTest {

    @Test
    void aGroupLinkHasAUrlExactlyWhenItHasASource() {
        assertEquals(new GroupLink(null, Source.NONE), GroupLink.none());
        assertEquals("https://chat.whatsapp.com/AbCdEfGhIj", new GroupLink("https://chat.whatsapp.com/AbCdEfGhIj", Source.ITEM).url());
        assertThrows(IllegalArgumentException.class, () -> new GroupLink(null, Source.SCHOOL));
        assertThrows(IllegalArgumentException.class, () -> new GroupLink("https://chat.whatsapp.com/AbCdEfGhIj", Source.NONE));
        assertThrows(IllegalArgumentException.class, () -> new GroupLink(null, null));
    }

    @Test
    void resolvedGroupsDefaultToNoneAndCopyTheVendors() {
        List<VendorSummary> vendors = new ArrayList<>(List.of(new VendorSummary(4, "Metro Offset Printers", List.of("OFFSET_PRINTERS"),
                null, null, null, null, true, true)));
        ResolvedGroups groups = new ResolvedGroups(null, null, vendors);
        assertEquals(GroupLink.none(), groups.school());
        assertEquals(GroupLink.none(), groups.internalApproval());
        vendors.clear();
        assertEquals(1, groups.vendors().size(), "the record keeps its own copy");
        assertEquals(List.of(), new ResolvedGroups(GroupLink.none(), GroupLink.none(), null).vendors());
        assertEquals(List.of(), new VendorSummary(1, "x", null, null, null, null, null, false, true).categories());
    }

    @Test
    void anAccessibleOrderKnowsItsRoleAndWhetherItIsFinished() {
        AccessibleOrder active = new AccessibleOrder(10, 3, "CK-1015", "FLIERS", "FLIER_ORDER_WORKFLOW", 5, Role.OPERATOR, 77);
        assertFalse(active.superadmin());
        assertFalse(active.finished());
        AccessibleOrder done = new AccessibleOrder(10, 3, "CK-1015", "FLIERS", "FLIER_ORDER_WORKFLOW", null, Role.SUPERADMIN, 1);
        assertTrue(done.superadmin());
        assertTrue(done.finished());
        assertThrows(IllegalArgumentException.class, () -> new AccessibleOrder(10, 3, " ", "FLIERS", "FLIER_ORDER_WORKFLOW", 1, Role.OPERATOR, 1));
        assertThrows(IllegalArgumentException.class, () -> new AccessibleOrder(10, 3, "CK-1", "FLIERS", "FLIER_ORDER_WORKFLOW", 1, null, 1));
    }

    @Test
    void anOpenCallbackNamesWhoWasContacted() {
        assertThrows(IllegalArgumentException.class, () -> new CallbackSource.OpenCallback(1, " ", null, null, null));
        assertEquals("SCHOOL", new CallbackSource.OpenCallback(1, "SCHOOL", "Mrs Rao", 2, null).contacted());
    }
}
