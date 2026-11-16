package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.ConceptController;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.controller.dto.WorkingCopySyncRequestDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotEndpointService;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceFixture;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationTestBeans;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.testsupport.pg.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Working-copy sync through the real controller, service, outbox and cache: the response carries the
 * post-sync detail. The sync transaction only enqueues its RDF delta, which reaches the store in the
 * after-commit drain, so the detail is read after the service call returns.
 *
 * <p>Real PG (Testcontainers), in-memory TDB2, real projection cache and deviation service; only the NKD
 * twin is stubbed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({WorkingCopySyncReadAfterCommitIntegrationTest.Beans.class, WorkingCopyDeviationTestBeans.class,
        TransactionTemplateConfig.class, PgTestData.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class})
// No ambient test transaction: the after-commit drain fires only on a real commit.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkingCopySyncReadAfterCommitIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";
    private static final String LOCAL_DEFINITION = "Místní definice";
    private static final String PUBLISHED_DEFINITION = "Publikovaná definice";

    @Autowired private ConceptMetadataRepository conceptMetadataRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ConceptServiceImpl service;
    @Autowired private InMemoryTdb2 tdb2;
    @Autowired private OntologyDetailExtractor detailExtractor;
    /** The NKD twin; Spring resets it after every test. */
    @MockitoBean private NkdSparqlClient nkdSparqlClient;
    @Autowired private PgTestData data;
    @Autowired private CacheManager cacheManager;

    private ConceptController controller;
    private Long conceptId;
    private String slug;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void setUp() {
        data.createOntology("g-ontology", GRAPH, USER, false);
        tdb2.reset();
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        controller = new ConceptController(service, mock(NkdSnapshotEndpointService.class));

        workingCopyDeviatingOnDefinition();
    }

    /** A working copy whose only difference from its NKD twin is the definition. */
    private void workingCopyDeviatingOnDefinition() {
        ClassConceptModel model = new ClassConceptModel();
        model.setConceptType("třída");
        model.setType("objekt");
        model.setOntologyGraphName(GRAPH);
        model.setNamespace(GRAPH);
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Alpha"));
        model.setNameModel(name);
        DefinitionModel definition = new DefinitionModel();
        definition.setDefinition(Map.of("cs", LOCAL_DEFINITION));
        model.setDefinitionModel(definition);
        service.createConcept(model, USER);

        ConceptMetadataEntity row = conceptMetadataRepository.findAll().get(0);
        conceptId = row.getId();
        slug = row.getSlug();
        String iri = row.getConceptIri();
        txTemplate.executeWithoutResult(tx -> {
            ConceptMetadataEntity managed = conceptMetadataRepository.findById(conceptId).orElseThrow();
            managed.setIsPublished(true);
            conceptMetadataRepository.save(managed);
        });

        when(nkdSparqlClient.fetchPublishedConcept(anyString())).thenReturn(Optional.of(twinOf(iri)));
    }

    /** The twin is the local concept's own projection with the definition literal swapped. */
    private ConceptDetailModel twinOf(String iri) {
        Model copy = tdb2.fetchGraph(GRAPH);
        List<Statement> definitions = copy.listStatements().toList().stream()
                .filter(s -> s.getObject().isLiteral()
                        && LOCAL_DEFINITION.equals(s.getObject().asLiteral().getLexicalForm()))
                .toList();
        assertThat(definitions).as("the created concept carries its definition").hasSize(1);
        Statement local = definitions.get(0);
        copy.remove(local);
        copy.add(local.getSubject(), local.getPredicate(),
                copy.createLiteral(PUBLISHED_DEFINITION, local.getObject().asLiteral().getLanguage()));
        return detailExtractor.extractConceptDetail(detailExtractor.applyOFNTransformations(copy), iri);
    }

    private GetConceptDto sync() {
        return controller.syncWorkingCopy(conceptId, new WorkingCopySyncRequestDto(List.of("definice")),
                new SecurityUser(USER, "User", List.of())).getBody().getData();
    }

    @Test
    void syncResponse_carriesThePostSyncDefinitionAndNoDeviation() {
        // The detail page was open before the sync, so the pre-sync projection is cached.
        GetConceptDto before = service.getConceptDetail(slug);
        assertThat(before.getConceptDetail().getDefinition()).containsEntry("cs", LOCAL_DEFINITION);
        assertThat(before.getPublishedConceptDeviationModel().getStatus())
                .as("baseline: the working copy deviates").isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(before.getPublishedConceptDeviationModel().getDefinition()).isNotNull();

        GetConceptDto response = sync();

        assertThat(response.getConceptDetail().getDefinition())
                .as("response reflects the synced definition").containsEntry("cs", PUBLISHED_DEFINITION);
        assertThat(response.getPublishedConceptDeviationModel().getStatus())
                .as("response reports no remaining deviation").isEqualTo(DeviationStatus.NO_DEVIATION);
        assertThat(response.getConceptMetadata().getIsPublished())
                .as("accepting every deviating field keeps it a working copy").isTrue();
    }

    @Test
    void aSeparateReadAfterTheSync_agreesWithTheResponse() {
        service.getConceptDetail(slug);

        GetConceptDto response = sync();
        GetConceptDto reread = service.getConceptDetail(slug);

        assertThat(reread.getConceptDetail().getDefinition())
                .isEqualTo(response.getConceptDetail().getDefinition())
                .containsEntry("cs", PUBLISHED_DEFINITION);
        assertThat(reread.getPublishedConceptDeviationModel().getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
    }

    /** With nothing left to sync, a repeated request is the 400 — the response must not invite one. */
    @Test
    void aSecondSync_isRejectedAsNothingToSync() {
        sync();

        assertThatThrownBy(this::sync)
                .isInstanceOf(OntologyValidationException.class)
                .hasMessageContaining("není co synchronizovat");
    }

    /** Why the read sits outside the transaction: before the commit the store still holds the old graph. */
    @Test
    void aReadInsideTheSyncTransaction_stillSeesThePreSyncGraph() {
        GetConceptDto insideTransaction = txTemplate.execute(tx -> {
            service.syncWorkingCopy(conceptId, List.of("definice"));
            return service.getConceptDetail(slug);
        });

        assertThat(insideTransaction.getConceptDetail().getDefinition()).containsEntry("cs", LOCAL_DEFINITION);
        assertThat(insideTransaction.getPublishedConceptDeviationModel().getStatus())
                .isEqualTo(DeviationStatus.HAS_DEVIATIONS);
    }

    @TestConfiguration
    @EnableCaching(proxyTargetClass = true)
    static class Beans {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean OutboxConfig outboxConfig() { return new OutboxConfig(); }
        @Bean InMemoryTdb2 inMemoryTdb2() { return new InMemoryTdb2(); }
        @Bean MetadataTouchService metadataTouchService(ConceptMetadataRepository c,
                                                        OntologyMetadataRepository o) {
            return new MetadataTouchService(c, o);
        }
        @Bean @Primary ConceptMetadataMapper conceptMetadataMapper() { return new ConceptMetadataMapperImpl(); }
        @Bean OutboxWriter outboxWriter(OutboxEntryRepository r) { return new OutboxWriter(r); }
        @Bean OutboxRelay outboxRelay(OutboxEntryRepository r, InMemoryTdb2 t, OutboxConfig c) {
            return new OutboxRelay(r, t, c);
        }
        @Bean OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) { return new OutboxRelayTrigger(relay); }
        @Bean OntologyDetailExtractor ontologyDetailExtractor(ConceptMetadataRepository c) {
            return new OntologyDetailExtractor(c);
        }

        @Bean ConceptServiceImpl conceptServiceImpl(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataMapper mapper, InMemoryTdb2 tdb2, OntologyDetailExtractor detailExtractor,
                NkdSparqlClient nkdSparqlClient, OutboxWriter writer, OutboxEntryRepository outboxRepository,
                OutboxRelayTrigger trigger, MetadataTouchService touchService,
                WorkingCopyDeviationServiceImpl deviationService) {
            return new ConceptServiceFixture()
                    .conceptMetadataRepository(conceptRepo)
                    .ontologyMetadataRepository(ontologyRepo)
                    .metadataTouchService(touchService)
                    .conceptMetadataMapper(mapper)
                    .conceptCreator(new ConceptCreator())
                    .conceptEditor(new ConceptEditor())
                    .jenaTDB2Repository(tdb2)
                    .detailExtractor(detailExtractor)
                    .nkdSparqlClient(nkdSparqlClient)
                    .outboxWriter(writer)
                    .outboxRepository(outboxRepository)
                    .outboxRelayTrigger(trigger)
                    .workingCopyDeviationService(deviationService)
                    .build();
        }
    }
}
