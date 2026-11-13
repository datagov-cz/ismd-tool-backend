package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.enums.DiagramFailureCode;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How a refused change is reported in the materialize result. */
class DiagramMaterializeServiceTest {

    private static final Long DIAGRAM_ID = 3L;
    private static final String CONCEPT_IRI = "https://slovník.gov.cz/test/pojem/a";

    private final DiagramChangeApplier applier = mock(DiagramChangeApplier.class);
    private final DiagramPendingEditRepository pendingEdits = mock(DiagramPendingEditRepository.class);
    private final DiagramMaterializeService service = new DiagramMaterializeService(applier, pendingEdits);

    @Test
    void anOfnRefusal_isAValidationFailureListingEveryIssue() {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setConceptIri(CONCEPT_IRI);
        when(pendingEdits.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(row));
        when(applier.applyChange(DIAGRAM_ID, CONCEPT_IRI)).thenThrow(new OfnConformanceException(
                OfnConformanceException.Context.WRITE, "https://slovník.gov.cz/test", List.of(
                new ConformanceIssue(ConformanceIssue.Kind.VALUE_DROPPED, CONCEPT_IRI, "pojmy[a]/definice",
                        "očekáván text s jazykem"),
                new ConformanceIssue(ConformanceIssue.Kind.CONCEPT_EXCLUDED, CONCEPT_IRI, "pojmy[a]",
                        "pojem nemá právě jeden druh"))));

        MaterializeResultDto result = service.materialize(DIAGRAM_ID, 1L);

        assertThat(result.materialized()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.conceptIri()).isEqualTo(CONCEPT_IRI);
            assertThat(failure.error()).isEqualTo(DiagramFailureCode.VALIDATION);
            assertThat(failure.message())
                    .startsWith("Zápis by uložil data nesplňující standard OFN Slovníky (2 problémy).")
                    .contains("pojmy[a]/definice: očekáván text s jazykem")
                    .contains("pojmy[a]: pojem nemá právě jeden druh");
        });
    }
}
