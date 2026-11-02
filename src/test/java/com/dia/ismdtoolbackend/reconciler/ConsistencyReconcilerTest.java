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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    @Test
    void cleanState_noMismatches() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/a";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, GRAPH, "a", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertThat(r.totalMismatches()).as("Matching PG + TDB2 yields no findings").isEqualTo(0);
        assertThat(r.ownedRdfConceptsScanned()).isEqualTo(1);
        assertThat(r.pgConceptsScanned()).isEqualTo(1);
    }

    @Test
    void rdfOrphan_ownedRdfWithNoPgRow() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/orphan";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(), List.of(GRAPH)); // no PG concept rows

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri)
                .containsExactly(tuple(MismatchCategory.RDF_ORPHAN, iri));
    }

    @Test
    void pgMissingRdf_pgRowNotOwnedResolvable() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of());
        String iri = GRAPH + "/pojem/gone";
        stubPg(List.of(pgConcept(iri, GRAPH, "gone", true)), List.of(GRAPH));

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri)
                .containsExactly(tuple(MismatchCategory.PG_MISSING_RDF, iri));
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

        assertThat(r.totalMismatches()).as("A draft with both stores in sync is clean").isEqualTo(0);
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

        assertThat(r.totalMismatches())
                .as("A referenced NKD concept (not in the owned set) produces no RDF_ORPHAN")
                .isEqualTo(0);
    }

    @Test
    void graphOrphan_tdb2GraphWithNoOntologyRow() {
        when(jenaTDB2Repository.listNamedGraphs()).thenReturn(List.of(GRAPH));
        String iri = GRAPH + "/pojem/x";
        when(jenaTDB2Repository.listOwnedConceptIrisInGraph(GRAPH)).thenReturn(List.of(iri));
        stubPg(List.of(pgConcept(iri, GRAPH, "x", true)), List.of()); // no ontology row for GRAPH

        ReconciliationReport r = reconciler.reconcile("TEST");

        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::graphName)
                .containsExactly(tuple(MismatchCategory.GRAPH_ORPHAN, GRAPH));
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

        // Found elsewhere ≠ missing: the mismatch is the only finding.
        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri)
                .containsExactly(tuple(MismatchCategory.IRI_GRAPH_MISMATCH, iri));
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

        // One finding: conceptIri = new (TDB2) IRI, relatedIri = old (PG) IRI. The paired
        // orphan and missing row are not reported on their own.
        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri, Mismatch::relatedIri)
                .containsExactly(tuple(MismatchCategory.SUSPECTED_RENAME, newIri, oldIri));
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

        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri)
                .containsExactlyInAnyOrder(
                        tuple(MismatchCategory.RDF_ORPHAN, newIri),
                        tuple(MismatchCategory.PG_MISSING_RDF, oldIri));
    }

    @Test
    void reentrancyGuard_secondConcurrentRunReturnsNull() throws Exception {
        // Hold the run inside listNamedGraphs while a second thread tries to reconcile.
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jenaTDB2Repository.listNamedGraphs()).thenAnswer(inv -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });

        ExecutorService firstRunner = Executors.newSingleThreadExecutor();
        try {
            Future<ReconciliationReport> first =
                    firstRunner.submit(() -> reconciler.reconcile("FIRST"));
            assertThat(inside.await(5, TimeUnit.SECONDS)).as("first run should start").isTrue();

            ReconciliationReport second = reconciler.reconcile("SECOND");
            assertThat(second).as("A run already in progress must return null").isNull();

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            firstRunner.shutdownNow();
        }
    }

    @Test
    void failedRun_releasesGuard_nextRunCompletes() {
        when(jenaTDB2Repository.listNamedGraphs())
                .thenThrow(new IllegalStateException("Fuseki down"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> reconciler.reconcile("FIRST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fuseki down");

        ReconciliationReport second = reconciler.reconcile("SECOND");
        assertThat(second).isNotNull();
        assertThat(second.triggeredBy()).isEqualTo("SECOND");
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

        assertThat(r.totalMismatches()).as("Owned in declared graph (plus another) is not a mismatch").isEqualTo(0);
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

        // Null graphName with a resolvable IRI is a data-quality finding.
        assertThat(r.mismatches())
                .extracting(Mismatch::category, Mismatch::conceptIri)
                .containsExactly(tuple(MismatchCategory.IRI_GRAPH_MISMATCH, iri));
    }

    @Test
    void report_countsByCategory_includesAllCategoriesWithZeros() {
        ReconciliationReport r = reconciler.reconcile("TEST");
        Map<MismatchCategory, Integer> counts = r.countsByCategory();
        assertThat(counts).hasSize(MismatchCategory.values().length);
        assertThat(counts.values().stream().allMatch(v -> v == 0)).isTrue();
    }
}
