package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SnapshotAction;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkSnapshotDtoSerializationTest {

    // Mirror Spring Boot's HTTP mapper: JSR-310 is auto-registered there (jackson-datatype-jsr310
    // on the classpath; OutboxStatusDto already returns an Instant over the wire).
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void getOntologyDto_withoutLinkSnapshots_omitsField() throws Exception {
        // The byte-identical guarantee: an ontology with no NKD-linking concepts must not carry a
        // linkSnapshots key. (linkSnapshots lives on the ONTOLOGY detail, not concept detail.)
        GetOntologyDto dto = new GetOntologyDto();
        String json = mapper.writeValueAsString(dto);
        assertThat(json).doesNotContain("linkSnapshots");
    }

    @Test
    void linkSnapshotDto_serializesExpectedShape() throws Exception {
        LinkSnapshotDto dto = LinkSnapshotDto.builder()
                .snapshotId(42L)
                .owningConceptId(17L)
                .linkPredicate(SnapshotLinkType.BROADER_CLASS)
                .origin(SnapshotOrigin.LINK_TARGET)
                .nkdConcept(NkdConceptRefDto.builder()
                        .iri("https://slovník.gov.cz/agendový/104/pojem/adresní-místo")
                        .label("Adresní místo")
                        .build())
                .snapshotAt(Instant.parse("2026-06-22T10:00:00Z"))
                .lastCheckedAt(Instant.parse("2026-06-22T11:00:00Z"))
                .status(DeviationStatus.HAS_DEVIATIONS)
                .deviation(PublishedConceptDeviationModel.builder()
                        .status(DeviationStatus.HAS_DEVIATIONS)
                        .build())
                .availableActions(List.of(SnapshotAction.UPDATE, SnapshotAction.REMOVE))
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(dto));

        assertThat(node.get("snapshotId").asLong()).isEqualTo(42L);
        assertThat(node.get("owningConceptId").asLong()).isEqualTo(17L);
        assertThat(node.get("linkPredicate").asText()).isEqualTo("BROADER_CLASS");
        assertThat(node.get("origin").asText()).isEqualTo("LINK_TARGET");
        assertThat(node.get("nkdConcept").get("label").asText()).isEqualTo("Adresní místo");
        assertThat(node.get("status").asText()).isEqualTo("HAS_DEVIATIONS");
        assertThat(node.get("availableActions")).hasSize(2);
        assertThat(node.has("deviation")).isTrue();
    }

    @Test
    void nkdConceptRef_nullLabel_omitted() throws Exception {
        NkdConceptRefDto ref = NkdConceptRefDto.builder().iri("https://x/y").build();
        String json = mapper.writeValueAsString(ref);
        assertThat(json).contains("iri").doesNotContain("label");
    }
}
