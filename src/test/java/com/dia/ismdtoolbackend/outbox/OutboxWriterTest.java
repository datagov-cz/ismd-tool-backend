package com.dia.ismdtoolbackend.outbox;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4 — verifies {@link OutboxWriter} enqueues the right row per op with serialized payloads, that
 * the N-Triples payload round-trips, and crucially that the row is bound to the CALLER's
 * transaction: a rolled-back outer tx leaves zero rows. That atomicity is the entire point of the
 * outbox pattern.
 *
 * <p>The whole class is {@code NOT_SUPPORTED} (no ambient @DataJpaTest transaction) so every test
 * drives its own transaction boundary via {@link TransactionTemplate} and commits real rows —
 * which is what makes the rollback assertion meaningful. {@code @AfterEach} cleans up.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({TransactionTemplateConfig.class, OutboxWriter.class})
@EntityScan(basePackageClasses = OutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = OutboxEntryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxWriterTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String IRI = "https://slovnik.gov.cz/g/pojem/a";
    private static final String PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxEntryRepository repository;

    @Autowired
    private TransactionTemplate txTemplate;

    @BeforeAll
    static void initJena() {
        // Force Jena's static system init before any vocabulary/Model use, so the Spring test
        // classloader can't trigger a partial vocabulary init (NodeFactory/SKOS NoClassDefFound).
        JenaSystem.init();
    }

    @AfterEach
    void cleanUp() {
        txTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

    /** A standalone triple (its own throwaway model) with a literal object. */
    private Statement literalTriple(String predicateIri, String literal) {
        Model m = ModelFactory.createDefaultModel();
        Resource subj = m.createResource(OutboxWriterTest.IRI);
        Property pred = m.createProperty(predicateIri);
        return m.createStatement(subj, pred, literal);
    }

    /** A standalone triple with a resource object. */
    private Statement resourceTriple() {
        Model m = ModelFactory.createDefaultModel();
        Resource subj = m.createResource(OutboxWriterTest.IRI);
        Property pred = m.createProperty(OutboxWriterTest.RDF_TYPE);
        return m.createStatement(subj, pred, m.createResource("http://www.w3.org/2004/02/skos/core#Concept"));
    }

    @Test
    void enqueueUpsert_writesOneRowWithRoundTrippablePayload() {
        Statement add1 = literalTriple(PREF_LABEL, "Label");
        Statement add2 = literalTriple(DEFINITION, "Def");
        Statement remove1 = literalTriple(PREF_LABEL, "Old label");

        OutboxEntry saved = txTemplate.execute(tx ->
                writer.enqueueUpsert(GRAPH, IRI, Set.of(remove1), Set.of(add1, add2)));

        assertThat(repository.count()).isEqualTo(1);
        assert saved != null;
        OutboxEntry row = repository.findById(saved.getId()).orElseThrow();
        assertThat(row.getOperation()).isEqualTo(OutboxOperation.UPSERT_CONCEPT);
        assertThat(row.getAggregateIri()).isEqualTo(IRI);
        assertThat(row.getGraphName()).isEqualTo(GRAPH);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getSeq()).isPositive();

        // Payloads round-trip to the exact statement sets.
        Model removed = OutboxTriples.parse(row.getDeleteTriples());
        Model added = OutboxTriples.parse(row.getInsertTriples());
        assertThat(removed.contains(remove1)).isTrue();
        assertThat(removed.size()).isEqualTo(1);
        assertThat(added.contains(add1)).isTrue();
        assertThat(added.contains(add2)).isTrue();
        assertThat(added.size()).isEqualTo(2);
    }

    @Test
    void enqueueUpsert_emptyRemoveSet_forCreate() {
        Statement add = resourceTriple();

        txTemplate.executeWithoutResult(tx -> writer.enqueueUpsert(GRAPH, IRI, Set.of(), Set.of(add)));

        OutboxEntry row = repository.findAll().get(0);
        assertThat(row.getDeleteTriples()).isEmpty();
        assertThat(OutboxTriples.parse(row.getInsertTriples()).size()).isEqualTo(1);
    }

    @Test
    void enqueueDeleteConcepts_serializesIriListAsJson() {
        txTemplate.executeWithoutResult(tx ->
                writer.enqueueDeleteConcepts(GRAPH, IRI, List.of(IRI, IRI + "-2")));

        OutboxEntry row = repository.findAll().get(0);
        assertThat(row.getOperation()).isEqualTo(OutboxOperation.DELETE_CONCEPTS);
        assertThat(row.getTargetIris()).contains(IRI).contains(IRI + "-2");
        assertThat(row.getDeleteTriples()).isNull();
    }

    @Test
    void enqueueDeleteGraph_noPayload_aggregateIsGraph() {
        txTemplate.executeWithoutResult(tx -> writer.enqueueDeleteGraph(GRAPH));

        OutboxEntry row = repository.findAll().get(0);
        assertThat(row.getOperation()).isEqualTo(OutboxOperation.DELETE_GRAPH);
        assertThat(row.getAggregateIri()).isEqualTo(GRAPH);
        assertThat(row.getDeleteTriples()).isNull();
        assertThat(row.getInsertTriples()).isNull();
        assertThat(row.getTargetIris()).isNull();
    }

    /**
     * The atomicity guarantee: enqueue inside a transaction that then throws → the row rolls back
     * with the (would-be) business change.
     */
    @Test
    void rolledBackOuterTransaction_leavesNoRow() {
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            writer.enqueueUpsert(GRAPH, IRI, Set.of(), Set.of(literalTriple(PREF_LABEL, "X")));
            throw new IllegalStateException("simulated business failure after enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
    }

    /**
     * Calling the writer with NO active transaction must fail loudly — otherwise the row would
     * auto-commit independently of the (absent) business change, silently breaking atomicity. This
     * method has no ambient tx (class is NOT_SUPPORTED) and does not wrap the call in txTemplate.
     */
    @Test
    void enqueueWithoutActiveTransaction_failsLoudly() {
        assertThatThrownBy(() -> writer.enqueueDeleteGraph(GRAPH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        assertThat(repository.count()).isZero();
    }
}
