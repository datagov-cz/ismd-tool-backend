package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
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
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConceptServiceImplTest {

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Mock
    private ConceptMetadataMapper conceptMetadataMapper;

    @Mock
    private ConceptCreator conceptCreator;

    @Mock
    private ConceptEditor conceptEditor;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @InjectMocks
    private ConceptServiceImpl conceptService;

    private ConceptMetadataEntity testConceptEntity;
    private Model testModel;
    private Resource testResource;
    private static final Long TEST_CONCEPT_ID = 1L;
    private static final String TEST_CONCEPT_IRI = "http://example.org/pojem/test-concept";
    private static final String TEST_GRAPH_NAME = "http://example.org/test-ontology";
    private static final String TEST_USER_ID = "user123";
    private static final String TEST_CONCEPT_NAME = "Test Concept";

    @BeforeEach
    void setUp() {
        testConceptEntity = new ConceptMetadataEntity();
        testConceptEntity.setId(TEST_CONCEPT_ID);
        testConceptEntity.setConceptIri(TEST_CONCEPT_IRI);
        testConceptEntity.setConceptName(TEST_CONCEPT_NAME);
        testConceptEntity.setConceptType(ConceptType.TRIDA);
        testConceptEntity.setGraphName(TEST_GRAPH_NAME);
        testConceptEntity.setUserId(TEST_USER_ID);
        testConceptEntity.setIsPublished(false);

        testModel = ModelFactory.createDefaultModel();
        testResource = testModel.createResource(TEST_CONCEPT_IRI);
    }

    // ========== createConcept Tests ==========

    @Test
    void createConcept_Success() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.createConcept(createModel, TEST_USER_ID);

        assertNotNull(result);
        verify(conceptCreator).createSingleConcept(createModel);
        verify(jenaTDB2Repository).saveConcept(testResource, TEST_GRAPH_NAME);
        verify(conceptMetadataRepository).save(any(ConceptMetadataEntity.class));
    }

    @Test
    void createConcept_AlreadyExists() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        ConceptMetadataModel existingDto = new ConceptMetadataModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(existingDto);

        ConceptMetadataModel result = conceptService.createConcept(createModel, TEST_USER_ID);

        assertNotNull(result);
        verify(jenaTDB2Repository, never()).saveConcept(any(), anyString());
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void createConcept_NullUserId() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.createConcept(createModel, null));

        assertTrue(exception.getMessage().contains("povinné"));
        verify(conceptCreator, never()).createSingleConcept(any());
    }

    @Test
    void createConcept_EmptyUserId() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.createConcept(createModel, "  "));

        assertTrue(exception.getMessage().contains("povinné"));
        verify(conceptCreator, never()).createSingleConcept(any());
    }

    @Test
    void createConcept_CreatorFails() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        when(conceptCreator.createSingleConcept(createModel)).thenThrow(new RuntimeException("Creator error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se transformovat pojem"));
        verify(jenaTDB2Repository, never()).saveConcept(any(), anyString());
    }

    @Test
    void createConcept_TDB2SaveFails() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME))
                .thenThrow(new RuntimeException("TDB2 error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit pojem do TDB2"));
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void createConcept_MetadataSaveFails_RollbackTDB2() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class)))
                .thenThrow(new RuntimeException("DB error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit metadata pojmu"));
        verify(jenaTDB2Repository).deleteConceptFromGraph(TEST_CONCEPT_IRI, TEST_GRAPH_NAME);
    }

    @Test
    void createConcept_SavesCorrectMetadata() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.createConcept(createModel, TEST_USER_ID);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());

        ConceptMetadataEntity savedEntity = captor.getValue();
        assertEquals(TEST_CONCEPT_NAME, savedEntity.getConceptName());
        assertEquals(ConceptType.TRIDA, savedEntity.getConceptType());
        assertEquals(TEST_CONCEPT_IRI, savedEntity.getConceptIri());
        assertEquals(TEST_GRAPH_NAME, savedEntity.getGraphName());
        assertEquals(TEST_USER_ID, savedEntity.getUserId());
        assertFalse(savedEntity.getIsPublished());
    }

    // ========== deleteConcept Tests ==========

    @Test
    void deleteConcept_Success() throws OntologyException {
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);

        conceptService.deleteConcept(TEST_CONCEPT_ID);
        List<String> testConceptIris = new ArrayList<>();
        testConceptIris.add(TEST_CONCEPT_IRI);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();
        conceptEntities.add(testConceptEntity);

        verify(jenaTDB2Repository).deleteConceptsFromGraph(testConceptIris, TEST_GRAPH_NAME);
        verify(conceptMetadataRepository).deleteAll(conceptEntities);
    }

    @Test
    void deleteConcept_ConceptNotFound() {
        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.empty());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.deleteConcept(TEST_CONCEPT_ID));

        assertTrue(exception.getMessage().contains("nebyla nalezena"));
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
        verify(conceptMetadataRepository, never()).deleteAll(any());
    }

    @Test
    void deleteConcept_EmptyGraph() {
        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.deleteConcept(TEST_CONCEPT_ID));

        assertTrue(exception.getMessage().contains("prázdný"));
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
    }

    @Test
    void deleteConcept_ConceptNotInGraph() {
        Model emptyModel = ModelFactory.createDefaultModel();

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(emptyModel);
        emptyModel.add(emptyModel.createResource("http://other.org/resource"),
                      emptyModel.createProperty("http://example.org/prop"), "value");

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.deleteConcept(TEST_CONCEPT_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
    }

    // ========== editConcept Tests ==========

    @Test
    void editConcept_WithoutIRIChange() {
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.editConcept(editModel);

        assertNotNull(result);
        verify(jenaTDB2Repository).putOntologyModel(TEST_GRAPH_NAME, testModel);
        verify(conceptMetadataRepository).save(any(ConceptMetadataEntity.class));
    }

    @Test
    void editConcept_WithIRIChange() {
        String newIRI = "http://example.org/pojem/new-concept";
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(newIRI, true, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.editConcept(editModel);

        assertNotNull(result);
        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertEquals(newIRI, captor.getValue().getConceptIri());
    }

    @Test
    void editConcept_UpdatesName() {
        String newName = "Updated Concept Name";
        ConceptEditModel editModel = createValidConceptEditModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", newName);
        editModel.getNameModel().setName(nameMap);
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.editConcept(editModel);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertEquals(newName, captor.getValue().getConceptName());
    }

    @Test
    void editConcept_UpdatesInTezaurus() {
        ConceptEditModel editModel = createValidConceptEditModel();
        editModel.setInTezaurus("true");
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.editConcept(editModel);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertEquals("true", captor.getValue().getInTezaurus());
    }

    @Test
    void editConcept_MetadataNotFound() {
        ConceptEditModel editModel = createValidConceptEditModel();

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("nebyla nalezena"));
        verify(jenaTDB2Repository, never()).fetchGraph(anyString());
    }

    @Test
    void editConcept_EmptyGraph() {
        ConceptEditModel editModel = createValidConceptEditModel();

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("prázdný"));
        verify(conceptEditor, never()).editConcept(any(), any(), anyString());
    }

    @Test
    void editConcept_ConceptNotInGraph() {
        ConceptEditModel editModel = createValidConceptEditModel();
        Model emptyModel = ModelFactory.createDefaultModel();
        emptyModel.add(emptyModel.createResource("http://other.org/resource"),
                      emptyModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(emptyModel);

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(conceptEditor, never()).editConcept(any(), any(), anyString());
    }

    @Test
    void editConcept_EditorFails() {
        ConceptEditModel editModel = createValidConceptEditModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME)))
                .thenThrow(new RuntimeException("Editor error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se upravit pojem"));
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
    }

    @Test
    void editConcept_TDB2SaveFails() {
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        doThrow(new RuntimeException("TDB2 error")).when(jenaTDB2Repository).putOntologyModel(TEST_GRAPH_NAME, testModel);

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit upravený pojem do TDB2"));
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void editConcept_MetadataUpdateFails() {
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME))).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class)))
                .thenThrow(new RuntimeException("DB error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se aktualizovat metadata pojmu"));
    }

    // ========== Helper Methods ==========

    private ConceptCreateModel createValidConceptCreateModel() {
        ClassConceptModel model = new ClassConceptModel();
        model.setOntologyGraphName(TEST_GRAPH_NAME);
        model.setConceptType("TRIDA");
        model.setNamespace(TEST_GRAPH_NAME);

        NameModel nameModel = new NameModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", TEST_CONCEPT_NAME);
        nameModel.setName(nameMap);
        model.setNameModel(nameModel);

        return model;
    }

    private ConceptEditModel createValidConceptEditModel() {
        ConceptEditModel model = new ConceptEditModel() {
            @Override
            public ConceptType getConceptTypeEnum() {
                // Override method not applicable for this test
                return null;
            }

            @Override
            protected void validateSpecificFields() {
                // Override method not applicable for this test
            }
        };
        model.setConceptIRI(TEST_CONCEPT_IRI);
        model.setConceptType("TRIDA");

        NameModel nameModel = new NameModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", TEST_CONCEPT_NAME);
        nameModel.setName(nameMap);
        model.setNameModel(nameModel);

        return model;
    }
}