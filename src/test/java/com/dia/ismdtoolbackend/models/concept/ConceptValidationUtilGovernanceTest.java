package com.dia.ismdtoolbackend.models.concept;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptValidationUtilGovernanceTest {

    private static final String ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/";

    @Test
    void storedItemIriMapsBackToTheAllowedValue() {
        assertThat(ConceptValidationUtil.governanceValueOf(ITEMS + "veřejně-přístupné")).isEqualTo("veřejně přístupné");
        assertThat(ConceptValidationUtil.governanceValueOf(ITEMS + "zpřístupňované-pro-výkon-agendy"))
                .isEqualTo("zpřístupňované pro výkon agendy");
    }

    @Test
    void unknownItemAndPlainValuesAreReturnedUnchanged() {
        assertThat(ConceptValidationUtil.governanceValueOf(ITEMS + "neznámé")).isEqualTo(ITEMS + "neznámé");
        assertThat(ConceptValidationUtil.governanceValueOf("vlastní")).isEqualTo("vlastní");
        assertThat(ConceptValidationUtil.governanceValueOf(null)).isNull();
    }
}
