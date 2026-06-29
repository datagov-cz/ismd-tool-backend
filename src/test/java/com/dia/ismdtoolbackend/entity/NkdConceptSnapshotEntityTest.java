package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NkdConceptSnapshotEntityTest {

    @Test
    void snapshot_jsonRoundTrip_preservesDetail() {
        ConceptDetailModel detail = ConceptDetailModel.builder()
                .iri("https://slovník.gov.cz/agendový/104/pojem/adresní-místo")
                .types(List.of("https://slovník.gov.cz/základní/pojem/typ-objektu"))
                .name(Map.of("cs", "Adresní místo", "en", "Address place"))
                .build();

        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        entity.setSnapshot(detail);

        // Stored as JSON, not null.
        assertThat(entity.getSnapshotJson()).isNotBlank();

        ConceptDetailModel restored = entity.getSnapshot();
        assertThat(restored).isNotNull();
        assertThat(restored.getIri()).isEqualTo(detail.getIri());
        assertThat(restored.getName()).containsEntry("cs", "Adresní místo").containsEntry("en", "Address place");
        assertThat(restored.getTypes()).containsExactlyElementsOf(detail.getTypes());
    }

    @Test
    void snapshot_jsonRoundTrip_preservesNestedBuilderDtos() {
        // Guards the @Jacksonized fix: ConceptDetailModel + nested @Builder/@AllArgsConstructor DTOs
        // (RppIsvs/RppAgenda) must round-trip. Without @Jacksonized / a no-args ctor these silently
        // fail deserialization and null out the whole snapshot.
        ConceptDetailModel detail = ConceptDetailModel.builder()
                .iri("https://slovník.gov.cz/agendový/104/pojem/x")
                .aisResolved(new RppIsvs("isvs-iri", "ISVS-1", "Systém", List.of("agenda-iri")))
                .agendaResolved(new RppAgenda("agenda-iri", "A104", "Agenda"))
                .build();

        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        entity.setSnapshot(detail);

        ConceptDetailModel restored = entity.getSnapshot();
        assertThat(restored).isNotNull();
        assertThat(restored.getAisResolved()).isNotNull();
        assertThat(restored.getAisResolved().getNazev()).isEqualTo("Systém");
        assertThat(restored.getAisResolved().getAgendaIris()).containsExactly("agenda-iri");
        assertThat(restored.getAgendaResolved()).isNotNull();
        assertThat(restored.getAgendaResolved().getCode()).isEqualTo("A104");
    }

    @Test
    void getSnapshot_nullOrBlankJson_returnsNull() {
        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        assertThat(entity.getSnapshot()).isNull();

        entity.setSnapshotJson("   ");
        assertThat(entity.getSnapshot()).isNull();
    }

    @Test
    void getSnapshot_malformedJson_returnsNullNotThrows() {
        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        entity.setSnapshotJson("{ this is : not valid json ]");

        assertThat(entity.getSnapshot()).isNull();
    }

    @Test
    void setSnapshot_null_clearsJson() {
        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        entity.setSnapshot(ConceptDetailModel.builder().iri("x").build());
        assertThat(entity.getSnapshotJson()).isNotNull();

        entity.setSnapshot(null);
        assertThat(entity.getSnapshotJson()).isNull();
    }

    @Test
    void materializedTriples_isPlainPassthrough() {
        // The column is exact N-Triples (the M1 delete-set source of truth) — the entity must not
        // transform it; it is read/written verbatim by the materializer.
        String nTriples = "<https://slovník.gov.cz/agendový/104/pojem/adresní-místo> "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Adresn\\u00ED m\\u00EDsto\" .\n";

        NkdConceptSnapshotEntity entity = new NkdConceptSnapshotEntity();
        entity.setMaterializedTriples(nTriples);

        assertThat(entity.getMaterializedTriples()).isEqualTo(nTriples);
    }
}
