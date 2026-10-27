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
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@OutboxFlowIT
class OntologyCreateWithConceptsIntegrationTest extends OutboxFlowTestBase {
    @Autowired OntologyService service;
    @Autowired ConceptService conceptService;
    @Autowired OntologyMetadataRepository ontologies;
    @Autowired ConceptMetadataRepository concepts;
    @Autowired InMemoryTdb2 rdf;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxEntryRepository outbox;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        outboxConfig.setBatchSize(100);
        outboxConfig.setMaxAttempts(3);
    }

    @Test
    void createsCompleteLinkedGraphAndMetadata() {
        var result = service.createWithConcepts(sample(), "creator");
        assertThat(result.ontology().getGraphName()).isEqualTo(GRAPH);
        assertThat(result.ontology().getConceptCount()).isEqualTo(4);
        assertThat(ontologies.count()).isEqualTo(1);
        assertThat(concepts.count()).isEqualTo(4);
        assertThat(outbox.findAll().get(0).getOperation()).isEqualTo(OutboxOperation.CREATE_GRAPH);
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.DONE);
        var iris = result.conceptIris();
        Model graph = rdf.fetchGraph(GRAPH);
        assertThat(graph.contains(graph.getResource(GRAPH), RDF.type, SKOS.ConceptScheme)).isTrue();
        assertThat(graph.contains(graph.getResource(iris.get("weight")), RDFS.domain, graph.getResource(iris.get("vehicle"))))
                .isTrue();
        assertThat(graph.contains(graph.getResource(iris.get("weight")), RDFS.range, graph.getResource("http://www.w3.org/2001/XMLSchema#decimal")))
                .isTrue();
        assertThat(graph.contains(graph.getResource(iris.get("drives")), RDFS.domain, graph.getResource(iris.get("driver"))))
                .isTrue();
        assertThat(graph.contains(graph.getResource(iris.get("drives")), RDFS.range, graph.getResource(iris.get("vehicle"))))
                .isTrue();
        for (String iri : iris.values()) {
            assertThat(graph.contains(graph.getResource(iri), SKOS.inScheme, graph.getResource(GRAPH))).isTrue();
            assertThat(concepts.findByConceptIri(iri).orElseThrow().getUserId()).isEqualTo("creator");
        }
    }

    @Test
    void deletesOntologyWhoseGraphIsMissing() {
        service.createWithConcepts(sample(), "creator");
        Long id = ontologies.findByGraphName(GRAPH).orElseThrow().getId();
        rdf.reset();
        assertThat(rdf.graphHasData(GRAPH)).isFalse();

        service.deleteOntology(id);

        assertThat(ontologies.count()).isEqualTo(0);
        assertThat(concepts.count()).isEqualTo(0);
        assertThat(outbox.findAll().stream().allMatch(row -> row.getStatus() == OutboxStatus.DONE)).isTrue();
        assertThat(rdf.graphHasData(GRAPH)).isFalse();
    }

    @Test
    void repeatRequestConflictsWithoutChangingStoredGraph() {
        service.createWithConcepts(sample(), "creator");
        Model before = rdf.fetchGraph(GRAPH);
        assertThatThrownBy(() -> service.createWithConcepts(sample(), "other"))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThat(ontologies.count()).isEqualTo(1);
        assertThat(concepts.count()).isEqualTo(4);
        assertThat(before.isIsomorphicWith(rdf.fetchGraph(GRAPH))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rdfOutageKeepsCommittedMetadataAndRetries(boolean ordinary) {
        rdf.failBeforeWrite(true);
        if (ordinary) service.createOntology(sample().ontology(), "creator");
        else service.createWithConcepts(sample(), "creator");
        assertThat(ontologies.count()).isEqualTo(1);
        assertThat(concepts.count()).isEqualTo(ordinary ? 0 : 4);
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.findAll().get(0).getAttempts()).isEqualTo(1);
        assertThat(rdf.graphHasData(GRAPH)).isFalse();
        rdf.failBeforeWrite(false);
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(rdf.graphHasData(GRAPH)).isTrue();
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(rdf.deleteCount()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackNeverWritesOrDeletesRdf(boolean ordinary) {
        tx.executeWithoutResult(status -> {
            if (ordinary) service.createOntology(sample().ontology(), "creator");
            else service.createWithConcepts(sample(), "creator");
            assertThat(outbox.count()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertRolledBackWithoutRdf();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseCommitFailureRollsBackMetadataAndOutbox(boolean ordinary) {
        // The FK is checked by PostgreSQL at COMMIT, after the service returned and JPA flushed.
        withDeferredCommitFailure(() -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                if (ordinary) service.createOntology(sample().ontology(), "creator");
                else service.createWithConcepts(sample(), "creator");
                injectCommitFailure();
            }))
                    .isInstanceOf(RuntimeException.class);
            assertRolledBackWithoutRdf();
        });
    }

    private void assertRolledBackWithoutRdf() {
        assertThat(ontologies.count()).isEqualTo(0);
        assertThat(concepts.count()).isEqualTo(0);
        assertThat(outbox.count()).isEqualTo(0);
        assertThat(rdf.writeCount()).isEqualTo(0);
        assertThat(rdf.deleteCount()).isEqualTo(0);
        assertThat(rdf.graphHasData(GRAPH)).isFalse();
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
        outboxConfig.setBatchSize(0); // defer the real after-commit nudge until fault injection is ready
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
            outboxConfig.setBatchSize(100);
            rdf.afterWrite(this::injectCommitFailure);
            assertThatThrownBy(() -> relay.drainOnce()).isInstanceOf(RuntimeException.class);
            assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
            Model first = rdf.fetchGraph(GRAPH);
            assertThat(first.listSubjects().toList().stream().anyMatch(r -> r.isAnon())).isTrue();
            rdf.afterWrite(() -> {});
            assertThat(relay.drainOnce()).isEqualTo(1);
            assertThat(first.isIsomorphicWith(rdf.fetchGraph(GRAPH))).isTrue();
            assertThat(rdf.fetchGraph(GRAPH).size()).isEqualTo(first.size());
            assertThat(rdf.deleteCount()).isEqualTo(0);
        });
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = {"PENDING", "FAILED"})
    void unfinishedCreateBlocksMutationsUntilRetryAndNeverOverwritesLaterEdit(OutboxStatus status) {
        rdf.failAfterWrite(true); // graph exists, but initial PUT is not yet acknowledged durably
        var created = service.createWithConcepts(sample(), "creator");
        setInitialStatus(status);
        var edit = new OntologyEditModel();
        var description = new DescriptionModel();
        description.setDescription(java.util.Map.of("cs", "Nový popis"));
        edit.setDescriptionModel(description);
        assertThatThrownBy(() -> service.editOntology(created.ontology().getId(), edit))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThatThrownBy(() -> service.deleteOntology(created.ontology().getId()))
                .isInstanceOf(OntologyCreationConflictException.class);

        Model delta = ModelFactory.createDefaultModel();
        delta.add(delta.createResource(GRAPH + "/later"), SKOS.prefLabel, "Later");
        tx.executeWithoutResult(txStatus -> writer.enqueueUpsert(GRAPH, GRAPH + "/later", Set.of(), delta.listStatements().toSet()));
        assertThat(relay.drainOnce()).isEqualTo(0);
        assertThat(rdf.fetchGraph(GRAPH).containsResource(delta.createResource(GRAPH + "/later"))).isFalse();
        rdf.failAfterWrite(false);
        setInitialStatus(OutboxStatus.PENDING);
        assertThat(relay.drainOnce()).isEqualTo(2);
        assertThat(rdf.fetchGraph(GRAPH).containsAll(delta)).isTrue();
        service.editOntology(created.ontology().getId(), edit);
        assertThat(relay.drainOnce()).isEqualTo(0);
        assertThat(rdf.fetchGraph(GRAPH).contains(rdf.fetchGraph(GRAPH).getResource(GRAPH), DCTerms.description, "Nový popis", "cs"))
                .isTrue();
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
        outboxConfig.setBatchSize(0);
        service.createWithConcepts(sample(), "creator");
        Model delta = ModelFactory.createDefaultModel();
        delta.add(delta.createResource(GRAPH + "/later"), SKOS.prefLabel, "Later");
        tx.executeWithoutResult(status -> writer.enqueueUpsert(GRAPH, GRAPH + "/later", Set.of(), delta.listStatements().toSet()));
        outboxConfig.setBatchSize(1); // worker one claims only CREATE_GRAPH
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rdf.afterWrite(() -> {
            reached.countDown();
            try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> relay.drainOnce());
            assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(relay.drainOnce()).isEqualTo(0); // skips claimed row; later aggregate still gated
            assertThat(rdf.fetchGraph(GRAPH).containsAll(delta)).isFalse();
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(relay.drainOnce()).isEqualTo(1);
            assertThat(rdf.fetchGraph(GRAPH).containsAll(delta)).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void duplicateOrdinaryCreateWhilePendingReturnsOriginalPayloadAndOnlyOneTask() {
        outboxConfig.setBatchSize(0);
        var original = service.createWithConcepts(sample(), "creator");
        var request = sample().ontology();
        request.getDescriptionModel().setDescription(java.util.Map.of("cs", "Must not replace original"));
        var duplicate = service.createOntology(request, "other");
        assertThat(duplicate.getId()).isEqualTo(original.ontology().getId());
        assertThat(duplicate.getName()).isEqualTo(original.ontology().getName());
        assertThat(duplicate.getPopis()).isEqualTo(original.ontology().getPopis());
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(rdf.writeCount()).isEqualTo(0);
        assertThatThrownBy(() -> service.createWithConcepts(sample(), "other"))
                .isInstanceOf(OntologyCreationConflictException.class);
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
        assertThat(ordinary.getSlug()).isEqualTo("doprava-řidič-1");

        // Start over from an empty database and rebuild the same input via the batch endpoint.
        wipeDatabase();
        resetFixtures();
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
        assertThat(batch).usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "updatedAt", "ontologyMetadata")
                .isEqualTo(ordinary);
        assertThat(tx.<String>execute(status -> concepts.findById(batch.getId()).orElseThrow().getOntologyMetadata().getGraphName()))
                .isEqualTo(GRAPH);
    }

    @Test
    void conceptMutationsRespectInitialGraphBarrier() {
        rdf.failAfterWrite(true);
        var created = service.createWithConcepts(sample(), "creator");
        var id = concepts.findByConceptIri(created.conceptIris().get("driver")).orElseThrow().getId();
        var edit = new com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel();
        edit.setConceptType("třída");
        assertThatThrownBy(() -> conceptService.editConcept(id, edit))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThatThrownBy(() -> conceptService.deleteConcept(id))
                .isInstanceOf(OntologyCreationConflictException.class);
        var concept = new com.dia.ismdtoolbackend.models.concept.ClassConceptModel();
        concept.setConceptType("třída");
        concept.setType("objekt");
        concept.setOntologyGraphName(GRAPH);
        concept.setNamespace(GRAPH);
        var name = new com.dia.ismdtoolbackend.models.NameModel();
        name.setName(java.util.Map.of("cs", "Další"));
        concept.setNameModel(name);
        assertThatThrownBy(() -> conceptService.createConcept(concept, "creator"))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(rdf.writeCount()).isEqualTo(1);
        assertThat(concepts.count()).isEqualTo(4);
    }

    @Test
    void checkAndBothCreatesAgreeOnGlobalSlugCollision() {
        outboxConfig.setBatchSize(0);
        service.createWithConcepts(sample(), "creator");
        var request = sample();
        request.ontology().setNamespace("https://other.example/");
        var check = service.checkIri(new com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto(
                request.ontology().getNamespace(), request.ontology().getNameModel()));
        assertThat(check.valid()).isTrue();
        assertThat(check.available()).isFalse();
        assertThatThrownBy(() -> service.createWithConcepts(request, "creator"))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThatThrownBy(() -> service.createOntology(request.ontology(), "creator"))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(ontologies.count()).isEqualTo(1);
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
            assertThat(second.get(30, TimeUnit.SECONDS)).isNotEqualTo(first.get(30, TimeUnit.SECONDS));
            assertThat(ontologies.count()).isEqualTo(1);
            assertThat(concepts.count()).isEqualTo(4);
            assertThat(rdf.graphHasData(GRAPH)).isTrue();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentOrdinaryCreateCannotOverwriteOrDeleteTheWinningDraft() throws Exception {
        CountDownLatch rdfWriteReached = new CountDownLatch(1);
        CountDownLatch continueWrite = new CountDownLatch(1);
        rdf.beforeWrite(() -> {
            rdfWriteReached.countDown();
            try { assertThat(continueWrite.await(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var draft = executor.submit(() -> service.createWithConcepts(sample(), "creator"));
            assertThat(rdfWriteReached.await(15, TimeUnit.SECONDS)).isTrue();
            var competing = sample().ontology();
            competing.getDescriptionModel().setDescription(java.util.Map.of("cs", "Must not replace original"));
            var ordinary = executor.submit(() -> service.createOntology(competing, "other"));
            continueWrite.countDown();
            var winner = draft.get(30, TimeUnit.SECONDS).ontology();
            var duplicate = ordinary.get(30, TimeUnit.SECONDS);

            // The competitor gets the draft back unchanged instead of creating or overwriting.
            assertThat(duplicate.getId()).isEqualTo(winner.getId());
            assertThat(duplicate.getPopis()).isEqualTo(java.util.Map.of("cs", "Rozpracovaný slovník dopravy"));
            var stored = ontologies.findByGraphName(GRAPH).orElseThrow();
            assertThat(stored.getId()).isEqualTo(winner.getId());
            assertThat(stored.getUserId()).isEqualTo("creator");
            assertThat(ontologies.count()).isEqualTo(1);
            assertThat(concepts.count()).isEqualTo(4);
            assertThat(outbox.count()).isEqualTo(1);
            Model graph = rdf.fetchGraph(GRAPH);
            assertThat(graph.listObjectsOfProperty(graph.getResource(GRAPH), DCTerms.description).toList().stream()
                            .map(o -> o.asLiteral().getString()).toList())
                    .isEqualTo(java.util.List.of("Rozpracovaný slovník dopravy"));
        } finally { continueWrite.countDown(); executor.shutdownNow(); rdf.beforeWrite(() -> {}); }
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
            assertThat(prepared.await(15, TimeUnit.SECONDS)).isTrue();
            var winningOrdinary = executor.submit(() -> {
                competitorStarted.countDown();
                return service.createOntology(sample().ontology(), "winner");
            });
            assertThat(competitorStarted.await(15, TimeUnit.SECONDS)).isTrue();
            awaitBlockedOnOntologyInsert(winningOrdinary);
            assertThat(rdf.writeCount()).isEqualTo(0);
            rollback.countDown();
            losingBatch.get(15, TimeUnit.SECONDS);
            assertThat(winningOrdinary.get(15, TimeUnit.SECONDS)).isNotNull();
            assertThat(ontologies.findByGraphName(GRAPH).orElseThrow().getUserId()).isEqualTo("winner");
            assertThat(ontologies.count()).isEqualTo(1);
            assertThat(concepts.count()).isEqualTo(0);
            assertThat(outbox.count()).isEqualTo(1);
            assertThat(rdf.writeCount()).isEqualTo(1);
            assertThat(rdf.deleteCount()).isEqualTo(0);
            assertThat(rdf.graphHasData(GRAPH)).isTrue();
        } finally { rollback.countDown(); executor.shutdownNow(); }
    }

    /** Returns once a backend waits on a row lock inside its ontology insert; fails if the competitor finishes first. */
    private void awaitBlockedOnOntologyInsert(Future<?> competitor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                            + " AND wait_event_type = 'Lock' AND query ILIKE 'insert into %ontologies%'",
                    Integer.class);
            if (waiting != null && waiting > 0) return;
            assertThat(competitor.isDone())
                    .as("the competitor finished without waiting on the uncommitted slug")
                    .isFalse();
            assertThat(System.nanoTime())
                    .as("the competitor never waited on the uncommitted slug")
                    .isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
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
        assertThatThrownBy(() -> service.editOntology(pending.ontology().getId(), rename))
                .isInstanceOf(OntologyCreationConflictException.class);

        outboxConfig.setBatchSize(0); // do not accidentally retry the first graph while creating the second
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
        int writesBeforeRename = rdf.writeCount();
        assertThatThrownBy(() -> service.editOntology(second.getId(), rename))
                .isInstanceOf(OntologyCreationConflictException.class);
        assertThat(rdf.writeCount()).isEqualTo(writesBeforeRename);
        assertThat(rdf.deleteCount()).isEqualTo(0);
        assertThat(ontologies.findById(second.getId()).orElseThrow().getGraphName()).isEqualTo(second.getGraphName());
    }

    @Test
    void deselectedClassIsRejectedBeforeAnyWrite() {
        var source = sample();
        var invalid = new com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto(
                source.ontology(), java.util.List.of(source.classes().get(0)), source.attributes(), source.relationships());
        assertThatThrownBy(() -> service.createWithConcepts(invalid, "creator"))
                .isInstanceOf(com.dia.ismdtoolbackend.exception.ConceptValidationException.class);
        assertThat(ontologies.count()).isEqualTo(0);
        assertThat(concepts.count()).isEqualTo(0);
        assertThat(rdf.graphHasData(GRAPH)).isFalse();
    }
}
