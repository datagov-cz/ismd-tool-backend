package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review #8(c) — the end-to-end seam: the REAL {@link ConceptServiceImpl} (real editor/creator/mapper,
 * real PG repos via Testcontainers, real outbox beans + in-mem TDB2),
 * driven through the actual enqueue → drain → apply flow. The headline case is **create → rename**:
 * the #4 fix keys both rows on the pre-edit IRI (same aggregate), so the rename's DELETE of old-IRI
 * triples can never apply before the create's INSERT of them.
 *
 * <p>Beans are Spring-managed ({@link OutboxFlowIT}) so {@code @Transactional}/REQUIRES_NEW on
 * the service and relay are actually proxied — hand-{@code new}'d beans would skip the proxies and the
 * after-commit nudge's REQUIRES_NEW drain would fail with "no transaction". Peripheral read-path deps
 * are mocked. No ambient test transaction: the service methods run in their OWN boundary so the
 * after-commit nudge fires on a real commit.
 */
@OutboxFlowIT
class ConceptOutboxFlowIntegrationTest extends OutboxFlowTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";

    @Autowired private ConceptMetadataRepository conceptMetadataRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private ConceptServiceImpl service;
    @Autowired private PgTestData data;

    @BeforeEach
    void setUp() {
        // The FK target ontology row (createMetadataEntity requires it).
        data.createOntology("g-ontology", GRAPH, USER, false);
    }

    private ClassConceptModel classModel(String name) {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("třída");
        m.setType("objekt"); // ConceptCreator.addSpecificClassType requires a non-null type
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", name);
        nm.setName(names);
        m.setNameModel(nm);
        return m;
    }

    @Test
    void bulkCreate_allValid_persistsEveryConcept() {
        service.createConcepts(List.of(classModel("Alpha"), classModel("Beta")), USER);

        List<ConceptMetadataEntity> rows = conceptMetadataRepository.findAll();
        assertThat(rows).hasSize(2);
        Model graph = tdb2.dataset().getNamedModel(GRAPH);
        assertThat(rows).allSatisfy(row ->
                assertThat(graph.containsResource(graph.getResource(row.getConceptIri()))).isTrue());
    }

    @Test
    void bulkCreate_failingItem_persistsNothing() {
        List<ConceptCreateModel> batch = List.of(classModel("Alpha"), classModel("Beta"), classModel("Alpha"));

        assertThatThrownBy(() -> service.createConcepts(batch, USER))
                .isInstanceOf(ConceptValidationException.class);

        assertThat(conceptMetadataRepository.count()).as("no metadata row survives the failed batch").isZero();
        assertThat(outboxRepository.count()).as("no RDF write is queued").isZero();
        assertThat(tdb2.dataset().getNamedModel(GRAPH).isEmpty()).as("nothing reached TDB2").isTrue();
    }

    @Test
    void deleteConcept_graphMissing_stillRemovesMetadata() {
        service.createConcept(classModel("Alpha"), USER);
        Long id = conceptMetadataRepository.findAll().get(0).getId();
        tdb2.reset();

        service.deleteConcept(id);

        assertThat(conceptMetadataRepository.count()).isZero();
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.DONE);
    }

    @Test
    void deleteConcept_conceptAbsentFromPopulatedGraph_removesOnlyItsMetadata() {
        service.createConcept(classModel("Alpha"), USER);
        service.createConcept(classModel("Beta"), USER);
        ConceptMetadataEntity alpha = conceptMetadataRepository.findAll().stream()
                .filter(row -> row.getConceptIri().endsWith("alpha")).findFirst().orElseThrow();
        ConceptMetadataEntity beta = conceptMetadataRepository.findAll().stream()
                .filter(row -> row.getConceptIri().endsWith("beta")).findFirst().orElseThrow();
        tdb2.deleteConceptsFromGraph(List.of(alpha.getConceptIri()), GRAPH);

        service.deleteConcept(alpha.getId());

        assertThat(conceptMetadataRepository.findAll()).extracting(ConceptMetadataEntity::getId)
                .containsExactly(beta.getId());
        Model graph = tdb2.dataset().getNamedModel(GRAPH);
        assertThat(graph.containsResource(graph.getResource(beta.getConceptIri()))).isTrue();
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.DONE);
    }

    @Test
    void createThenRename_throughRealService_ordersCorrectly_noLostConcept() {
        // CREATE: the synchronous after-commit nudge drains, so the concept lands in TDB2.
        service.createConcept(classModel("Alpha"), USER);
        ConceptMetadataEntity row = conceptMetadataRepository.findAll().get(0);
        Long id = row.getId();
        String oldIri = row.getConceptIri();
        Model afterCreate = tdb2.dataset().getNamedModel(GRAPH);
        assertThat(afterCreate.containsResource(afterCreate.getResource(oldIri)))
                .as("create's nudge drained the concept into TDB2").isTrue();

        // RENAME: rename to a new IRI. The outbox row keys on the PRE-EDIT (old) IRI — same
        // aggregate as the create — so ordering holds and the rename relocates old→new in TDB2.
        com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel rename =
                new com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel();
        rename.setConceptType("třída");
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", "Beta");
        nm.setName(names);
        rename.setNameModel(nm);

        service.editConcept(id, rename);

        String newIri = conceptMetadataRepository.findById(id).orElseThrow().getConceptIri();
        assertThat(newIri).as("rename changed the IRI").isNotEqualTo(oldIri);

        Model g = tdb2.dataset().getNamedModel(GRAPH);
        assertThat(g.containsResource(g.getResource(newIri))).as("new IRI present in TDB2").isTrue();
        assertThat(g.listStatements(g.getResource(oldIri), null, (RDFNode) null).hasNext())
                .as("old IRI fully relocated, not left behind").isFalse();
        assertThat(g.size()).as("concept survived the rename").isGreaterThan(0);

        // Both outbox rows applied (DONE) and keyed on the SAME aggregate (the old IRI) — the #4 fix.
        assertThat(outboxRepository.findAll()).allMatch(r -> r.getStatus() == OutboxStatus.DONE);
        assertThat(outboxRepository.findAll()).allMatch(r -> oldIri.equals(r.getAggregateIri()));
    }
}
