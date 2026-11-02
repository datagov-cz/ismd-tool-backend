package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.DiagramCascadeConflictException;
import com.dia.ismdtoolbackend.exception.DiagramForeignConceptException;
import com.dia.ismdtoolbackend.exception.DiagramStaleBaseException;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for applying one staged overlay: the outcome, the edit model handed to {@link ConceptService},
 * the graph guards, and the membership rekeys. Repositories and the concept service are mocked; the graph
 * is a real Jena model parsed from Turtle.
 */
@ExtendWith(MockitoExtension.class)
class DiagramChangeApplierTest {

    private static final long DIAGRAM_ID = 7L;
    private static final String GRAPH = Ttl.VOCABULARY;
    private static final String FOREIGN_GRAPH = "https://slovník.gov.cz/datový/cizí/";
    private static final String A = Ttl.POJEM + "a";
    private static final String B = Ttl.POJEM + "b";
    private static final String C = Ttl.POJEM + "c";
    private static final String D = Ttl.POJEM + "d";
    private static final String REL = Ttl.POJEM + "rel";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0);

    @Mock private ConceptService conceptService;
    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private DiagramPendingEditRepository pendingEditRepository;
    @Mock private DiagramEdgeRepository diagramEdgeRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;

    @InjectMocks private DiagramChangeApplier applier;

    @BeforeAll
    static void initJena() {
        Ttl.initJena();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private static ConceptMetadataEntity concept(long id, String iri, ConceptType type, String graph) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setId(id);
        c.setConceptIri(iri);
        c.setConceptType(type);
        c.setGraphName(graph);
        c.setUpdatedAt(T0);
        return c;
    }

    private DiagramPendingEditEntity stage(String iri, DiagramPendingEdit edit) {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName(GRAPH);
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setOntologyMetadata(ontology);
        row.setConceptIri(iri);
        if (edit != null) {
            row.setPendingEdit(edit);
        } else {
            row.setPendingEditJson("{malformed");
        }
        when(pendingEditRepository.findByDiagramIdAndConceptIri(DIAGRAM_ID, iri)).thenReturn(Optional.of(row));
        return row;
    }

    private void known(ConceptMetadataEntity c) {
        when(conceptMetadataRepository.findByConceptIri(c.getConceptIri())).thenReturn(Optional.of(c));
    }

    private static DiagramEdgeEntity edgeRow(long id, String key) {
        DiagramEntity diagram = new DiagramEntity();
        diagram.setId(DIAGRAM_ID);
        DiagramEdgeEntity e = new DiagramEdgeEntity();
        e.setId(id);
        e.setDiagram(diagram);
        e.setEdgeKey(key);
        return e;
    }

    private static String subclass(String source, String target) {
        return DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, source, target);
    }

    private static Model graph(String body) {
        return Ttl.parse(Ttl.PREFIXES + body);
    }

    private static DiagramPendingEdit broader(String... targets) {
        DiagramPendingEdit e = new DiagramPendingEdit();
        e.setBroaderConcept(List.of(targets));
        return e;
    }

    private static DiagramPendingEdit convert(String addBroaderOn, String broader) {
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(addBroaderOn);
        marker.setBroader(broader);
        DiagramPendingEdit e = new DiagramPendingEdit();
        e.setConvertToHierarchy(marker);
        return e;
    }

    // ---- outcome --------------------------------------------------------------------------------

    @Test
    void noStagedRow_isNothingStaged() {
        when(pendingEditRepository.findByDiagramIdAndConceptIri(DIAGRAM_ID, A)).thenReturn(Optional.empty());

        assertThat(applier.applyChange(DIAGRAM_ID, A))
                .isEqualTo(new DiagramChangeApplier.Outcome(null, DiagramChangeApplier.Outcome.Kind.NOTHING_STAGED));
        verifyNoInteractions(conceptService);
    }

    /** A row whose JSON no longer parses reads as no edit at all. */
    @Test
    void stagedRowWithUnreadableEdit_isNothingStaged() {
        stage(A, null);

        assertThat(applier.applyChange(DIAGRAM_ID, A).kind())
                .isEqualTo(DiagramChangeApplier.Outcome.Kind.NOTHING_STAGED);
        verifyNoInteractions(conceptService);
    }

    @Test
    void deletedConcept_isSkippedStale_andKeepsTheOverlay() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(B);
        stage(A, edit);
        when(conceptMetadataRepository.findByConceptIri(A)).thenReturn(Optional.empty());

        assertThat(applier.applyChange(DIAGRAM_ID, A)).isEqualTo(new DiagramChangeApplier.Outcome(
                DiagramOp.CHANGE_PROPERTY_PARENT, DiagramChangeApplier.Outcome.Kind.SKIPPED_STALE));
        verify(pendingEditRepository, never()).delete(any());
    }

    @Test
    void conceptOfAnotherGraph_isRejectedAsForeign() {
        stage(A, broader(B));
        known(concept(1, A, ConceptType.TRIDA, FOREIGN_GRAPH));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, A))
                .isInstanceOf(DiagramForeignConceptException.class)
                .hasMessageContaining(A);
        verifyNoInteractions(conceptService);
    }

    @Test
    void conceptEditedSinceStaging_isStaleBase() {
        DiagramPendingEdit edit = broader(B);
        edit.setBaseUpdatedAt(T0.minusMinutes(5));
        stage(A, edit);
        known(concept(1, A, ConceptType.TRIDA, GRAPH));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, A))
                .isInstanceOf(DiagramStaleBaseException.class);
        verifyNoInteractions(conceptService);
    }

    @Test
    void matchingFingerprint_materializesAndClearsTheOverlay() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setExactMatch(List.of(C));
        edit.setBaseUpdatedAt(T0);
        DiagramPendingEditEntity row = stage(A, edit);
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of());

        assertThat(applier.applyChange(DIAGRAM_ID, A)).isEqualTo(new DiagramChangeApplier.Outcome(
                DiagramOp.CHANGE_HIERARCHY_TYPE, DiagramChangeApplier.Outcome.Kind.MATERIALIZED));

        ArgumentCaptor<ClassConceptEditModel> model = ArgumentCaptor.forClass(ClassConceptEditModel.class);
        verify(conceptService).editConcept(eq(1L), model.capture());
        assertThat(model.getValue().getExactMatch()).containsExactly(C);
        assertThat(model.getValue().getBroaderConcept()).isNull();
        verify(pendingEditRepository).delete(row);
    }

    // ---- edit model per concept type ------------------------------------------------------------

    @Test
    void propertyEdit_carriesDomainAndExactMatchOnly() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(B);
        stage(A, edit);
        known(concept(1, A, ConceptType.VLASTNOST, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, GRAPH));

        applier.applyChange(DIAGRAM_ID, A);

        ArgumentCaptor<PropertyConceptEditModel> model = ArgumentCaptor.forClass(PropertyConceptEditModel.class);
        verify(conceptService).editConcept(eq(1L), model.capture());
        assertThat(model.getValue().getDomain()).isEqualTo(B);
        assertThat(model.getValue().getConceptType()).isEqualTo(ConceptType.VLASTNOST.getValue());
    }

    @Test
    void swapDirection_writesBothEndpointsOfTheRelationship() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(B);
        edit.setRange(A);
        stage(REL, edit);
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, GRAPH));

        assertThat(applier.applyChange(DIAGRAM_ID, REL).op()).isEqualTo(DiagramOp.SWAP_DIRECTION);

        ArgumentCaptor<RelationshipConceptEditModel> model =
                ArgumentCaptor.forClass(RelationshipConceptEditModel.class);
        verify(conceptService).editConcept(eq(3L), model.capture());
        assertThat(model.getValue().getDomain()).isEqualTo(B);
        assertThat(model.getValue().getRange()).isEqualTo(A);
    }

    @Test
    void rolelessConcept_cannotBeMaterialized() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange(B);
        stage(A, edit);
        known(concept(1, A, ConceptType.KONCEPT, GRAPH));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, A))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining("KONCEPT");
        verify(pendingEditRepository, never()).delete(any());
    }

    // ---- domain graph guard ---------------------------------------------------------------------

    @Test
    void domainInAnotherGraph_isRejectedAsForeign() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(B);
        stage(A, edit);
        known(concept(1, A, ConceptType.VLASTNOST, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, FOREIGN_GRAPH));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, A))
                .isInstanceOf(DiagramForeignConceptException.class)
                .hasMessageContaining(B);
        verifyNoInteractions(conceptService);
    }

    @Test
    void deletedOwnGraphDomain_isStaleBase() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(B);
        stage(A, edit);
        known(concept(1, A, ConceptType.VLASTNOST, GRAPH));
        when(conceptMetadataRepository.findByConceptIri(B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, A))
                .isInstanceOf(DiagramStaleBaseException.class)
                .hasMessageContaining(B);
        verifyNoInteractions(conceptService);
    }

    @Test
    void unresolvableExternalDomain_isWrittenAsAPlainObject() {
        String external = "https://example.org/external/trida";
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(external);
        stage(A, edit);
        known(concept(1, A, ConceptType.VLASTNOST, GRAPH));
        when(conceptMetadataRepository.findByConceptIri(external)).thenReturn(Optional.empty());

        assertThat(applier.applyChange(DIAGRAM_ID, A).kind())
                .isEqualTo(DiagramChangeApplier.Outcome.Kind.MATERIALIZED);
        ArgumentCaptor<PropertyConceptEditModel> model = ArgumentCaptor.forClass(PropertyConceptEditModel.class);
        verify(conceptService).editConcept(eq(1L), model.capture());
        assertThat(model.getValue().getDomain()).isEqualTo(external);
    }

    // ---- membership rekeys ----------------------------------------------------------------------

    @Test
    void repointedParent_movesTheMembershipRowOntoTheNewKey() {
        stage(A, broader(C));
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity row = edgeRow(10, subclass(A, B));
        DiagramEdgeEntity unrelated = edgeRow(11, subclass(C, D));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(row, unrelated));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph(
                "<" + A + "> rdfs:subClassOf <" + B + "> , [ a owl:Class ] ."));

        applier.applyChange(DIAGRAM_ID, A);

        assertThat(row.getEdgeKey()).isEqualTo(subclass(A, C));
        assertThat(unrelated.getEdgeKey()).isEqualTo(subclass(C, D));
        verify(diagramEdgeRepository).save(row);
        verify(diagramEdgeRepository, never()).delete(any());
    }

    /** A staged clear has no n-th target to pair with a live one, so no row moves. */
    @Test
    void stagedClear_movesNoRows() {
        stage(A, broader());
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity row = edgeRow(10, subclass(A, B));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(row));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph("<" + A + "> rdfs:subClassOf <" + B + "> ."));

        applier.applyChange(DIAGRAM_ID, A);

        assertThat(row.getEdgeKey()).isEqualTo(subclass(A, B));
        verify(diagramEdgeRepository, never()).save(any());
        ArgumentCaptor<ClassConceptEditModel> model = ArgumentCaptor.forClass(ClassConceptEditModel.class);
        verify(conceptService).editConcept(eq(1L), model.capture());
        assertThat(model.getValue().getBroaderConcept()).isEmpty();
    }

    @Test
    void rekeyOntoAnAlreadyPlacedKey_dropsTheMovedRow() {
        stage(A, broader(C));
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity moving = edgeRow(10, subclass(A, B));
        DiagramEdgeEntity alreadyThere = edgeRow(11, subclass(A, C));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(moving, alreadyThere));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph("<" + A + "> rdfs:subClassOf <" + B + "> ."));

        applier.applyChange(DIAGRAM_ID, A);

        verify(diagramEdgeRepository).delete(moving);
        verify(diagramEdgeRepository, never()).save(any());
        assertThat(moving.getEdgeKey()).isEqualTo(subclass(A, B));
    }

    @Test
    void keptAndAddedTargets_moveNoRows() {
        // Position 0 keeps B, position 1 adds D with no live counterpart; the row for B stays put.
        stage(A, broader(B, D));
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity row = edgeRow(10, subclass(A, B));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(row));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph(
                "<" + A + "> rdfs:subClassOf <" + B + "> ; skos:exactMatch <" + C + "> ."));

        applier.applyChange(DIAGRAM_ID, A);

        assertThat(row.getEdgeKey()).isEqualTo(subclass(A, B));
        verify(diagramEdgeRepository, never()).save(any());
        verify(diagramEdgeRepository, never()).delete(any());
    }

    @Test
    void repointedTargetWithNoPlacedRow_movesNothing() {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setExactMatch(List.of(D));
        stage(A, edit);
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity unrelated = edgeRow(10, subclass(A, B));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(unrelated));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph("<" + A + "> skos:exactMatch <" + C + "> ."));

        applier.applyChange(DIAGRAM_ID, A);

        assertThat(unrelated.getEdgeKey()).isEqualTo(subclass(A, B));
        verify(diagramEdgeRepository, never()).save(any());
    }

    @Test
    void stagedNullTarget_isNotARekey() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setBroaderConcept(withNull);
        stage(A, edit);
        known(concept(1, A, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity row = edgeRow(10, subclass(A, B));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(row));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph("<" + A + "> rdfs:subClassOf <" + B + "> ."));

        applier.applyChange(DIAGRAM_ID, A);

        assertThat(row.getEdgeKey()).isEqualTo(subclass(A, B));
        verify(diagramEdgeRepository, never()).save(any());
    }

    // ---- op 6 -----------------------------------------------------------------------------------

    @Test
    void convert_mergesExistingParents_deletesTheVztah_andRekeysItsRow() {
        stage(REL, convert(B, A));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, GRAPH));
        DiagramEdgeEntity relRow = edgeRow(10, REL);
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of(relRow));
        when(jenaTDB2Repository.findRelatedConceptUris(REL, GRAPH)).thenReturn(List.of());
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph(
                "<" + B + "> rdfs:subClassOf <" + C + "> , <" + A + "> , [ a owl:Class ] ."));

        assertThat(applier.applyChange(DIAGRAM_ID, REL)).isEqualTo(new DiagramChangeApplier.Outcome(
                DiagramOp.CONVERT_TO_HIERARCHY, DiagramChangeApplier.Outcome.Kind.MATERIALIZED));

        ArgumentCaptor<ClassConceptEditModel> model = ArgumentCaptor.forClass(ClassConceptEditModel.class);
        verify(conceptService).editConcept(eq(2L), model.capture());
        assertThat(model.getValue().getBroaderConcept())
                .as("existing parents carried through, the already-present new one not duplicated")
                .containsExactlyInAnyOrder(C, A)
                .doesNotContainNull()
                .hasSize(2);
        verify(conceptService).deleteConcept(3L);
        assertThat(relRow.getEdgeKey()).isEqualTo(subclass(B, A));
    }

    @Test
    void convert_withNoRelatedList_appendsTheNewParent() {
        stage(REL, convert(B, A));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, GRAPH));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of());
        when(jenaTDB2Repository.findRelatedConceptUris(REL, GRAPH)).thenReturn(null);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph("<" + B + "> rdfs:subClassOf <" + C + "> ."));

        applier.applyChange(DIAGRAM_ID, REL);

        ArgumentCaptor<ClassConceptEditModel> model = ArgumentCaptor.forClass(ClassConceptEditModel.class);
        verify(conceptService).editConcept(eq(2L), model.capture());
        assertThat(model.getValue().getBroaderConcept()).containsExactly(C, A);
    }

    @Test
    void convert_referencedByAnotherConcept_isACascadeConflict() {
        stage(REL, convert(B, A));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of());
        when(jenaTDB2Repository.findRelatedConceptUris(REL, GRAPH)).thenReturn(List.of(D));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, REL))
                .isInstanceOf(DiagramCascadeConflictException.class);
        verify(conceptService, never()).deleteConcept(anyLong());
        verify(conceptService, never()).editConcept(anyLong(), any());
    }

    @Test
    void convert_withAMissingTargetClass_isAValidationFailure() {
        stage(REL, convert(B, A));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of());
        when(jenaTDB2Repository.findRelatedConceptUris(REL, GRAPH)).thenReturn(List.of());
        when(conceptMetadataRepository.findByConceptIri(B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, REL))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining(B);
        verify(conceptService, never()).deleteConcept(anyLong());
    }

    @Test
    void convert_ontoAForeignClass_isRejected() {
        stage(REL, convert(B, A));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));
        known(concept(2, B, ConceptType.TRIDA, FOREIGN_GRAPH));
        when(diagramEdgeRepository.findByDiagramId(DIAGRAM_ID)).thenReturn(List.of());
        when(jenaTDB2Repository.findRelatedConceptUris(REL, GRAPH)).thenReturn(List.of());

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, REL))
                .isInstanceOf(DiagramForeignConceptException.class);
        verify(conceptService, never()).deleteConcept(anyLong());
    }

    /** An op-6 marker missing either end is rejected before anything is read or written. */
    @ParameterizedTest
    @CsvSource(value = {"NULL, A", "'', A", "' ', A", "B, NULL", "B, ''", "B, ' '"}, nullValues = "NULL")
    void convert_withAnIncompleteMarker_isRejectedBeforeAnyChange(String addBroaderOn, String broader) {
        stage(REL, convert(resolve(addBroaderOn), resolve(broader)));
        known(concept(3, REL, ConceptType.VZTAH, GRAPH));

        assertThatThrownBy(() -> applier.applyChange(DIAGRAM_ID, REL))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining(REL);
        verify(jenaTDB2Repository, never()).findRelatedConceptUris(any(), any());
        verify(diagramEdgeRepository, never()).findByDiagramId(anyLong());
        verify(conceptService, never()).editConcept(anyLong(), any());
        verify(conceptService, never()).deleteConcept(anyLong());
    }

    private static String resolve(String token) {
        if (token == null) return null;
        return switch (token) {
            case "A" -> A;
            case "B" -> B;
            default -> token;
        };
    }

    // ---- classify -------------------------------------------------------------------------------

    @Test
    void classify_namesTheOpFromTheOverlayFieldsAlone() {
        DiagramPendingEdit domainOnly = new DiagramPendingEdit();
        domainOnly.setDomain(A);
        DiagramPendingEdit rangeOnly = new DiagramPendingEdit();
        rangeOnly.setRange(A);
        DiagramPendingEdit both = new DiagramPendingEdit();
        both.setDomain(A);
        both.setRange(B);
        DiagramPendingEdit exact = new DiagramPendingEdit();
        exact.setExactMatch(List.of());

        assertThat(applier.classify(convert(A, B))).isEqualTo(DiagramOp.CONVERT_TO_HIERARCHY);
        assertThat(applier.classify(both)).isEqualTo(DiagramOp.SWAP_DIRECTION);
        assertThat(applier.classify(broader(A))).isEqualTo(DiagramOp.CHANGE_HIERARCHY_TYPE);
        assertThat(applier.classify(exact)).isEqualTo(DiagramOp.CHANGE_HIERARCHY_TYPE);
        assertThat(applier.classify(domainOnly)).isEqualTo(DiagramOp.CHANGE_PROPERTY_PARENT);
        assertThat(applier.classify(rangeOnly)).isEqualTo(DiagramOp.CHANGE_PROPERTY_PARENT);
    }
}
