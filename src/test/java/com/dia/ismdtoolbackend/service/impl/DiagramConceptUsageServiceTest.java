package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageKind;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptHierarchyLinks;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for the concept-usage read: per-placement layering of each diagram's overlay over the live
 * structure, the single batched resolution, and tolerance of malformed overlays. Repositories and the
 * resolution engine are mocked; overlays are real JSON parsed by the service.
 */
@ExtendWith(MockitoExtension.class)
class DiagramConceptUsageServiceTest {

    private static final String GRAPH = "https://x/slovnik";
    private static final String SELF = "https://x/pojem/self";
    private static final String A = "https://x/pojem/a";
    private static final String B = "https://x/pojem/b";
    private static final String C = "https://x/pojem/c";
    private static final String HOST = "https://x/pojem/host";

    @Mock private DiagramRepository diagramRepository;
    @Mock private DiagramPendingEditRepository pendingEditRepository;
    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private ReferencedConceptResolutionEngine resolutionEngine;

    @InjectMocks private DiagramConceptUsageService service;

    private record Usage(Long getDiagramId, String getDiagramName, String getOntologySlug, String getKind,
                         String getHostClassIri) implements DiagramRepository.ConceptUsageRow {
    }

    private record Overlay(Long getDiagramId, String getPendingEditJson)
            implements DiagramPendingEditRepository.ConceptOverlayRow {
    }

    private static ResolvedConceptDto resolved(String iri) {
        return resolved(iri, null, null);
    }

    private static ResolvedConceptDto resolved(String iri, ResolvedConceptDto domain, ResolvedConceptDto range) {
        return new ResolvedConceptDto(iri, Map.of("cs", iri), null, null, null, null, domain, range);
    }

    private ConceptMetadataEntity concept(String iri, String name) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setSlug("self");
        c.setGraphName(GRAPH);
        when(conceptMetadataRepository.findBySlug("self")).thenReturn(Optional.of(c));
        return c;
    }

    private void placedOn(String iri, Usage... rows) {
        when(diagramRepository.findConceptUsage(eq(iri), eq("[\"" + iri.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"]"))).thenReturn(List.of(rows));
    }

    private void overlays(String iri, List<Long> diagramIds, Overlay... rows) {
        when(pendingEditRepository.findByConceptIriAcrossDiagrams(iri, diagramIds)).thenReturn(List.of(rows));
    }

    private void live(String iri, List<String> broader, List<String> exactMatch) {
        when(jenaTDB2Repository.fetchConceptHierarchy(iri, GRAPH))
                .thenReturn(new ConceptHierarchyLinks(broader, exactMatch));
    }

    /** Resolves exactly the given concepts; any other IRI in the batch resolves to nothing. */
    private void resolving(ResolvedConceptDto... known) {
        Map<String, ResolvedConceptDto> byIri = new HashMap<>();
        Arrays.stream(known).forEach(r -> byIri.put(r.iri(), r));
        when(resolutionEngine.resolveAll(anyList())).thenAnswer(inv -> {
            Map<String, ResolvedConceptDto> out = new HashMap<>();
            for (String iri : inv.<List<String>>getArgument(0)) {
                if (byIri.containsKey(iri)) {
                    out.put(iri, byIri.get(iri));
                }
            }
            return out;
        });
    }

    @Test
    void unknownSlug_isNotFound() {
        when(conceptMetadataRepository.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.usageForSlug("missing"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void conceptOnNoDiagram_isIdentifiedWithAnEmptyList_withoutTouchingRdf() {
        concept(SELF, null);
        placedOn(SELF);

        DiagramConceptUsageDto dto = service.usageForSlug("self");

        assertThat(dto.conceptIri()).isEqualTo(SELF);
        assertThat(dto.conceptName()).as("no PG name, no label").isEmpty();
        assertThat(dto.placements()).isEmpty();
        verifyNoInteractions(jenaTDB2Repository, resolutionEngine, pendingEditRepository);
    }

    /** The containment operand is escaped, so a quote or backslash cannot break the JSON cast. */
    @Test
    void iriWithQuoteAndBackslash_isEscapedInTheContainmentOperand() {
        String odd = "https://x/pojem/a\"b\\c";
        concept(odd, "Divný");
        when(diagramRepository.findConceptUsage(odd, "[\"https://x/pojem/a\\\"b\\\\c\"]")).thenReturn(List.of());

        assertThat(service.usageForSlug("self").conceptName()).containsEntry("cs", "Divný");
    }

    @Test
    void placementWithoutOverlay_reportsLiveStructure_andDropsUnresolvedTargets() {
        concept(SELF, "Pojem");
        placedOn(SELF, new Usage(1L, "D1", "slovnik", "NODE", null));
        overlays(SELF, List.of(1L));
        live(SELF, List.of(A, B), List.of());
        resolving(resolved(SELF), resolved(A));

        DiagramConceptUsageDto dto = service.usageForSlug("self");

        assertThat(dto.placements()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(DiagramConceptUsageKind.NODE);
            assertThat(p.pending()).isFalse();
            assertThat(p.hostClass()).isNull();
            assertThat(p.domain()).isNull();
            assertThat(p.broader()).as("B resolved to nothing and is dropped")
                    .extracting(ResolvedConceptDto::iri).containsExactly(A);
            assertThat(p.exactMatch()).isEmpty();
        });
    }

    /** Only the overlay fields that are set override; a staged clear empties the predicate. */
    @Test
    void eachPlacementLayersItsOwnOverlay_fieldByField() {
        concept(SELF, "Pojem");
        placedOn(SELF,
                new Usage(1L, "D1", "slovnik", "EDGE", null),
                new Usage(2L, "D2", "slovnik", "EDGE", null),
                new Usage(3L, "D3", "slovnik", "PROPERTY_ROW", HOST));
        overlays(SELF, List.of(1L, 2L, 3L),
                new Overlay(1L, "{\"range\":\"" + C + "\",\"exactMatch\":[\"" + B + "\"]}"),
                new Overlay(2L, "{\"domain\":\"" + C + "\",\"broaderConcept\":[],\"exactMatch\":[\" \"]}"),
                new Overlay(3L, "{not json"));
        live(SELF, List.of(A), List.of(A));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batch = ArgumentCaptor.forClass(List.class);
        resolving(resolved(SELF, resolved(A), resolved(B)), resolved(A), resolved(B), resolved(C),
                resolved(HOST));

        DiagramConceptUsageDto dto = service.usageForSlug("self");

        verify(resolutionEngine).resolveAll(batch.capture());
        assertThat(batch.getValue()).as("one batch for every IRI any placement names; blanks skipped")
                .containsExactlyInAnyOrder(SELF, A, B, C, HOST);

        Map<Long, DiagramConceptUsageDto.Placement> byDiagram = new HashMap<>();
        dto.placements().forEach(p -> byDiagram.put(p.diagramId(), p));

        assertThat(byDiagram.get(1L)).satisfies(p -> {
            assertThat(p.pending()).isTrue();
            assertThat(p.domain().iri()).as("live domain kept").isEqualTo(A);
            assertThat(p.range().iri()).as("staged range wins").isEqualTo(C);
            assertThat(p.broader()).extracting(ResolvedConceptDto::iri).containsExactly(A);
            assertThat(p.exactMatch()).extracting(ResolvedConceptDto::iri).containsExactly(B);
        });
        assertThat(byDiagram.get(2L)).satisfies(p -> {
            assertThat(p.domain().iri()).isEqualTo(C);
            assertThat(p.range().iri()).as("live range kept").isEqualTo(B);
            assertThat(p.broader()).as("staged clear").isEmpty();
            assertThat(p.exactMatch()).as("a blank IRI resolves to nothing").isEmpty();
        });
        assertThat(byDiagram.get(3L)).satisfies(p -> {
            assertThat(p.pending()).as("a malformed overlay is skipped, not fatal").isFalse();
            assertThat(p.kind()).isEqualTo(DiagramConceptUsageKind.PROPERTY_ROW);
            assertThat(p.hostClass().iri()).isEqualTo(HOST);
            assertThat(p.exactMatch()).extracting(ResolvedConceptDto::iri).containsExactly(A);
        });
    }

    @Test
    void blankOverlayJson_isNoOverlay_andAnUnresolvedSelfHasNoEndpoints() {
        concept(SELF, "Pojem");
        placedOn(SELF, new Usage(1L, "D1", "slovnik", "EDGE", null));
        overlays(SELF, List.of(1L), new Overlay(1L, "  "));
        live(SELF, List.of(), List.of());
        resolving();

        assertThat(service.usageForSlug("self").placements()).singleElement().satisfies(p -> {
            assertThat(p.pending()).isFalse();
            assertThat(p.domain()).isNull();
            assertThat(p.range()).isNull();
            assertThat(p.broader()).isEmpty();
        });
    }

    @Test
    void nullOverlayJson_isNoOverlay() {
        concept(SELF, "Pojem");
        placedOn(SELF, new Usage(1L, "D1", "slovnik", "NODE", null));
        overlays(SELF, List.of(1L), new Overlay(1L, null));
        live(SELF, List.of(), List.of());
        resolving(resolved(SELF));

        assertThat(service.usageForSlug("self").placements()).singleElement()
                .satisfies(p -> assertThat(p.pending()).isFalse());
    }
}
