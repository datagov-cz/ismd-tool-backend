package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiConceptReferenceDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.SkippedNkdChecks;
import com.dia.ismdtoolbackend.support.VocabularyCreationRequests;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.NkdSnapshotMaterializer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Each NKD-checked write path with NKD not answering and answering "nothing published", on real Postgres. */
@OutboxFlowIT
@Import(NkdWritePathsIntegrationTest.Beans.class)
class NkdWritePathsIntegrationTest extends OutboxFlowTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";
    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/999/pojem/cizi";

    @MockitoBean private NkdSparqlClient nkdSparqlClient;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("nkdConceptService")
    private ConceptServiceImpl conceptService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("nkdOntologyService")
    private OntologyServiceImpl ontologyService;
    @Autowired private ConceptServiceFixture fixture;
    @Autowired private DiagramMaterializeService materializeService;
    @Autowired private ConceptMetadataRepository concepts;
    @Autowired private NkdConceptSnapshotRepository snapshots;
    @Autowired private TransactionTemplate tx;
    @Autowired private PgTestData data;

    private OntologyMetadataEntity ontology;

    @BeforeEach
    void setUp() {
        SecurityUser user = new SecurityUser(USER, USER, List.of("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        ontology = data.createOntology("g-ontology", GRAPH, USER, false);
        startRequest();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static void startRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private void nkd(boolean answers) {
        when(nkdSparqlClient.findPublishedAmong(anyList()))
                .thenReturn(answers ? Optional.of(Set.of()) : Optional.empty());
    }

    private static void assertSkipped(boolean answers, String... iris) {
        if (answers) {
            assertThat(SkippedNkdChecks.current()).isEmpty();
        } else {
            assertThat(SkippedNkdChecks.current()).containsExactlyInAnyOrder(iris);
        }
    }

    private static NameModel name(String cs) {
        NameModel model = new NameModel();
        model.setName(Map.of("cs", cs));
        return model;
    }

    private static PropertyConceptModel property(String cs, String domain) {
        PropertyConceptModel m = new PropertyConceptModel();
        m.setConceptType("vlastnost");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        m.setDomain(domain);
        m.setNameModel(name(cs));
        return m;
    }

    private ConceptMetadataEntity row(String cs) {
        return concepts.findAll().stream()
                .filter(c -> c.getConceptName() != null && c.getConceptName().contains(cs))
                .findFirst().orElseThrow();
    }

    /** A stored property whose domain is the foreign IRI, with the request's warnings cleared. */
    private ConceptMetadataEntity storedProperty(boolean answers) {
        nkd(true);
        conceptService.createConcept(property("Vlastnost", NKD_IRI), USER);
        startRequest();
        nkd(answers);
        return row("Vlastnost");
    }

    private static PropertyConceptEditModel editOf(String domain) {
        PropertyConceptEditModel edit = new PropertyConceptEditModel();
        edit.setConceptType("vlastnost");
        edit.setDomain(domain);
        return edit;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void create(boolean answers) {
        nkd(answers);
        String iri = conceptService.createConcept(property("Vlastnost", NKD_IRI), USER).getConceptIri();
        assertSkipped(answers, iri);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bulkCreate(boolean answers) {
        nkd(answers);
        var created = conceptService.createConcepts(
                List.of(property("Prvni", NKD_IRI), property("Druha", NKD_IRI)), USER);
        assertSkipped(answers, created.get(0).getConceptIri(), created.get(1).getConceptIri());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createWithConcepts(boolean answers) {
        nkd(answers);
        var sample = VocabularyCreationRequests.sample();
        var a = sample.attributes().get(0);
        var request = new OntologyCreateWithConceptsRequestDto(sample.ontology(), sample.classes(),
                List.of(new OntologyCreateWithConceptsRequestDto.AttributeDto(a.ref(),
                        new AiConceptReferenceDto(null, NKD_IRI), a.name(), a.definition(), a.explanation(),
                        a.legalAct(), a.dataType())),
                sample.relationships());

        var result = ontologyService.createWithConcepts(request, USER);

        assertSkipped(answers, result.conceptIris().get(a.ref()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void edit(boolean answers) {
        ConceptMetadataEntity stored = storedProperty(answers);
        conceptService.editConcept(stored.getId(), editOf(NKD_IRI));
        assertSkipped(answers, stored.getConceptIri());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void syncApply(boolean answers) {
        ConceptMetadataEntity stored = storedProperty(answers);
        tx.executeWithoutResult(s -> {
            ConceptMetadataEntity managed = concepts.findById(stored.getId()).orElseThrow();
            managed.setIsPublished(true);
            concepts.save(managed);
        });
        ConceptMetadataEntity published = concepts.findById(stored.getId()).orElseThrow();
        ConceptEditOperation editOperation = fixture.buildEditOperation();
        WorkingCopySyncOperation sync = fixture.buildSyncOperation(editOperation, fixture.buildDetailReader());
        var plan = new WorkingCopySyncOperation.SyncPlan(published.getUpdatedAt(), published.getSlug(),
                editOf(NKD_IRI), false, 1, 1, fixture.buildNkdLinkReconciler().prefetch(GRAPH, List.of(NKD_IRI)));

        tx.executeWithoutResult(s -> sync.applySync(stored.getId(), plan));

        assertSkipped(answers, stored.getConceptIri());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void diagramMaterialize(boolean answers) {
        ConceptMetadataEntity stored = storedProperty(answers);
        Long diagramId = data.createDiagram(ontology, "Diagram");
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(NKD_IRI);
        overlay.setBaseUpdatedAt(stored.getUpdatedAt());
        data.stagePendingEdit(diagramId, ontology, stored.getConceptIri(), overlay);

        var result = materializeService.materialize(diagramId, ontology.getId());

        assertThat(result.failed()).isEmpty();
        assertSkipped(answers, stored.getConceptIri());
    }

    @Test
    void aFailedDiagramChangeReportsNoSkippedCheck() {
        ConceptMetadataEntity stored = storedProperty(false);
        Long diagramId = data.createDiagram(ontology, "Diagram");
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(NKD_IRI);
        overlay.setBaseUpdatedAt(stored.getUpdatedAt().minusDays(1));
        data.stagePendingEdit(diagramId, ontology, stored.getConceptIri(), overlay);

        var result = materializeService.materialize(diagramId, ontology.getId());

        assertThat(result.failed()).hasSize(1);
        assertThat(SkippedNkdChecks.current()).isEmpty();
    }

    @Test
    void aCreatedRelationshipHasItsNkdRangeSnapshotRightAfterCreate() {
        ClassConceptModel source = new ClassConceptModel();
        source.setConceptType("třída");
        source.setType("objekt");
        source.setOntologyGraphName(GRAPH);
        source.setNamespace(GRAPH);
        source.setIsPublic(true);
        source.setNameModel(name("Zdroj"));
        nkd(true);
        String sourceIri = conceptService.createConcept(source, USER).getConceptIri();

        RelationshipConceptModel relationship = new RelationshipConceptModel();
        relationship.setConceptType("vztah");
        relationship.setOntologyGraphName(GRAPH);
        relationship.setNamespace(GRAPH);
        relationship.setDomain(sourceIri);
        relationship.setRange(NKD_IRI);
        relationship.setNameModel(name("Vede"));
        Model raw = ModelFactory.createDefaultModel();
        raw.add(raw.createResource(NKD_IRI), RDFS.label, "Cizí");
        when(nkdSparqlClient.findPublishedAmong(anyList())).thenReturn(Optional.of(Set.of(NKD_IRI)));
        stubPublishedCopy(raw);

        Long id = conceptService.createConcept(relationship, USER).getId();

        NkdConceptSnapshotEntity snapshot = snapshots.findByOwningConceptIdAndNkdIri(id, NKD_IRI).orElseThrow();
        assertThat(snapshot.getLinkPredicate()).isEqualTo(SnapshotLinkType.RANGE_TARGET.value());
        assertThat(snapshot.getMaterializedTriples()).contains("Cizí");
    }

    @Test
    void bulkCreateAsksNkdOnce_andFetchesASharedTargetOnce() {
        ClassConceptModel source = new ClassConceptModel();
        source.setConceptType("třída");
        source.setType("objekt");
        source.setOntologyGraphName(GRAPH);
        source.setNamespace(GRAPH);
        source.setIsPublic(true);
        source.setNameModel(name("Zdroj"));
        nkd(true);
        String sourceIri = conceptService.createConcept(source, USER).getConceptIri();
        org.mockito.Mockito.clearInvocations(nkdSparqlClient);

        Model raw = ModelFactory.createDefaultModel();
        raw.add(raw.createResource(NKD_IRI), RDFS.label, "Cizí");
        when(nkdSparqlClient.findPublishedAmong(anyList())).thenReturn(Optional.of(Set.of(NKD_IRI)));
        stubPublishedCopy(raw);

        var created = conceptService.createConcepts(
                List.of(relationship("Vede", sourceIri), relationship("Ridi", sourceIri)), USER);

        org.mockito.Mockito.verify(nkdSparqlClient).findPublishedAmong(anyList());
        org.mockito.Mockito.verify(nkdSparqlClient).fetchPublishedConceptsWithRaw(List.of(NKD_IRI));
        created.forEach(c -> assertThat(snapshots.findByOwningConceptIdAndNkdIri(c.getId(), NKD_IRI)).isPresent());
    }

    private void stubPublishedCopy(Model raw) {
        var concept = new NkdSparqlClient.PublishedConcept(OntologyDetailModel.ConceptDetailModel.builder()
                .iri(NKD_IRI).name(Map.of("cs", "Cizí")).build(), "https://nkd/g");
        when(nkdSparqlClient.fetchPublishedConceptsWithRaw(List.of(NKD_IRI))).thenReturn(
                Map.of(NKD_IRI, Optional.of(new NkdSparqlClient.PublishedConceptWithRaw(concept, raw))));
    }

    private static RelationshipConceptModel relationship(String cs, String domain) {
        RelationshipConceptModel m = new RelationshipConceptModel();
        m.setConceptType("vztah");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        m.setDomain(domain);
        m.setRange(NKD_IRI);
        m.setNameModel(name(cs));
        return m;
    }

    @TestConfiguration
    static class Beans {

        @Bean
        NkdSnapshotService nkdSnapshotServiceReal(NkdConceptSnapshotRepository repository) {
            return new NkdSnapshotServiceImpl(repository, new NkdSnapshotMaterializer(),
                    new ConceptDeviationComparator());
        }

        @Bean
        ConceptServiceFixture nkdConceptFixture(ConceptMetadataRepository conceptRepository,
                                                OntologyMetadataRepository ontologyRepository,
                                                MetadataTouchService touchService, ConceptMetadataMapper mapper,
                                                InMemoryTdb2 tdb2, OutboxWriter writer,
                                                OutboxEntryRepository outboxRepository, OutboxRelayTrigger trigger,
                                                NkdSparqlClient nkdSparqlClient, NkdSnapshotService snapshotService,
                                                PlatformTransactionManager txManager) {
            return new ConceptServiceFixture()
                    .conceptMetadataRepository(conceptRepository)
                    .ontologyMetadataRepository(ontologyRepository)
                    .metadataTouchService(touchService)
                    .conceptMetadataMapper(mapper)
                    .conceptCreator(new ConceptCreator())
                    .conceptEditor(new ConceptEditor())
                    .jenaTDB2Repository(tdb2)
                    .outboxWriter(writer)
                    .outboxRepository(outboxRepository)
                    .outboxRelayTrigger(trigger)
                    .nkdSparqlClient(nkdSparqlClient)
                    .nkdSnapshotService(snapshotService)
                    .writeTransaction(new ConceptWriteTransaction(txManager));
        }

        @Bean
        @Primary
        ConceptServiceImpl nkdConceptService(ConceptServiceFixture fixture) {
            return fixture.build();
        }

        @Bean
        OntologyServiceImpl nkdOntologyService(OntologyMetadataRepository ontologyRepository,
                                               MetadataTouchService touchService,
                                               ConceptMetadataRepository conceptRepository, InMemoryTdb2 tdb2,
                                               OutboxWriter writer, OutboxEntryRepository outboxRepository,
                                               OutboxRelayTrigger trigger, NkdSparqlClient nkdSparqlClient,
                                               NkdSnapshotService snapshotService,
                                               PlatformTransactionManager txManager) {
            return new OntologyServiceFixture()
                    .writeTransaction(new ConceptWriteTransaction(txManager))
                    .ontologyMetadataRepository(ontologyRepository)
                    .metadataTouchService(touchService)
                    .conceptMetadataRepository(conceptRepository)
                    .jenaTDB2Repository(tdb2)
                    .ontologyMetadataMapper(new OntologyMetadataMapperImpl())
                    .ontologyEditor(new OntologyEditor())
                    .outboxWriter(writer)
                    .outboxRepository(outboxRepository)
                    .outboxRelayTrigger(trigger)
                    .nkdSparqlClient(nkdSparqlClient)
                    .nkdSnapshotService(snapshotService)
                    .build();
        }

        @Bean
        DiagramChangeApplier diagramChangeApplier(ConceptServiceFixture fixture, ConceptServiceImpl conceptService,
                                                  ConceptMetadataRepository conceptRepository,
                                                  DiagramPendingEditRepository pendingEdits,
                                                  DiagramEdgeRepository edges, InMemoryTdb2 tdb2) {
            return new DiagramChangeApplier(conceptService, conceptRepository, pendingEdits, edges, tdb2);
        }

        @Bean
        DiagramMaterializeService diagramMaterializeService(DiagramChangeApplier applier,
                                                            DiagramPendingEditRepository pendingEdits) {
            return new DiagramMaterializeService(applier, pendingEdits);
        }
    }
}
