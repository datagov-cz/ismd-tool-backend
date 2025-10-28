package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyStorageException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceImpl;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
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
import java.util.List;
import java.util.Optional;

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
    private OntologyMetadataMapper ontologyMetadataMapper;

    @Mock
    private OntologyEditor ontologyEditor;

    @InjectMocks
    private OntologyServiceImpl ontologyService;

    private OntologyMetadataEntity testOntologyEntity;
    private Model testModel;
    private static final Long TEST_ONTOLOGY_ID = 1L;
    private static final String TEST_GRAPH_NAME = "http://example.org/test-ontology";
    private static final String TEST_USER_ID = "user123";

    @BeforeEach
    void setUp() {
        testOntologyEntity = new OntologyMetadataEntity();
        testOntologyEntity.setId(TEST_ONTOLOGY_ID);
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
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");

        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        verify(validationReportRepository).delete(validationReport);
        verify(jenaTDB2Repository).deleteGraph(TEST_GRAPH_NAME);
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
    }

    @Test
    void deleteOntology_OntologyNotFound() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository, never()).deleteById(any());
    }

    @Test
    void deleteOntology_EmptyModel() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("prázdný"));
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    }

    @Test
    void deleteOntology_NoValidationReport() throws OntologyException {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(validationReportRepository.findByOntologyMetadataId(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");

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
        OntologyValidationException exception = assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(null, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("prázdná"));
    }

    @Test
    void createOntology_InvalidIRI() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();
        createModel.setNamespace("invalid iri with spaces");

        assertThrows(OntologyValidationException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));
    }

    @Test
    void createOntology_TDB2SaveFails() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("TDB2 error")).when(jenaTDB2Repository).saveOntologyModel(anyString(), any(Model.class));

        OntologyStorageException exception = assertThrows(OntologyStorageException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit RDF model"));
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void createOntology_MetadataSaveFails_CleanupTDB2() {
        OntologyCreateModel createModel = createValidOntologyCreateModel();

        when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenThrow(new RuntimeException("DB error"));

        OntologyStorageException exception = assertThrows(OntologyStorageException.class,
                () -> ontologyService.createOntology(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit metadata"));
        verify(jenaTDB2Repository).deleteGraph(anyString());
    }

    // ========== getOntologyDetailModel Tests ==========

    @Test
    void getOntologyDetailModel_Success() throws OntologyException {
        Model modelWithData = createModelWithOntologyData();
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(modelWithData);

        OntologyDetailModel result = ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_ID);

        assertNotNull(result);
        verify(jenaTDB2Repository).fetchGraph(TEST_GRAPH_NAME);
    }

    @Test
    void getOntologyDetailModel_OntologyNotFound() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("nebyla nalezena"));
    }

    @Test
    void getOntologyDetailModel_EmptyModel() {
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.getOntologyDetailModel(TEST_ONTOLOGY_ID));

        assertTrue(exception.getMessage().contains("prázdný"));
    }

    // ========== editOntology Tests ==========

    @Test
    void editOntology_WithoutIRIChange() throws OntologyException {
        OntologyEditModel editModel = createValidOntologyEditModel();
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(TEST_GRAPH_NAME, false);
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString())).thenReturn(editResult);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(editModel);

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

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(new ArrayList<>());
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(editModel);

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

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString())).thenReturn(editResult);
        when(conceptMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(concepts);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(testOntologyEntity);
        when(ontologyMetadataMapper.toDto(testOntologyEntity)).thenReturn(expectedDto);

        OntologyMetadataModel result = ontologyService.editOntology(editModel);

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
        EmptyDataException exception = assertThrows(EmptyDataException.class,
                () -> ontologyService.editOntology(null));

        assertTrue(exception.getMessage().contains("prázdná"));
    }

    @Test
    void editOntology_NullIRI() {
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI(null);

        OntologyValidationException exception = assertThrows(OntologyValidationException.class,
                () -> ontologyService.editOntology(editModel));

        assertTrue(exception.getMessage().contains("povinné"));
    }

    @Test
    void editOntology_EmptyIRI() {
        OntologyEditModel editModel = new OntologyEditModel();
        editModel.setOntologyIRI("  ");

        OntologyValidationException exception = assertThrows(OntologyValidationException.class,
                () -> ontologyService.editOntology(editModel));

        assertTrue(exception.getMessage().contains("povinné"));
    }

    @Test
    void editOntology_OntologyNotFound() {
        OntologyEditModel editModel = createValidOntologyEditModel();

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.empty());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.editOntology(editModel));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
    }

    @Test
    void editOntology_EmptyModel() {
        OntologyEditModel editModel = createValidOntologyEditModel();

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyNotFoundException exception = assertThrows(OntologyNotFoundException.class,
                () -> ontologyService.editOntology(editModel));

        assertTrue(exception.getMessage().contains("prázdný"));
    }

    @Test
    void editOntology_IRIChangeFails() {
        OntologyEditModel editModel = createValidOntologyEditModel();
        String newIRI = "http://example.org/new-ontology";
        OntologyEditor.EditResult editResult = new OntologyEditor.EditResult(newIRI, true);

        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(testOntologyEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        testModel.add(testModel.createResource("http://example.org/test"), testModel.createProperty("http://example.org/prop"), "value");
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString())).thenReturn(editResult);
        doThrow(new RuntimeException("Save error")).when(jenaTDB2Repository).saveOntologyModel(eq(newIRI), any(Model.class));

        OntologyStorageException exception = assertThrows(OntologyStorageException.class,
                () -> ontologyService.editOntology(editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit změny"));
    }

    // ========== Helper Methods ==========

    private OntologyCreateModel createValidOntologyCreateModel() {
        OntologyCreateModel model = new OntologyCreateModel();
        NameModel nameModel = new NameModel();
        nameModel.setName("test-ontology");
        nameModel.setLanguageTag("cs");
        model.setNameModel(nameModel);
        model.setNamespace("http://example.org/");

        DescriptionModel descModel = new DescriptionModel();
        descModel.setDescription("Test description");
        descModel.setLanguageTag("cs");
        model.setDescriptionModel(descModel);

        return model;
    }

    private OntologyEditModel createValidOntologyEditModel() {
        OntologyEditModel model = new OntologyEditModel();
        model.setOntologyIRI(TEST_GRAPH_NAME);
        return model;
    }

    private Model createModelWithOntologyData() {
        Model model = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        model.createResource(ns + "TestOntology")
                .addProperty(model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        model.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        return model;
    }
}