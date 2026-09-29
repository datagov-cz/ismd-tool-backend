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
                .concepts(List.of())
                .conceptCount(0)
                .distributions(List.of(NkodDistributionDto.builder()
                        .iri("urn:d1")
                        .link("https://host/data.csv")
                        .format("urn:fmt:CSV")
                        .sluzba(false)
                        .build()))
                .build();

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .contains("\"název\"")
                .contains("\"popis\"")
                .contains("\"počet-pojmů\"")
                .contains("\"distribuce\"")
                .contains("\"odkaz\"")
                .contains("\"formát\"");
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

        assertThat(json).doesNotContain("\"popis\"");
    }

    /**
     * {@code je-služba} drives the "Otevřít" vs "Stáhnout" label, so it must survive
     * serialization even when false — a primitive boolean is not suppressed by NON_NULL.
     */
    @Test
    void serializesServiceFlagWhenFalse() throws Exception {
        String json = mapper.writeValueAsString(NkodDistributionDto.builder()
                .iri("urn:d1")
                .link("https://host/data.csv")
                .build());

        assertThat(json).contains("\"je-služba\":false");
    }

    /**
     * Regression: the flag shipped twice, as {@code je-služba} AND {@code service}. A field
     * named {@code isService} makes Lombok generate {@code isService()}, which Jackson reads
     * as a separate bean property — a field-level {@code @JsonProperty} does not suppress it.
     */
    @Test
    void doesNotDuplicateServiceFlagUnderAnEnglishName() throws Exception {
        String json = mapper.writeValueAsString(NkodDistributionDto.builder()
                .iri("urn:d1")
                .link("https://host/sparql")
                .sluzba(true)
                .build());

        assertThat(json)
                .contains("\"je-služba\":true")
                .doesNotContain("\"service\"")
                .doesNotContain("\"sluzba\"");
    }
}