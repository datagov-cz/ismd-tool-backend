package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsistencyReconcilerTest {

    private static final String GRAPH = "https://slovník.gov.cz/g";
    private static final String PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private PgMetadataSnapshot pgMetadataSnapshot;

    private ConsistencyReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ConsistencyReconciler(jenaTDB2Repository, pgMetadataSnapshot);
        // Default: empty stores. Individual tests override via stubPg(...) / listNamedGraphs.
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of());
        stubPg(List.of(), List.of());
        lenient().when(jenaTDB2Repository.fetchConceptLabels(anyList()))
                .thenReturn(ModelFactory.createDefaultModel());
    }

    /** Stubs the PG snapshot from concept rows + known ontology graph names. */
    private void stubPg(List<ConceptMetadataEntity> concepts, List<String> knownGraphs) {
        Map<String, ConceptMetadataEntity> byIri = new java.util.HashMap<>();
        for (ConceptMetadataEntity c : concepts) {
            if (c.getConceptIri() != null) {
                byIri.put(c.getConceptIri(), c);
            }
        }
        when(pgMetadataSnapshot.load()).thenReturn(
                new PgMetadataSnapshot.Snapshot(concepts, byIri, new java.util.HashSet<>(knownGraphs)));
    }

    private ConceptMetadataEntity pgConcept(String iri, String graph, String name, boolean published) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setGraphName(graph);
        c.setConceptName(name);
        c.setSlug(name);
        c.setIsPublished(published);
        return c;
    }

    private Model labelModel(String iri, String label) {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(iri), m.createProperty(PREF_LABEL), label);
        return m;
    }

    private long count(ReconciliationReport r, MismatchCategory c) {
        return r.mismatches().stream().filter(m -> m.category() == c).count();
    }

    @Test
    void cleanState_noMismatches() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/a";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, GRAPH, "a", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(0, r.totalMismatches(), "Matching PG + TDB2 yields no findings");
        assertEquals(1, r.ownedRdfConceptsScanned());
        assertEquals(1, r.pgConceptsScanned());
    }

    @Test
    void rdfOrphan_ownedRdfWithNoPgRow() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/orphan";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(), List.of(GRAPH)); // no PG concept rows

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.RDF_ORPHAN));
        assertEquals(iri, r.mismatches().get(0).conceptIri());
    }

    @Test
    void pgMissingRdf_pgRowNotOwnedResolvable() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of());
        String iri = GRAPH + "/pojem/gone";
        stubPg(List.of(pgConcept(iri, GRAPH, "gone", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.PG_MISSING_RDF));
    }

    @Test
    void draftConcept_inScope_andClean() {
        // A draft (isPublished=false) with full owned RDF + PG row must NOT be flagged —
        // proves there's no published-only filter suppressing or mis-flagging drafts.
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/draft";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, GRAPH, "draft", false)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(0, r.totalMismatches(), "A draft with both stores in sync is clean");
    }

    @Test
    void referencedNkdConcept_notFlagged() {
        // The NKD concept is referenced object-only, so it never appears in the OWNED set
        // (listOwnedConceptIrisInGraph already excludes it). The reconciler must not invent
        // an orphan for it. We model that by simply not returning it as owned.
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String local = GRAPH + "/pojem/local";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(local));
        stubPg(List.of(pgConcept(local, GRAPH, "local", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(0, r.totalMismatches(),
                "A referenced NKD concept (not in the owned set) produces no RDF_ORPHAN");
    }

    @Test
    void graphOrphan_tdb2GraphWithNoOntologyRow() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/x";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, GRAPH, "x", true)), List.of()); // no ontology row for GRAPH

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.GRAPH_ORPHAN));
    }

    @Test
    void iriGraphMismatch_pgGraphDiffersFromWhereOwned() {
        String otherGraph = "https://slovník.gov.cz/other";
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/m";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        // PG says this concept's graphName is a DIFFERENT graph than where it's owned-resolvable.
        stubPg(List.of(pgConcept(iri, otherGraph, "m", true)), List.of(GRAPH, otherGraph));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.IRI_GRAPH_MISMATCH));
        assertEquals(0, count(r, MismatchCategory.PG_MISSING_RDF), "Found elsewhere ≠ missing");
    }

    @Test
    void suspectedRename_pairsOrphanAndMissing_notTwoOrphans() {
        // TDB2 has the NEW IRI (orphan); PG still has the OLD IRI (missing). Same graph,
        // same label, IRIs differ only in the /pojem/ tail → ONE SUSPECTED_RENAME.
        String newIri = GRAPH + "/pojem/novy-nazev";
        String oldIri = GRAPH + "/pojem/stary-nazev";
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(newIri));
        stubPg(List.of(pgConcept(oldIri, GRAPH, "Adresa", true)), List.of(GRAPH));
        // The orphan's TDB2 prefLabel matches the PG row's conceptName.
        when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenReturn(labelModel(newIri, "Adresa"));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.SUSPECTED_RENAME));
        assertEquals(0, count(r, MismatchCategory.RDF_ORPHAN), "Paired orphan must not also be RDF_ORPHAN");
        assertEquals(0, count(r, MismatchCategory.PG_MISSING_RDF), "Paired old IRI must not also be PG_MISSING_RDF");
        Mismatch rename = r.mismatches().stream()
                .filter(m -> m.category() == MismatchCategory.SUSPECTED_RENAME).findFirst().orElseThrow();
        assertEquals(newIri, rename.conceptIri(), "conceptIri = new (TDB2) IRI");
        assertEquals(oldIri, rename.relatedIri(), "relatedIri = old (PG) IRI");
    }

    @Test
    void suspectedRename_doesNotPair_whenLabelDiffers() {
        // Same /pojem/ prefix but labels don't match → NOT a rename; stays two findings.
        String newIri = GRAPH + "/pojem/aaa";
        String oldIri = GRAPH + "/pojem/bbb";
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(newIri));
        stubPg(List.of(pgConcept(oldIri, GRAPH, "Completely Different", true)), List.of(GRAPH));
        when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenReturn(labelModel(newIri, "Something Else"));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(0, count(r, MismatchCategory.SUSPECTED_RENAME));
        assertEquals(1, count(r, MismatchCategory.RDF_ORPHAN));
        assertEquals(1, count(r, MismatchCategory.PG_MISSING_RDF));
    }

    @Test
    void reentrancyGuard_secondConcurrentRunReturnsNull() throws Exception {
        // Hold the run inside listNamedGraphs while a second thread tries to reconcile.
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(jenaTDB2Repository.listNamedGraphs()).thenAnswer(inv -> {
            inside.countDown();
            release.await();
            return List.of();
        });

        Thread first = new Thread(() -> reconciler.reconcile("FIRST"));
        first.start();
        assertTrue(inside.await(2, java.util.concurrent.TimeUnit.SECONDS), "first run should start");

        ReconciliationReport second = reconciler.reconcile("SECOND");
        assertNull(second, "A run already in progress must return null");

        release.countDown();
        first.join(2000);
    }

    @Test
    void crossGraphOwnership_ownedInDeclaredGraphPlusAnother_noMismatch() {
        // A4/R4 guard: a concept owned in its declared graph AND legitimately appearing as an
        // owned subject in another graph must NOT be flagged IRI_GRAPH_MISMATCH — its declared
        // graph is among the found set.
        String otherGraph = "https://slovník.gov.cz/other";
        String iri = GRAPH + "/pojem/shared";
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH, otherGraph));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(otherGraph)).thenReturn(List.of(iri));
        // PG declares GRAPH, which is one of the two it's owned-resolvable in → consistent.
        stubPg(List.of(pgConcept(iri, GRAPH, "shared", true)), List.of(GRAPH, otherGraph));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(0, r.totalMismatches(), "Owned in declared graph (plus another) is not a mismatch");
    }

    @Test
    void nullGraphName_butResolvableIri_flaggedNotSilentlyConsistent() {
        // A4: a PG row whose IRI IS owned-resolvable but whose graphName is null must surface as a
        // finding, not short-circuit to "consistent".
        String iri = GRAPH + "/pojem/nogr";
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, null, "nogr", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertEquals(1, count(r, MismatchCategory.IRI_GRAPH_MISMATCH),
                "Null graphName with a resolvable IRI is a data-quality finding");
        assertEquals(0, count(r, MismatchCategory.PG_MISSING_RDF));
    }

    @Test
    void report_countsByCategory_includesAllCategoriesWithZeros() {
        ReconciliationReport r = reconciler.reconcile("TEST");
        Map<MismatchCategory, Integer> counts = r.countsByCategory();
        assertEquals(MismatchCategory.values().length, counts.size());
        assertTrue(counts.values().stream().allMatch(v -> v == 0));
    }
}
