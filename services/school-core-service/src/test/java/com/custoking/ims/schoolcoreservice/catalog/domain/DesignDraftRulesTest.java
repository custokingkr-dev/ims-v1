package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignDraftRulesTest {

    @Test
    void reviewStartsAtFourWithoutAPrototypeDraftCountCap() {
        assertFalse(DesignDraftRules.requiresReview(3));
        assertTrue(DesignDraftRules.requiresReview(4));
        assertTrue(DesignDraftRules.requiresReview(20));
    }

    @Test
    void anEmailIntentIsDueOnlyWhenTheCountCrossesFour() {
        assertFalse(DesignDraftRules.emailIntentDue(0, 1));
        assertTrue(DesignDraftRules.emailIntentDue(3, 4));
        assertFalse(DesignDraftRules.emailIntentDue(4, 5));
        assertFalse(DesignDraftRules.emailIntentDue(5, 6));
        assertFalse(DesignDraftRules.emailIntentDue(6, 5));
    }

    @Test
    void theKindMatchesTheDatabaseConstraint() {
        assertEquals("DESIGN_DRAFT", DesignDraftRules.KIND);
    }
}
