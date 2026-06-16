package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — the heart of the suite. Drives the real {@link OutboxRelay} against REAL Postgres outbox
 * rows (Testcontainers) and a REAL in-memory Jena dataset behind {@link JenaTDB2Repository}, so the
 * apply has true RDF semantics. Covers the plan's verification list: lost-update prevention,
 * strict per-aggregate ordering, idempotent re-apply, FAILED-blocks-its-aggregate, the DELETE_GRAPH
 * barrier, and blank-node rejection.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({TransactionTemplateConfig.class, OutboxRelayTest.RelayTestConfig.class})
@EntityScan(basePackageClasses = OutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = OutboxEntryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String A = GRAPH + "/pojem/a";
    private static final String B = GRAPH + "/pojem/b";
    private static final String PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxEntryRepository repository;
    @Autowired
    private OutboxConfig config;
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired
    private InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void resetState() {
        tdb2.reset();
        // The OutboxConfig bean is a shared singleton; tests mutate maxAttempts/batchSize. Reset to
        // defaults each test so mutations can't leak across an undefined JUnit method order.
        config.setMaxAttempts(10);
        config.setBatchSize(100);
    }

    @AfterEach
    void cleanUp() {
        txTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

    // ---- helpers ----

    private static Statement triple(String subject, String literal) {
        Model m = ModelFactory.createDefaultModel();
        Resource s = m.createResource(subject);
        Property p = m.createProperty(PREF_LABEL);
        return m.createStatement(s, p, literal);
    }

    private OutboxEntry upsert(String aggregate, Set<Statement> remove, Set<Statement> add) {
        OutboxEntry e = new OutboxEntry();
        e.setGraphName(GRAPH);
        e.setAggregateIri(aggregate);
        e.setOperation(OutboxOperation.UPSERT_CONCEPT);
        e.setDeleteTriples(OutboxTriples.toNTriples(remove));
        e.setInsertTriples(OutboxTriples.toNTriples(add));
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(repository.nextSeq());
        return e;
    }

    private OutboxEntry save(OutboxEntry e) {
        return txTemplate.execute(tx -> repository.save(e));
    }

    private int drain() {
        Integer applied = txTemplate.execute(tx -> relay.drainOnce());
        return applied == null ? 0 : applied;
    }

    private boolean graphHas(String subject, String literal) {
        return tdb2.dataset().getNamedModel(GRAPH).contains(triple(subject, literal));
    }

    private OutboxStatus statusOf(Long id) {
        return txTemplate.execute(tx -> repository.findById(id).orElseThrow().getStatus());
    }

    // ---- tests ----

    @Test
    void lostUpdate_twoConceptsSameGraph_bothSurvive() {
        save(upsert(A, Set.of(), Set.of(triple(A, "Alpha"))));
        save(upsert(B, Set.of(), Set.of(triple(B, "Beta"))));

        int applied = drain();

        assertThat(applied).isEqualTo(2);
        assertThat(graphHas(A, "Alpha")).isTrue();
        assertThat(graphHas(B, "Beta")).isTrue(); // neither delta clobbered the other
        assertThat(repository.countByStatus(OutboxStatus.DONE)).isEqualTo(2);
    }

    @Test
    void sameAggregate_appliesInSeqOrder_withinOnePass() {
        // Two edits to the SAME concept in one batch: first sets "v1", second removes "v1"/sets "v2".
        // Within a pass, each row's status is flushed before the next row's gate, so seq2 sees seq1
        // DONE and proceeds. End state must be v2 (seq1 then seq2), not v1.
        save(upsert(A, Set.of(), Set.of(triple(A, "v1"))));
        save(upsert(A, Set.of(triple(A, "v1")), Set.of(triple(A, "v2"))));

        int applied = drain();

        assertThat(applied).isEqualTo(2);
        assertThat(graphHas(A, "v1")).isFalse();
        assertThat(graphHas(A, "v2")).isTrue();
        assertThat(repository.countByStatus(OutboxStatus.DONE)).isEqualTo(2);
    }

    // F8a/F8b — the real claim-then-gate-ACROSS-PASSES proof. With batchSize=1 the second
    // same-aggregate row is NOT in pass 1's batch, so it must be left PENDING (gated by seq), then
    // applied on pass 2 — proving order is preserved across passes, not just within a batch. A
    // broken gate would apply the second row early / out of order; this test fails for that reason.
    @Test
    void sameAggregate_gatedRow_leftPendingThenAppliedNextPass() {
        config.setBatchSize(1);
        OutboxEntry first = save(upsert(A, Set.of(), Set.of(triple(A, "v1"))));
        OutboxEntry second = save(upsert(A, Set.of(triple(A, "v1")), Set.of(triple(A, "v2"))));

        // Pass 1: claims only `first` (batch size 1), applies it. `second` is untouched → PENDING.
        assertThat(drain()).isEqualTo(1);
        assertThat(statusOf(first.getId())).isEqualTo(OutboxStatus.DONE);
        assertThat(statusOf(second.getId())).isEqualTo(OutboxStatus.PENDING);
        assertThat(graphHas(A, "v1")).isTrue();
        assertThat(graphHas(A, "v2")).isFalse();

        // Pass 2: claims `second`, now at its aggregate's head, applies it.
        assertThat(drain()).isEqualTo(1);
        assertThat(statusOf(second.getId())).isEqualTo(OutboxStatus.DONE);
        assertThat(graphHas(A, "v1")).isFalse();
        assertThat(graphHas(A, "v2")).isTrue();
    }

    // F8a variant: a row whose EARLIER same-aggregate sibling is stuck PENDING must itself be gated
    // out (left PENDING), even though both are in the same batch — proving the gate, not just the
    // halt-set. We stick the earlier row by making its apply fail transiently (attempts<cap, so it
    // stays PENDING, not FAILED), then assert the later row did NOT apply out of order.
    @Test
    void sameAggregate_laterRowGated_whenEarlierStuckPending() {
        config.setMaxAttempts(5); // earlier row fails but stays PENDING (not FAILED) this pass
        tdb2.failApplyForGraph(GRAPH);
        OutboxEntry first = save(upsert(A, Set.of(), Set.of(triple(A, "v1"))));
        OutboxEntry second = save(upsert(A, Set.of(), Set.of(triple(A, "v2"))));

        drain();

        assertThat(statusOf(first.getId())).isEqualTo(OutboxStatus.PENDING); // failed once, will retry
        assertThat(statusOf(second.getId())).isEqualTo(OutboxStatus.PENDING); // gated behind `first`
        assertThat(graphHas(A, "v2")).isFalse(); // NOT applied out of order
    }

    @Test
    void idempotentReapply_appliedRowReappliedYieldsSameState() {
        OutboxEntry row = save(upsert(A, Set.of(), Set.of(triple(A, "Once"))));
        drain();
        assertThat(graphHas(A, "Once")).isTrue();

        // Force a re-apply: reset the row to PENDING (simulating a crash before DONE committed) and
        // drain again. Idempotent apply must leave exactly one triple, not duplicate/error.
        txTemplate.executeWithoutResult(tx -> {
            OutboxEntry r = repository.findById(row.getId()).orElseThrow();
            r.setStatus(OutboxStatus.PENDING);
            repository.save(r);
        });
        drain();

        assertThat(tdb2.dataset().getNamedModel(GRAPH).listStatements().toList()).hasSize(1);
        assertThat(graphHas(A, "Once")).isTrue();
    }

    @Test
    void failure_blocksOwnAggregateNotOthers() {
        config.setMaxAttempts(1); // fail fast to FAILED
        tdb2.failApplyForGraph(GRAPH + "-broken");

        OutboxEntry bad = save(upsertInGraph());
        OutboxEntry good = save(upsert(B, Set.of(), Set.of(triple(B, "Good"))));

        drain();

        assertThat(statusOf(bad.getId())).isEqualTo(OutboxStatus.FAILED);
        assertThat(statusOf(good.getId())).isEqualTo(OutboxStatus.DONE); // unrelated aggregate unaffected
        assertThat(graphHas(B, "Good")).isTrue();
    }

    // F8d — barrier blocks the DELETE_GRAPH while an earlier same-graph edit is genuinely PENDING
    // (NOT failed). batchSize=1 means pass 1 claims only the edit; the delete is considered on a
    // later pass and must be barrier-blocked until the edit is DONE. (The earlier failed-only test
    // could pass even if the barrier wrongly keyed on FAILED; this one cannot.)
    @Test
    void deleteGraphBarrier_blockedWhileEarlierEditPending_appliesAfterItIsDone() {
        config.setBatchSize(1);
        OutboxEntry edit = save(upsert(A, Set.of(), Set.of(triple(A, "Edit"))));
        OutboxEntry del = save(deleteGraph());

        // Pass 1: claims only the edit (batch 1). The DELETE_GRAPH is not even claimed yet, but the
        // graph must still hold the edit; the delete stays PENDING.
        drain();
        assertThat(statusOf(edit.getId())).isEqualTo(OutboxStatus.DONE);
        assertThat(statusOf(del.getId())).isEqualTo(OutboxStatus.PENDING);
        assertThat(graphHas(A, "Edit")).isTrue();

        // Pass 2: the edit is DONE so the barrier clears; the delete applies and drops the graph.
        drain();
        assertThat(statusOf(del.getId())).isEqualTo(OutboxStatus.DONE);
        assertThat(tdb2.dataset().getNamedModel(GRAPH).isEmpty()).isTrue();
    }

    // F8d (stronger) — within ONE batch, a DELETE_GRAPH must be held when an earlier same-graph row
    // is PENDING because it is itself gated/stuck (not failed). Earlier edit stuck PENDING (transient
    // fail, attempts<cap); the delete in the same batch must NOT drop the graph.
    @Test
    void deleteGraphBarrier_blockedByStuckPendingEarlierRow_sameBatch() {
        config.setMaxAttempts(5);
        tdb2.failApplyForGraph(GRAPH); // the edit's apply fails → stays PENDING this pass
        OutboxEntry edit = save(upsert(A, Set.of(), Set.of(triple(A, "Edit"))));
        OutboxEntry del = save(deleteGraph());

        drain();

        assertThat(statusOf(edit.getId())).isEqualTo(OutboxStatus.PENDING);
        assertThat(statusOf(del.getId())).isEqualTo(OutboxStatus.PENDING); // barrier held, not dropped
    }

    // F9 — DELETE_CONCEPTS happy path: removes the listed concepts' triples from the graph.
    @Test
    void deleteConcepts_removesListedConceptsTriples() {
        // Seed two concepts directly via an upsert, then a DELETE_CONCEPTS for one of them.
        save(upsert(A, Set.of(), Set.of(triple(A, "Alpha"))));
        save(upsert(B, Set.of(), Set.of(triple(B, "Beta"))));
        drain();
        assertThat(graphHas(A, "Alpha")).isTrue();

        save(deleteConcepts(A, List.of(A)));
        drain();

        assertThat(graphHas(A, "Alpha")).isFalse(); // A removed
        assertThat(graphHas(B, "Beta")).isTrue();   // B untouched
    }

    // F9 — corrupt target_iris JSON is treated as a (transient-wrapped) failure, not a pass crash.
    @Test
    void deleteConcepts_corruptJson_failsRowNotPass() {
        config.setMaxAttempts(1);
        OutboxEntry bad = new OutboxEntry();
        bad.setGraphName(GRAPH);
        bad.setAggregateIri(A);
        bad.setOperation(OutboxOperation.DELETE_CONCEPTS);
        bad.setTargetIris("{not valid json");
        bad.setStatus(OutboxStatus.PENDING);
        bad.setCreatedAt(Instant.now());
        bad.setSeq(repository.nextSeq());
        save(bad);

        drain(); // must not throw out of the pass

        assertThat(statusOf(bad.getId())).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void blankNode_isRejected_rowGoesToFailed() {
        config.setMaxAttempts(1);
        Model m = ModelFactory.createDefaultModel();
        Resource subj = m.createResource(A);
        Resource bnode = m.createResource(); // blank node object
        Statement withBnode = m.createStatement(subj, m.createProperty(PREF_LABEL), bnode);

        OutboxEntry row = save(upsert(A, Set.of(), Set.of(withBnode)));
        drain();

        // applyConceptDelta rejects blank nodes (DELETE/INSERT DATA cannot apply them); the relay
        // treats this as a PERMANENT failure — straight to FAILED, dataset untouched, no crash.
        assertThat(statusOf(row.getId())).isEqualTo(OutboxStatus.FAILED);
        assertThat(tdb2.dataset().getNamedModel(GRAPH).isEmpty()).isTrue();
    }

    // F6 — a blank node in the DELETE set (not just the insert set) is equally rejected.
    @Test
    void blankNodeInDeleteSet_isRejected_rowGoesToFailed() {
        config.setMaxAttempts(1);
        Model m = ModelFactory.createDefaultModel();
        Statement bnodeRemove = m.createStatement(
                m.createResource(A), m.createProperty(PREF_LABEL), m.createResource()); // blank object

        OutboxEntry row = save(upsert(A, Set.of(bnodeRemove), Set.of()));
        drain();

        assertThat(statusOf(row.getId())).isEqualTo(OutboxStatus.FAILED);
    }

    private OutboxEntry deleteConcepts(String aggregate, List<String> targetIris) {
        OutboxEntry e = new OutboxEntry();
        e.setGraphName(GRAPH);
        e.setAggregateIri(aggregate);
        e.setOperation(OutboxOperation.DELETE_CONCEPTS);
        try {
            e.setTargetIris(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(targetIris));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(repository.nextSeq());
        return e;
    }

    private OutboxEntry upsertInGraph() {
        String aggregate = "https://slovnik.gov.cz/g-broken" + "/" + "x/pojem/bad";
        OutboxEntry e = new OutboxEntry();
        e.setGraphName("https://slovnik.gov.cz/g-broken");
        e.setAggregateIri(aggregate);
        e.setOperation(OutboxOperation.UPSERT_CONCEPT);
        e.setDeleteTriples("");
        e.setInsertTriples(OutboxTriples.toNTriples(Set.of(triple(aggregate, "boom"))));
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(repository.nextSeq());
        return e;
    }

    private OutboxEntry deleteGraph() {
        OutboxEntry e = new OutboxEntry();
        e.setGraphName(GRAPH);
        e.setAggregateIri(GRAPH);
        e.setOperation(OutboxOperation.DELETE_GRAPH);
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(repository.nextSeq());
        return e;
    }

    // ---- test infra: a JenaTDB2Repository backed by an in-memory dataset ----

    static class RelayTestConfig {
        @Bean
        OutboxConfig outboxConfig() {
            return new OutboxConfig(); // defaults; individual tests mutate maxAttempts/batchSize
        }

        @Bean
        InMemoryTdb2 inMemoryTdb2() {
            return new InMemoryTdb2();
        }

        @Bean
        OutboxRelay outboxRelay(OutboxEntryRepository repo, InMemoryTdb2 tdb2, OutboxConfig config) {
            return new OutboxRelay(repo, tdb2, config);
        }
    }
}
