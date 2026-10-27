package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.NkodCodelistCheckDto;
import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptDetailCodeListResolvedTest {

    private static final String RAW = "instance-definovány-číselníkem";
    private static final String RESOLVED = "instance-definovány-číselníkem-resolved";

    private final ObjectMapper mapper = new ObjectMapper();

    private static OntologyDetailModel.ConceptDetailModel.ConceptDetailModelBuilder detailWithCodeList() {
        return OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.org/pojem/a")
                .codeList(CodeListDto.builder()
                        .iri("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2024-01-01")
                        .datovaSadaVNkod("https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289")
                        .build());
    }

    @Test
    void resolvedIsAbsent_whenNotSet_andTheRawFieldStays() {
        JsonNode json = mapper.valueToTree(detailWithCodeList().build());

        assertThat(json.has(RAW)).isTrue();
        assertThat(json.has(RESOLVED)).isFalse();
    }

    @Test
    void resolvedSerializesBesideTheRawField() {
        JsonNode json = mapper.valueToTree(detailWithCodeList()
                .codeListResolved(NkodCodelistCheckDto.builder().status(NkodCodelistStatus.MISSING).build())
                .build());

        assertThat(json.has(RAW)).isTrue();
        assertThat(json.at("/" + RESOLVED + "/status").asString()).isEqualTo("MISSING");
        assertThat(json.get(RESOLVED).has("codelist")).isFalse();
    }
}
