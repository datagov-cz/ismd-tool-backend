package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceFixture;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.POJEM_JSON_LD;
import static com.dia.constants.VocabularyConstants.TRIDA_JSON_LD;
import static com.dia.constants.VocabularyConstants.VZTAH_JSON_LD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OntologyServiceImplTest {

    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private ValidationReportRepository validationReportRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private CommentRepository commentRepository;
    private OntologyEditor ontologyEditor;
    private OntologyDetailExtractor detailExtractor;
    private PublishedResourceUtil deviationChecker;
    private OutboxWriter outboxWriter;
    private OutboxRelayTrigger outboxRelayTrigger;
    private NkdSnapshotService nkdSnapshotService;
    private NkdDetailService nkdDetailService;

    private OntologyServiceImpl ontologyService;

    @Captor private ArgumentCaptor<List<String>> irisCaptor;

    private OntologyMetadataEntity testOntologyEntity;
    private Model testModel;
    private static final Long TEST_ONTOLOGY_ID = 1L;
    private static final String TEST_ONTOLOGY_SLUG = "test-ontology";
    private static final String TEST_GRAPH_NAME = "http://example.org/test-ontology";
    private static final String TEST_USER_ID = "user123";

    @BeforeEach
    void setUp() {
        OntologyServiceFixture fixture = new OntologyServiceFixture();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        validationReportRepository = fixture.validationReportRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        commentRepository = fixture.commentRepository();
        ontologyEditor = fixture.ontologyEditor();
        detailExtractor = fixture.detailExtractor();
        deviationChecker = fixture.deviationChecker();
        outboxWriter = fixture.outboxWriter();
        outboxRelayTrigger = fixture.outboxRelayTrigger();
        nkdSnapshotService = fixture.nkdSnapshotService();
        nkdDetailService = fixture.nkdDetailService();
        ontologyService = fixture.build();

        testOntologyEntity = new OntologyMetadataEntity();
        testOntologyEntity.setId(TEST_ONTOLOGY_ID);
        testOntologyEntity.setSlug(TEST_ONTOLOGY_SLUG);
        testOntologyEntity.setGraphName(TEST_GRAPH_NAME);
        testOntologyEntity.setUserId(TEST_USER_ID);
        testOntologyEntity.setIsPublished(false);

        testModel = ModelFactory.createDefaultModel();
    }

    // ========== deleteOntology Tests ==========

    @Test
    void deleteOntology_Success() throws OntologyException {
        ValidationReportEntity validationReport = new ValidationReportEntity();
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(validationReportRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(validationReport));

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(validationReportRepository).delete(validationReport);
        verify(outboxWriter).enqueueDeleteGraph(TEST_GRAPH_NAME);
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
        verify(outboxRelayTrigger).nudgeAfterCommit();
    }

    @Test
    void deleteOntology_OntologyNotFound() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository, never()).deleteById(any());
    }

    @Test
    void deleteOntology_neverReadsTheGraph() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(jenaTDB2Repository, never()).graphHasData(anyString());
        verify(outboxWriter).enqueueDeleteGraph(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
    }

    @Test
    void deleteOntology_NoValidationReport() throws OntologyException {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(validationReportRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(validationReportRepository, never()).delete(any());
        verify(outboxWriter).enqueueDeleteGraph(TEST_GRAPH_NAME);
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
        verify(outboxRelayTrigger).nudgeAfterCommit();
    }

    // ========== getOntologyMetadataBySlug Tests ==========

    @Test
    void getOntologyMetadataBySlug_Success() {
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));

        OntologyMetadataModel result = ontologyService.getOntologyMetadataBySlug(TEST_ONTOLOGY_SLUG);

        assertEquals(TEST_ONTOLOGY_ID, result.getId());
        assertEquals(TEST_ONTOLOGY_SLUG, result.getSlug());
        assertEquals(TEST_GRAPH_NAME, result.getGraphName());
        assertEquals(TEST_USER_ID, result.getUser().getUserId());
        assertEquals(ConceptSourceTag.DRAFT, result.getSourceTag());
    }

    @Test
    void getOntologyMetadataBySlug_NotFound() {
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.empty());

        assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getOntologyMetadataBySlug(TEST_ONTOLOGY_SLUG));
    }

    @Test
    void checkIri_Available_DoesNotWriteOrContactNkd() {
        var model = createValidOntologyCreateModel();
        var result = ontologyService.checkIri(new OntologyIriCheckRequestDto(model.getNamespace(), model.getNameModel()));

        assertEquals(TEST_GRAPH_NAME, result.iri());
        assertTrue(result.valid());
        assertTrue(result.available());
        verify(ontologyMetadataRepository).findByGraphName(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).findBySlug(TEST_ONTOLOGY_SLUG);
        verifyNoMoreInteractions(ontologyMetadataRepository);
        verifyNoInteractions(jenaTDB2Repository, deviationChecker, nkdDetailService, nkdSnapshotService, outboxWriter);
    }

    @Test
    void checkIri_Occupied_DoesNotReturnOrModifyExistingOntology() {
        var model = createValidOntologyCreateModel();
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));

        var result = ontologyService.checkIri(new OntologyIriCheckRequestDto(model.getNamespace(), model.getNameModel()));

        assertEquals(TEST_GRAPH_NAME, result.iri());
        assertTrue(result.valid());
        assertFalse(result.available());
        verify(ontologyMetadataRepository).findByGraphName(TEST_GRAPH_NAME);
        verifyNoMoreInteractions(ontologyMetadataRepository);
        verifyNoInteractions(jenaTDB2Repository, deviationChecker, nkdDetailService);
    }

    @Test
    void checkIri_InvalidNamespace_DoesNotQueryDatabase() {
        var result = ontologyService.checkIri(new OntologyIriCheckRequestDto(
                "invalid iri with spaces", createValidOntologyCreateModel().getNameModel()));

        assertFalse(result.valid());
        assertFalse(result.available());
        verifyNoInteractions(ontologyMetadataRepository, jenaTDB2Repository, deviationChecker);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://example.org/", "https://slovník.gov.cz/datový/"})
    void checkIri_UsesSameIriAsCreate(String namespace) {
        var model = createValidOntologyCreateModel();
        model.setNamespace(namespace);
        model.getNameModel().setName(Map.of("cs", "Silniční provoz", "en", "Road traffic"));
        var result = ontologyService.checkIri(new OntologyIriCheckRequestDto(namespace, model.getNameModel()));
        assertTrue(result.valid());
        clearInvocations(ontologyMetadataRepository);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);

        ontologyService.createOntology(model, TEST_USER_ID);

        verify(ontologyMetadataRepository).findByGraphName(result.iri());
        verify(outboxWriter).enqueueCreateGraph(eq(result.iri()), any(Model.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   "})
    void checkIri_RequiresCzechName(String czechName) {
        NameModel name = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("en", "Road traffic");
        if (czechName != null) names.put("cs", czechName);
        name.setName(names);

        assertThrows(OntologyValidationException.class, () -> ontologyService.checkIri(
                new OntologyIriCheckRequestDto("http://example.org/", name)));
        verifyNoInteractions(ontologyMetadataRepository, jenaTDB2Repository, deviationChecker);
    }

    // ========== createOntology Tests ==========

    @Test
    void createOntology_Success() throws OntologyException {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);

        OntologyMetadataModel result = ontologyService.createOntology(createModel, TEST_USER_ID);

        assertEquals(TEST_ONTOLOGY_ID, result.getId());
        assertEquals(TEST_GRAPH_NAME, result.getGraphName());
        assertEquals(TEST_USER_ID, result.getUser().getUserId());
        verify(outboxWriter).enqueueCreateGraph(anyString(), any(Model.class));
        verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
    }

    @Test
    void createOntology_AlreadyExists() throws OntologyException {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.of(testOntologyEntity));

        OntologyMetadataModel result = ontologyService.createOntology(createModel, TEST_USER_ID);

        // The existing vocabulary is returned unchanged.
        assertEquals(TEST_ONTOLOGY_ID, result.getId());
        assertEquals(TEST_ONTOLOGY_SLUG, result.getSlug());
        verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
        verify(ontologyMetadataRepository, never()).save(any(OntologyMetadataEntity.class));
    }

    @Test
    void createOntology_NullModel() {
        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.createOntology(null, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("prázdná"));
    }

    @Test
    void createOntology_InvalidIRI() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        createModel.setNamespace("invalid iri with spaces");

        assertThrows(OntologyException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
    }

    @Test
    void checkIri_SameSlugInDifferentNamespaceAlsoConflictsOnCreate() {
        var model = createValidOntologyCreateModel();
        var existing = new OntologyMetadataEntity();
        existing.setGraphName("https://other.example/" + TEST_ONTOLOGY_SLUG);
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(existing));
        assertFalse(ontologyService.checkIri(new OntologyIriCheckRequestDto(model.getNamespace(), model.getNameModel())).available());
        assertThrows(com.dia.ismdtoolbackend.exception.OntologyCreationConflictException.class,
                () -> ontologyService.createOntology(model, TEST_USER_ID));
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void createOntology_MetadataSaveFails_DoesNotTouchTDB2() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenThrow(new RuntimeException("DB error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit metadata"));
        verifyNoInteractions(jenaTDB2Repository);
    }

    // ========== createOntology required-field validation ==========

    @Test
    void createOntology_NameMissing_Throws() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        createModel.setNameModel(new NameModel());

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("Název slovníku je povinný"));
        verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
    }

    @Test
    void createOntology_NameMissingCsVariant_Throws() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("en", "test-ontology");
        createModel.getNameModel().setName(nameMap);

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("českou variantu"));
        verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
    }

    @Test
    void createOntology_NameCsBlank_Throws() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", "   ");
        createModel.getNameModel().setName(nameMap);

        assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
    }

    @Test
    void createOntology_DescriptionPresentWithoutCs_Throws() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        Map<String, String> descMap = new HashMap<>();
        descMap.put("en", "English only description");
        createModel.getDescriptionModel().setDescription(descMap);

        OntologyValidationException ex = assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("Popis slovníku"));
    }

    @Test
    void createOntology_DescriptionEmpty_Succeeds() throws OntologyException {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        createModel.setDescriptionModel(new DescriptionModel());

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);

        assertNotNull(ontologyService.createOntology(createModel, TEST_USER_ID));
    }

    // ========== getOntologyDetailModel Tests ==========

    @Test
    void getOntologyDetail_ReturnsCoreDetailWithoutBuildingFullApiResponse() {
        Model modelWithData = createModelWithOntologyData();
        OntologyDetailModel detailModel = OntologyDetailModel.builder()
                .iri(TEST_GRAPH_NAME)
                .concepts(List.of())
                .build();

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailModel);

        OntologyDetailModel result = ontologyService.getOntologyDetail(TEST_ONTOLOGY_SLUG);

        assertSame(detailModel, result);
        verify(jenaTDB2Repository).fetchGraph(TEST_GRAPH_NAME);
        verify(detailExtractor).applyOFNTransformations(modelWithData);
        verify(detailExtractor).extractOntologyDetail(modelWithData);
        // No metadata DTO is built: nothing reads the comments or concept rows it would map.
        verifyNoInteractions(
                commentRepository,
                conceptMetadataRepository,
                deviationChecker
        );
    }

    @Test
    void getOntologyDetailModel_Success() throws OntologyException {
        Model modelWithData = createModelWithOntologyData();
        OntologyDetailModel detailModel = OntologyDetailModel.builder()
                .context("http://example.org/context")
                .iri(TEST_GRAPH_NAME)
                .types(List.of())
                .name(java.util.Map.of())
                .description(java.util.Map.of())
                .creationDate("")
                .modificationDate("")
                .concepts(List.of())
                .build();

        ConceptMetadataEntity conceptEntity = new ConceptMetadataEntity();
        conceptEntity.setConceptIri(TEST_GRAPH_NAME + "/pojem/test-concept");
        conceptEntity.setConceptName("test-concept");
        conceptEntity.setGraphName(TEST_GRAPH_NAME);

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailModel);
        when(commentRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(new ArrayList<>());
        when(conceptMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(List.of(conceptEntity));
        PublishedOntologyDeviationModel ontologyDeviation = PublishedOntologyDeviationModel.builder()
                .status(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS)
                .build();
        PublishedConceptDeviationModel conceptDeviation = PublishedConceptDeviationModel.builder()
                .status(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS)
                .build();
        Map<String, PublishedConceptDeviationModel> conceptDeviations =
                Map.of(conceptEntity.getConceptIri(), conceptDeviation);
        when(deviationChecker.checkOntologyDeviation(same(detailModel),
                argThat(m -> m != null && TEST_ONTOLOGY_SLUG.equals(m.getSlug()))))
                .thenReturn(ontologyDeviation);
        when(deviationChecker.checkConceptsDeviation(same(modelWithData), eq(List.of(conceptEntity))))
                .thenReturn(conceptDeviations);

        GetOntologyDto result = ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_SLUG);

        assertThat(result.getPublishedOntologyDeviationModel()).isSameAs(ontologyDeviation);
        assertThat(result.getPublishedConceptDeviations())
                .containsExactly(entry(conceptEntity.getConceptIri(), conceptDeviation));
        assertNotNull(result);
        assertNotNull(result.getOntologyMetadata());
        assertNotNull(result.getOntologyDetail());
        assertEquals(1, result.getOntologyMetadata().getConcepts().size());
        ConceptMetadataModel concept = result.getOntologyMetadata().getConcepts().get(0);
        assertEquals(TEST_GRAPH_NAME + "/pojem/test-concept", concept.getConceptIri());
        assertEquals("test-concept", concept.getConceptName());
        assertEquals(TEST_ONTOLOGY_SLUG, result.getOntologyMetadata().getSlug());
        assertEquals(TEST_GRAPH_NAME, result.getOntologyDetail().getIri());
        // fetchGraph is called once: enrichMetadataFromModel reuses the already-fetched rawModel
        verify(jenaTDB2Repository, times(1)).fetchGraph(TEST_GRAPH_NAME);
        verify(detailExtractor).applyOFNTransformations(modelWithData);
        // extractOntologyDetail is called once in main flow (not in checkPublishedOntology since ontology is not published)
        verify(detailExtractor).extractOntologyDetail(modelWithData);
    }

    // ========== foreign member count Tests ==========

    /**
     * Ontology detail scopes members to its own graph while concept detail merges cross-graph
     * ones in; the counts are what the two surfaces differ by, so they must reach the DTO.
     */
    @Test
    void getOntologyDetail_annotatesConceptsWithForeignMemberCounts() {
        String classIri = TEST_GRAPH_NAME + "/pojem/osoba";
        String bareIri = TEST_GRAPH_NAME + "/pojem/bez-cizích";

        OntologyDetailModel detailModel = detailWithConcepts(classIri, bareIri);

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        Model modelWithData = createModelWithOntologyData();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailModel);
        when(jenaTDB2Repository.countExternalDomainMembers(eq(TEST_GRAPH_NAME), anyList()))
                .thenReturn(java.util.Map.of(classIri, new ForeignMemberCount(3, 1)));

        OntologyDetailModel result = ontologyService.getOntologyDetail(TEST_ONTOLOGY_SLUG);

        OntologyDetailModel.ConceptDetailModel annotated = conceptByIri(result, classIri);
        assertEquals(3, annotated.getForeignPropertyCount());
        assertEquals(1, annotated.getForeignRelationshipCount());

        // Absent from the count map => null, not 0, so the keys stay out of the JSON entirely.
        OntologyDetailModel.ConceptDetailModel untouched = conceptByIri(result, bareIri);
        assertNull(untouched.getForeignPropertyCount());
        assertNull(untouched.getForeignRelationshipCount());
    }

    /** One batched call for the whole ontology — a per-concept call would be an N+1 fan-out. */
    @Test
    void getOntologyDetail_countsForeignMembersInASingleBatchedCall() {
        String a = TEST_GRAPH_NAME + "/pojem/a";
        String b = TEST_GRAPH_NAME + "/pojem/b";
        String c = TEST_GRAPH_NAME + "/pojem/c";

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        Model modelWithData = createModelWithOntologyData();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailWithConcepts(a, b, c));
        when(jenaTDB2Repository.countExternalDomainMembers(anyString(), anyList())).thenReturn(java.util.Map.of());

        ontologyService.getOntologyDetail(TEST_ONTOLOGY_SLUG);

        verify(jenaTDB2Repository, times(1))
                .countExternalDomainMembers(eq(TEST_GRAPH_NAME), irisCaptor.capture());
        assertEquals(List.of(a, b, c), irisCaptor.getValue());
    }

    /** Counting is supplementary: a Fuseki failure must not take ontology detail down with it. */
    @Test
    void getOntologyDetail_survivesForeignMemberCountFailure() {
        String classIri = TEST_GRAPH_NAME + "/pojem/osoba";

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        Model modelWithData = createModelWithOntologyData();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailWithConcepts(classIri));
        when(jenaTDB2Repository.countExternalDomainMembers(anyString(), anyList()))
                .thenThrow(new RuntimeException("Fuseki down"));

        OntologyDetailModel result = ontologyService.getOntologyDetail(TEST_ONTOLOGY_SLUG);

        assertNotNull(result);
        assertNull(conceptByIri(result, classIri).getForeignPropertyCount());
    }

    /** No concepts => no query at all. */
    @Test
    void getOntologyDetail_skipsForeignMemberCountWhenNoConcepts() {
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        Model modelWithData = createModelWithOntologyData();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData))
                .thenReturn(OntologyDetailModel.builder().iri(TEST_GRAPH_NAME).concepts(List.of()).build());

        ontologyService.getOntologyDetail(TEST_ONTOLOGY_SLUG);

        verify(jenaTDB2Repository, never()).countExternalDomainMembers(anyString(), anyList());
    }

    private static OntologyDetailModel detailWithConcepts(String... conceptIris) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = new ArrayList<>();
        for (String iri : conceptIris) {
            concepts.add(OntologyDetailModel.ConceptDetailModel.builder().iri(iri).build());
        }
        return OntologyDetailModel.builder().iri(TEST_GRAPH_NAME).concepts(concepts).build();
    }

    private static OntologyDetailModel.ConceptDetailModel conceptByIri(OntologyDetailModel detail, String iri) {
        return detail.getConcepts().stream()
                .filter(c -> iri.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("concept not in detail: " + iri));
    }

    @Test
    void getOntologyDetailModel_OntologyNotFound() {
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.empty());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_SLUG));

        assertTrue(exception.getMessage().contains("nebyla nalezena"));
    }

    @Test
    void getOntologyDetailModel_EmptyModel() {
        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_SLUG));

        assertTrue(exception.getMessage().contains("prázdný"));
    }

    // ========== editOntology Tests ==========

    @Test
    void editOntology_WithoutIRIChange() throws OntologyException {
        OntologyEditModel editModel = createValidOntologyEditModel();
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(TEST_GRAPH_NAME, false);

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertEquals(TEST_GRAPH_NAME, result.getGraphName());
        verify(jenaTDB2Repository).saveOntologyModel(TEST_GRAPH_NAME, testModel);
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void editOntology_WithIRIChange() throws OntologyException {
        OntologyEditModel editModel = createValidOntologyEditModel();
        String newIRI = "http://example.org/new-ontology";
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(newIRI, true);

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(new ArrayList<>());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertEquals(newIRI, result.getGraphName());
        verify(jenaTDB2Repository).saveOntologyModel(newIRI, testModel);
        verify(jenaTDB2Repository).deleteGraph(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));

        ArgumentCaptor<OntologyMetadataEntity> captor = ArgumentCaptor.forClass(OntologyMetadataEntity.class);
        verify(ontologyMetadataRepository).save(captor.capture());
        assertEquals(newIRI, captor.getValue().getGraphName());
    }

    @Test
    void editOntology_WithIRIChange_UpdatesConceptMetadata() throws OntologyException {
        OntologyEditModel editModel = createValidOntologyEditModel();
        String newIRI = "http://example.org/new-ontology";
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(newIRI, true);

        List<ConceptMetadataEntity> concepts = getConceptMetadataEntities();

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(concepts);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertEquals(newIRI, result.getGraphName());
        verify(conceptMetadataRepository).saveAll(argThat(savedConcepts -> {
            List<ConceptMetadataEntity> conceptList = (List<ConceptMetadataEntity>) savedConcepts;
            return conceptList.size() == 2 &&
                    conceptList.stream().allMatch(c -> c.getGraphName().equals(newIRI)) &&
                    conceptList.stream().allMatch(c -> c.getConceptIri().startsWith(newIRI));
        }));
    }

    private static @NotNull List<ConceptMetadataEntity> getConceptMetadataEntities() {
        ConceptMetadataEntity concept1 = new ConceptMetadataEntity();
        concept1.setConceptIri(TEST_GRAPH_NAME + "/pojem/concept1");
        concept1.setConceptName("concept1");
        concept1.setGraphName(TEST_GRAPH_NAME);

        ConceptMetadataEntity concept2 = new ConceptMetadataEntity();
        concept2.setConceptIri(TEST_GRAPH_NAME + "/pojem/concept2");
        concept2.setConceptName("concept2");
        concept2.setGraphName(TEST_GRAPH_NAME);

        return List.of(concept1, concept2);
    }

    @Test
    void editOntology_NullModel() {
        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.editOntology(TEST_ONTOLOGY_ID, null));

        assertTrue(exception.getMessage().contains("prázdná"));
    }

    @Test
    void editOntology_OntologyNotFound() {
        OntologyEditModel editModel = createValidOntologyEditModel();

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
    }

    @Test
    void editOntology_EmptyModel() {
        OntologyEditModel editModel = createValidOntologyEditModel();

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        assertThrows(OntologyException.class,
                () -> ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel));
    }

    @Test
    void editOntology_IRIChangeFails() {
        OntologyEditModel editModel = createValidOntologyEditModel();
        String newIRI = "http://example.org/new-ontology";
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(newIRI, true);

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        doThrow(new RuntimeException("Save error")).when(jenaTDB2Repository).saveOntologyModel(eq(newIRI), any(Model.class));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit změny"));
    }

    // ========== Helper Methods ==========

    private OntologyCreateModel createValidOntologyCreateModel() {
        OntologyCreateModel model = new OntologyCreateModel();
        NameModel nameModel = new NameModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", "test-ontology");
        nameModel.setName(nameMap);
        model.setNameModel(nameModel);
        model.setNamespace("http://example.org/");

        DescriptionModel descModel = new DescriptionModel();
        Map<String, String> descMap = new HashMap<>();
        descMap.put("cs", "Test description");
        descModel.setDescription(descMap);
        model.setDescriptionModel(descModel);

        return model;
    }

    private OntologyEditModel createValidOntologyEditModel() {
        return new OntologyEditModel();
    }

    private Model createModelWithOntologyData() {
        Model model = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        model.createResource(ns + "TestOntology")
                .addProperty(model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        model.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        return model;
    }

    // ========== getConceptsByIri Tests (B1) ==========
    //
    // Covers /api/ontology/concepts?iri=...&source=... — the slim concept-listing
    // endpoint used by the FE for navigation. Branches: input validation,
    // source dispatch, ontology-not-found, empty-graph, empty-concepts-list
    // short-circuit, the slug-join from PG metadata, conceptType resolution,
    // and the NKD source path.

    @Test
    void getConceptsByIri_nullIri_throws() {
        OntologyException ex = assertThrows(OntologyException.class,
                () -> ontologyService.getConceptsByIri(null, SearchSource.ISMD));
        assertTrue(ex.getMessage().toLowerCase().contains("iri"));
    }

    @Test
    void getConceptsByIri_blankIri_throws() {
        OntologyException ex = assertThrows(OntologyException.class,
                () -> ontologyService.getConceptsByIri("   ", SearchSource.ISMD));
        assertTrue(ex.getMessage().toLowerCase().contains("iri"));
    }

    @Test
    void getConceptsByIri_nullSource_throws() {
        OntologyException ex = assertThrows(OntologyException.class,
                () -> ontologyService.getConceptsByIri(TEST_GRAPH_NAME, null));
        assertTrue(ex.getMessage().toLowerCase().contains("zdroj")
                || ex.getMessage().toLowerCase().contains("source"));
    }

    @Test
    void getConceptsByIri_unsupportedSource_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.UNPUBLISHED));
    }

    @Test
    void getConceptsByIri_ontologyNotFound_throws() {
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.empty());

        OntologyNotFoundException ex = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.ISMD));
        assertTrue(ex.getMessage().contains(TEST_GRAPH_NAME));
    }

    @Test
    void getConceptsByIri_emptyGraph_throws() {
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME))
                .thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyNotFoundException ex = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.ISMD));
        assertTrue(ex.getMessage().toLowerCase().contains("prázdn")
                || ex.getMessage().toLowerCase().contains("empty"));
    }

    @Test
    void getConceptsByIri_noConceptsInDetail_returnsEmptyList() {
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME))
                .thenReturn(Optional.of(testOntologyEntity));
        Model rawModel = nonEmptyOntologyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(rawModel);
        when(detailExtractor.extractOntologyDetail(rawModel))
                .thenReturn(OntologyDetailModel.builder().concepts(null).build());

        List<MinimalConceptDto> out = ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.ISMD);

        assertTrue(out.isEmpty());
        // PG lookup is never executed when the RDF detail has no concepts.
        verify(conceptMetadataRepository, never()).findByGraphName(anyString());
    }

    @Test
    void getConceptsByIri_emptyConceptsList_returnsEmptyList() {
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME))
                .thenReturn(Optional.of(testOntologyEntity));
        Model rawModel = nonEmptyOntologyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(rawModel);
        when(detailExtractor.extractOntologyDetail(rawModel))
                .thenReturn(OntologyDetailModel.builder().concepts(List.of()).build());

        List<MinimalConceptDto> out = ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.ISMD);

        assertTrue(out.isEmpty());
    }

    @Test
    void getConceptsByIri_joinsConceptSlugsFromPg_andResolvesConceptType() {
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME))
                .thenReturn(Optional.of(testOntologyEntity));
        Model rawModel = nonEmptyOntologyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(rawModel);

        // Types carry the short OFN JSON-LD labels the detail extractor actually
        // emits (ConceptDetailModel.types), not raw rdf:type IRIs.
        OntologyDetailModel.ConceptDetailModel c1 = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("http://example.org/c/1")
                .name(Map.of("cs", "Pojem 1"))
                .types(List.of(POJEM_JSON_LD, "Koncept", TRIDA_JSON_LD))
                .build();
        OntologyDetailModel.ConceptDetailModel c2 = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("http://example.org/c/2")
                .name(Map.of("cs", "Pojem 2"))
                .types(List.of(POJEM_JSON_LD, "Koncept", VZTAH_JSON_LD))
                .build();
        OntologyDetailModel.ConceptDetailModel c3UnmappedSlug = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("http://example.org/c/3")
                .name(Map.of("cs", "Pojem 3"))
                .build();
        when(detailExtractor.extractOntologyDetail(rawModel))
                .thenReturn(OntologyDetailModel.builder().concepts(List.of(c1, c2, c3UnmappedSlug)).build());

        // PG returns slugs for c1 and c2 only; c3 has no metadata row.
        // Also include a noise entity with null conceptIri/slug to exercise the filter.
        ConceptMetadataEntity e1 = new ConceptMetadataEntity();
        e1.setConceptIri("http://example.org/c/1");
        e1.setSlug("pojem-1");
        ConceptMetadataEntity e2 = new ConceptMetadataEntity();
        e2.setConceptIri("http://example.org/c/2");
        e2.setSlug("pojem-2");
        ConceptMetadataEntity eNoise = new ConceptMetadataEntity();
        eNoise.setConceptIri(null);
        eNoise.setSlug(null);
        when(conceptMetadataRepository.findByGraphName(TEST_GRAPH_NAME))
                .thenReturn(List.of(e1, e2, eNoise));

        List<MinimalConceptDto> out = ontologyService.getConceptsByIri(TEST_GRAPH_NAME, SearchSource.ISMD);

        assertEquals(3, out.size());
        assertEquals("http://example.org/c/1", out.get(0).getIri());
        assertEquals("pojem-1", out.get(0).getSlug());
        assertEquals(ConceptType.TRIDA, out.get(0).getConceptType());
        assertEquals("http://example.org/c/2", out.get(1).getIri());
        assertEquals("pojem-2", out.get(1).getSlug());
        assertEquals(ConceptType.VZTAH, out.get(1).getConceptType());
        assertEquals("http://example.org/c/3", out.get(2).getIri());
        assertNull(out.get(2).getSlug(), "Concepts without a PG metadata row should have null slug");
        assertEquals(ConceptType.KONCEPT, out.get(2).getConceptType(),
                "Concepts with no rdf:type role should fall back to KONCEPT");
    }

    @Test
    void getConceptsByIri_nkdSource_omitsSlug_andResolvesConceptType() {
        String iri = "https://data.gov.cz/zdroj/slovnik/test";

        // The NKD projection comes from the slim concept listing, not the full ontology detail —
        // the heavy CONSTRUCT would fetch every concept's whole graph to keep three fields.
        when(nkdDetailService.listOntologyConcepts(iri)).thenReturn(List.of(
                MinimalConceptDto.builder()
                        .iri(iri + "/pojem/bar")
                        .name(Map.of("cs", "Bar"))
                        .conceptType(ConceptType.TRIDA)
                        .build()));

        List<MinimalConceptDto> out = ontologyService.getConceptsByIri(iri, SearchSource.NKD);

        assertEquals(1, out.size());
        assertEquals(iri + "/pojem/bar", out.get(0).getIri());
        assertNull(out.get(0).getSlug(), "NKD concepts have no local slug");
        assertEquals(ConceptType.TRIDA, out.get(0).getConceptType());
        // ISMD path must not be touched for an NKD request.
        verify(ontologyMetadataRepository, never()).findByGraphName(anyString());
        // The full-ontology detail path must not be used for this projection.
        verify(nkdDetailService, never()).getOntologyDetail(anyString());
    }

    @Test
    void getConceptsByIri_nkdSource_noConcepts_returnsEmptyList() {
        String iri = "https://data.gov.cz/zdroj/slovnik/test";

        when(nkdDetailService.listOntologyConcepts(iri)).thenReturn(List.of());

        List<MinimalConceptDto> out = ontologyService.getConceptsByIri(iri, SearchSource.NKD);

        assertTrue(out.isEmpty());
    }

    // ========== getAll / getBySlugs Tests (B2 — enrichEntitiesWithBatchMetadata) ==========
    //
    // Covers /api/ontology/list — both `userId`/`isPublished` repo selectors AND
    // the batch-metadata join through enrichEntitiesWithBatchMetadata +
    // partitionModelBySubject + enrichMetadataFromModel.

    @Test
    void getAll_noFilters_callsFindAll() {
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of());

        List<OntologyMetadataModel> out = ontologyService.getAll(null, null);

        assertTrue(out.isEmpty());
        verify(ontologyMetadataRepository).findAll();
        verify(ontologyMetadataRepository, never()).findAllByUserId(anyString());
        verify(ontologyMetadataRepository, never()).findAllByIsPublished(anyBoolean());
    }

    @Test
    void getAll_userIdOnly_callsFindAllByUserId() {
        when(ontologyMetadataRepository.findAllByUserId(TEST_USER_ID)).thenReturn(List.of());

        ontologyService.getAll(TEST_USER_ID, null);

        verify(ontologyMetadataRepository).findAllByUserId(TEST_USER_ID);
    }

    @Test
    void getAll_isPublishedOnly_callsFindAllByIsPublished() {
        when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of());

        ontologyService.getAll(null, true);

        verify(ontologyMetadataRepository).findAllByIsPublished(true);
    }

    @Test
    void getAll_userIdAndIsPublished_callsCombinedFinder() {
        when(ontologyMetadataRepository.findAllByUserIdAndIsPublished(TEST_USER_ID, false))
                .thenReturn(List.of());

        ontologyService.getAll(TEST_USER_ID, false);

        verify(ontologyMetadataRepository).findAllByUserIdAndIsPublished(TEST_USER_ID, false);
    }

    @Test
    void getAll_enrichesEachEntityWithBatchMetadata() {
        OntologyMetadataEntity e1 = new OntologyMetadataEntity();
        e1.setId(1L);
        e1.setGraphName("http://example.org/o/1");
        OntologyMetadataEntity e2 = new OntologyMetadataEntity();
        e2.setId(2L);
        e2.setGraphName("http://example.org/o/2");
        // Entity with null graphName must be filtered out of the batch fetch but
        // still mapped via the entity stream — its enrichMetadataFromModel call
        // receives a null model.
        OntologyMetadataEntity eNoGraph = new OntologyMetadataEntity();
        eNoGraph.setId(3L);
        eNoGraph.setGraphName(null);
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(e1, e2, eNoGraph));

        Model batchModel = buildBatchMetadataModel();
        when(jenaTDB2Repository.fetchMetadataProperties(List.of("http://example.org/o/1", "http://example.org/o/2")))
                .thenReturn(batchModel);

        // Comments come back in ONE batched query and are grouped by owning ontology in memory.
        CommentEntity comment = new CommentEntity();
        comment.setComment("K o/1");
        comment.setOntologyMetadata(e1);
        when(commentRepository.findByOntologyMetadataIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(comment));

        List<OntologyMetadataModel> out = ontologyService.getAll(null, null);

        assertEquals(3, out.size());
        OntologyMetadataModel m1 = out.get(0);
        OntologyMetadataModel m2 = out.get(1);
        OntologyMetadataModel mNoGraph = out.get(2);
        assertEquals("K o/1", m1.getComments().get(0).getComment());
        assertEquals("http://example.org/o/1", m1.getComments().get(0).getOntologyIRI());
        assertTrue(m2.getComments().isEmpty());
        // o/1 has one untagged skos:prefLabel — keys under DEFAULT_LANG.
        assertEquals(Map.of("cs", "Slovník 1"), m1.getName());
        // o/2 has cs+en variants of both prefLabel and description — every one is returned.
        assertEquals(Map.of("cs", "Slovník 2", "en", "Vocabulary 2"), m2.getName());
        assertEquals(Map.of("cs", "Popis 2", "en", "Description 2"), m2.getPopis());
        // Null-graph entity has no IRI to derive a fallback name from, so the map stays empty
        // rather than carrying a null-valued DEFAULT_LANG entry.
        assertNotNull(mNoGraph);
        assertTrue(mNoGraph.getName().isEmpty());
        // One query for the whole page, and never the per-row variant (guards the N+1 regression).
        verify(commentRepository).findByOntologyMetadataIdIn(List.of(1L, 2L, 3L));
        verify(commentRepository, never()).findByOntologyMetadataId(anyLong());
    }

    @Test
    void getAll_multipleLabelsInSameLanguage_keepsFirstAndDoesNotCollapseOthers() {
        OntologyMetadataEntity e1 = new OntologyMetadataEntity();
        e1.setId(1L);
        e1.setGraphName("http://example.org/o/1");
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(e1));

        // Two cs labels plus an en label: the old single-statement read returned whichever
        // prefLabel Jena handed back first and dropped every other variant.
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource("http://example.org/o/1"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Slovník", "cs"));
        m.add(m.createResource("http://example.org/o/1"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Slovník duplicitní", "cs"));
        m.add(m.createResource("http://example.org/o/1"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Vocabulary", "en"));
        when(jenaTDB2Repository.fetchMetadataProperties(List.of("http://example.org/o/1"))).thenReturn(m);

        OntologyMetadataModel m1 = ontologyService.getAll(null, null).get(0);

        assertEquals(2, m1.getName().size());
        assertEquals("Vocabulary", m1.getName().get("en"));
        assertTrue(m1.getName().get("cs").startsWith("Slovník"));
    }

    @Test
    void getAll_noPrefLabel_fallsBackToGraphDerivedNameUnderDefaultLang() {
        OntologyMetadataEntity e1 = new OntologyMetadataEntity();
        e1.setId(1L);
        e1.setGraphName("http://example.org/muj-slovnik");
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(e1));

        // Non-empty model that carries no label for this ontology at all.
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource("http://example.org/other"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Jiný"));
        when(jenaTDB2Repository.fetchMetadataProperties(List.of("http://example.org/muj-slovnik"))).thenReturn(m);

        OntologyMetadataModel m1 = ontologyService.getAll(null, null).get(0);

        assertEquals(Map.of("cs", "Muj slovnik"), m1.getName());
        assertNull(m1.getPopis());
    }

    @Test
    void getAll_emptyResult_returnsEmptyListAndSkipsBatchFetch() {
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of());

        List<OntologyMetadataModel> out = ontologyService.getAll(null, null);

        assertTrue(out.isEmpty());
        verify(jenaTDB2Repository, never()).fetchMetadataProperties(anyList());
    }

    @Test
    void getBySlugs_nullList_throws() {
        OntologyException ex = assertThrows(OntologyException.class,
                () -> ontologyService.getBySlugs(null));
        assertTrue(ex.getMessage().toLowerCase().contains("slugů")
                || ex.getMessage().toLowerCase().contains("prázdn"));
    }

    @Test
    void getBySlugs_emptyList_throws() {
        assertThrows(OntologyException.class, () -> ontologyService.getBySlugs(List.of()));
    }

    @Test
    void getBySlugs_overLimit_throws() {
        List<String> sevenSlugs = List.of("a", "b", "c", "d", "e", "f", "g");
        OntologyException ex = assertThrows(OntologyException.class,
                () -> ontologyService.getBySlugs(sevenSlugs));
        assertTrue(ex.getMessage().contains("6"));
    }

    @Test
    void getBySlugs_happyPath_enrichesViaBatchMetadata() {
        OntologyMetadataEntity e1 = new OntologyMetadataEntity();
        e1.setId(1L);
        e1.setGraphName("http://example.org/o/1");
        when(ontologyMetadataRepository.findBySlugIn(List.of("slug-1"))).thenReturn(List.of(e1));

        Model batchModel = buildBatchMetadataModel();
        when(jenaTDB2Repository.fetchMetadataProperties(List.of("http://example.org/o/1")))
                .thenReturn(batchModel);

        List<OntologyMetadataModel> out = ontologyService.getBySlugs(List.of("slug-1"));

        assertEquals(1, out.size());
        assertEquals("http://example.org/o/1", out.get(0).getGraphName());
        assertEquals(Map.of("cs", "Slovník 1"), out.get(0).getName());
    }

    @Test
    void getBySlugs_emptyRepoResult_returnsEmptyList() {
        when(ontologyMetadataRepository.findBySlugIn(List.of("missing"))).thenReturn(List.of());

        List<OntologyMetadataModel> out = ontologyService.getBySlugs(List.of("missing"));

        assertTrue(out.isEmpty());
        verify(jenaTDB2Repository, never()).fetchMetadataProperties(anyList());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Model nonEmptyOntologyModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(TEST_GRAPH_NAME),
                m.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
                "x");
        return m;
    }

    /**
     * Build a batch model that {@code partitionModelBySubject} will split into per-graph
     * submodels. Triples for o/1: only skos:prefLabel. For o/2: skos:prefLabel + dcterms:description.
     * Lets enrichMetadataFromModel exercise both the prefLabel branch and the description branch.
     * <p>
     * o/1 carries an untagged prefLabel (keys under DEFAULT_LANG); o/2 carries cs+en variants of
     * both prefLabel and description, so the list read is asserted to keep every language.
     */
    private Model buildBatchMetadataModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource("http://example.org/o/1"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Slovník 1"));
        m.add(m.createResource("http://example.org/o/2"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Slovník 2", "cs"));
        m.add(m.createResource("http://example.org/o/2"),
                m.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                m.createLiteral("Vocabulary 2", "en"));
        m.add(m.createResource("http://example.org/o/2"),
                m.createProperty("http://purl.org/dc/terms/description"),
                m.createLiteral("Popis 2", "cs"));
        m.add(m.createResource("http://example.org/o/2"),
                m.createProperty("http://purl.org/dc/terms/description"),
                m.createLiteral("Description 2", "en"));
        return m;
    }
}
