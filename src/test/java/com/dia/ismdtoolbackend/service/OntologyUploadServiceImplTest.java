package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyUploadConflictingTypesException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.validation.ValidationReport;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceFixture;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OntologyUploadServiceImplTest {

    @Mock
    private MultipartFile multipartFile;

    private OntologyUploadServiceFixture fixture;
    private OntologyMetadataMapper ontologyMetadataMapper;
    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private ValidationClient validationClient;
    private ValidationReportRepository validationReportRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private PublishedResourceUtil publishedResourceUtil;

    private OntologyUploadServiceImpl ontologyUploadService;

    @BeforeEach
    void setUp() {
        fixture = new OntologyUploadServiceFixture();
        ontologyMetadataMapper = fixture.ontologyMetadataMapper();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        validationClient = fixture.validationClient();
        validationReportRepository = fixture.validationReportRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        publishedResourceUtil = fixture.publishedResourceUtil();
        ontologyUploadService = fixture.build();
    }

    @Test
    void requestAndSaveValidationReport_validatorReturnsReport_marksValidated() {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setId(7L);
        ontology.setGraphName("http://example.org/o");
        when(ontologyMetadataRepository.findByGraphName("http://example.org/o")).thenReturn(Optional.of(ontology));
        when(validationReportRepository.findByOntologyMetadataId(7L)).thenReturn(Optional.empty());

        ValidationReport report = mock(ValidationReport.class);
        when(report.getResults()).thenReturn(Collections.emptyList());
        when(validationClient.requestValidationLenient(anyString(), anyString())).thenReturn(Optional.of(report));

        fixture.runValidation("@prefix x: <x> .", "http://example.org/o");

        ArgumentCaptor<OntologyMetadataEntity> captor = ArgumentCaptor.forClass(OntologyMetadataEntity.class);
        verify(ontologyMetadataRepository).save(captor.capture());
        assertThat(captor.getValue().getLastValidationStatus()).isEqualTo(OntologyValidationStatus.VALIDATED);
        assertThat(captor.getValue().getLastValidationAt()).isNotNull();
    }

    @Test
    void requestAndSaveValidationReport_validatorUnavailable_marksSkipped() {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setId(7L);
        ontology.setGraphName("http://example.org/o");
        when(ontologyMetadataRepository.findByGraphName("http://example.org/o")).thenReturn(Optional.of(ontology));
        when(validationReportRepository.findByOntologyMetadataId(7L)).thenReturn(Optional.empty());
        // Lenient returns empty when the validator was unavailable — ingest proceeds, status flagged.
        when(validationClient.requestValidationLenient(anyString(), anyString())).thenReturn(Optional.empty());

        fixture.runValidation("@prefix x: <x> .", "http://example.org/o");

        ArgumentCaptor<OntologyMetadataEntity> captor = ArgumentCaptor.forClass(OntologyMetadataEntity.class);
        verify(ontologyMetadataRepository).save(captor.capture());
        assertThat(captor.getValue().getLastValidationStatus()).isEqualTo(OntologyValidationStatus.SKIPPED_UNAVAILABLE);
        assertThat(captor.getValue().getLastValidationAt()).isNotNull();
        verify(validationReportRepository, never()).save(any());
    }

    @Test
    void testDetermineRDFFormat_TTLExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.TURTLE);
    }

    @Test
    void testDetermineRDFFormat_TurtleExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.TURTLE);
    }

    @Test
    void testDetermineRDFFormat_JsonLdExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.jsonld");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.JSONLD);
    }

    @Test
    void testDetermineRDFFormat_JsonLdAlternativeExtension() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.json-ld");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.JSONLD);
    }

    @Test
    void testDetermineRDFFormat_ByContentType_Turtle() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("text/turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.TURTLE);
    }

    @Test
    void testDetermineRDFFormat_ByContentType_Json() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/json");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.JSONLD);
    }

    @Test
    void testDetermineRDFFormat_UnknownFormat() {
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/unknown");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isNull();
    }

    @Test
    void testDetermineRDFFormat_NoFilename() {
        when(multipartFile.getOriginalFilename()).thenReturn(null);
        when(multipartFile.getContentType()).thenReturn("text/turtle");
        
        Lang result = ontologyUploadService.determineRDFFormat(multipartFile);
        
        assertThat(result).isEqualTo(Lang.TURTLE);
    }

    @Test
    void testUploadFromFile_WithOntologyIRI() throws Exception {
        String userId = "user123";
        String ontologyIRI = "http://example.org/test-ontology";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setGraphName(ontologyIRI);
        expectedDto.setUser(new UserModel(userId));
        expectedDto.setId(1L);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));


        doNothing().when(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));

        OntologyMetadataModel result = ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        assertThat(result).isNotNull();
        assertThat(result.getGraphName()).isEqualTo(ontologyIRI);
        assertThat(result.getUser().getUserId()).isEqualTo(userId);

        verify(jenaTDB2Repository).putOntologyModel(eq(ontologyIRI), any(OntModel.class));
    }

    @Test
    void upload_runsValidationOnTheInjectedExecutor_notTheCommonPool() throws Exception {
        String ontologyIRI = "http://example.org/test-ontology";
        when(multipartFile.getBytes()).thenReturn(String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setId(1L);
        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        OntologyMetadataModel savedModel = new OntologyMetadataModel();
        savedModel.setId(1L);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(savedModel);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));

        AtomicReference<String> validationThread = new AtomicReference<>();
        CountDownLatch validated = new CountDownLatch(1);
        when(validationClient.requestValidationLenient(anyString(), eq(ontologyIRI))).thenAnswer(invocation -> {
            validationThread.set(Thread.currentThread().getName());
            validated.countDown();
            return Optional.empty();
        });

        ontologyUploadService.uploadFromFile(multipartFile, "user123", NormalizeMode.NORMALIZE_ALL, null);

        // The wiring passes a direct executor, so the validation runs on this thread.
        assertThat(validated.await(5, TimeUnit.SECONDS)).as("validation was never started").isTrue();
        assertThat(validationThread.get()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    void testUploadFromFile_NoDerivableOntologyIRI_throws() throws Exception {
        // RDF with no owl:Ontology / skos:ConceptScheme subject — graph name cannot be
        // derived from the data. Per the authority decision, we must FAIL rather than
        // fabricate a filename+UUID name.
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test-ontology.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);


        assertThatThrownBy(() ->
                ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(OntologyUploadException.class);

        // Nothing persisted — fail-fast before any TDB2/metadata write.
        // (verifyNoInteractions rather than never().putOntologyModel(..) — the latter
        // makes Mockito toString() the OntModel, which is already closed by the finally.)
        verifyNoInteractions(jenaTDB2Repository);
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_NoFilename_noDerivableIRI_throwsWithoutNPE() throws Exception {
        // Null filename + no derivable ontology IRI: must fail cleanly (no NPE from the
        // old filename-fallback path, which no longer exists).
        String userId = "user123";
        byte[] fileContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> .".getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn(null);
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getContentType()).thenReturn("text/turtle");


        assertThatThrownBy(() ->
                ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(OntologyUploadException.class);

        verifyNoInteractions(jenaTDB2Repository);
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_alienConcept_notSavedAsOwned() throws Exception {
        // The vocabulary IRI (graphName) is derived from owl:Ontology. The file embeds
        // TWO slovníky:pojem concepts: one OWNED (under graphName/pojem/...) and one
        // ALIEN (a legislative reference outside graphName's namespace). Only the owned
        // concept may get a Postgres ownership row; the alien must be skipped.
        String userId = "user123";
        String ontologyIRI = "https://slovník.gov.cz/a3791---registr";
        String ownedConcept = ontologyIRI + "/pojem/vysoká-škola";
        String alienConcept = "https://slovník.gov.cz/128-2000/pojem/obec";
        String pojem = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

        // owl:Class on both: a bare slovníky:pojem declares no recognizable role and would be
        // dropped by the concept-type guard before the ownership check under test is reached.
        String ttl = String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s>, owl:Class ."
                        + " <%s> a <%s>, owl:Class .",
                ontologyIRI, ownedConcept, pojem, alienConcept, pojem);

        when(multipartFile.getBytes()).thenReturn(ttl.getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);
        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));
        when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());
        when(conceptMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        List<String> savedIris = captor.getValue().stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .toList();

        assertThat(savedIris).as("Owned concept under graphName must be saved as owned").contains(ownedConcept);
        assertThat(savedIris)
                .as("Alien concept outside graphName's namespace must NOT be claimed as owned")
                .doesNotContain(alienConcept);
    }

    // --- Missing-inScheme decision flow ---

    private static final String DECISION_ONTOLOGY = "https://slovník.gov.cz/a3791---registr";
    private static final String DECISION_C1 = DECISION_ONTOLOGY + "/pojem/vysoká-škola";
    private static final String DECISION_C2 = DECISION_ONTOLOGY + "/pojem/fakulta";
    private static final String DECISION_POJEM =
            "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

    /** Two owned concepts, both missing skos:inScheme. */
    /**
     * Both concepts carry {@code owl:Class} alongside {@code slovníky:pojem}: a bare pojem
     * declares no role ISMD can act on and is dropped by the concept-type guard, which would
     * mask what these tests actually cover (the inScheme decision flow and ownership).
     */
    private byte[] twoMissingInSchemeTtl() {
        return String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s>, owl:Class ."
                        + " <%s> a <%s>, owl:Class .",
                DECISION_ONTOLOGY, DECISION_C1, DECISION_POJEM, DECISION_C2, DECISION_POJEM
        ).getBytes();
    }

    /** The vocabulary row saves; with {@code conceptsOwned}, concept slugs and IRIs are free as well. */
    private void stubPersistenceForDecisionFlow(String userId, boolean conceptsOwned) {
        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(DECISION_ONTOLOGY);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);
        OntologyMetadataModel dto = new OntologyMetadataModel();
        dto.setId(1L);
        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(dto);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));
        if (conceptsOwned) {
            when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());
            when(conceptMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        }
    }

    private List<String> capturedSavedConceptIris() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        return captor.getValue().stream().map(ConceptMetadataEntity::getConceptIri).toList();
    }

    @Test
    void testUploadFromFile_missingInScheme_noDecision_throwsWithList() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(twoMissingInSchemeTtl());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());

        InSchemeDecisionRequiredException ex = assertThatExceptionOfType(InSchemeDecisionRequiredException.class)
                .isThrownBy(() ->
                ontologyUploadService.uploadFromFile(multipartFile, userId, null, null))
                .actual();

        assertThat(ex.getGraphName()).isEqualTo(DECISION_ONTOLOGY);
        List<String> missingIris = ex.getConceptsMissingInScheme().stream()
                .map(c -> c.conceptIri()).toList();
        assertThat(missingIris.contains(DECISION_C1) && missingIris.contains(DECISION_C2))
                .as("Both missing concepts must be reported")
                .isTrue();
        ex.getConceptsMissingInScheme().forEach(c ->
                assertThat(c.proposedInScheme())
                        .as("Proposed inScheme must be the graphName")
                        .isEqualTo(DECISION_ONTOLOGY));

        // Nothing persisted.
        verifyNoInteractions(jenaTDB2Repository);
        verify(ontologyMetadataRepository, never()).save(any());
        verify(conceptMetadataRepository, never()).saveAll(any());
    }

    @Test
    void testUploadFromFile_excludeAll_noOwnershipRows() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(twoMissingInSchemeTtl());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, false);

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.EXCLUDE_ALL, null);

        // Both concepts excluded → no inScheme → no ownership rows, so no concept row is written.
        verify(conceptMetadataRepository, never()).saveAll(anyList());
        // TDB2 still persisted (triples kept as context).
        verify(jenaTDB2Repository).putOntologyModel(eq(DECISION_ONTOLOGY), any(OntModel.class));
    }

    @Test
    void testUploadFromFile_perConcept_onlyChosenOwned() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(twoMissingInSchemeTtl());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, true);

        ontologyUploadService.uploadFromFile(
                multipartFile, userId, NormalizeMode.PER_CONCEPT, List.of(DECISION_C1));

        List<String> saved = capturedSavedConceptIris();
        assertThat(saved).as("Chosen concept must be normalized and owned").contains(DECISION_C1);
        assertThat(saved).as("Unchosen concept must be excluded (no ownership row)").doesNotContain(DECISION_C2);
    }

    @Test
    void testUploadFromFile_normalizeAll_bothOwned() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(twoMissingInSchemeTtl());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, true);

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        List<String> saved = capturedSavedConceptIris();
        assertThat(saved.contains(DECISION_C1) && saved.contains(DECISION_C2))
                .as("NORMALIZE_ALL must own both previously-missing concepts")
                .isTrue();
    }

    @Test
    void testUploadFromFile_excludeAll_prunesUnreferencedFromTdb2_keepsReferenced() throws Exception {
        // EXCLUDE_ALL: neither concept gets inScheme / a PG row. C1 references C2 via
        // rdfs:subClassOf. The prune must drop C1 (unreferenced) from the model written
        // to TDB2, but KEEP C2 (still referenced by C1) so no dangling edge is left —
        // closing the "excluded concept leaks into TDB2" gap without losing context.
        String userId = "user123";
        byte[] ttl = String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s> ; rdfs:subClassOf <%s> ."
                        + " <%s> a <%s> .",
                DECISION_ONTOLOGY, DECISION_C1, DECISION_POJEM, DECISION_C2, DECISION_C2, DECISION_POJEM
        ).getBytes();

        when(multipartFile.getBytes()).thenReturn(ttl);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, false);

        // Snapshot the model contents DURING the call — the service closes finalModel in
        // its finally block, so post-call inspection would hit a closed model.
        boolean[] c1Present = {true};
        boolean[] c2Present = {false};
        boolean[] edgeFromC1ToC2 = {false};
        doAnswer(invocation -> {
            OntModel model = invocation.getArgument(1);
            c1Present[0] = model.containsResource(model.createResource(DECISION_C1))
                    && model.listStatements(model.createResource(DECISION_C1), null, (org.apache.jena.rdf.model.RDFNode) null).hasNext();
            c2Present[0] = model.listStatements(model.createResource(DECISION_C2), null, (org.apache.jena.rdf.model.RDFNode) null).hasNext();
            edgeFromC1ToC2[0] = model.contains(
                    model.createResource(DECISION_C1),
                    model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf"),
                    model.createResource(DECISION_C2));
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(eq(DECISION_ONTOLOGY), any(OntModel.class));

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.EXCLUDE_ALL, null);

        assertThat(c1Present[0])
                .as("Unreferenced excluded concept C1 must be pruned from the model written to TDB2")
                .isFalse();
        assertThat(c2Present[0])
                .as("Excluded concept C2 is referenced by C1 and must be KEPT as inert context")
                .isTrue();
        assertThat(edgeFromC1ToC2[0])
                .as("C1's body (incl. its edge to C2) is gone because C1 itself was pruned")
                .isFalse();
    }

    // --- Concept-type guard -------------------------------------------------
    //
    // Role resolution must be total: every persisted row carries a real type, and a
    // concept whose type cannot be resolved is dropped rather than saved as a null-typed
    // row (which downstream consumers read as "absent" — a VZTAH imported that way once
    // vanished from the diagram projector).

    /** An OFN concept carrying the role tag but no matching OWL type — the null-type case. */
    private byte[] ofnRoleTaggedTtl(String roleTag) {
        return String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s>, <%s> ; skos:inScheme <%s> .",
                DECISION_ONTOLOGY, DECISION_C1, DECISION_POJEM, roleTag, DECISION_ONTOLOGY
        ).getBytes();
    }

    private List<ConceptMetadataEntity> capturedSavedConcepts() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void testUploadFromFile_ofnRoleTagVztah_resolvesToVztahWithoutOwlType() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(
                ofnRoleTaggedTtl("https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/vztah"));
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, true);

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        List<ConceptMetadataEntity> saved = capturedSavedConcepts();
        assertThat(saved).as("The role-tagged concept must be owned").hasSize(1);
        assertThat(saved.get(0).getConceptType())
                .as("An OFN vztah role tag must resolve even with no owl:ObjectProperty")
                .isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void testUploadFromFile_ofnRoleTagVlastnost_resolvesToVlastnostWithoutOwlType() throws Exception {
        String userId = "user123";
        when(multipartFile.getBytes()).thenReturn(
                ofnRoleTaggedTtl("https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/vlastnost"));
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, true);

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        List<ConceptMetadataEntity> saved = capturedSavedConcepts();
        assertThat(saved).as("The role-tagged concept must be owned").hasSize(1);
        assertThat(saved.get(0).getConceptType())
                .as("An OFN vlastnost role tag must resolve even with no owl:DatatypeProperty")
                .isEqualTo(ConceptType.VLASTNOST);
    }

    @Test
    void testUploadFromFile_bothObjectAndDatatypeProperty_failsTheWholeUpload() throws Exception {
        String userId = "user123";
        // Contradictory input: C1 is both a vlastnost and a vztah. The upload fails as a whole and
        // names C1; the well-typed C2 in the same file is not imported either.
        String ttl = String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s>, owl:ObjectProperty, owl:DatatypeProperty ;"
                        + " skos:inScheme <%s> ."
                        + " <%s> a <%s>, owl:Class ; skos:inScheme <%s> .",
                DECISION_ONTOLOGY,
                DECISION_C1, DECISION_POJEM, DECISION_ONTOLOGY,
                DECISION_C2, DECISION_POJEM, DECISION_ONTOLOGY);
        when(multipartFile.getBytes()).thenReturn(ttl.getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);

        OntologyUploadConflictingTypesException ex = assertThatExceptionOfType(OntologyUploadConflictingTypesException.class)
                .isThrownBy(() -> ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .actual();

        assertThat(ex.getConceptIris()).isEqualTo(List.of(DECISION_C1));
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any(OntModel.class));
        verify(ontologyMetadataRepository, never()).save(any());
        verify(conceptMetadataRepository, never()).saveAll(any());
    }

    @Test
    void resolveRole_reasonsNameTheActualDefect() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();

        org.apache.jena.rdf.model.Resource bare = model.createResource(DECISION_C1);
        bare.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        assertThat(OntologyUploadServiceFixture.roleRejectionReason(bare))
                .as("A concept declaring no role must say so plainly")
                .isEqualTo("no recognized type");

        org.apache.jena.rdf.model.Resource both = model.createResource(DECISION_C2);
        both.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        both.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL2.ObjectProperty);
        both.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL2.DatatypeProperty);
        assertThat(OntologyUploadServiceFixture.roleRejectionReason(both))
                .as("A contradiction must name both roles it declared, so the file's author can fix it")
                .isEqualTo("conflicting types: VLASTNOST + VZTAH");
    }

    /**
     * The contradiction must be caught in the OFN role-tag form too, not just the OWL one —
     * otherwise the guard is sidestepped by whichever vocabulary the file happens to use.
     */
    @Test
    void resolveConceptType_bothOfnRoleTags_isRejected() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();
        org.apache.jena.rdf.model.Resource both = model.createResource(DECISION_C1);
        both.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        both.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(
                "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/vlastnost"));
        both.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(
                "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/vztah"));

        ConceptType resolved = OntologyUploadServiceFixture.resolveConceptType(both);

        assertThat(resolved)
                .as("A concept carrying both the vlastnost and vztah OFN role tags "
                + "declares two roles and must be dropped")
                .isNull();
    }

    /**
     * The OFN role tag and its OWL equivalent describe the SAME role — that is agreement,
     * not a contradiction, and must not trip the multi-role guard.
     */
    @Test
    void resolveConceptType_ofnTagAndMatchingOwlType_isNotAContradiction() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();
        org.apache.jena.rdf.model.Resource rel = model.createResource(DECISION_C1);
        rel.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        rel.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(
                "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/vztah"));
        rel.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL2.ObjectProperty);

        ConceptType resolved = OntologyUploadServiceFixture.resolveConceptType(rel);

        assertThat(resolved)
                .as("slovníky:vztah + owl:ObjectProperty is one role stated twice — the normal shape "
                        + "after OFNTypeNormalizer runs, and it must resolve normally")
                .isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void testUploadFromFile_unrecognizedType_conceptDroppedNotPersistedAsNull() throws Exception {
        String userId = "user123";
        // A concept already carrying skos:inScheme (so the decision gate leaves it alone) but no
        // class/property/relationship signal. It reaches extraction untyped only because
        // OFNTypeNormalizer.ensureConceptsHaveSkosType stamps skos:Concept on slovníky:pojem
        // resources — this one is NOT a slovníky:pojem, so nothing types it.
        String ttl = String.format(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                        + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
                        + " <%s> a owl:Ontology ."
                        + " <%s> a <%s> ; skos:inScheme <%s> ."
                        + " <%s> a <%s>, owl:Class ; skos:inScheme <%s> .",
                DECISION_ONTOLOGY,
                DECISION_C1, DECISION_POJEM, DECISION_ONTOLOGY,
                DECISION_C2, DECISION_POJEM, DECISION_ONTOLOGY);
        when(multipartFile.getBytes()).thenReturn(ttl.getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.isEmpty()).thenReturn(false);
        stubPersistenceForDecisionFlow(userId, true);

        ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null);

        List<ConceptMetadataEntity> saved = capturedSavedConcepts();
        assertThat(saved.stream().noneMatch(c -> c.getConceptType() == null))
                .as("No concept may be persisted with a null concept_type")
                .isTrue();
        assertThat(saved.stream().anyMatch(c -> DECISION_C2.equals(c.getConceptIri())))
                .as("The well-typed concept in the same upload must still be imported (warn-and-drop, "
                        + "not reject-the-whole-upload)")
                .isTrue();
    }

    /**
     * The guard's own unit: extraction must never emit a null-typed row even when the
     * normalizer has not typed the resource. Drives the role resolver directly because
     * the upload normalizer stamps {@code skos:Concept} on every {@code slovníky:pojem},
     * which masks the untyped case in a full-pipeline test.
     */
    @Test
    void resolveConceptType_noRecognizableType_returnsNullSoCallerDrops() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();
        org.apache.jena.rdf.model.Resource bare = model.createResource(DECISION_C1);
        bare.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));

        ConceptType resolved = OntologyUploadServiceFixture.resolveConceptType(bare);

        assertThat(resolved)
                .as("A concept with no class/property/relationship signal must resolve to "
                + "null so extractAndSaveConceptMetadata drops it instead of saving a null type")
                .isNull();
    }

    /**
     * skos:Concept is the base marker (OFN koncept), not a role. OFNTypeNormalizer stamps it on
     * every slovníky:pojem, so treating it as TRIDA would silently type every otherwise-untyped
     * concept as a class — and make the untyped guard unreachable.
     */
    @Test
    void resolveConceptType_skosConceptAlone_isNotATrida() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();
        org.apache.jena.rdf.model.Resource base = model.createResource(DECISION_C1);
        base.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        base.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.SKOS.Concept);

        ConceptType resolved = OntologyUploadServiceFixture.resolveConceptType(base);

        assertThat(resolved)
                .as("skos:Concept is the base type every concept carries — it must not "
                + "claim the TRIDA role on its own")
                .isNull();
    }

    @Test
    void resolveConceptType_owlClass_isTrida() {
        OntModel model = org.apache.jena.rdf.model.ModelFactory.createOntologyModel();
        org.apache.jena.rdf.model.Resource cls = model.createResource(DECISION_C2);
        cls.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(DECISION_POJEM));
        cls.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.SKOS.Concept);
        cls.addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL2.Class);

        ConceptType resolved = OntologyUploadServiceFixture.resolveConceptType(cls);

        assertThat(resolved)
                .as("owl:Class is the real class signal and must still resolve to TRIDA")
                .isEqualTo(ConceptType.TRIDA);
    }

    @Test
    void testUploadFromFile_IOExceptionHandling() throws Exception {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(multipartFile.getBytes()).thenThrow(new RuntimeException("File read error"));

        // The implementation catches IOException and wraps it, but RuntimeException propagates directly
        assertThatThrownBy(() ->
            ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(RuntimeException.class);

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_EmptyFile() {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(true);

        assertThatThrownBy(() ->
            ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(EmptyFileException.class);

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void testUploadFromFile_UnsupportedFormat() {
        String userId = "user123";

        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("test.unknown");
        when(multipartFile.getContentType()).thenReturn("application/unknown");

        assertThatThrownBy(() ->
                ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(UnsupportedRdfFormatException.class);

        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void upload_conceptsPublishedInNkd_areSavedAsPublished() throws IOException {
        String graphName = "http://example.org/test-ontology";
        String published = graphName + "/pojem/published";
        String draft = graphName + "/pojem/draft";
        when(multipartFile.getBytes()).thenReturn(("""
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                @prefix ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> .
                <%1$s> a owl:Ontology, skos:ConceptScheme .
                <%2$s> a ofn:pojem, owl:Class ; skos:inScheme <%1$s> .
                <%3$s> a ofn:pojem, owl:Class ; skos:inScheme <%1$s> .
                """.formatted(graphName, published, draft)).getBytes());
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");
        when(publishedResourceUtil.checkPublishedResourcesInNKD(any(), any())).thenReturn(List.of(published));

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(graphName);
        savedEntity.setId(1L);
        OntologyMetadataModel savedModel = new OntologyMetadataModel();
        savedModel.setId(1L);
        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(savedModel);
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(savedEntity));

        ontologyUploadService.uploadFromFile(multipartFile, "user123", NormalizeMode.NORMALIZE_ALL, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(saved.capture());
        Map<String, Boolean> publishedByIri = new HashMap<>();
        saved.getValue().forEach(c -> publishedByIri.put(c.getConceptIri(), c.getIsPublished()));
        assertThat(publishedByIri).isEqualTo(Map.of(published, true, draft, false));
    }

    @Test
    void testUploadFromFile_RollbackOnTDB2Failure() throws IOException {
        String ontologyIRI = "http://example.org/test-ontology";
        String userId = "user123";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());


        // Simulate TDB2 failure
        doThrow(new RuntimeException("TDB2 connection failed"))
            .when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        // Expect exception to be thrown
        assertThatThrownBy(() ->
            ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(OntologyUploadException.class);

        // TDB2 save fails before any metadata is created — no cleanup needed
        verify(ontologyMetadataRepository, never()).save(any());
        verify(ontologyMetadataRepository, never()).deleteById(anyLong());
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    }

    @Test
    void testUploadFromFile_RollbackOnConceptMetadataExtractionFailure() throws IOException {
        String ontologyIRI = "http://example.org/test-ontology";
        String userId = "user123";
        byte[] fileContent = String.format("@prefix owl: <http://www.w3.org/2002/07/owl#> . <%s> a owl:Ontology .", ontologyIRI).getBytes();

        when(multipartFile.getBytes()).thenReturn(fileContent);
        when(multipartFile.getOriginalFilename()).thenReturn("test.ttl");

        OntologyMetadataEntity savedEntity = new OntologyMetadataEntity();
        savedEntity.setGraphName(ontologyIRI);
        savedEntity.setUserId(userId);
        savedEntity.setId(1L);

        OntologyMetadataModel expectedDto = new OntologyMetadataModel();
        expectedDto.setId(1L);

        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(savedEntity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(savedEntity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenReturn(expectedDto);


        doNothing().when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        // Simulate failure when finding metadata for concept extraction
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.empty());

        // Expect exception to be thrown
        assertThatThrownBy(() ->
            ontologyUploadService.uploadFromFile(multipartFile, userId, NormalizeMode.NORMALIZE_ALL, null))
                .isInstanceOf(Exception.class);

        // PostgreSQL rollback is handled by @Transactional — no manual deleteById
        verify(ontologyMetadataRepository, never()).deleteById(anyLong());
        // TDB2 graph should be cleaned up since it was saved before the failure
        verify(jenaTDB2Repository).deleteGraph(ontologyIRI);
    }
}