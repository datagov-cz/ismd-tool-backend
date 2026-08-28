package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Guards {@code updatedAt} propagation against REAL Postgres through the REAL {@link ConceptServiceImpl}.
 *
 * <p>The headline case is the **RDF-only edit**: changing a concept's definition dirties no mapped
 * column, so before {@link MetadataTouchService} the {@code save()} was a silent no-op and neither
 * timestamp moved. The whole suite missed this because it mocks the persistence layer — only a real
 * PG flush can tell "saved" apart from "no UPDATE emitted".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({UpdatedAtPropagationIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
// No ambient test transaction: the service must run in its own @Transactional boundary so each write
// really commits and the next read sees a committed timestamp.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UpdatedAtPropagationIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";

    @Autowired private ConceptMetadataRepository conceptMetadataRepository;
    @Autowired private OntologyMetadataRepository ontologyMetadataRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ConceptServiceImpl service;
    @Autowired private InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            outboxRepository.deleteAll();
            conceptMetadataRepository.deleteAll();
            // Flush the child deletes before removing the parents: the ontology→concepts cascade would
            // otherwise re-attach rows already queued for deletion and leave the ontology row behind.
            conceptMetadataRepository.flush();
            ontologyMetadataRepository.deleteAll();
            ontologyMetadataRepository.flush();
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
        m.setType("objekt");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", name);
        nm.setName(names);
        m.setNameModel(nm);
        return m;
    }

    private LocalDateTime ontologyUpdatedAt() {
        return ontologyMetadataRepository.findByGraphName(GRAPH).orElseThrow().getUpdatedAt();
    }

    private LocalDateTime conceptUpdatedAt(Long id) {
        return conceptMetadataRepository.findById(id).orElseThrow().getUpdatedAt();
    }

    private void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An edit that changes ONLY RDF (definition) must still bump both timestamps. */
    @Test
    void rdfOnlyEdit_bumpsConceptAndOntologyUpdatedAt() {
        service.createConcept(classModel("Alpha"), USER);
        Long id = conceptMetadataRepository.findAll().get(0).getId();

        String iriBefore = conceptMetadataRepository.findById(id).orElseThrow().getConceptIri();
        LocalDateTime conceptBefore = conceptUpdatedAt(id);
        LocalDateTime ontologyBefore = ontologyUpdatedAt();
        pause();

        // Same name (no IRI/name change) + a new definition → RDF changes, mapped columns do not.
        ClassConceptEditModel edit = new ClassConceptEditModel();
        edit.setConceptType("třída");
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", "Alpha");
        nm.setName(names);
        edit.setNameModel(nm);
        Map<String, String> definition = new HashMap<>();
        definition.put("cs", "Nová definice pojmu.");
        com.dia.ismdtoolbackend.models.concept.DefinitionModel dm =
                new com.dia.ismdtoolbackend.models.concept.DefinitionModel();
        dm.setDefinition(definition);
        edit.setDefinitionModel(dm);

        service.editConcept(id, edit);

        assertThat(conceptMetadataRepository.findById(id).orElseThrow().getConceptIri())
                .as("precondition: this edit is RDF-only — the IRI must not change")
                .isEqualTo(iriBefore);
        assertThat(conceptUpdatedAt(id))
                .as("RDF-only edit bumps the concept's updatedAt")
                .isAfter(conceptBefore);
        assertThat(ontologyUpdatedAt())
                .as("concept edit propagates to the parent ontology's updatedAt")
                .isAfter(ontologyBefore);
    }

    /** Creating a concept modifies the vocabulary, so the parent ontology must move. */
    @Test
    void createConcept_bumpsOntologyUpdatedAt() {
        LocalDateTime before = ontologyUpdatedAt();
        pause();

        service.createConcept(classModel("Alpha"), USER);

        assertThat(ontologyUpdatedAt()).isAfter(before);
    }

    /** Deleting a concept likewise modifies the vocabulary. */
    @Test
    void deleteConcept_bumpsOntologyUpdatedAt() {
        service.createConcept(classModel("Alpha"), USER);
        Long id = conceptMetadataRepository.findAll().get(0).getId();

        LocalDateTime before = ontologyUpdatedAt();
        pause();

        service.deleteConcept(id);

        assertThat(conceptMetadataRepository.findById(id)).isEmpty();
        assertThat(ontologyUpdatedAt())
                .as("deleting a concept bumps the parent ontology's updatedAt")
                .isAfter(before);
    }

    @TestConfiguration
    static class Beans {
        @Bean OutboxConfig outboxConfig() {
            OutboxConfig c = new OutboxConfig();
            c.setEnabled(true);
            return c;
        }
        @Bean InMemoryTdb2 inMemoryTdb2() { return new InMemoryTdb2(); }
        @Bean @Primary ConceptMetadataMapper conceptMetadataMapper() { return new ConceptMetadataMapperImpl(); }
        @Bean OutboxWriter outboxWriter(OutboxEntryRepository r) { return new OutboxWriter(r); }
        @Bean OutboxRelay outboxRelay(OutboxEntryRepository r, InMemoryTdb2 t, OutboxConfig c) {
            return new OutboxRelay(r, t, c);
        }
        @Bean OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) { return new OutboxRelayTrigger(relay); }
        @Bean MetadataTouchService metadataTouchService(ConceptMetadataRepository c,
                                                        OntologyMetadataRepository o) {
            return new MetadataTouchService(c, o);
        }

        @Bean ConceptServiceImpl conceptServiceImpl(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo,
                MetadataTouchService touchService, ConceptMetadataMapper mapper, InMemoryTdb2 tdb2,
                OutboxConfig outboxConfig, OutboxWriter writer, OutboxRelayTrigger trigger) {
            return new ConceptServiceImpl(
                    conceptRepo, ontologyRepo, touchService, mapper,
                    new ConceptCreator(), new ConceptEditor(), tdb2,
                    mock(OntologyDetailExtractor.class),
                    mock(com.dia.ismdtoolbackend.repository.CommentRepository.class),
                    mock(com.dia.ismdtoolbackend.client.NkdSparqlClient.class),
                    mock(ConceptDeviationComparator.class),
                    mock(RppSnapshotHolder.class),
                    mock(ReferencedConceptsEnricher.class),
                    outboxConfig, writer, trigger,
                    mock(com.dia.ismdtoolbackend.service.NkdSnapshotService.class),
                    new NkdLinkDetector(),
                    new WorkingCopySyncFields(),
                    mock(NkdSnapshotWarmer.class),
                    new NkdConfig(),
                    mock(WorkingCopyDeviationServiceImpl.class));
        }
    }
}
