package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NkodCodelistCheckDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missing_serializesWithoutCodelist() throws Exception {
        JsonNode json = mapper.valueToTree(
                NkodCodelistCheckDto.builder().status(NkodCodelistStatus.MISSING).build());

        assertThat(json.get("status").asText()).isEqualTo("MISSING");
        assertThat(json.has("codelist")).isFalse();
    }

    @Test
    void newVersion_serializesTheCurrentCodelistWithoutUnsetOptionals() throws Exception {
        NkodCodelist current = NkodCodelist.builder()
                .datasetIri("https://data.gov.cz/zdroj/datové-sady/17651921/b")
                .codeListIri("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01")
                .title("Pohlaví")
                .publisher("Digitální a informační agentura")
                .build();

        JsonNode json = mapper.valueToTree(NkodCodelistCheckDto.builder()
                .status(NkodCodelistStatus.NEW_VERSION).codelist(current).build());

        assertThat(json.get("status").asText()).isEqualTo("NEW_VERSION");
        assertThat(json.at("/codelist/codeListIri").asText()).endsWith("/151/2025-01-01");
        assertThat(json.get("codelist").has("validFrom")).isFalse();
        assertThat(json.get("codelist").has("description")).isFalse();
    }
}
