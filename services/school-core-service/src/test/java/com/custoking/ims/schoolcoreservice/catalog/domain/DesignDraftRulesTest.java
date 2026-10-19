package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignDraftRulesTest {

    @Test
    void theCapIsSixAndOnlyTheSeventhIsRefused() {
        for (int count = 0; count <= 5; count++) assertTrue(DesignDraftRules.hasRoom(count), "with " + count);
        assertFalse(DesignDraftRules.hasRoom(6));
        assertFalse(DesignDraftRules.hasRoom(7));
        assertEquals("No more images allowed. Contact IT.", DesignDraftRules.LIMIT_MESSAGE);
    }

    @Test
    void anEmailIntentIsDueOnlyWhenTheCountCrossesFour() {
        assertFalse(DesignDraftRules.emailIntentDue(0, 1));
        assertFalse(DesignDraftRules.emailIntentDue(3, 4));
        assertTrue(DesignDraftRules.emailIntentDue(4, 5));
        assertFalse(DesignDraftRules.emailIntentDue(5, 6));
        assertFalse(DesignDraftRules.emailIntentDue(6, 5));
    }

    @Test
    void theKindMatchesTheDatabaseConstraint() {
        assertEquals("DESIGN_DRAFT", DesignDraftRules.KIND);
    }
}
