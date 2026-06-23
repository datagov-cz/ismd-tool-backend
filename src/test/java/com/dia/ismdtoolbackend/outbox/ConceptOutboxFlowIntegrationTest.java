package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Review #8(c) — the end-to-end seam: the REAL {@link ConceptServiceImpl} (real editor/creator/mapper,
 * real PG repos via Testcontainers, real outbox beans + in-mem TDB2) with {@code outbox.enabled=true},
 * driven through the actual enqueue → drain → apply flow. The headline case is **create → rename**:
 * the #4 fix keys both rows on the pre-edit IRI (same aggregate), so the rename's DELETE of old-IRI
 * triples can never apply before the create's INSERT of them.
 *
 * <p>Beans are Spring-managed (a {@link TestConfiguration}) so {@code @Transactional}/REQUIRES_NEW on
 * the service and relay are actually proxied — hand-{@code new}'d beans would skip the proxies and the
 * after-commit nudge's REQUIRES_NEW drain would fail with "no transaction". Peripheral read-path deps
 * are mocked.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({ConceptOutboxFlowIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
// No ambient test transaction: the service methods must run in their OWN @Transactional boundary so
// the after-commit nudge fires on a real commit (a wrapping test tx would never commit).
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConceptOutboxFlowIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";

    @Autowired private ConceptMetadataRepository conceptMetadataRepository;
    @Autowired private OntologyMetadataRepository ontologyMetadataRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ConceptServiceImpl service;
    @Autowired private OutboxConfig outboxConfig;
    @Autowired private InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void setUp() {
        outboxConfig.setEnabled(true);
        outboxConfig.setBatchSize(100);
        // Clean slate + the FK target ontology row (createMetadataEntity requires it).
        txTemplate.executeWithoutResult(tx -> {
            outboxRepository.deleteAll();
            conceptMetadataRepository.deleteAll();
            ontologyMetadataRepository.deleteAll();
            OntologyMetadataEntity ont = new OntologyMetadataEntity();
            ont.setSlug("g-ontology");
            ont.setGraphName(GRAPH);
            ont.setUserId(USER);
            ont.setIsPublished(false);
            ont.setCreatedAt(LocalDateTime.now());
            ontologyMetadataRepository.save(ont);
        });
        tdb2.reset();
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
    void createThenRename_throughRealService_ordersCorrectly_noLostConcept() {
        // CREATE (flag on): the synchronous after-commit nudge drains, so the concept lands in TDB2.
        service.createConcept(classModel("Alpha"), USER);
        ConceptMetadataEntity row = conceptMetadataRepository.findAll().get(0);
        Long id = row.getId();
        String oldIri = row.getConceptIri();
        Model afterCreate = tdb2.dataset().getNamedModel(GRAPH);
        assertThat(afterCreate.containsResource(afterCreate.getResource(oldIri)))
                .as("create's nudge drained the concept into TDB2").isTrue();

        // RENAME (flag on): rename to a new IRI. The outbox row keys on the PRE-EDIT (old) IRI — same
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

    @TestConfiguration
    static class Beans {
        @Bean OutboxConfig outboxConfig() {
            OutboxConfig c = new OutboxConfig();
            c.setEnabled(true);
            return c;
        }
        @Bean InMemoryTdb2 inMemoryTdb2() { return new InMemoryTdb2(); }
        @Bean ConceptMetadataMapper conceptMetadataMapper() { return new ConceptMetadataMapperImpl(); }
        @Bean OutboxWriter outboxWriter(OutboxEntryRepository r) { return new OutboxWriter(r); }
        @Bean OutboxRelay outboxRelay(OutboxEntryRepository r, InMemoryTdb2 t, OutboxConfig c) {
            return new OutboxRelay(r, t, c);
        }
        @Bean OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) { return new OutboxRelayTrigger(relay); }

        @Bean ConceptServiceImpl conceptServiceImpl(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataMapper mapper, InMemoryTdb2 tdb2,
                OutboxConfig outboxConfig, OutboxWriter writer, OutboxRelayTrigger trigger) {
            return new ConceptServiceImpl(
                    conceptRepo, ontologyRepo, mapper,
                    new ConceptCreator(), new ConceptEditor(), tdb2,
                    mock(OntologyDetailExtractor.class),
                    mock(com.dia.ismdtoolbackend.repository.CommentRepository.class),
                    mock(com.dia.ismdtoolbackend.client.NkdSparqlClient.class),
                    mock(ConceptDeviationComparator.class),
                    mock(RppSnapshotHolder.class),
                    mock(ReferencedConceptsEnricher.class),
                    outboxConfig, writer, trigger,
                    // No external NKD links in this flow's test data → real detector returns empty and the
                    // mocked snapshot service is never called; reconcileNkdLinks is a no-op here.
                    mock(com.dia.ismdtoolbackend.service.NkdSnapshotService.class),
                    new com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector());
        }
    }
}
