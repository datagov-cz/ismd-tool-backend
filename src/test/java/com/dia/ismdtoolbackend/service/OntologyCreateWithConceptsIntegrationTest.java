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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.DCTerms;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
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
    @Autowired ConceptService conceptService;
    @Autowired OntologyMetadataRepository ontologies;
    @Autowired ConceptMetadataRepository concepts;
    @Autowired FaultInjectingTdb2 rdf;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxEntryRepository outbox;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay relay;
    @Autowired OutboxConfig config;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        tx.executeWithoutResult(status -> { outbox.deleteAll(); concepts.deleteAll(); ontologies.deleteAll(); });
        config.setEnabled(true);
        config.setBatchSize(100);
        config.setMaxAttempts(3);
        rdf.reset();
        rdf.failAfterWrite = false;
        rdf.failBeforeWrite = false;
        rdf.beforeWrite = () -> {};
        rdf.afterWrite = () -> {};
        rdf.writes = 0;
        rdf.deletes = 0;
    }

    @Test
    void createsCompleteLinkedGraphAndMetadata() {
        var result = service.createWithConcepts(sample(), "creator");
        assertEquals(GRAPH, result.ontology().getGraphName());
        assertEquals(4, result.ontology().getConceptCount());
        assertEquals(1, ontologies.count());
        assertEquals(4, concepts.count());
        assertEquals(OutboxOperation.CREATE_GRAPH, outbox.findAll().get(0).getOperation());
        assertEquals(OutboxStatus.DONE, outbox.findAll().get(0).getStatus());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rdfOutageKeepsCommittedMetadataAndRetries(boolean ordinary) {
        rdf.failBeforeWrite = true;
        if (ordinary) service.createOntology(sample().ontology(), "creator");
        else service.createWithConcepts(sample(), "creator");
        assertEquals(1, ontologies.count());
        assertEquals(ordinary ? 0 : 4, concepts.count());
        assertEquals(OutboxStatus.PENDING, outbox.findAll().get(0).getStatus());
        assertEquals(1, outbox.findAll().get(0).getAttempts());
        assertFalse(rdf.graphHasData(GRAPH));
        rdf.failBeforeWrite = false;
        assertEquals(1, relay.drainOnce());
        assertTrue(rdf.graphHasData(GRAPH));
        assertEquals(OutboxStatus.DONE, outbox.findAll().get(0).getStatus());
        assertEquals(0, rdf.deletes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackNeverWritesOrDeletesRdf(boolean ordinary) {
        tx.executeWithoutResult(status -> {
            if (ordinary) service.createOntology(sample().ontology(), "creator");
            else service.createWithConcepts(sample(), "creator");
            assertEquals(1, outbox.count());
            status.setRollbackOnly();
        });
        assertRolledBackWithoutRdf();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseCommitFailureRollsBackMetadataAndOutbox(boolean ordinary) {
        // The FK is checked by PostgreSQL at COMMIT, after the service returned and JPA flushed.
        withDeferredCommitFailure(() -> {
            assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                if (ordinary) service.createOntology(sample().ontology(), "creator");
                else service.createWithConcepts(sample(), "creator");
                injectCommitFailure();
            }));
            assertRolledBackWithoutRdf();
        });
    }

    private void assertRolledBackWithoutRdf() {
        assertEquals(0, ontologies.count());
        assertEquals(0, concepts.count());
        assertEquals(0, outbox.count());
        assertEquals(0, rdf.writes);
        assertEquals(0, rdf.deletes);
        assertFalse(rdf.graphHasData(GRAPH));
    }

    private void withDeferredCommitFailure(Runnable test) {
        jdbc.execute("CREATE TABLE ismd_schema.test_commit_failure (ontology_id BIGINT REFERENCES ismd_schema.ontologies(id) DEFERRABLE INITIALLY DEFERRED)");
        try { test.run(); }
        finally { jdbc.execute("DROP TABLE ismd_schema.test_commit_failure"); }
    }

    private void injectCommitFailure() {
        jdbc.update("INSERT INTO ismd_schema.test_commit_failure VALUES (-1)");
    }

    @Test
    void replayAfterRdfSuccessAndFailedDoneCommitIsIdempotentIncludingBlankNodes() {
        config.setBatchSize(0); // defer the real after-commit nudge until fault injection is ready
        service.createWithConcepts(sample(), "creator");
        // Include shared blank nodes on different subjects in the durable whole-graph payload.
        tx.executeWithoutResult(status -> {
            var row = outbox.findAll().get(0);
            row.setInsertTriples(row.getInsertTriples()
                    + "<" + GRAPH + "> <https://example.org/shared> _:shared .\n"
                    + "<https://example.org/second> <https://example.org/shared> _:shared .\n"
                    + "_:shared <https://example.org/value> \"blank node\" .\n");
            outbox.save(row);
        });
        withDeferredCommitFailure(() -> {
            config.setBatchSize(100);
            rdf.afterWrite = this::injectCommitFailure;
            assertThrows(RuntimeException.class, () -> relay.drainOnce());
            assertEquals(OutboxStatus.PENDING, outbox.findAll().get(0).getStatus());
            Model first = rdf.fetchGraph(GRAPH);
            assertTrue(first.listSubjects().toList().stream().anyMatch(r -> r.isAnon()));
            rdf.afterWrite = () -> {};
            assertEquals(1, relay.drainOnce());
            assertTrue(first.isIsomorphicWith(rdf.fetchGraph(GRAPH)));
            assertEquals(first.size(), rdf.fetchGraph(GRAPH).size());
            assertEquals(0, rdf.deletes);
        });
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = {"PENDING", "FAILED"})
    void unfinishedCreateBlocksMutationsUntilRetryAndNeverOverwritesLaterEdit(OutboxStatus status) {
        rdf.failAfterWrite = true; // graph exists, but initial PUT is not yet acknowledged durably
        var created = service.createWithConcepts(sample(), "creator");
        setInitialStatus(status);
        var edit = new OntologyEditModel();
        var description = new DescriptionModel();
        description.setDescription(java.util.Map.of("cs", "Nový popis"));
        edit.setDescriptionModel(description);
        assertThrows(OntologyCreationConflictException.class, () -> service.editOntology(created.ontology().getId(), edit));
        assertThrows(OntologyCreationConflictException.class, () -> service.deleteOntology(created.ontology().getId()));

        Model delta = ModelFactory.createDefaultModel();
        delta.add(delta.createResource(GRAPH + "/later"), SKOS.prefLabel, "Later");
        tx.executeWithoutResult(txStatus -> writer.enqueueUpsert(GRAPH, GRAPH + "/later", Set.of(), delta.listStatements().toSet()));
        assertEquals(0, relay.drainOnce());
        assertFalse(rdf.fetchGraph(GRAPH).containsResource(delta.createResource(GRAPH + "/later")));
        rdf.failAfterWrite = false;
        setInitialStatus(OutboxStatus.PENDING);
        assertEquals(2, relay.drainOnce());
        assertTrue(rdf.fetchGraph(GRAPH).containsAll(delta));
        service.editOntology(created.ontology().getId(), edit);
        assertEquals(0, relay.drainOnce());
        assertTrue(rdf.fetchGraph(GRAPH).contains(rdf.fetchGraph(GRAPH).getResource(GRAPH), DCTerms.description, "Nový popis", "cs"));
    }

    private void setInitialStatus(OutboxStatus status) {
        tx.executeWithoutResult(txStatus -> {
            var row = outbox.findAll().stream().filter(r -> r.getOperation() == OutboxOperation.CREATE_GRAPH).findFirst().orElseThrow();
            row.setStatus(status);
            outbox.save(row);
        });
    }

    @Test
    void anotherWorkerCannotPassLockedCreateEvenAfterItsPutSucceeded() throws Exception {
        config.setBatchSize(0);
        service.createWithConcepts(sample(), "creator");
        Model delta = ModelFactory.createDefaultModel();
        delta.add(delta.createResource(GRAPH + "/later"), SKOS.prefLabel, "Later");
        tx.executeWithoutResult(status -> writer.enqueueUpsert(GRAPH, GRAPH + "/later", Set.of(), delta.listStatements().toSet()));
        config.setBatchSize(1); // worker one claims only CREATE_GRAPH
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rdf.afterWrite = () -> {
            reached.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> relay.drainOnce());
            assertTrue(reached.await(15, TimeUnit.SECONDS));
            assertEquals(0, relay.drainOnce()); // skips claimed row; later aggregate still gated
            assertFalse(rdf.fetchGraph(GRAPH).containsAll(delta));
            release.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS));
            assertEquals(1, relay.drainOnce());
            assertTrue(rdf.fetchGraph(GRAPH).containsAll(delta));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void duplicateOrdinaryCreateWhilePendingReturnsOriginalPayloadAndOnlyOneTask() {
        config.setBatchSize(0);
        var original = service.createWithConcepts(sample(), "creator");
        var request = sample().ontology();
        request.getDescriptionModel().setDescription(java.util.Map.of("cs", "Must not replace original"));
        var duplicate = service.createOntology(request, "other");
        assertEquals(original.ontology().getId(), duplicate.getId());
        assertEquals(original.ontology().getName(), duplicate.getName());
        assertEquals(original.ontology().getPopis(), duplicate.getPopis());
        assertEquals(1, outbox.count());
        assertEquals(0, rdf.writes);
        assertThrows(OntologyCreationConflictException.class, () -> service.createWithConcepts(sample(), "other"));
    }

    @Test
    void batchAndOrdinaryConceptCreationProduceSameMetadataIncludingExistingSlugCollision() {
        // Reserve the same metadata slug in another namespace before either creation path runs.
        service.createOntology(sample().ontology(), "creator");
        var ontology = ontologies.findByGraphName(GRAPH).orElseThrow();
        var collision = new ConceptMetadataEntity();
        collision.setSlug("doprava-řidič");
        collision.setConceptIri("https://elsewhere.example/driver");
        collision.setGraphName(GRAPH);
        collision.setUserId("creator");
        collision.setIsPublished(false);
        collision.setOntologyMetadata(ontology);
        concepts.save(collision);
        var prepared = com.dia.ismdtoolbackend.utility.creator.VocabularyConceptBuilder.prepare(sample(), GRAPH, "creator");
        var concept = prepared.concepts().get(0);
        conceptService.createConcept(concept, "creator");
        var ordinary = concepts.findByConceptIri(concept.getIdentifier()).orElseThrow();
        assertEquals("doprava-řidič-1", ordinary.getSlug());

        // Clear only our test data and rebuild the same input via the batch endpoint.
        reset();
        var otherOntology = new OntologyMetadataEntity();
        otherOntology.setSlug("other");
        otherOntology.setGraphName("https://other.example/other");
        otherOntology.setUserId("creator");
        otherOntology.setIsPublished(false);
        ontologies.save(otherOntology);
        collision.setId(null);
        collision.setOntologyMetadata(otherOntology);
        collision.setGraphName(otherOntology.getGraphName());
        concepts.save(collision);
        service.createWithConcepts(sample(), "creator");
        var batch = concepts.findByConceptIri(concept.getIdentifier()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(batch).usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "updatedAt", "ontologyMetadata")
                .isEqualTo(ordinary);
        assertEquals(GRAPH, tx.execute(status -> concepts.findById(batch.getId()).orElseThrow().getOntologyMetadata().getGraphName()));
    }

    @Test
    void conceptMutationsRespectInitialGraphBarrierEvenWhenOutboxIsDisabled() {
        rdf.failAfterWrite = true;
        var created = service.createWithConcepts(sample(), "creator");
        var id = concepts.findByConceptIri(created.conceptIris().get("driver")).orElseThrow().getId();
        var edit = new com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel();
        edit.setConceptType("třída");
        for (boolean enabled : new boolean[]{true, false}) {
            config.setEnabled(enabled);
            assertThrows(OntologyCreationConflictException.class, () -> conceptService.editConcept(id, edit));
            assertThrows(OntologyCreationConflictException.class, () -> conceptService.deleteConcept(id));
        }
        var concept = new com.dia.ismdtoolbackend.models.concept.ClassConceptModel();
        concept.setConceptType("třída");
        concept.setType("objekt");
        concept.setOntologyGraphName(GRAPH);
        concept.setNamespace(GRAPH);
        var name = new com.dia.ismdtoolbackend.models.NameModel();
        name.setName(java.util.Map.of("cs", "Další"));
        concept.setNameModel(name);
        assertThrows(OntologyCreationConflictException.class, () -> conceptService.createConcept(concept, "creator"));
        assertEquals(1, rdf.writes);
        assertEquals(4, concepts.count());
    }

    @Test
    void checkAndBothCreatesAgreeOnGlobalSlugCollision() {
        config.setBatchSize(0);
        service.createWithConcepts(sample(), "creator");
        var request = sample();
        request.ontology().setNamespace("https://other.example/");
        var check = service.checkIri(new com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto(
                request.ontology().getNamespace(), request.ontology().getNameModel()));
        assertTrue(check.valid());
        assertFalse(check.available());
        assertThrows(OntologyCreationConflictException.class, () -> service.createWithConcepts(request, "creator"));
        assertThrows(OntologyCreationConflictException.class, () -> service.createOntology(request.ontology(), "creator"));
        assertEquals(1, outbox.count());
        assertEquals(1, ontologies.count());
    }

    @Test
    void disabledOutboxRejectsBothCreatesWithoutWrites() {
        config.setEnabled(false);
        assertThrows(org.apache.jena.ontology.OntologyException.class, () -> service.createOntology(sample().ontology(), "creator"));
        assertThrows(org.apache.jena.ontology.OntologyException.class, () -> service.createWithConcepts(sample(), "creator"));
        assertRolledBackWithoutRdf();
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
    void rollbackReleasesSlugToCompetingOrdinaryCreateWithoutRdfCleanup() throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch rollback = new CountDownLatch(1);
        CountDownLatch competitorStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var losingBatch = executor.submit(() -> tx.executeWithoutResult(status -> {
                service.createWithConcepts(sample(), "loser");
                prepared.countDown();
                await(rollback);
                status.setRollbackOnly();
            }));
            assertTrue(prepared.await(15, TimeUnit.SECONDS));
            var winningOrdinary = executor.submit(() -> {
                competitorStarted.countDown();
                return service.createOntology(sample().ontology(), "winner");
            });
            assertTrue(competitorStarted.await(15, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> winningOrdinary.get(200, TimeUnit.MILLISECONDS));
            assertEquals(0, rdf.writes);
            rollback.countDown();
            losingBatch.get(15, TimeUnit.SECONDS);
            assertNotNull(winningOrdinary.get(15, TimeUnit.SECONDS));
            assertEquals("winner", ontologies.findByGraphName(GRAPH).orElseThrow().getUserId());
            assertEquals(1, ontologies.count());
            assertEquals(0, concepts.count());
            assertEquals(1, outbox.count());
            assertEquals(1, rdf.writes);
            assertEquals(0, rdf.deletes);
            assertTrue(rdf.graphHasData(GRAPH));
        } finally { rollback.countDown(); executor.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = {"PENDING", "FAILED"})
    void renameRejectsUnfinishedSourceAndTargetGraphs(OutboxStatus status) {
        var pending = service.createWithConcepts(sample(), "creator");
        // Its PUT is durable, but simulate the unapplied state seen after a lost DONE commit.
        setInitialStatus(status);
        var rename = new OntologyEditModel();
        var name = new com.dia.ismdtoolbackend.models.NameModel();
        name.setName(java.util.Map.of("cs", "Other"));
        rename.setNameModel(name);
        assertThrows(OntologyCreationConflictException.class, () -> service.editOntology(pending.ontology().getId(), rename));

        config.setBatchSize(0); // do not accidentally retry the first graph while creating the second
        var secondRequest = sample().ontology();
        secondRequest.getNameModel().setName(java.util.Map.of("cs", "Other"));
        var second = service.createOntology(secondRequest, "creator");
        // Apply only the second graph's initial row, independently of the blocked first graph.
        tx.executeWithoutResult(txStatus -> {
            var row = outbox.findAll().stream().filter(r -> r.getGraphName().equals(second.getGraphName())).findFirst().orElseThrow();
            Model model = OutboxTriples.parse(row.getInsertTriples());
            try { rdf.saveOntologyModel(row.getGraphName(), model); }
            finally { model.close(); }
            row.setStatus(OutboxStatus.DONE);
            outbox.save(row);
        });
        name.setName(java.util.Map.of("cs", "Doprava"));
        int writesBeforeRename = rdf.writes;
        assertThrows(OntologyCreationConflictException.class, () -> service.editOntology(second.getId(), rename));
        assertEquals(writesBeforeRename, rdf.writes);
        assertEquals(0, rdf.deletes);
        assertEquals(second.getGraphName(), ontologies.findById(second.getId()).orElseThrow().getGraphName());
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
        boolean failBeforeWrite;
        int writes;
        int deletes;
        Runnable afterWrite = () -> {};
        Runnable beforeWrite = () -> {};
        @Override public void saveOntologyModel(String graph, Model model) {
            beforeWrite.run();
            if (failBeforeWrite) throw new JenaTDB2Exception("Injected outage before RDF PUT");
            writes++;
            super.saveOntologyModel(graph, model);
            afterWrite.run();
            if (failAfterWrite) throw new JenaTDB2Exception("Injected failure after RDF PUT");
        }
        @Override public void deleteGraph(String graph) {
            deletes++;
            super.deleteGraph(graph);
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean OutboxConfig config() { var c = new OutboxConfig(); c.setEnabled(true); return c; }
        @Bean OutboxWriter writer(OutboxEntryRepository r) { return new OutboxWriter(r); }
        @Bean OutboxRelay relay(OutboxEntryRepository r, FaultInjectingTdb2 rdf, OutboxConfig c) { return new OutboxRelay(r, rdf, c); }
        @Bean OutboxRelayTrigger trigger(OutboxRelay relay) { return new OutboxRelayTrigger(relay); }
        @Bean ConceptServiceImpl conceptService(ConceptMetadataRepository concepts, OntologyMetadataRepository ontologies,
                                               FaultInjectingTdb2 rdf, OutboxConfig config, OutboxWriter writer,
                                               OutboxEntryRepository outbox, OutboxRelayTrigger trigger) {
            return new ConceptServiceImpl(concepts, ontologies, mock(MetadataTouchService.class), new ConceptMetadataMapperImpl(),
                    new com.dia.ismdtoolbackend.utility.creator.ConceptCreator(), new com.dia.ismdtoolbackend.utility.editor.ConceptEditor(), rdf,
                    mock(OntologyDetailExtractor.class), mock(CommentRepository.class), mock(com.dia.ismdtoolbackend.client.NkdSparqlClient.class),
                    mock(ConceptDeviationComparator.class), mock(com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder.class),
                    mock(ReferencedConceptsEnricher.class), config, writer, outbox, trigger, mock(NkdSnapshotService.class),
                    new com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector(),
                    new com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields(), mock(NkdSnapshotWarmer.class),
                    new NkdConfig(), mock(WorkingCopyDeviationServiceImpl.class));
        }
        @Bean FaultInjectingTdb2 rdf() { return new FaultInjectingTdb2(); }
        @Bean OntologyServiceImpl ontologyService(OntologyMetadataRepository ontologies, ConceptMetadataRepository concepts, FaultInjectingTdb2 rdf, OutboxConfig config, OutboxWriter writer, OutboxEntryRepository outbox, OutboxRelayTrigger trigger) {
            return new OntologyServiceImpl(ontologies, mock(MetadataTouchService.class), concepts,
                    mock(ValidationReportRepository.class), rdf, mock(CommentRepository.class),
                    new OntologyMetadataMapperImpl(), mock(ConceptMetadataMapper.class), new OntologyEditor(),
                    mock(OntologyDetailExtractor.class), mock(PublishedResourceUtil.class), config,
                    writer, outbox, trigger, mock(NkdConceptSnapshotRepository.class),
                    mock(NkdSnapshotWarmer.class), mock(NkdSnapshotService.class), new NkdConfig(), mock(NkdDetailService.class));
        }
    }
}
