package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
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
import org.mockito.Spy;
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
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptCreator conceptCreator;

    @Mock
    private ConceptEditor conceptEditor;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private RppSnapshotHolder rppSnapshotHolder;

    @Mock
    private OntologyDetailExtractor detailExtractor;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @Mock
    private ConceptDeviationComparator deviationComparator;

    @Mock
    private ReferencedConceptsEnricher referencedConceptsEnricher;

    // Outbox deps: the OutboxConfig mock's isEnabled() defaults to false, so these tests exercise
    // the existing DIRECT write path unchanged (the outbox path is covered by the outbox tests).
    @Mock
    private com.dia.ismdtoolbackend.outbox.OutboxConfig outboxConfig;

    @Mock
    private com.dia.ismdtoolbackend.outbox.OutboxWriter outboxWriter;

    @Mock
    private com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger outboxRelayTrigger;

    @Mock
    private com.dia.ismdtoolbackend.service.NkdSnapshotService nkdSnapshotService;

    @Mock
    private WorkingCopyDeviationServiceImpl workingCopyDeviationService;

    // Real detector (stateless, pure). On these unit tests the edited concept has no external NKD link
    // triples, so detection returns empty and reconcileNkdLinks is a no-op — the mocked snapshot service
    // is never called. A mock would return null lists and NPE inside the reconcile.
    @Spy
    private com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector nkdLinkDetector =
            new com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector();

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

        // getConceptDetail merges cross-graph rdfs:domain members into the fetched
        // graph; default to no extra members so existing detail tests are unaffected.
        when(jenaTDB2Repository.fetchExternalDomainMembers(anyString()))
                .thenReturn(ModelFactory.createDefaultModel());
    }

    // ========== createConcept Tests ==========

    @Test
    void createConcept_Success() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        OntologyMetadataEntity ontologyMetadata = new OntologyMetadataEntity();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName(TEST_GRAPH_NAME);

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(ontologyMetadata));
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.createConcept(createModel, TEST_USER_ID);

        assertNotNull(result);
        verify(conceptCreator).createSingleConcept(createModel);
        verify(jenaTDB2Repository).saveConcept(testResource, TEST_GRAPH_NAME);
        verify(conceptMetadataRepository).save(any(ConceptMetadataEntity.class));
    }

    @Test
    void createConcept_AlreadyExists_rejectedAs400() {
        ConceptCreateModel createModel = createValidConceptCreateModel();

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));

        assertThrows(ConceptValidationException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));

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
    void createConcept_NameMissing_Throws() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        createModel.setNameModel(new NameModel());

        ConceptValidationException ex = assertThrows(ConceptValidationException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("Název pojmu je povinný"));
        verify(conceptCreator, never()).createSingleConcept(any());
    }

    @Test
    void createConcept_NameMissingCsVariant_Throws() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("en", "name");
        createModel.getNameModel().setName(nameMap);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("českou variantu"));
    }

    @Test
    void createConcept_DescriptionPresentWithoutCs_Throws() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        DescriptionModel desc = new DescriptionModel();
        Map<String, String> descMap = new HashMap<>();
        descMap.put("en", "English only");
        desc.setDescription(descMap);
        createModel.setDescriptionModel(desc);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("Popis pojmu"));
    }

    @Test
    void createConcept_DefinitionPresentWithoutCs_Throws() {
        ConceptCreateModel createModel = createValidConceptCreateModel();
        DefinitionModel def = new DefinitionModel();
        Map<String, String> defMap = new HashMap<>();
        defMap.put("en", "English only");
        def.setDefinition(defMap);
        createModel.setDefinitionModel(def);

        ConceptValidationException ex = assertThrows(ConceptValidationException.class,
                () -> conceptService.createConcept(createModel, TEST_USER_ID));
        assertTrue(ex.getMessage().contains("Definice pojmu"));
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

        OntologyMetadataEntity ontologyMetadata = new OntologyMetadataEntity();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName(TEST_GRAPH_NAME);

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(ontologyMetadata));
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

        OntologyMetadataEntity ontologyMetadata = new OntologyMetadataEntity();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName(TEST_GRAPH_NAME);

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(jenaTDB2Repository.saveConcept(testResource, TEST_GRAPH_NAME)).thenReturn(TEST_CONCEPT_IRI);
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(ontologyMetadata));
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
        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(true);
        when(jenaTDB2Repository.conceptNotFoundInGraph(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(false);
        when(jenaTDB2Repository.findRelatedConceptUris(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(new ArrayList<>());

        conceptService.deleteConcept(TEST_CONCEPT_ID);
        List<String> testConceptIris = new ArrayList<>();
        testConceptIris.add(TEST_CONCEPT_IRI);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();
        conceptEntities.add(testConceptEntity);

        verify(jenaTDB2Repository).deleteConceptsFromGraph(testConceptIris, TEST_GRAPH_NAME);
        verify(conceptMetadataRepository).deleteAll(conceptEntities);
    }

    // ========== T7 outbox wiring (flag ON) ==========

    @Test
    void deleteConcept_outboxEnabled_enqueuesInsteadOfDirectDelete() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        // Outbox path takes the pessimistic lock finder (HIGH review fix), not plain findById.
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(true);
        when(jenaTDB2Repository.conceptNotFoundInGraph(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(false);
        when(jenaTDB2Repository.findRelatedConceptUris(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(new ArrayList<>());

        conceptService.deleteConcept(TEST_CONCEPT_ID);

        // Outbox path: enqueue + PG delete + nudge; NO direct TDB2 delete.
        verify(outboxWriter).enqueueDeleteConcepts(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anyList());
        verify(conceptMetadataRepository).deleteAll(anyList());
        verify(outboxRelayTrigger).nudgeAfterCommit();
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
    }

    @Test
    void editConcept_outboxEnabled_enqueuesChangeSetsInsteadOfDirectPut() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        ConceptEditModel editModel = createValidConceptEditModel();
        // The graph must be non-empty and contain the concept (fetchAndValidateGraph / validateConceptInGraph).
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        org.apache.jena.rdf.model.Statement add = testModel.createStatement(
                testModel.createResource(TEST_CONCEPT_IRI),
                testModel.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                "New");
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                TEST_CONCEPT_IRI, false, java.util.Set.of(), java.util.Set.of(add));

        // Outbox path takes the pessimistic lock finder (HIGH review fix), not plain findById.
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        // Outbox path: enqueue the editor's change sets, nudge, and NO direct whole-graph PUT.
        verify(outboxWriter).enqueueUpsert(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anySet(), anySet());
        verify(outboxRelayTrigger).nudgeAfterCommit();
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
    }

    // Review #4: on a RENAME the outbox row must key on the PRE-EDIT (old) IRI, not the new one, so
    // it shares an aggregate with the concept's create/prior rows and the ordering gate relates them.
    @Test
    void editConcept_outboxEnabled_rename_aggregateIsPreEditIri() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        String newIri = "http://example.org/pojem/renamed-concept";
        ConceptEditModel editModel = createValidConceptEditModel();
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        ConceptEditor.EditResult renameResult = new ConceptEditor.EditResult(
                newIri, true, java.util.Set.of(), java.util.Set.of()); // iriChanged=true

        // Outbox path takes the pessimistic lock finder (HIGH review fix), not plain findById.
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(renameResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        // Aggregate = OLD IRI (TEST_CONCEPT_IRI), NOT the new one — this is the #4 fix.
        verify(outboxWriter).enqueueUpsert(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anySet(), anySet());
        verify(outboxWriter, never()).enqueueUpsert(anyString(), eq(newIri), anySet(), anySet());
    }

    /**
     * The uniqueness predicate handed to the editor excludes the concept being edited, so
     * re-saving a concept under its own name is not a collision, while another concept's IRI is.
     */
    @Test
    @SuppressWarnings("unchecked")
    void editConcept_uniquenessPredicate_excludesSelfButRejectsOthers() {
        when(outboxConfig.isEnabled()).thenReturn(false);
        ConceptEditModel editModel = createValidConceptEditModel();
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                TEST_CONCEPT_IRI, false, java.util.Set.of(), java.util.Set.of());

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        ArgumentCaptor<java.util.function.Predicate<String>> captor =
                ArgumentCaptor.forClass(java.util.function.Predicate.class);
        verify(conceptEditor).editConcept(anyString(), any(), any(Model.class), anyString(), captor.capture());
        java.util.function.Predicate<String> iriTaken = captor.getValue();

        // The concept's own row is not a collision.
        testConceptEntity.setId(TEST_CONCEPT_ID);
        when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/x"))
                .thenReturn(Optional.of(testConceptEntity));
        assertFalse(iriTaken.test("http://example.org/pojem/x"));

        // A different concept's row is.
        ConceptMetadataEntity other = new ConceptMetadataEntity();
        other.setId(TEST_CONCEPT_ID + 1);
        when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/y"))
                .thenReturn(Optional.of(other));
        assertTrue(iriTaken.test("http://example.org/pojem/y"));

        // A free IRI is not.
        when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/z"))
                .thenReturn(Optional.empty());
        assertFalse(iriTaken.test("http://example.org/pojem/z"));
    }

    // Review #4 HIGH — the outbox edit path must take the PESSIMISTIC row lock (findWithLockById),
    // not plain findById, so two concurrent edits of the same concept are serialized and cannot
    // enqueue out-of-order same-aggregate outbox rows.
    @Test
    void editConcept_outboxEnabled_takesPessimisticLock() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        ConceptEditModel editModel = createValidConceptEditModel();
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                TEST_CONCEPT_IRI, false, java.util.Set.of(), java.util.Set.of());
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(conceptMetadataRepository).findWithLockById(TEST_CONCEPT_ID);
        verify(conceptMetadataRepository, never()).findById(TEST_CONCEPT_ID);
    }

    // ========== Step 8: NKD local-copy reconcile on edit (reconcileNkdLinks) ==========

    private static final String NKD_SUPERCLASS = "https://slovník.gov.cz/agendový/104/pojem/nadrazena";
    private static final org.apache.jena.rdf.model.Property SUBCLASS_OF = org.apache.jena.vocabulary.RDFS.subClassOf;
    private static final org.apache.jena.rdf.model.Property RDFS_DOMAIN = org.apache.jena.vocabulary.RDFS.domain;
    private static final org.apache.jena.rdf.model.Property RDFS_RANGE = org.apache.jena.vocabulary.RDFS.range;

    /** Stage an outbox-path edit whose POST-edit model is {@code testModel} (which the test pre-populates). */
    private ConceptEditModel stageEdit(boolean iriChanged, String newIri) {
        when(outboxConfig.isEnabled()).thenReturn(true);
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                iriChanged ? newIri : TEST_CONCEPT_IRI, iriChanged,
                new java.util.HashSet<>(), new java.util.HashSet<>());
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());
        return editModel;
    }

    @Test
    void editConcept_linkToPublishedNkdSuperclass_createsSnapshot() {
        // Post-edit model: this TRIDA concept now has subClassOf → an external (NKD) concept.
        testModel.add(testResource, SUBCLASS_OF, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        // Allowed external broaderClass target → snapshot create/refresh, with the BROADER_CLASS link type.
        verify(nkdSnapshotService).createOrRefreshSnapshot(
                eq(testConceptEntity), eq(NKD_SUPERCLASS),
                eq(com.dia.ismdtoolbackend.enums.SnapshotLinkType.BROADER_CLASS.value()), any());
    }

    @Test
    void editConcept_outboxDisabled_snapshotTriplesWrittenToTDB2() {
        // Adversarial-review fix A: on the DIRECT (outbox-disabled) path, the materialized copy triples
        // that reconcile folds into editResult must reach TDB2 — not just the PG row. Otherwise the snapshot
        // row claims a copy that isn't in the graph (silent C1 violation on the legacy path).
        when(outboxConfig.isEnabled()).thenReturn(false);
        testModel.add(testResource, SUBCLASS_OF, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                TEST_CONCEPT_IRI, false, new java.util.HashSet<>(), new java.util.HashSet<>());
        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        // Mock the snapshot service to contribute a sentinel materialized-copy triple into the change set,
        // exactly as the real createOrRefreshSnapshot would.
        org.apache.jena.rdf.model.Statement copyTriple = testModel.createStatement(
                testModel.createResource(NKD_SUPERCLASS),
                org.apache.jena.vocabulary.RDFS.label,
                testModel.createLiteral("NKD copy"));
        doAnswer(inv -> {
            com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet cs = inv.getArgument(3);
            cs.toAdd.add(copyTriple);
            return null;
        }).when(nkdSnapshotService).createOrRefreshSnapshot(any(), eq(NKD_SUPERCLASS), anyString(), any());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        // The model saved to TDB2 must contain the materialized copy triple.
        ArgumentCaptor<Model> saved = ArgumentCaptor.forClass(Model.class);
        verify(jenaTDB2Repository).putOntologyModel(eq(TEST_GRAPH_NAME), saved.capture());
        assertTrue(saved.getValue().contains(copyTriple),
                "direct path must write the materialized copy triple to TDB2");
    }

    @Test
    void editConcept_ownedSuperclass_notSnapshotted() {
        // subClassOf an OWNED concept (same graph scheme) → not external → never snapshotted.
        String ownedParent = TEST_GRAPH_NAME + "/pojem/local-parent";
        testModel.add(testResource, SUBCLASS_OF, testModel.createResource(ownedParent));
        ConceptEditModel editModel = stageEdit(false, null);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
    }

    @Test
    void editConcept_domainPointsAtPublishedNkd_rejectedAs400() {
        // C3: rdfs:domain → a CONFIRMED-published NKD concept is invalid input → OntologyValidationException (400).
        testModel.add(testResource, RDFS_DOMAIN, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(java.util.List.of(NKD_SUPERCLASS));

        assertThrows(com.dia.ismdtoolbackend.exception.OntologyValidationException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        // Atomic reject: no snapshot, no enqueue.
        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        verify(outboxWriter, never()).enqueueUpsert(anyString(), anyString(), anySet(), anySet());
    }

    @Test
    void editConcept_domainPointsAtNkd_nkdOutage_failsOpen() {
        // C3 fail-OPEN: NKD published-check throws (outage) → enforcement skipped, edit commits.
        testModel.add(testResource, RDFS_DOMAIN, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        when(nkdSparqlClient.getPublishedResourcesList(anyList()))
                .thenThrow(new RuntimeException("NKD down"));
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);   // must NOT throw

        verify(outboxWriter).enqueueUpsert(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anySet(), anySet());
    }

    /**
     * Phase A: a locally-owned concept whose own IRI is in NKD (a working copy). It reads as external to
     * NkdLinkDetector (its IRI is not prefixed by the owner graph's scheme) and is published, so it used
     * to be rejected/snapshotted as if it were someone else's concept.
     */
    private void stageLocallyOwnedWorkingCopy(String... iris) {
        java.util.List<ConceptMetadataEntity> owned = java.util.Arrays.stream(iris).map(iri -> {
            ConceptMetadataEntity e = new ConceptMetadataEntity();
            e.setConceptIri(iri);
            e.setGraphName(TEST_GRAPH_NAME);
            e.setIsPublished(true);
            return e;
        }).toList();
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(owned);
    }

    @Test
    void editConcept_domainPointsAtLocallyOwnedWorkingCopy_notRejectedAndNkdNotQueried() {
        // Phase A trigger bug: domain → a working copy is our own concept, so it must not 400 — and NKD
        // must not even be asked, since the only candidate was locally owned.
        testModel.add(testResource, RDFS_DOMAIN, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        stageLocallyOwnedWorkingCopy(NKD_SUPERCLASS);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);   // must NOT throw

        verify(nkdSparqlClient, never()).getPublishedResourcesList(anyList());
        verify(outboxWriter).enqueueUpsert(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anySet(), anySet());
    }

    @Test
    void editConcept_domainMixesOwnedAndForeignPublished_rejectsOnlyForeign() {
        // The reject message must name the foreign IRI only — the owned working copy is legitimate.
        String foreign = "https://slovník.gov.cz/agendový/999/pojem/foreign";
        testModel.add(testResource, RDFS_DOMAIN, testModel.createResource(NKD_SUPERCLASS));
        testModel.add(testResource, RDFS_RANGE, testModel.createResource(foreign));
        ConceptEditModel editModel = stageEdit(false, null);
        stageLocallyOwnedWorkingCopy(NKD_SUPERCLASS);
        when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(java.util.List.of(foreign));

        com.dia.ismdtoolbackend.exception.OntologyValidationException ex = assertThrows(
                com.dia.ismdtoolbackend.exception.OntologyValidationException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(ex.getMessage().contains(foreign), "reject must name the foreign IRI");
        assertTrue(!ex.getMessage().contains(NKD_SUPERCLASS), "reject must not name the owned working copy");

        // Only the foreign IRI is worth asking NKD about; the owned one is filtered out first.
        ArgumentCaptor<java.util.List<String>> asked = ArgumentCaptor.forClass(java.util.List.class);
        verify(nkdSparqlClient).getPublishedResourcesList(asked.capture());
        assertTrue(asked.getValue().contains(foreign));
        assertTrue(!asked.getValue().contains(NKD_SUPERCLASS), "owned IRI must not be sent to NKD");
    }

    @Test
    void editConcept_superclassIsLocallyOwnedWorkingCopy_notSnapshotted() {
        // A link to a working copy is a first-class local link, never a LINK_TARGET snapshot of a
        // concept we already own.
        testModel.add(testResource, SUBCLASS_OF, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        stageLocallyOwnedWorkingCopy(NKD_SUPERCLASS);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
    }

    @Test
    void editConcept_linkTargetBecameOwned_staleSnapshotRemoved() {
        // The target is now locally owned, so it drops out of allowedTargets and the existing removal
        // loop tears down the snapshot row it no longer warrants.
        testModel.add(testResource, SUBCLASS_OF, testModel.createResource(NKD_SUPERCLASS));
        ConceptEditModel editModel = stageEdit(false, null);
        stageLocallyOwnedWorkingCopy(NKD_SUPERCLASS);
        com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity stale =
                new com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity();
        stale.setNkdIri(NKD_SUPERCLASS);
        stale.setGraphName(TEST_GRAPH_NAME);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of(stale));

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(nkdSnapshotService).removeSnapshotAndLink(eq(stale), anySet(), any());
        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
    }

    @Test
    void editConcept_droppedLink_removesSnapshot() {
        // Existing snapshot for an NKD IRI that is NO LONGER linked in the post-edit model → unlink + remove.
        // A benign triple keeps the graph non-empty (validateConceptInGraph) but is NOT a subClassOf link.
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        ConceptEditModel editModel = stageEdit(false, null);   // testModel has NO subClassOf triple now
        com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity stale =
                new com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity();
        stale.setNkdIri(NKD_SUPERCLASS);
        stale.setGraphName(TEST_GRAPH_NAME);
        when(nkdSnapshotService.findForConcept(TEST_CONCEPT_ID)).thenReturn(java.util.List.of(stale));

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(nkdSnapshotService).removeSnapshotAndLink(eq(stale), anySet(), any());
        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
    }

    // Review #4 HIGH — same guarantee for the outbox delete path.
    @Test
    void deleteConcept_outboxEnabled_takesPessimisticLock() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        when(conceptMetadataRepository.findWithLockById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(true);
        when(jenaTDB2Repository.conceptNotFoundInGraph(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(false);
        when(jenaTDB2Repository.findRelatedConceptUris(TEST_CONCEPT_IRI, TEST_GRAPH_NAME)).thenReturn(new ArrayList<>());

        conceptService.deleteConcept(TEST_CONCEPT_ID);

        verify(conceptMetadataRepository).findWithLockById(TEST_CONCEPT_ID);
        verify(conceptMetadataRepository, never()).findById(TEST_CONCEPT_ID);
    }

    // Conversely, with the flag OFF the direct path must keep plain findById (no lock) — byte-for-byte
    // unchanged behavior, so the lock can never affect production until outbox is enabled.
    @Test
    void editConcept_outboxDisabled_usesPlainFindByIdNoLock() {
        when(outboxConfig.isEnabled()).thenReturn(false);
        ConceptEditModel editModel = createValidConceptEditModel();
        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(
                TEST_CONCEPT_IRI, false, java.util.Set.of(), java.util.Set.of());
        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(new ConceptMetadataModel());

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        verify(conceptMetadataRepository).findById(TEST_CONCEPT_ID);
        verify(conceptMetadataRepository, never()).findWithLockById(TEST_CONCEPT_ID);
    }

    @Test
    void createConcept_outboxEnabled_enqueuesConceptTriplesAndNudges() {
        when(outboxConfig.isEnabled()).thenReturn(true);
        ConceptCreateModel createModel = createValidConceptCreateModel();
        // The concept's triples come from the created resource's model.
        testResource.addProperty(testModel.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"), "C");

        OntologyMetadataEntity ontologyMetadata = new OntologyMetadataEntity();
        ontologyMetadata.setId(1L);
        ontologyMetadata.setGraphName(TEST_GRAPH_NAME);

        when(conceptCreator.createSingleConcept(createModel)).thenReturn(testResource);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.findByGraphName(TEST_GRAPH_NAME)).thenReturn(Optional.of(ontologyMetadata));
        when(conceptMetadataRepository.save(any())).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(any())).thenReturn(new ConceptMetadataModel());

        conceptService.createConcept(createModel, TEST_USER_ID);

        // Outbox path: enqueue (empty remove set, the concept's triples) keyed on the concept IRI;
        // nudge; NO direct saveConcept to TDB2.
        verify(outboxWriter).enqueueUpsert(eq(TEST_GRAPH_NAME), eq(TEST_CONCEPT_IRI), anySet(), anySet());
        verify(outboxRelayTrigger).nudgeAfterCommit();
        verify(jenaTDB2Repository, never()).saveConcept(any(), anyString());
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
        when(jenaTDB2Repository.graphHasData(TEST_GRAPH_NAME)).thenReturn(false);

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

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.editConcept(TEST_CONCEPT_ID, editModel);

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

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        ConceptMetadataModel result = conceptService.editConcept(TEST_CONCEPT_ID, editModel);

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

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertEquals(newName, captor.getValue().getConceptName());
    }

    @Test
    void editConcept_UpdatesInTezaurus() {
        ConceptEditModel editModel = createValidConceptEditModel();
        editModel.setInTezaurus(true);
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertTrue(captor.getValue().getInTezaurus());
    }

    @Test
    void editConcept_UpdatesInTezaurusToFalse() {
        ConceptEditModel editModel = createValidConceptEditModel();
        editModel.setInTezaurus(false);
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);
        ConceptMetadataModel expectedDto = new ConceptMetadataModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(testConceptEntity);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(expectedDto);

        conceptService.editConcept(TEST_CONCEPT_ID, editModel);

        ArgumentCaptor<ConceptMetadataEntity> captor = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(captor.capture());
        assertFalse(captor.getValue().getInTezaurus());
    }

    @Test
    void editConcept_MetadataNotFound() {
        ConceptEditModel editModel = createValidConceptEditModel();

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.empty());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("nebyla nalezena"));
        verify(jenaTDB2Repository, never()).fetchGraph(anyString());
    }

    @Test
    void editConcept_EmptyGraph() {
        ConceptEditModel editModel = createValidConceptEditModel();

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("prázdný"));
        verify(conceptEditor, never()).editConcept(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void editConcept_ConceptNotInGraph() {
        ConceptEditModel editModel = createValidConceptEditModel();
        Model emptyModel = ModelFactory.createDefaultModel();
        emptyModel.add(emptyModel.createResource("http://other.org/resource"),
                      emptyModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(emptyModel);

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(conceptEditor, never()).editConcept(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void editConcept_EditorFails() {
        ConceptEditModel editModel = createValidConceptEditModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenThrow(new RuntimeException("Editor error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se upravit pojem"));
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
    }

    @Test
    void editConcept_ValidationException_propagatesUnwrappedAs400() {
        // A ConceptValidationException from the editor (invalid input) must NOT be
        // re-wrapped into OntologyException — that would turn the 400 into a 500.
        ConceptEditModel editModel = createValidConceptEditModel();

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any()))
                .thenThrow(new ConceptValidationException("Neplatné hodnoty v úpravě pojmu: exactMatch"));

        ConceptValidationException exception = assertThrows(ConceptValidationException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("Neplatné hodnoty"));
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void editConcept_TDB2SaveFails() {
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        doThrow(new RuntimeException("TDB2 error")).when(jenaTDB2Repository).putOntologyModel(TEST_GRAPH_NAME, testModel);

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit upravený pojem do TDB2"));
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void editConcept_MetadataUpdateFails() {
        ConceptEditModel editModel = createValidConceptEditModel();
        ConceptEditor.EditResult editResult = new ConceptEditor.EditResult(TEST_CONCEPT_IRI, false, 5);

        testModel.add(testResource, testModel.createProperty("http://example.org/prop"), "value");

        when(conceptMetadataRepository.findById(TEST_CONCEPT_ID)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(testModel);
        when(conceptEditor.editConcept(eq(TEST_CONCEPT_IRI), eq(editModel), any(Model.class), eq(TEST_GRAPH_NAME), any())).thenReturn(editResult);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class)))
                .thenThrow(new RuntimeException("DB error"));

        OntologyException exception = assertThrows(OntologyException.class,
                () -> conceptService.editConcept(TEST_CONCEPT_ID, editModel));

        assertTrue(exception.getMessage().contains("Nepodařilo se aktualizovat metadata pojmu"));
    }

    // ========== getConceptDetail Tests ==========
    //
    // Covers ConceptServiceImpl.getConceptDetail + checkPublishedConcept +
    // resolveRppReferences — the full /api/concept/{slug}/detail read workflow.
    // Each test mocks the orchestration boundary; the deviation/comparator/RPP
    // helpers have their own dedicated test suites.

    private static final String TEST_SLUG = "test-slug";

    @Test
    void getConceptDetail_slugNotFound_throws() {
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.empty());

        OntologyException ex = assertThrows(OntologyException.class,
                () -> conceptService.getConceptDetail(TEST_SLUG));
        assertTrue(ex.getMessage().contains(TEST_SLUG));
    }

    @Test
    void getConceptDetail_emptyGraph_throws() {
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

        OntologyException ex = assertThrows(OntologyException.class,
                () -> conceptService.getConceptDetail(TEST_SLUG));
        assertTrue(ex.getMessage().toLowerCase().contains("prázdn")
                || ex.getMessage().toLowerCase().contains("empty"));
    }

    @Test
    void getConceptDetail_extractorReturnsNull_throws() {
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(null);

        OntologyException ex = assertThrows(OntologyException.class,
                () -> conceptService.getConceptDetail(TEST_SLUG));
        assertTrue(ex.getMessage().contains(TEST_CONCEPT_IRI));
    }

    @Test
    void getConceptDetail_unpublished_skipsDeviationCheck() {
        // isPublished=false → checkPublishedConcept must short-circuit, NKD client never called.
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);

        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .iri(TEST_CONCEPT_IRI)
                .build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(detail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        GetConceptDto result = conceptService.getConceptDetail(TEST_SLUG);

        assertNotNull(result);
        assertEquals(detail, result.getConceptDetail());
        assertNull(result.getPublishedConceptDeviationModel());
        verify(referencedConceptsEnricher).enrich(detail);
        verify(nkdSparqlClient, never()).fetchPublishedConcept(anyString());
        verify(deviationComparator, never()).compareConceptDetails(any(), any(), any(), any());
    }

    @Test
    void getConceptDetail_published_delegatesDeviationToTheSharedService() {
        // Deviation is now the single-source-of-truth service's job (WorkingCopyDeviationService); concept
        // detail delegates on conceptIri and surfaces the result. The comparison logic itself is covered in
        // WorkingCopyDeviationServiceTest — here we only pin the delegation + surfacing.
        testConceptEntity.setIsPublished(true);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);

        OntologyDetailModel.ConceptDetailModel localDetail =
                OntologyDetailModel.ConceptDetailModel.builder().iri(TEST_CONCEPT_IRI).build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(localDetail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(true);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        PublishedConceptDeviationModel deviationResult = PublishedConceptDeviationModel.builder()
                .status(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION)
                .build();
        when(workingCopyDeviationService.deviationFor(TEST_CONCEPT_IRI)).thenReturn(deviationResult);

        GetConceptDto result = conceptService.getConceptDetail(TEST_SLUG);

        assertNotNull(result);
        assertEquals(deviationResult, result.getPublishedConceptDeviationModel());
        verify(workingCopyDeviationService).deviationFor(TEST_CONCEPT_IRI);
    }

    @Test
    void getConceptDetail_notPublished_skipsDeviationEntirely() {
        // A non-working-copy has no NKD twin — the deviation service must not even be consulted.
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);

        OntologyDetailModel.ConceptDetailModel localDetail =
                OntologyDetailModel.ConceptDetailModel.builder().iri(TEST_CONCEPT_IRI).build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(localDetail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        GetConceptDto result = conceptService.getConceptDetail(TEST_SLUG);

        assertNull(result.getPublishedConceptDeviationModel());
        verify(workingCopyDeviationService, never()).deviationFor(any());
    }

    @Test
    void getConceptDetail_attachesComments() {
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI))
                .thenReturn(OntologyDetailModel.ConceptDetailModel.builder().iri(TEST_CONCEPT_IRI).build());

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);

        CommentEntity comment = new CommentEntity();
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of(comment));
        when(conceptMetadataMapper.commentEntitiesToModels(List.of(comment)))
                .thenReturn(new ArrayList<>(List.of()));

        GetConceptDto result = conceptService.getConceptDetail(TEST_SLUG);

        assertNotNull(result.getConceptMetadata());
        verify(commentRepository).findByConceptMetadataId(TEST_CONCEPT_ID);
        verify(conceptMetadataMapper).commentEntitiesToModels(List.of(comment));
    }

    // ── resolveRppReferences branches ──────────────────────────────────

    @Test
    void getConceptDetail_resolvesAgendaWhenSet() {
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);

        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .iri(TEST_CONCEPT_IRI)
                .agenda("https://rpp.example/agenda/A1")
                .build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(detail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        RppAgenda agenda = mock(RppAgenda.class);
        when(rppSnapshotHolder.findAgendaByIri("https://rpp.example/agenda/A1")).thenReturn(Optional.of(agenda));

        conceptService.getConceptDetail(TEST_SLUG);

        verify(rppSnapshotHolder).findAgendaByIri("https://rpp.example/agenda/A1");
        verify(rppSnapshotHolder, never()).findIsvsByIri(anyString());
    }

    @Test
    void getConceptDetail_resolvesAisWhenSet() {
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);

        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .iri(TEST_CONCEPT_IRI)
                .ais("https://rpp.example/isvs/I1")
                .build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(detail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        RppIsvs isvs = mock(RppIsvs.class);
        when(rppSnapshotHolder.findIsvsByIri("https://rpp.example/isvs/I1")).thenReturn(Optional.of(isvs));

        conceptService.getConceptDetail(TEST_SLUG);

        verify(rppSnapshotHolder).findIsvsByIri("https://rpp.example/isvs/I1");
        verify(rppSnapshotHolder, never()).findAgendaByIri(anyString());
    }

    @Test
    void getConceptDetail_noRppReferences_skipsHolder() {
        testConceptEntity.setIsPublished(false);
        when(conceptMetadataRepository.findBySlug(TEST_SLUG)).thenReturn(Optional.of(testConceptEntity));
        Model rawModel = nonEmptyModel();
        when(jenaTDB2Repository.fetchGraph(TEST_GRAPH_NAME)).thenReturn(rawModel);
        // No agenda, no ais set on the detail.
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .iri(TEST_CONCEPT_IRI)
                .build();
        when(detailExtractor.extractConceptDetail(rawModel, TEST_CONCEPT_IRI)).thenReturn(detail);

        ConceptMetadataModel metadataDto = new ConceptMetadataModel();
        metadataDto.setIsPublished(false);
        metadataDto.setConceptIri(TEST_CONCEPT_IRI);
        when(conceptMetadataMapper.toDto(testConceptEntity)).thenReturn(metadataDto);
        when(commentRepository.findByConceptMetadataId(TEST_CONCEPT_ID)).thenReturn(List.of());

        conceptService.getConceptDetail(TEST_SLUG);

        verify(rppSnapshotHolder, never()).findAgendaByIri(anyString());
        verify(rppSnapshotHolder, never()).findIsvsByIri(anyString());
    }

    private static Model nonEmptyModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(TEST_CONCEPT_IRI),
                m.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
                "Test");
        return m;
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
                return ConceptType.TRIDA;
            }
        };
        model.setConceptType("TRIDA");

        NameModel nameModel = new NameModel();
        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("cs", TEST_CONCEPT_NAME);
        nameModel.setName(nameMap);
        model.setNameModel(nameModel);

        return model;
    }
}