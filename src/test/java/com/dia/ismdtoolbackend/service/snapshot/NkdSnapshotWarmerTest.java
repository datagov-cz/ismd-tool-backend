package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkdSnapshotWarmerTest {

    private static final String GRAPH = "https://example.org/slovnik/mestys";
    private static final String SUBCLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String NKD_A = "https://slovník.gov.cz/agendový/104/pojem/a";
    private static final String NKD_B = "https://slovník.gov.cz/agendový/104/pojem/b";

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private NkdSnapshotOwnerWarmer ownerWarmer;

    // Real detector — stateless, pure; exercises the actual external-link detection against the test graph.
    private final NkdLinkDetector linkDetector = new NkdLinkDetector();

    private NkdSnapshotWarmer warmer;

    // Owner concept IRI → its subClassOf target (test-local; not on the entity).
    private final Map<ConceptMetadataEntity, String> targetByOwner = new java.util.LinkedHashMap<>();

    private ConceptMetadataEntity owner(long id, String localName, String target) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setId(id);
        c.setConceptType(ConceptType.TRIDA);
        c.setGraphName(GRAPH);
        c.setConceptIri(GRAPH + "/pojem/" + localName);
        targetByOwner.put(c, target);
        return c;
    }

    @BeforeEach
    void setUp() {
        targetByOwner.clear();
        // Real config: the per-graph scan throttle reads its TTL from here, and a fresh warmer per
        // test means the first warmGraph call always claims the slot.
        warmer = new NkdSnapshotWarmer(conceptMetadataRepository, jenaTDB2Repository,
                nkdSparqlClient, ownerWarmer, linkDetector, new NkdConfig());
    }

    /** Build a graph model with each owner subClassOf its target. */
    private Model graphWith(List<ConceptMetadataEntity> owners) {
        Model m = ModelFactory.createDefaultModel();
        for (ConceptMetadataEntity o : owners) {
            Resource subj = m.getResource(o.getConceptIri());
            m.add(subj, m.createProperty(SUBCLASS_OF), m.getResource(targetByOwner.get(o)));
        }
        return m;
    }

    @Test
    void warmGraph_oneOwnerFails_othersStillWarmed() {
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        ConceptMetadataEntity b = owner(2L, "y", NKD_B);
        List<ConceptMetadataEntity> owners = List.of(a, b);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(owners));
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of(NKD_A, NKD_B));
        // Owner A's isolated unit throws (simulating its REQUIRES_NEW tx rolling back); B must still run.
        when(ownerWarmer.warmOwner(eq(GRAPH), eq(a), anyList())).thenThrow(new RuntimeException("boom"));
        when(ownerWarmer.warmOwner(eq(GRAPH), eq(b), anyList())).thenReturn(true);

        warmer.warmGraph(GRAPH);

        // Both owners attempted; A's failure did not abort the batch.
        verify(ownerWarmer).warmOwner(eq(GRAPH), eq(a), anyList());
        verify(ownerWarmer).warmOwner(eq(GRAPH), eq(b), anyList());
    }

    @Test
    void warmGraph_noPublishedTargets_skipsOwnerWarming() {
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        List<ConceptMetadataEntity> owners = List.of(a);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(owners));
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of()); // none published

        warmer.warmGraph(GRAPH);

        verify(ownerWarmer, never()).warmOwner(any(), any(), anyList());
    }

    @Test
    void warmGraph_secondCallWithinTtl_doesNotRescanGraph() {
        // The zero-row case that motivated the throttle: an ontology whose external links are not
        // published in NKD keeps no snapshot rows, so the detail read re-triggers warming forever.
        // The scan materializes the whole graph out of TDB2, so it must not run per detail view.
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        List<ConceptMetadataEntity> owners = List.of(a);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(owners));
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of());

        warmer.warmGraph(GRAPH);
        warmer.warmGraph(GRAPH);
        warmer.warmGraph(GRAPH);

        // Only the first call did the expensive work.
        verify(jenaTDB2Repository, times(1)).fetchGraph(GRAPH);
    }

    @Test
    void warmGraphNow_bypassesThrottle() {
        // Upload just changed the graph, so its scan must not be suppressed by a marker a read left.
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        List<ConceptMetadataEntity> owners = List.of(a);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(owners));
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of());

        warmer.warmGraph(GRAPH);
        warmer.warmGraphNow(GRAPH);

        verify(jenaTDB2Repository, times(2)).fetchGraph(GRAPH);
    }

    @Test
    void warmGraph_differentGraphs_eachScannedIndependently() {
        String otherGraph = "https://example.org/slovnik/jiny";
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        List<ConceptMetadataEntity> owners = List.of(a);

        when(conceptMetadataRepository.findByGraphName(any())).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(any())).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(any())).thenReturn(graphWith(owners));
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of());

        warmer.warmGraph(GRAPH);
        warmer.warmGraph(otherGraph);

        // The throttle is per graph — one graph's scan must not suppress another's.
        verify(jenaTDB2Repository).fetchGraph(GRAPH);
        verify(jenaTDB2Repository).fetchGraph(otherGraph);
    }

    @Test
    void warmGraph_noOwners_noNkdCall() {
        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(List.of());

        warmer.warmGraph(GRAPH);

        verify(nkdSparqlClient, never()).getPublishedResourcesList(anyList());
        verify(ownerWarmer, never()).warmOwner(any(), any(), anyList());
    }

    @Test
    void warmGraph_targetIsLocallyOwnedWorkingCopy_notSnapshotted() {
        // Phase A: a working copy (locally-owned concept whose own IRI is in NKD) is external-looking and
        // published, but it is ours. The edit hook excludes it; the warmer must too, or the two churn
        // create/remove against each other.
        ConceptMetadataEntity a = owner(1L, "x", NKD_A);
        ConceptMetadataEntity workingCopy = new ConceptMetadataEntity();
        workingCopy.setId(2L);
        workingCopy.setConceptType(ConceptType.TRIDA);
        workingCopy.setGraphName(GRAPH);
        workingCopy.setConceptIri(NKD_A);      // own IRI IS the NKD IRI
        List<ConceptMetadataEntity> owners = List.of(a, workingCopy);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(List.of(a)));

        warmer.warmGraph(GRAPH);

        // a's only target is the working copy → no candidates at all → NKD never asked, nothing warmed.
        verify(nkdSparqlClient, never()).getPublishedResourcesList(anyList());
        verify(ownerWarmer, never()).warmOwner(any(), any(), anyList());
    }

    @Test
    void warmGraph_localTargetOnly_notTreatedAsExternal() {
        // Owner links a SAME-GRAPH concept (owned) — not an NKD target, so no batch check / warming.
        ConceptMetadataEntity a = owner(1L, "x", GRAPH + "/pojem/local-parent");
        List<ConceptMetadataEntity> owners = List.of(a);

        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(owners);
        when(jenaTDB2Repository.graphHasData(GRAPH)).thenReturn(true);
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graphWith(owners));

        warmer.warmGraph(GRAPH);

        verify(nkdSparqlClient, never()).getPublishedResourcesList(anyList());
        verify(ownerWarmer, never()).warmOwner(any(), any(), anyList());
    }
}
