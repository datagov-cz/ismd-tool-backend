package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyUploadConflictingTypesException;
import com.dia.ismdtoolbackend.exception.OntologyUploadIriCollisionException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.controller.dto.OntologyUploadResultDto;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceFixture;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceImpl;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload must not import a concept in a state create/edit would reject, and must hand a
 * vocabulary the same IRIs create/edit would.
 */
@ExtendWith(MockitoExtension.class)
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

    @Mock private MultipartFile file;

    private OntologyMetadataMapper ontologyMetadataMapper;
    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private ValidationClient validationClient;
    private JenaTDB2Repository jenaTDB2Repository;
    private PublishedResourceUtil publishedResourceUtil;

    private OntologyUploadServiceImpl service;
    /** Turtle of the model handed to TDB2, captured during the call (the service closes the model). */
    private String storedTurtle;
    private String storedGraphName;

    @BeforeEach
    void setUp() {
        OntologyUploadServiceFixture fixture = new OntologyUploadServiceFixture();
        ontologyMetadataMapper = fixture.ontologyMetadataMapper();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        validationClient = fixture.validationClient();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        publishedResourceUtil = fixture.publishedResourceUtil();
        service = fixture.build();

        // Working baseline: storage and persistence succeed. Lenient because the tests that reject the
        // whole upload stop before reaching them, each at its own step.
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setId(1L);
        lenient().when(ontologyMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        lenient().when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(entity);
        lenient().when(ontologyMetadataRepository.findById(1L)).thenReturn(Optional.of(entity));
        lenient().when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());
        lenient().when(conceptMetadataRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        lenient().doAnswer(invocation -> {
            storedGraphName = invocation.getArgument(0);
            StringWriter out = new StringWriter();
            invocation.<OntModel>getArgument(1).write(out, "N-TRIPLE");
            storedTurtle = out.toString();
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));
        when(file.getOriginalFilename()).thenReturn("slovnik.ttl");
        when(file.isEmpty()).thenReturn(false);
    }

    private OntologyUploadResultDto upload(String turtle) throws Exception {
        when(file.getBytes()).thenReturn((PREFIXES + turtle).getBytes(StandardCharsets.UTF_8));
        return service.uploadFromFile(file, "user", null, null);
    }

    private static String vocabulary() {
        return "<" + VOCABULARY + "> a owl:Ontology, skos:ConceptScheme, ofn:slovník . ";
    }

    private static String concept(String iri, String type, String body) {
        return "<" + iri + "> a ofn:pojem, skos:Concept, " + type + " ; skos:inScheme <" + VOCABULARY + "> " + body + " . ";
    }

    private List<String> savedConceptIris() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConceptMetadataEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).saveAll(captor.capture());
        return captor.getValue().stream().map(ConceptMetadataEntity::getConceptIri).toList();
    }

    private static List<String> rejectedIris(OntologyUploadResultDto uploaded) {
        return uploaded.rejectedConcepts().stream().map(RejectedConceptDto::iri).toList();
    }

    // --- OFN gate -----------------------------------------------------------

    @Test
    void vocabularyThatIsNotOfnItself_rejectsTheWholeUpload_storingNothing() {
        assertThatThrownBy(() -> upload("<" + VOCABULARY + "> a owl:Ontology, skos:ConceptScheme . "
                + concept(GOOD, "owl:Class", "")))
                .isInstanceOfSatisfying(OfnConformanceException.class, e -> {
                    assertThat(e.getContext()).isEqualTo(OfnConformanceException.Context.UPLOAD);
                    assertThat(e.getIssues()).allSatisfy(issue -> assertThat(issue.conceptIri()).isNull());
                });
        verifyNoInteractions(jenaTDB2Repository);
    }

    @Test
    void conceptWithANonOfnValue_isRejected_withTheFieldAndReason_othersImported() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(BAD, "owl:Class", "; <https://slovník.gov.cz/agendový/999/inTezaurus> \"true\""));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(uploaded.rejectedConcepts().get(0).reason())
                .startsWith("OFN: ")
                .contains("není definována v kontextu OFN");
    }

    @Test
    void incompleteConcept_isImportedAsADraft() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:ObjectProperty", ""));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(uploaded.rejectedConcepts()).isEmpty();
    }

    // --- Trailing-slash IRIs ------------------------------------------------

    @Test
    void trailingSlashIrisAreCorrectedBeforeAnythingIsStored() throws Exception {
        OntologyUploadResultDto uploaded = upload("<" + VOCABULARY + "/> a owl:Ontology, skos:ConceptScheme, ofn:slovník . "
                + "<" + GOOD + "/> a ofn:pojem, skos:Concept, owl:Class ; skos:inScheme <" + VOCABULARY + "/> ;"
                + " ofn:definující-ustanovení <" + ELI + "/> .");

        assertThat(storedGraphName).isEqualTo(VOCABULARY);
        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(storedTurtle).as(storedTurtle).doesNotContain(GOOD + "/>");
        assertThat(storedTurtle).as(storedTurtle).doesNotContain(ELI + "/>");
        assertThat(uploaded.correctedIris()).isEqualTo(List.of(ELI + "/", VOCABULARY + "/", GOOD + "/"));
        assertThat(uploaded.rejectedConcepts()).isEmpty();
        verify(ontologyMetadataRepository).findBySlug("999");
    }

    @Test
    void fileHoldingBothFormsOfAnIriIsRejectedWhole() {
        assertThatThrownBy(() -> upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(GOOD + "/", "owl:Class", "")))
                .isInstanceOf(OntologyUploadIriCollisionException.class);

        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any(OntModel.class));
        verify(conceptMetadataRepository, never()).saveAll(anyList());
    }

    // --- Create/edit rules --------------------------------------------------

    @Test
    void conceptBreakingACreateEditRuleIsNotImportedAndIsReported() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; ofn:definující-ustanovení <" + ELI + ">")
                + concept(BAD, "owl:Class", "; ofn:definující-ustanovení <http://example.org/not-eli>"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        String reason = uploaded.rejectedConcepts().get(0).reason();
        assertThat(reason.contains("definingLegalSource") && reason.contains("http://example.org/not-eli"))
                .as(reason)
                .isTrue();
        assertThat(storedTurtle)
                .as("unreferenced rejected concept must not reach TDB2")
                .doesNotContain("<" + BAD + ">");
    }

    @Test
    void rejectedConceptStillReferencedIsKeptWithoutOwnership() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; rdfs:subClassOf <" + BAD + ">")
                + concept(BAD, "owl:Class", "; skos:exactMatch \"not an iri\""));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(storedTurtle).contains("<" + BAD + "> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>");
        assertThat(storedTurtle).doesNotContain("<" + BAD + "> <http://www.w3.org/2004/02/skos/core#inScheme>");
    }

    @Test
    void publicConceptWithAPrivacyProvisionIsRejected() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class, l111:veřejný-údaj",
                "; <" + VOCABULARY + "/ustanovení-dokládající-neveřejnost-údaje> <" + ELI + ">"));

        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(uploaded.rejectedConcepts().get(0).reason()).contains("nemůže být současně");
    }

    private static final String DIGITAL_OBJECT =
            "<https://slovník.gov.cz/generický/digitální-objekty/pojem/digitální-objekt>";

    /** The validator writes a title-only digital object for a free-text source. */
    @Test
    void nonLegalSourceWithoutAUrlIsImported() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "; ofn:definující-nelegislativní-zdroj"
                + " [ a " + DIGITAL_OBJECT + " ; <http://purl.org/dc/terms/title> \"Směrnice EU\"@cs ]"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(uploaded.rejectedConcepts()).as(uploaded.rejectedConcepts().toString()).isEmpty();
        assertThat(storedTurtle).contains("Směrnice EU");
    }

    @Test
    void nonLegalSourceWithAnInvalidUrlIsRejected() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class", "; ofn:definující-nelegislativní-zdroj"
                + " [ a " + DIGITAL_OBJECT + " ; <http://schema.org/url> \"not a url\" ]"));

        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(uploaded.rejectedConcepts().get(0).reason()).contains("definingNonLegalSource");
    }

    @Test
    void everyViolationOfOneConceptIsListedInOneEntry() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(BAD, "owl:Class", "; ofn:definující-ustanovení <http://example.org/not-eli> ;"
                + " ofn:má-typ-obsahu-údaje <https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/neznámé>"));

        assertThat(uploaded.rejectedConcepts()).hasSize(1);
        String reason = uploaded.rejectedConcepts().get(0).reason();
        assertThat(reason.contains("definingLegalSource") && reason.contains("typ obsahu")).as(reason).isTrue();
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
        graph.createResource(VOCABULARY).addProperty(RDF.type, OWL2.Ontology).addProperty(RDF.type, SKOS.ConceptScheme)
                .addProperty(RDF.type, graph.createResource(OfnIris.SLOVNIK));
        StringWriter turtle = new StringWriter();
        graph.write(turtle, "TTL");
        when(file.getBytes()).thenReturn(turtle.toString().getBytes(StandardCharsets.UTF_8));

        OntologyUploadResultDto uploaded = service.uploadFromFile(file, "user", null, null);

        assertThat(uploaded.rejectedConcepts()).as(uploaded.rejectedConcepts().toString()).isEmpty();
        assertThat(savedConceptIris()).isEqualTo(List.of(created.getURI()));
    }

    // --- Domain/range pointing at a concept published in NKD ------------------

    private void nkdReportsPublished(String... iris) {
        when(publishedResourceUtil.checkPublishedResourcesInNKD(any(), any())).thenReturn(List.of(iris));
    }

    @Test
    void domainPointingAtAPublishedNkdConceptIsRejected() throws Exception {
        nkdReportsPublished(NKD_CLASS);

        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(BAD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(uploaded.rejectedConcepts().get(0).reason()).contains(NKD_CLASS);
    }

    @Test
    void domainRangeTargetsRideTheSingleNkdQuery() throws Exception {
        upload(vocabulary() + concept(BAD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> extra = ArgumentCaptor.forClass(Collection.class);
        verify(publishedResourceUtil).checkPublishedResourcesInNKD(any(), extra.capture());
        assertThat(extra.getValue()).contains(NKD_CLASS);
        verify(publishedResourceUtil, never()).checkPublishedResourcesInNKD(any());
    }

    @Test
    void domainPointingAtAnUnpublishedForeignConceptIsImported() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(uploaded.rejectedConcepts()).isEmpty();
    }

    @Test
    void relationshipRangePointingAtAPublishedNkdConceptIsImported() throws Exception {
        nkdReportsPublished(NKD_CLASS);

        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:ObjectProperty", "; rdfs:range <" + NKD_CLASS + ">"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(uploaded.rejectedConcepts()).isEmpty();
    }

    @Test
    void domainPointingAtALocallyOwnedWorkingCopyIsImported() throws Exception {
        nkdReportsPublished(NKD_CLASS);
        ConceptMetadataEntity workingCopy = new ConceptMetadataEntity();
        workingCopy.setConceptIri(NKD_CLASS);
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of(workingCopy));

        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:DatatypeProperty", "; rdfs:domain <" + NKD_CLASS + ">"));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(uploaded.rejectedConcepts()).isEmpty();
    }

    // --- Role guard -----------------------------------------------------------

    @Test
    void conceptTypedAsBothPropertyAndRelationshipFailsTheWholeUpload() {
        OntologyUploadConflictingTypesException ex = assertThatExceptionOfType(OntologyUploadConflictingTypesException.class)
                .isThrownBy(() -> upload(vocabulary()
                        + concept(GOOD, "owl:Class", "")
                        + concept(BAD, "owl:DatatypeProperty, owl:ObjectProperty", "")))
                .actual();

        assertThat(ex.getConceptIris()).isEqualTo(List.of(BAD));
        assertThat(storedTurtle).as("nothing may reach TDB2").isNull();
    }

    @Test
    void conceptWithoutASingleRoleIsReportedAndLeavesNoOwnedTriples() throws Exception {
        OntologyUploadResultDto uploaded = upload(vocabulary()
                + concept(GOOD, "owl:Class", "")
                + concept(BAD, "owl:Class, owl:DatatypeProperty", ""));

        assertThat(savedConceptIris()).isEqualTo(List.of(GOOD));
        assertThat(rejectedIris(uploaded)).isEqualTo(List.of(BAD));
        assertThat(storedTurtle).as(storedTurtle).doesNotContain("<" + BAD + ">");
    }
}
