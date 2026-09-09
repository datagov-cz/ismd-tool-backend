package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.entity.*;
import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.mapper.*;
import com.dia.ismdtoolbackend.outbox.*;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.impl.*;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import static com.dia.ismdtoolbackend.support.VocabularyCreationRequests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({OntologyCreateWithConceptsIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OntologyMetadataEntity.class, OutboxEntry.class})
@EnableJpaRepositories(basePackageClasses = {OntologyMetadataRepository.class, OutboxEntryRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OntologyCreateWithConceptsIntegrationTest extends PostgresIntegrationTestBase {
    @Autowired OntologyService service;
    @Autowired OntologyMetadataRepository ontologies;
    @Autowired ConceptMetadataRepository concepts;
    @Autowired FaultInjectingTdb2 rdf;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx.executeWithoutResult(status -> { concepts.deleteAll(); ontologies.deleteAll(); });
        rdf.reset();
        rdf.failAfterWrite = false;
    }

    @Test
    void createsCompleteLinkedGraphAndMetadata() {
        var result = service.createWithConcepts(sample(), "creator");
        assertEquals(GRAPH, result.ontology().getGraphName());
        assertEquals(4, result.ontology().getConceptCount());
        assertEquals(1, ontologies.count());
        assertEquals(4, concepts.count());
        var iris = result.conceptIris();
        Model graph = rdf.fetchGraph(GRAPH);
        assertTrue(graph.contains(graph.getResource(GRAPH), RDF.type, SKOS.ConceptScheme));
        assertTrue(graph.contains(graph.getResource(iris.get("weight")), RDFS.domain, graph.getResource(iris.get("vehicle"))));
        assertTrue(graph.contains(graph.getResource(iris.get("weight")), RDFS.range, graph.getResource("http://www.w3.org/2001/XMLSchema#decimal")));
        assertTrue(graph.contains(graph.getResource(iris.get("drives")), RDFS.domain, graph.getResource(iris.get("driver"))));
        assertTrue(graph.contains(graph.getResource(iris.get("drives")), RDFS.range, graph.getResource(iris.get("vehicle"))));
        for (String iri : iris.values()) {
            assertTrue(graph.contains(graph.getResource(iri), SKOS.inScheme, graph.getResource(GRAPH)));
            assertEquals("creator", concepts.findByConceptIri(iri).orElseThrow().getUserId());
        }
    }

    @Test
    void repeatRequestConflictsWithoutChangingStoredGraph() {
        service.createWithConcepts(sample(), "creator");
        Model before = rdf.fetchGraph(GRAPH);
        assertThrows(OntologyCreationConflictException.class, () -> service.createWithConcepts(sample(), "other"));
        assertEquals(1, ontologies.count());
        assertEquals(4, concepts.count());
        assertTrue(before.isIsomorphicWith(rdf.fetchGraph(GRAPH)));
    }

    @Test
    void rdfFailureAfterWriteRollsBackDatabaseAndCleansGraph() {
        rdf.failAfterWrite = true;
        assertThrows(JenaTDB2Exception.class, () -> service.createWithConcepts(sample(), "creator"));
        assertEquals(0, ontologies.count());
        assertEquals(0, concepts.count());
        assertFalse(rdf.graphHasData(GRAPH));
    }

    @Test
    void failureDuringCommitAlsoCleansGraph() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            service.createWithConcepts(sample(), "creator");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("Injected commit failure"); }
            });
        }));
        assertEquals(0, ontologies.count());
        assertEquals(0, concepts.count());
        assertFalse(rdf.graphHasData(GRAPH));
    }

    @Test
    void twoConcurrentCreatesHaveOneWinnerAndDoNotDeleteItsGraph() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> create = () -> {
                start.await();
                try { service.createWithConcepts(sample(), "creator"); return true; }
                catch (OntologyCreationConflictException e) { return false; }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, ontologies.count());
            assertEquals(4, concepts.count());
            assertTrue(rdf.graphHasData(GRAPH));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentOrdinaryCreateCannotOverwriteOrDeleteTheWinningDraft() throws Exception {
        CountDownLatch rdfWriteReached = new CountDownLatch(1);
        CountDownLatch continueWrite = new CountDownLatch(1);
        rdf.beforeWrite = () -> {
            rdfWriteReached.countDown();
            try { assertTrue(continueWrite.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var draft = executor.submit(() -> service.createWithConcepts(sample(), "creator"));
            assertTrue(rdfWriteReached.await(15, TimeUnit.SECONDS));
            var ordinary = executor.submit(() -> {
                try { return service.createOntology(sample().ontology(), "other"); }
                catch (org.apache.jena.ontology.OntologyException e) { return null; }
            });
            continueWrite.countDown();
            assertNotNull(draft.get(30, TimeUnit.SECONDS));
            ordinary.get(30, TimeUnit.SECONDS);
            assertEquals(1, ontologies.count());
            assertEquals(4, concepts.count());
            assertTrue(rdf.graphHasData(GRAPH));
        } finally { continueWrite.countDown(); executor.shutdownNow(); rdf.beforeWrite = () -> {}; }
    }

    @Test
    void deselectedClassIsRejectedBeforeAnyWrite() {
        var source = sample();
        var invalid = new com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto(
                source.ontology(), java.util.List.of(source.classes().get(0)), source.attributes(), source.relationships());
        assertThrows(com.dia.ismdtoolbackend.exception.ConceptValidationException.class,
                () -> service.createWithConcepts(invalid, "creator"));
        assertEquals(0, ontologies.count());
        assertEquals(0, concepts.count());
        assertFalse(rdf.graphHasData(GRAPH));
    }

    public static class FaultInjectingTdb2 extends InMemoryTdb2 {
        boolean failAfterWrite;
        Runnable beforeWrite = () -> {};
        @Override public void saveOntologyModel(String graph, Model model) {
            beforeWrite.run();
            super.saveOntologyModel(graph, model);
            if (failAfterWrite) throw new JenaTDB2Exception("Injected failure after RDF PUT");
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean FaultInjectingTdb2 rdf() { return new FaultInjectingTdb2(); }
        @Bean OntologyServiceImpl ontologyService(OntologyMetadataRepository ontologies, ConceptMetadataRepository concepts, FaultInjectingTdb2 rdf) {
            return new OntologyServiceImpl(ontologies, mock(MetadataTouchService.class), concepts,
                    mock(ValidationReportRepository.class), rdf, mock(CommentRepository.class),
                    new OntologyMetadataMapperImpl(), mock(ConceptMetadataMapper.class), mock(OntologyEditor.class),
                    mock(OntologyDetailExtractor.class), mock(PublishedResourceUtil.class), mock(OutboxConfig.class),
                    mock(OutboxWriter.class), mock(OutboxRelayTrigger.class), mock(NkdConceptSnapshotRepository.class),
                    mock(NkdSnapshotWarmer.class), mock(NkdSnapshotService.class), new NkdConfig(), mock(NkdDetailService.class));
        }
    }
}
