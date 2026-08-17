package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.POJEM_JSON_LD;
import static com.dia.constants.VocabularyConstants.TRIDA_JSON_LD;
import static com.dia.constants.VocabularyConstants.VZTAH_JSON_LD;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyServiceImplTest {

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Mock
    private ValidationReportRepository validationReportRepository;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private OntologyMetadataMapper ontologyMetadataMapper;

    @Mock
    private ConceptMetadataMapper conceptMetadataMapper;

    @Mock
    private OntologyEditor ontologyEditor;

    @Mock
    private OntologyDetailExtractor detailExtractor;

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @Mock
    private PublishedResourceUtil deviationChecker;

    // OutboxConfig mock isEnabled() defaults to false → existing tests exercise the direct path.
    @Mock
    private OutboxConfig outboxConfig;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private OutboxRelayTrigger outboxRelayTrigger;

    @Mock
    private NkdSnapshotService nkdSnapshotService;

    @Mock
    private NkdDetailService nkdDetailService;

    @InjectMocks
    private OntologyServiceImpl ontologyService;

    private OntologyMetadataEntity testOntologyEntity;
    private Model testModel;
    private static final Long TEST_ONTOLOGY_ID = 1L;
    private static final String TEST_ONTOLOGY_SLUG = "test-ontology";
    private static final String TEST_GRAPH_NAME = "http://example.org/test-ontology";
    private static final String TEST_USER_ID = "user123";

    @BeforeEach
    void setUp() {
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
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(true);

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(validationReportRepository).delete(validationReport);
        verify(jenaTDB2Repository).deleteGraph(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
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
    void deleteOntology_EmptyModel() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(false);

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("prázdný"));
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    }

    @Test
    void deleteOntology_NoValidationReport() throws OntologyException {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(validationReportRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(true);

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(validationReportRepository, never()).delete(any());
        verify(jenaTDB2Repository).deleteGraph(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
    }

    // ========== createOntology Tests ==========

    @Test
    void createOntology_Success() throws OntologyException {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.createOntology(createModel, TEST_USER_ID);

        assertNotNull(result);
        verify(jenaTDB2Repository).saveOntologyModel(anyString(), any(Model.class));
        verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
    }

    @Test
    void createOntology_AlreadyExists() throws OntologyException {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.of(testOntologyEntity));
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.createOntology(createModel, TEST_USER_ID);

        assertNotNull(result);
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
    void createOntology_TDB2SaveFails() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("TDB2 error")).when(jenaTDB2Repository).saveOntologyModel(anyString(), any(Model.class));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit RDF model"));
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void createOntology_MetadataSaveFails_CleanupTDB2() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenThrow(new RuntimeException("DB error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit metadata"));
        verify(jenaTDB2Repository).deleteGraph(anyString());
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
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

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
        verifyNoInteractions(
                ontologyMetadataMapper,
                conceptMetadataMapper,
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

        OntologyMetadataModel metadataModel = new OntologyMetadataModel();
        metadataModel.setSlug(TEST_ONTOLOGY_SLUG);
        metadataModel.setGraphName(TEST_GRAPH_NAME);

        ConceptMetadataEntity conceptEntity = new ConceptMetadataEntity();
        conceptEntity.setConceptIri(TEST_GRAPH_NAME + "/pojem/test-concept");
        conceptEntity.setConceptName("test-concept");
        conceptEntity.setGraphName(TEST_GRAPH_NAME);

        ConceptMetadataModel conceptModel = new ConceptMetadataModel();
        conceptModel.setConceptIri(TEST_GRAPH_NAME + "/pojem/test-concept");
        conceptModel.setConceptName("test-concept");
        conceptModel.setGraphName(TEST_GRAPH_NAME);

        when(ontologyMetadataRepository.findBySlug(TEST_ONTOLOGY_SLUG)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);
        when(detailExtractor.applyOFNTransformations(modelWithData)).thenReturn(modelWithData);
        when(detailExtractor.extractOntologyDetail(modelWithData)).thenReturn(detailModel);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(metadataModel);
        when(commentRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(new ArrayList<>());
        when(ontologyMetadataMapper.commentEntitiesToModels(anyList())).thenReturn(new ArrayList<>());
        when(conceptMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(List.of(conceptEntity));
        when(conceptMetadataMapper.toDto(conceptEntity)).thenReturn(conceptModel);

        GetOntologyDto result = ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_SLUG);

        assertNotNull(result);
        assertNotNull(result.getOntologyMetadata());
        assertNotNull(result.getOntologyDetail());
        assertNotNull(result.getOntologyMetadata().getConcepts());
        assertEquals(1, result.getOntologyMetadata().getConcepts().size());
        assertEquals(TEST_ONTOLOGY_SLUG, result.getOntologyMetadata().getSlug());
        assertEquals(TEST_GRAPH_NAME, result.getOntologyDetail().getIri());
        // fetchGraph is called once: enrichMetadataFromModel reuses the already-fetched rawModel
        verify(jenaTDB2Repository, times(1)).fetchGraph(TEST_GRAPH_NAME);
        verify(detailExtractor).applyOFNTransformations(modelWithData);
        // extractOntologyDetail is called once in main flow (not in checkPublishedOntology since ontology is not published)
        verify(detailExtractor).extractOntologyDetail(modelWithData);
        verify(conceptMetadataMapper).toDto(conceptEntity);
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
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertNotNull(result);
        verify(jenaTDB2Repository).saveOntologyModel(TEST_GRAPH_NAME, testModel);
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void editOntology_WithIRIChange() throws OntologyException {
        OntologyEditModel editModel = createValidOntologyEditModel();
        String newIRI = "http://example.org/new-ontology";
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(newIRI, true);
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(new ArrayList<>());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertNotNull(result);
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
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        ConceptMetadataEntity concept1 = new ConceptMetadataEntity();
        concept1.setConceptIri(TEST_GRAPH_NAME + "/pojem/concept1");
        concept1.setConceptName("concept1");
        concept1.setGraphName(TEST_GRAPH_NAME);

        ConceptMetadataEntity concept2 = new ConceptMetadataEntity();
        concept2.setConceptIri(TEST_GRAPH_NAME + "/pojem/concept2");
        concept2.setConceptName("concept2");
        concept2.setGraphName(TEST_GRAPH_NAME);

        List<ConceptMetadataEntity> concepts = List.of(concept1, concept2);

        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(concepts);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(TEST_ONTOLOGY_ID, editModel);

        assertNotNull(result);
        verify(conceptMetadataRepository).saveAll(argThat(savedConcepts -> {
            List<ConceptMetadataEntity> conceptList = (List<ConceptMetadataEntity>) savedConcepts;
            return conceptList.size() == 2 &&
                    conceptList.stream().allMatch(c -> c.getGraphName().equals(newIRI)) &&
                    conceptList.stream().allMatch(c -> c.getConceptIri().startsWith(newIRI));
        }));
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

        OntologyMetadataModel m1 = new OntologyMetadataModel();
        OntologyMetadataModel m2 = new OntologyMetadataModel();
        OntologyMetadataModel mNoGraph = new OntologyMetadataModel();
        when(ontologyMetadataMapper.toDto(e1)).thenReturn(m1);
        when(ontologyMetadataMapper.toDto(e2)).thenReturn(m2);
        when(ontologyMetadataMapper.toDto(eNoGraph)).thenReturn(mNoGraph);

        // Comments come back in ONE batched query and are grouped by owning ontology in memory.
        CommentEntity comment = new CommentEntity();
        comment.setOntologyMetadata(e1);
        when(commentRepository.findByOntologyMetadataIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(comment));
        when(ontologyMetadataMapper.commentEntitiesToModels(anyList())).thenReturn(new ArrayList<>());

        List<OntologyMetadataModel> out = ontologyService.getAll(null, null);

        assertEquals(3, out.size());
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

        OntologyMetadataModel m1 = new OntologyMetadataModel();
        when(ontologyMetadataMapper.toDto(e1)).thenReturn(m1);
        when(ontologyMetadataMapper.commentEntitiesToModels(anyList())).thenReturn(new ArrayList<>());

        ontologyService.getAll(null, null);

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

        OntologyMetadataModel m1 = new OntologyMetadataModel();
        when(ontologyMetadataMapper.toDto(e1)).thenReturn(m1);
        when(ontologyMetadataMapper.commentEntitiesToModels(anyList())).thenReturn(new ArrayList<>());

        ontologyService.getAll(null, null);

        assertEquals(Map.of("cs", "Muj slovnik"), m1.getName());
        assertNull(m1.getPopis());
    }

    @Test
    void getAll_emptyResult_returnsEmptyListAndSkipsBatchFetch() {
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of());

        List<OntologyMetadataModel> out = ontologyService.getAll(null, null);

        assertTrue(out.isEmpty());
        verify(jenaTDB2Repository, never()).fetchMetadataProperties(anyList());
        verify(ontologyMetadataMapper, never()).toDto(any(OntologyMetadataEntity.class));
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

        OntologyMetadataModel m1 = new OntologyMetadataModel();
        when(ontologyMetadataMapper.toDto(e1)).thenReturn(m1);
        when(ontologyMetadataMapper.commentEntitiesToModels(anyList())).thenReturn(new ArrayList<>());

        List<OntologyMetadataModel> out = ontologyService.getBySlugs(List.of("slug-1"));

        assertEquals(1, out.size());
        assertEquals(Map.of("cs", "Slovník 1"), m1.getName());
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
     */
    /**
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
