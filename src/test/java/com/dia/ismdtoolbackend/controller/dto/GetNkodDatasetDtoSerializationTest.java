package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire shape the FE consumes.
 *
 * <p>An empty concept list is the <em>expected</em> state for this feature until publishers
 * populate {@code týká-se-pojmu}, so {@code pojmy} must still appear as {@code []} rather
 * than vanishing — the FE distinguishes "no concepts" from "field missing".
 */
class GetNkodDatasetDtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesEmptyConceptListAsEmptyArray() throws Exception {
        GetNkodDatasetDto dto = GetNkodDatasetDto.builder()
                .iri("https://data.gov.cz/zdroj/datové-sady/1/a")
                .name(Map.of("cs", "Testovací sada"))
                .concepts(List.of())
                .conceptCount(0)
                .build();

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"pojmy\":[]");
    }

    @Test
    void usesCzechFieldNames() throws Exception {
        GetNkodDatasetDto dto = GetNkodDatasetDto.builder()
                .iri("https://data.gov.cz/zdroj/datové-sady/1/a")
                .name(Map.of("cs", "Testovací sada"))
                .description(Map.of("cs", "Popis"))
                .landingPage("https://data.gov.cz/datová-sada?iri=x")
                .concepts(List.of())
                .conceptCount(0)
                .build();

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .contains("\"název\"")
                .contains("\"popis\"")
                .contains("\"vstupní-stránka\"")
                .contains("\"počet-pojmů\"");
    }

    /** Absent optional fields stay out of the payload rather than shipping as nulls. */
    @Test
    void omitsNullOptionalFields() throws Exception {
        GetNkodDatasetDto dto = GetNkodDatasetDto.builder()
                .iri("https://data.gov.cz/zdroj/datové-sady/1/a")
                .concepts(List.of())
                .conceptCount(0)
                .build();

        String json = mapper.writeValueAsString(dto);

        assertThat(json).doesNotContain("vstupní-stránka");
    }
}