package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.client.NkdSparqlClient.PublishedConcept;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.utility.published.NkdSnapshotMaterializer;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkdSnapshotServiceImplTest {

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";
    private static final String NKD_SCHEME = "https://slovník.gov.cz/agendový/104";
    private static final String OWNER_GRAPH = "https://example.org/slovnik/mestys";
    private static final String OWNER_IRI = OWNER_GRAPH + "/pojem/budova";

    @Mock private NkdConceptSnapshotRepository snapshotRepository;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private ConceptDeviationComparator conceptDeviationComparator;
    @Spy private NkdSnapshotMaterializer materializer = new NkdSnapshotMaterializer();

    @InjectMocks private NkdSnapshotServiceImpl service;

    private ConceptMetadataEntity owner;

    @BeforeEach
    void setUp() {
        owner = new ConceptMetadataEntity();
        owner.setId(17L);
        owner.setConceptIri(OWNER_IRI);
        owner.setGraphName(OWNER_GRAPH);
    }

    private Model rawNkd(String label) {
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.getResource(NKD_IRI);
        m.add(c, m.createProperty(SKOS_IN_SCHEME), m.getResource(NKD_SCHEME));
        m.add(c, m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"), label);
        return m;
    }

    private ConceptDetailModel detail() {
        return ConceptDetailModel.builder().iri(NKD_IRI).build();
    }

    @Test
    void createOrRefresh_rejectsDisallowedLinkPredicate() {
        OwnerChangeSet cs = new OwnerChangeSet();
        assertThatThrownBy(() -> service.createOrRefreshSnapshot(owner, NKD_IRI, "domain", cs))
                .isInstanceOf(OntologyValidationException.class)   // → HTTP 400, not 500
                .hasMessageContaining("domain");

        // Nothing fetched, nothing enqueued.
        assertThat(cs.toAdd).isEmpty();
        assertThat(cs.toRemove).isEmpty();
    }

    @Test
    void createOrRefresh_nkdFound_createsRowWithMaterializedTriples_noTdb2Delta() {
        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.empty());
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI))
                .thenReturn(Optional.of(new PublishedConcept(detail(), NKD_SCHEME)));
        when(nkdSparqlClient.fetchPublishedConceptRaw(NKD_IRI)).thenReturn(Optional.of(rawNkd("Adresní místo")));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerChangeSet cs = new OwnerChangeSet();
        NkdConceptSnapshotEntity saved = service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        assertThat(saved).isNotNull();
        assertThat(saved.getOrigin()).isEqualTo(SnapshotOrigin.LINK_TARGET);
        // The copy lives ONLY in the PG column — the provenance marker is present there ...
        assertThat(saved.getMaterializedTriples()).isNotBlank()
                .contains(NkdSnapshotMaterializer.NKD_SNAPSHOT_OF);
        // ... and NOTHING copy-related is contributed to the TDB2 change set.
        assertThat(cs.toRemove).isEmpty();
        assertThat(cs.toAdd).isEmpty();
    }

    @Test
    void createOrRefresh_reSnapshot_updatesPgCopy_noTdb2Delta() {
        // Existing row with a STORED old triple set (label "Old").
        NkdConceptSnapshotEntity existing = new NkdConceptSnapshotEntity();
        existing.setOwningConcept(owner);
        existing.setNkdIri(NKD_IRI);
        existing.setGraphName(OWNER_GRAPH);
        existing.setOrigin(SnapshotOrigin.LINK_TARGET);
        existing.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        existing.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("Old"), NKD_IRI, OWNER_IRI)));

        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.of(existing));
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI))
                .thenReturn(Optional.of(new PublishedConcept(detail(), NKD_SCHEME)));
        when(nkdSparqlClient.fetchPublishedConceptRaw(NKD_IRI)).thenReturn(Optional.of(rawNkd("New")));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerChangeSet cs = new OwnerChangeSet();
        service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        // The PG copy is refreshed to the NEW payload; the old label is gone from it.
        assertThat(existing.getMaterializedTriples()).contains("New").doesNotContain("Old");
        // No copy triples flow to TDB2.
        assertThat(cs.toRemove).isEmpty();
        assertThat(cs.toAdd).isEmpty();
    }

    @Test
    void createOrRefresh_nkdEmpty_existingSnapshot_deletesRow_noTdb2Delta() {
        NkdConceptSnapshotEntity existing = new NkdConceptSnapshotEntity();
        existing.setOwningConcept(owner);
        existing.setNkdIri(NKD_IRI);
        existing.setGraphName(OWNER_GRAPH);
        existing.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        existing.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("Gone"), NKD_IRI, OWNER_IRI)));

        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.of(existing));
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI)).thenReturn(Optional.empty());

        OwnerChangeSet cs = new OwnerChangeSet();
        NkdConceptSnapshotEntity result = service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        assertThat(result).isNull();
        verify(snapshotRepository).delete(existing);
        // The copy lived only in PG (row deleted); no TDB2 copy triple to remove. The dangling owner link
        // triple is the edit's own change-set concern, not create/refresh (no owner-graph view here).
        assertThat(cs.toRemove).isEmpty();
        assertThat(cs.toAdd).isEmpty();
    }

    @Test
    void createOrRefresh_nkdOutage_skipsAndKeepsExisting_noThrow_noRemoval() {
        // NKD UNAVAILABLE (not confirmed-absent): the strict fetch throws. Must skip — NOT remove the
        // existing snapshot (that would delete a valid copy on a transient blip) and NOT propagate (the
        // edit hook runs this in its tx; a throw would roll back a valid edit).
        NkdConceptSnapshotEntity existing = new NkdConceptSnapshotEntity();
        existing.setOwningConcept(owner);
        existing.setNkdIri(NKD_IRI);
        existing.setGraphName(OWNER_GRAPH);
        existing.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());

        when(snapshotRepository.findByOwningConceptIdAndNkdIri(17L, NKD_IRI)).thenReturn(Optional.of(existing));
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(NKD_IRI))
                .thenThrow(new com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException(
                        "NKD", "down", null));

        OwnerChangeSet cs = new OwnerChangeSet();
        NkdConceptSnapshotEntity result =
                service.createOrRefreshSnapshot(owner, NKD_IRI, SnapshotLinkType.BROADER_CLASS.value(), cs);

        assertThat(result).isSameAs(existing);       // returns the untouched existing snapshot
        verify(snapshotRepository, never()).delete(any());
        assertThat(cs.toRemove).isEmpty();
        assertThat(cs.toAdd).isEmpty();
    }

    /** Owner's outgoing triples: a subClassOf link + the namespaced hierarchy prop, both → nkdIri. */
    private Set<Statement> ownerOutgoingToNkd() {
        Model m = ModelFactory.createDefaultModel();
        Resource owner = m.getResource(OWNER_IRI);
        Resource nkd = m.getResource(NKD_IRI);
        m.add(owner, m.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"), nkd);
        m.add(owner, m.createProperty(OWNER_GRAPH + "/nadřazená-třída"), nkd);
        m.add(owner, m.createProperty("http://example.org/unrelated"), m.getResource("http://other/x"));
        return m.listStatements().toSet();
    }

    @Test
    void removeSnapshotAndLink_dropsAllLinkPredicates_andDeletesRow() {
        NkdConceptSnapshotEntity snap = snapshotRow();

        OwnerChangeSet cs = new OwnerChangeSet();
        service.removeSnapshotAndLink(snap, ownerOutgoingToNkd(), cs);

        // BOTH link predicates to nkdIri removed (subClassOf + hierarchy prop); unrelated edge kept.
        long ownerLinksRemoved = cs.toRemove.stream()
                .filter(s -> s.getSubject().getURI().equals(OWNER_IRI)
                        && s.getObject().isURIResource()
                        && s.getObject().asResource().getURI().equals(NKD_IRI))
                .count();
        assertThat(ownerLinksRemoved).isEqualTo(2);
        assertThat(cs.toRemove).noneMatch(s -> s.getObject().isURIResource()
                && s.getObject().asResource().getURI().equals("http://other/x"));
        // The copy lives only in PG (row deleted) — no copy triple (subject = nkdIri) in the TDB2 delta.
        assertThat(cs.toRemove).noneMatch(s -> s.getSubject().getURI().equals(NKD_IRI));
        verify(snapshotRepository).delete(snap);
    }

    private NkdConceptSnapshotEntity snapshotRow() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setOwningConcept(owner);
        snap.setNkdIri(NKD_IRI);
        snap.setGraphName(OWNER_GRAPH);
        snap.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        snap.setMaterializedTriples(materializer.toNTriples(
                materializer.materialize(rawNkd("X"), NKD_IRI, OWNER_IRI)));
        return snap;
    }

    @Test
    void evaluateDeviation_cachesStatusAndTimestamp() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setNkdIri(NKD_IRI);
        snap.setSnapshot(detail());

        when(nkdSparqlClient.fetchPublishedConcept(NKD_IRI)).thenReturn(Optional.of(detail()));
        // The snapshot path stamps the deviation with its origin + the NKD IRI it tracks (Phase D).
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.HAS_DEVIATIONS).build());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PublishedConceptDeviationModel result = service.evaluateDeviation(snap);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(snap.getLastDeviationStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(snap.getLastCheckedAt()).isNotNull();
    }

    @Test
    void evaluateDeviation_conceptGoneFromNkd_returnsNotFound() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        snap.setNkdIri(NKD_IRI);
        snap.setSnapshot(detail());

        when(nkdSparqlClient.fetchPublishedConcept(NKD_IRI)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PublishedConceptDeviationModel result = service.evaluateDeviation(snap);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        verify(conceptDeviationComparator, never()).compareConceptDetails(any(), any());
    }

    @Test
    void findForConcept_delegatesToRepository() {
        NkdConceptSnapshotEntity snap = new NkdConceptSnapshotEntity();
        when(snapshotRepository.findByOwningConceptId(17L)).thenReturn(List.of(snap));
        assertThat(service.findForConcept(17L)).containsExactly(snap);
    }

    // ========== Phase 10: deletion cascades ==========

    private NkdConceptSnapshotEntity rowFor(String nkdIri) {
        NkdConceptSnapshotEntity s = new NkdConceptSnapshotEntity();
        s.setNkdIri(nkdIri);
        s.setGraphName(OWNER_GRAPH);
        return s;
    }

    @Test
    void cascadeConceptDeletion_deletesRowsForGraph_noTdb2Sweep() {
        NkdConceptSnapshotEntity rowA = rowFor(NKD_IRI);
        NkdConceptSnapshotEntity rowB = rowFor(NKD_IRI);
        when(snapshotRepository.findByOwningConceptId(17L)).thenReturn(List.of(rowA));
        when(snapshotRepository.findByOwningConceptId(18L)).thenReturn(List.of(rowB));

        service.cascadeConceptDeletion(List.of(17L, 18L), OWNER_GRAPH);

        // The copy lives only in PG — the rows are dropped, nothing to sweep from TDB2.
        verify(snapshotRepository).deleteAll(List.of(rowA, rowB));
    }

    @Test
    void cascadeConceptDeletion_filtersOtherGraphRows() {
        NkdConceptSnapshotEntity thisGraph = rowFor(NKD_IRI);
        NkdConceptSnapshotEntity otherGraph = rowFor(NKD_IRI);
        otherGraph.setGraphName("https://example.org/slovnik/jiny");
        when(snapshotRepository.findByOwningConceptId(17L)).thenReturn(List.of(thisGraph, otherGraph));

        service.cascadeConceptDeletion(List.of(17L), OWNER_GRAPH);

        verify(snapshotRepository).deleteAll(List.of(thisGraph));
    }

    @Test
    void cascadeConceptDeletion_noSnapshots_deletesNothing() {
        when(snapshotRepository.findByOwningConceptId(17L)).thenReturn(List.of());
        service.cascadeConceptDeletion(List.of(17L), OWNER_GRAPH);
        verify(snapshotRepository, never()).deleteAll(any());
    }

    @Test
    void cascadeGraphDeletion_deletesAllRowsForGraph_noCopyRemoval() {
        NkdConceptSnapshotEntity r1 = rowFor(NKD_IRI);
        NkdConceptSnapshotEntity r2 = rowFor("https://slovník.gov.cz/agendový/104/pojem/jine");
        when(snapshotRepository.findByGraphName(OWNER_GRAPH)).thenReturn(List.of(r1, r2));

        service.cascadeGraphDeletion(OWNER_GRAPH);

        // No refcount / copy-triple bookkeeping — the copy lives only in PG.
        verify(snapshotRepository).deleteAll(List.of(r1, r2));
    }
}
