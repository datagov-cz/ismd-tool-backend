package com.dia.ismdtoolbackend.models.concept;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConceptValidationUtilGovernanceTest {

    private static final String ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/";

    @Test
    void storedItemIriMapsBackToTheAllowedValue() {
        assertEquals("veřejně přístupné", ConceptValidationUtil.governanceValueOf(ITEMS + "veřejně-přístupné"));
        assertEquals("zpřístupňované pro výkon agendy",
                ConceptValidationUtil.governanceValueOf(ITEMS + "zpřístupňované-pro-výkon-agendy"));
    }

    @Test
    void unknownItemAndPlainValuesAreReturnedUnchanged() {
        assertEquals(ITEMS + "neznámé", ConceptValidationUtil.governanceValueOf(ITEMS + "neznámé"));
        assertEquals("vlastní", ConceptValidationUtil.governanceValueOf("vlastní"));
        assertNull(ConceptValidationUtil.governanceValueOf(null));
    }
}
