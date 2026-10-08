package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyUploadIriCollisionException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import com.dia.ismdtoolbackend.service.impl.UploadConceptGate;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload must not import a concept in a state create/edit would reject, and must hand a
 * vocabulary the same IRIs create/edit would.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyUploadValidationParityTest {

    private static final String PREFIXES = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
            + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
            + " @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."
            + " @prefix ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> ."
            + " @prefix l111: <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/> . ";
    private static final String VOCABULARY = "https://slovník.gov.cz/agendový/999";
    private static final String GOOD = VOCABULARY + "/pojem/osoba";
    private static final String BAD = VOCABULARY + "/pojem/student";
    private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String NKD_CLASS = "https://slovník.gov.cz/generický/veřejný-sektor/pojem/adresa";

    @Mock private OntologyMetadataMapper ontologyMetadataMapper;
    @Mock private OntologyMetadataRepository ontologyMetadataRepository;
    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private ValidationClient validationClient;
    @Mock private ValidationReportRepository validationReportRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private PublishedResourceUtil publishedResourceUtil;
    @Mock private MultipartFile file;

    private OntologyUploadServiceImpl service;
    /** Turtle of the model handed to TDB2, captured during the call (the service closes the model). */
    private String storedTurtle;
    private String storedGraphName;

    @BeforeEach
    void setUp() {
        service = new OntologyUploadServiceImpl(ontologyMetadataMapper, ontologyMetadataRepository,
                conceptMetadataRepository, validationClient, validationReportRepository, jenaTDB2Repository,
                publishedResourceUtil,
                new UploadConceptGate(new OntologyDetailExtractor(conceptMetadataRepository),
                        new NkdLinkDetector(), conceptMetadataRepository));
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "maxFileSizeConfig", "10MB");
        ReflectionTestUtils.setField(service, "rdfParsingTimeoutSeconds", 60);

        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setId(1L);
        when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(ontologyMetadataMapper.toEntity(any(OntologyMetadataModel.class))).thenReturn(entity);
        when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(entity);
        when(ontologyMetadataMapper.toDto(any(OntologyMetadataEntity.class))).thenAnswer(i -> {
            OntologyMetadataModel dto = new OntologyMetadataModel();
            dto.setId(1L);
            return dto;
        });
        when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());
        when(conceptMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            storedGraphName = invocation.getArgument(0);
            StringWriter out = new StringWriter();
            invocation.<OntModel>getArgument(1).write(out, "N-TRIPLE");
            storedTurtle = out.toString();
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));
        when(file.getOriginalFilename()).thenReturn("slovnik.ttl");
        when(file.isEmpty()).thenReturn(false);
    }

    private OntologyMetadataModel upload(String turtle) throws Exception {
        when(file.getBytes()).thenReturn((PREFIXES + turtle).getBytes(StandardCharsets.UTF_8));
        return service.uploadFromFile(file, "user", null, null);
    }

    private static String vocabulary() {
        return "<" + VOCABULARY + "> a owl:Ontology, skos:ConceptScheme . ";
    }

    private static String concept(String iri, String type, String body) {
        return "<" + iri + "> a ofn:pojem, " + type + " ; skos:inScheme <" + VOCABULARY + "> " + body + " . ";
    }

    private List<String> savedConceptIris() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        return captor.getValue().stream().map(ConceptMetadataEntity::getConceptIri).toList();
    }

    private static List<String> rejectedIris(OntologyMetadataModel uploaded) {
        return uploaded.getRejectedConcepts().stream().map(RejectedConceptDto::iri).toList();
    }

    // --- Trailing-slash IRIs ------------------------------------------------

    @Test
    void trailingSlashIrisAreCorrectedBeforeAnythingIsStored() throws Exception {
        OntologyMetadataModel uploaded = upload("<" + VOCABULARY + "/> a owl:Ontology, skos:ConceptScheme . "
                + "<" + GOOD + "/> a ofn:pojem, owl:Class ; skos:inScheme <" + VOCABULARY + "/> ;"
                + " ofn:definující-ustanovení <" + ELI + "/> .");

        assertEquals(VOCABULARY, storedGraphName);
        assertEquals(List.of(GOOD), savedConceptIris());
        assertFalse(storedTurtle.contains(GOOD + "/>"), storedTurtle);
        assertFalse(storedTurtle.contains(ELI + "/>"), storedTurtle);
        assertEquals(List.of(ELI + "/", VOCABULARY + "/", GOOD + "/"), uploaded.getCorrectedIris());
        assertTrue(uploaded.getRejectedConcepts().isEmpty());
        verify(ontologyMetadataRepository).findBySlug("999");
    }

    @Test
    void fileHoldingBothFormsOfAnIriIsRejectedWhole() {
        assertThrows(OntologyUploadIriCollisionException.class, () -> upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(GOOD + "/", "owl:Class", "")));

        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any(OntModel.class));
        verify(conceptMetadataRepository, never()).saveAll(anyList());
    }

    // --- Create/edit rules --------------------------------------------------

    @Test
    void conceptBreakingACreateEditRuleIsNotImportedAndIsReported() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; ofn:definující-ustanovení <" + ELI + ">")
                + concept(BAD, "owl:Class", "; ofn:definující-ustanovení <http://example.org/not-eli>"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertEquals(List.of(BAD), rejectedIris(uploaded));
        String reason = uploaded.getRejectedConcepts().get(0).reason();
        assertTrue(reason.contains("definingLegalSource") && reason.contains("http://example.org/not-eli"), reason);
        assertFalse(storedTurtle.contains("<" + BAD + ">"), "unreferenced rejected concept must not reach TDB2");
    }

    @Test
    void rejectedConceptStillReferencedIsKeptWithoutOwnership() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; rdfs:subClassOf <" + BAD + ">")
                + concept(BAD, "owl:Class", "; skos:exactMatch \"not an iri\""));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertEquals(List.of(BAD), rejectedIris(uploaded));
        assertTrue(storedTurtle.contains("<" + BAD + "> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"));
        assertFalse(storedTurtle.contains("<" + BAD + "> <http://www.w3.org/2004/02/skos/core#inScheme>"));
    }

    @Test
    void publicConceptWithAPrivacyProvisionIsRejected() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class, l111:veřejný-údaj",
                "; <" + VOCABULARY + "/ustanovení-dokládající-neveřejnost-údaje> <" + ELI + ">"));

        assertEquals(List.of(BAD), rejectedIris(uploaded));
        assertTrue(uploaded.getRejectedConcepts().get(0).reason().contains("nemůže být současně"));
    }

    private static final String DIGITAL_OBJECT =
            "<https://slovník.gov.cz/generický/digitální-objekty/pojem/digitální-objekt>";

    /** The validator writes a title-only digital object for a free-text source. */
    @Test
    void nonLegalSourceWithoutAUrlIsImported() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; <https://slovník.gov.cz/definující-nelegislativní-zdroj>"
                + " [ a " + DIGITAL_OBJECT + " ; <http://purl.org/dc/terms/title> \"Směrnice EU\"@cs ]"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertTrue(uploaded.getRejectedConcepts().isEmpty(), uploaded.getRejectedConcepts().toString());
        assertTrue(storedTurtle.contains("Směrnice EU"));
    }

    @Test
    void nonLegalSourceWithAnInvalidUrlIsRejected() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class", "; <https://slovník.gov.cz/definující-nelegislativní-zdroj>"
                + " [ a " + DIGITAL_OBJECT + " ; <http://schema.org/url> \"not a url\" ]"));

        assertEquals(List.of(BAD), rejectedIris(uploaded));
        assertTrue(uploaded.getRejectedConcepts().get(0).reason().contains("definingNonLegalSource"));
    }

    @Test
    void everyViolationOfOneConceptIsListedInOneEntry() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class", "; ofn:definující-ustanovení <http://example.org/not-eli> ;"
                + " ofn:má-typ-obsahu-údaje <https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/neznámé>"));

        assertEquals(1, uploaded.getRejectedConcepts().size());
        String reason = uploaded.getRejectedConcepts().get(0).reason();
        assertTrue(reason.contains("definingLegalSource") && reason.contains("typ obsahu"), reason);
    }

    /** A concept the create path writes must pass on upload: the two paths share one rule set. */
    @Test
    void conceptWrittenByTheCreatePathImportsUntouched() throws Exception {
        ClassConceptModel model = new ClassConceptModel();
        model.setConceptType("TRIDA");
        model.setType("objekt");
        model.setOntologyGraphName(VOCABULARY);
        model.setNamespace(VOCABULARY);
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Osoba"));
        model.setNameModel(name);
        model.setDefiningLegalSource(List.of(ELI));
        model.setRelatedLegalSource(List.of(ELI));
        model.setPrivacyProvisions(List.of(ELI));
        model.setIsPublic(false);
        model.setExactMatch(List.of("https://example.org/pojem/osoba"));
        model.setAgendaCode("A104");
        model.setAgendaSystemCode("123");
        model.setSharingMethod(List.of("veřejně přístupné", "zpřístupňované pro výkon agendy"));
        model.setAcquisitionMethod("základních registrů");
        model.setContentType("identifikační");
        DigitalObjectModel document = new DigitalObjectModel();
        document.setUrl("https://example.org/dokument");
        model.setDefiningNonLegalSource(List.of(document));
        model.setCodeListIri("https://example.org/ciselnik");
        model.setCodeListDataset("https://data.gov.cz/zdroj/datové-sady/00000000/abc");

        Resource created = new ConceptCreator().createSingleConcept(model);
        Model graph = ModelFactory.createDefaultModel().add(created.getModel());
        graph.createResource(VOCABULARY).addProperty(RDF.type, OWL2.Ontology).addProperty(RDF.type, SKOS.ConceptScheme);
        created.inModel(graph).addProperty(RDF.type, OWL2.Class);
        StringWriter turtle = new StringWriter();
        graph.write(turtle, "TTL");
        when(file.getBytes()).thenReturn(turtle.toString().getBytes(StandardCharsets.UTF_8));

        OntologyMetadataModel uploaded = service.uploadFromFile(file, "user", null, null);

        assertTrue(uploaded.getRejectedConcepts().isEmpty(), uploaded.getRejectedConcepts().toString());
        assertEquals(List.of(created.getURI()), savedConceptIris());
    }

    // --- Domain/range pointing at a concept published in NKD ------------------

    private void nkdReportsPublished(String... iris) {
        when(publishedResourceUtil.checkPublishedResourcesInNKD(any(), any())).thenReturn(List.of(iris));
    }

    @Test
    void domainPointingAtAPublishedNkdConceptIsRejected() throws Exception {
        nkdReportsPublished(NKD_CLASS);

        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(BAD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertEquals(List.of(BAD), rejectedIris(uploaded));
        assertTrue(uploaded.getRejectedConcepts().get(0).reason().contains(NKD_CLASS));
    }

    @Test
    void domainRangeTargetsRideTheSingleNkdQuery() throws Exception {
        upload(vocabulary() + concept(BAD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> extra = ArgumentCaptor.forClass(Collection.class);
        verify(publishedResourceUtil).checkPublishedResourcesInNKD(any(), extra.capture());
        assertTrue(extra.getValue().contains(NKD_CLASS));
        verify(publishedResourceUtil, never()).checkPublishedResourcesInNKD(any());
    }

    @Test
    void domainPointingAtAnUnpublishedForeignConceptIsImported() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertTrue(uploaded.getRejectedConcepts().isEmpty());
    }

    @Test
    void relationshipRangePointingAtAPublishedNkdConceptIsImported() throws Exception {
        nkdReportsPublished(NKD_CLASS);

        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:ObjectProperty", "; rdfs:range <" + NKD_CLASS + ">"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertTrue(uploaded.getRejectedConcepts().isEmpty());
    }

    @Test
    void domainPointingAtALocallyOwnedWorkingCopyIsImported() throws Exception {
        nkdReportsPublished(NKD_CLASS);
        ConceptMetadataEntity workingCopy = new ConceptMetadataEntity();
        workingCopy.setConceptIri(NKD_CLASS);
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of(workingCopy));

        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertTrue(uploaded.getRejectedConcepts().isEmpty());
    }

    // --- Role guard -----------------------------------------------------------

    @Test
    void conceptWithoutASingleRoleIsReportedAndLeavesNoOwnedTriples() throws Exception {
        OntologyMetadataModel uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(BAD, "owl:DatatypeProperty, owl:ObjectProperty", ""));

        assertEquals(List.of(GOOD), savedConceptIris());
        assertEquals(List.of(BAD), rejectedIris(uploaded));
        assertFalse(storedTurtle.contains("<" + BAD + ">"), storedTurtle);
    }
}
