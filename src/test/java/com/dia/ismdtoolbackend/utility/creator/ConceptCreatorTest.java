package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.models.concept.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConceptCreatorTest {

    // TEST COVERAGE MAP
    // A — ClassConcept
    //   A0 – Base creation behavior
    //   A1 – Legal sources
    //   A2 – Non-legal sources
    //   A3 – Privacy & governance
    //   A4 – ExactMatch & broader handling (incl. edge cases)
    //
    // B — PropertyConcept
    //   B1 – Range/Domain & base classification
    //   B2 – Governance & PPDF
    //   B3 – ExactMatch & sources (incl. mixed / edge cases)
    //
    // C — RelationshipConcept
    //   C1 – Public/Private handling
    //   C2 – Domain/Range relations
    //   C3 – Governance rules
    //   C4 – Sources & ExactMatch (incl. mixed / edge cases)

    @InjectMocks
    private ConceptCreator conceptCreator;

    @Mock
    private ClassConceptModel classConceptModel;

    @Mock
    private PropertyConceptModel propertyConceptModel;

    @Mock
    private RelationshipConceptModel relationshipConceptModel;

    private NameModel nameModel;
    private DescriptionModel descriptionModel;
    private DefinitionModel definitionModel;
    private AltNameModel altNameModel;

    @BeforeEach
    void setUp() {
        // Initialize models with Map-based structure
        nameModel = new NameModel();
        descriptionModel = new DescriptionModel();
        definitionModel = new DefinitionModel();
        altNameModel = new AltNameModel();
    }

    // ========== Helper Methods for Model Creation ==========

    /**
     * Creates a NameModel with the given language code and value
     */
    private NameModel createNameModel(String languageCode, String value) {
        NameModel model = new NameModel();
        model.setName(Map.of(languageCode, value));
        return model;
    }

    /**
     * Creates an AltNameModel with the given language code and value
     */
    private AltNameModel createAltNameModel(String languageCode, String value) {
        AltNameModel model = new AltNameModel();
        model.setAltName(Map.of(languageCode, value));
        return model;
    }

    /**
     * Sets up basic ClassConcept model with name, type, and null sources
     */
    private void setupBasicClassConcept(String name, String type) {
        when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptModel.getOntologyGraphName()).thenReturn(null);
        when(classConceptModel.getNameModel()).thenReturn(createNameModel("cs", name));
        when(classConceptModel.getDescriptionModel()).thenReturn(null);
        when(classConceptModel.getDefinitionModel()).thenReturn(null);
        when(classConceptModel.getAltNameModel()).thenReturn(null);
        when(classConceptModel.getType()).thenReturn(type);
        when(classConceptModel.getIsPublic()).thenReturn(null);
        when(classConceptModel.getPrivacyProvisions()).thenReturn(null);
        setupNullGovernanceFields(classConceptModel);
        setupNullSourceFields(classConceptModel);
    }

    /**
     * Sets up null values for governance fields for ClassConcept
     */
    private void setupNullGovernanceFields(ClassConceptModel model) {
        when(model.getAgendaCode()).thenReturn(null);
        when(model.getAgendaSystemCode()).thenReturn(null);
        when(model.getSharingMethod()).thenReturn(null);
        when(model.getAcquisitionMethod()).thenReturn(null);
        when(model.getContentType()).thenReturn(null);
        when(model.getBroaderConcept()).thenReturn(null);
    }

    /**
     * Sets up null values for governance fields for PropertyConcept
     */
    private void setupNullGovernanceFields(PropertyConceptModel model) {
        when(model.getAgendaCode()).thenReturn(null);
        when(model.getAgendaSystemCode()).thenReturn(null);
        when(model.getSharingMethod()).thenReturn(null);
        when(model.getAcquisitionMethod()).thenReturn(null);
        when(model.getContentType()).thenReturn(null);
    }

    /**
     * Sets up null values for governance fields for RelationshipConcept
     */
    private void setupNullGovernanceFields(RelationshipConceptModel model) {
        when(model.getAgendaCode()).thenReturn(null);
        when(model.getAgendaSystemCode()).thenReturn(null);
        when(model.getSharingMethod()).thenReturn(null);
        when(model.getAcquisitionMethod()).thenReturn(null);
        when(model.getContentType()).thenReturn(null);
    }

    /**
     * Sets up null values for all source fields for ClassConcept
     */
    private void setupNullSourceFields(ClassConceptModel model) {
        when(model.getDefiningLegalSource()).thenReturn(null);
        when(model.getRelatedLegalSource()).thenReturn(null);
        when(model.getDefiningNonLegalSource()).thenReturn(null);
        when(model.getRelatedNonLegalSource()).thenReturn(null);
        when(model.getExactMatch()).thenReturn(null);
        when(model.getIdentifier()).thenReturn(null);
    }

    /**
     * Sets up null values for all source fields for PropertyConcept
     */
    private void setupNullSourceFields(PropertyConceptModel model) {
        when(model.getDefiningLegalSource()).thenReturn(null);
        when(model.getRelatedLegalSource()).thenReturn(null);
        when(model.getDefiningNonLegalSource()).thenReturn(null);
        when(model.getRelatedNonLegalSource()).thenReturn(null);
        when(model.getExactMatch()).thenReturn(null);
        when(model.getIdentifier()).thenReturn(null);
    }

    /**
     * Sets up null values for all source fields for RelationshipConcept
     */
    private void setupNullSourceFields(RelationshipConceptModel model) {
        when(model.getDefiningLegalSource()).thenReturn(null);
        when(model.getRelatedLegalSource()).thenReturn(null);
        when(model.getDefiningNonLegalSource()).thenReturn(null);
        when(model.getRelatedNonLegalSource()).thenReturn(null);
        when(model.getExactMatch()).thenReturn(null);
        when(model.getIdentifier()).thenReturn(null);
    }

    /**
     * Sets up basic PropertyConcept model with name and datatype
     */
    private void setupBasicPropertyConcept(String name, String dataType) {
        when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        when(propertyConceptModel.getOntologyGraphName()).thenReturn(null);
        when(propertyConceptModel.getNameModel()).thenReturn(createNameModel("cs", name));
        when(propertyConceptModel.getDataType()).thenReturn(dataType);
        when(propertyConceptModel.getDomain()).thenReturn(null);
        when(propertyConceptModel.getSuperProperty()).thenReturn(null);
        when(propertyConceptModel.getIsPublic()).thenReturn(null);
        when(propertyConceptModel.getIsInPPDF()).thenReturn(null);
        when(propertyConceptModel.getPrivacyProvisions()).thenReturn(null);
        setupNullGovernanceFields(propertyConceptModel);
        setupNullSourceFields(propertyConceptModel);
    }

    /**
     * Sets up basic RelationshipConcept model with name, domain, and range
     */
    private void setupBasicRelationshipConcept(String name, String domain, String range) {
        when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);
        when(relationshipConceptModel.getNameModel()).thenReturn(createNameModel("cs", name));
        when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
        when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
        when(relationshipConceptModel.getAltNameModel()).thenReturn(null);
        when(relationshipConceptModel.getDomain()).thenReturn(domain);
        when(relationshipConceptModel.getRange()).thenReturn(range);
        when(relationshipConceptModel.getSuperRelation()).thenReturn(null);
        when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
        when(relationshipConceptModel.getIsPublic()).thenReturn(null);
        when(relationshipConceptModel.getPrivacyProvisions()).thenReturn(null);
        setupNullGovernanceFields(relationshipConceptModel);
        setupNullSourceFields(relationshipConceptModel);
    }

    // ========== A. ClassConcept Tests ==========

    @Nested
    class ClassConceptTests {

        // --- A0. Base ClassConcept creation ---

        @Test
        void createSingleConcept_ShouldCreateClassConceptWithBasicMetadata() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            nameModel.setName(Map.of("cs", "Test class"));
            when(classConceptModel.getNameModel()).thenReturn(nameModel);

            descriptionModel.setDescription(Map.of("cs", "Test description"));
            when(classConceptModel.getDescriptionModel()).thenReturn(descriptionModel);

            definitionModel.setDefinition(Map.of("cs", "Test definition"));
            when(classConceptModel.getDefinitionModel()).thenReturn(definitionModel);

            when(classConceptModel.getAltNameModel()).thenReturn(createAltNameModel("cs", "Alt name"));

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(true);
            when(classConceptModel.getPrivacyProvisions()).thenReturn(List.of(""));

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("ID-123");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertNotNull(result);

            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + POJEM)
            ));
            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + TRIDA)
            ));
            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + TSP)
            ));

            Literal prefLabel = result.getProperty(SKOS.prefLabel).getObject().asLiteral();
            assertEquals("Test class", prefLabel.getString());
            assertEquals("cs", prefLabel.getLanguage());

            Property descProperty = result.getModel().createProperty("http://purl.org/dc/terms/description");
            Literal descriptionLiteral = result.getProperty(descProperty).getObject().asLiteral();
            assertEquals("Test description", descriptionLiteral.getString());
            assertEquals("cs", descriptionLiteral.getLanguage());

            Literal definitionLiteral = result.getProperty(SKOS.definition).getObject().asLiteral();
            assertEquals("Test definition", definitionLiteral.getString());
            assertEquals("cs", definitionLiteral.getLanguage());

            Property altLabelProperty = SKOS.altLabel;
            assertTrue(result.hasProperty(altLabelProperty));
        }

        @Test
        void createSingleConcept_ShouldAddBroaderConceptAndSubclassRelation() {
            // arrange
            setupBasicClassConcept("Child class", "objekt");
            when(classConceptModel.getBroaderConcept()).thenReturn(List.of("ParentOne", "ParentTwo"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + TOP)
            ));

            assertTrue(result.hasProperty(RDFS.subClassOf));
            assertTrue(result.listProperties(RDFS.subClassOf).toList().size() >= 2);
        }

        // ========== A1. Legal sources for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldProcessLegalSourcesWithEliPattern() {
            // arrange
            setupBasicClassConcept("Legal Source Class", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-1");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/12"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2022/100"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertTrue(result.hasProperty(defining));
            assertTrue(result.hasProperty(related));

            String iri = result.listProperties(defining)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();

            assertTrue(iri.contains("2021/12"));
        }

        @Test
        void createSingleConcept_ShouldAddOnlyDefiningLegalSourcesWhenRelatedIsEmpty() {
            // arrange
            setupBasicClassConcept("Class with defining only", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-DEF-ONLY");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2023/11"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of());

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertTrue(result.hasProperty(defining));
            assertFalse(result.hasProperty(related));

            String iri = result.listProperties(defining)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();

            assertTrue(iri.contains("2023/11"));
        }

        @Test
        void createSingleConcept_ShouldAddOnlyRelatedLegalSourcesWhenDefiningIsEmpty() {
            // arrange
            setupBasicClassConcept("Class with related only", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-REL-ONLY");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of());
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2024/7"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertFalse(result.hasProperty(defining));
            assertTrue(result.hasProperty(related));

            String iri = result.listProperties(related)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();

            assertTrue(iri.contains("2024/7"));
        }

        @Test
        void createSingleConcept_ShouldAddBothLegalSourcesWhenBothProvided() {
            // arrange
            setupBasicClassConcept("Class with both legal sources", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-BOTH-1");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2019/10"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/5"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertTrue(result.hasProperty(defining));
            assertTrue(result.hasProperty(related));

            String definingIri = result.listProperties(defining)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();
            String relatedIri = result.listProperties(related)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();

            assertTrue(definingIri.contains("2019/10"));
            assertTrue(relatedIri.contains("2020/5"));
        }

        @Test
        void createSingleConcept_ShouldIgnoreLegalSourcesWithoutEliPattern() {
            // arrange
            setupBasicClassConcept("Class with invalid legal sources", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-NO-ELI");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("http://example.org/not-eli-1"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("http://example.org/not-eli-2"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertFalse(result.hasProperty(defining));
            assertFalse(result.hasProperty(related));
        }

        @Test
        void createSingleConcept_ShouldDropLegalSourcesOnBrokenLegacyHost() {
            // Pre-#106 broken host (missing .gov) was silently accepted and host-rewritten;
            // the new contract requires the canonical opendata.eselpoint.gov.cz host.
            setupBasicClassConcept("Class with broken-host legal sources", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-BROKEN-HOST");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2021/12"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2022/100"));

            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);
            assertFalse(result.hasProperty(defining));
            assertFalse(result.hasProperty(related));
        }

        @Test
        void createSingleConcept_ShouldStoreLegalSourceVerbatimOnCanonicalHost() {
            // Canonical e-Sbírka ELI must be stored verbatim — no host rewriting, no path slicing.
            String canonical = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/12";
            setupBasicClassConcept("Class with canonical legal source", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-VERBATIM");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of(canonical));
            when(classConceptModel.getRelatedLegalSource()).thenReturn(List.of());

            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            Property defining = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            String storedIri = result.listProperties(defining).nextStatement()
                    .getObject().asResource().getURI();
            assertEquals(canonical, storedIri);
        }

        // ========== A2. Non-legal sources for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldProcessNonLegalSources() {
            // arrange
            setupBasicClassConcept("NonLegal", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("NL-1");
            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("dummy-legal"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("dummy-legal-2"));
            when(classConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel("Doc A", "Popis A", "https://example.org/doc1")));
            when(classConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel(null, null, "https://example.org/doc2")));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defProp = result.getModel().createProperty(
                    DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ
            );
            Property relProp = result.getModel().createProperty(
                    DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ
            );
            Property schemaUrlProp = result.getModel().createProperty(SCHEMA_URL);
            Property dctTitleProp = result.getModel().createProperty(DCT_NS + "title");
            Property dctDescProp = result.getModel().createProperty(DCT_NS + "description");

            assertTrue(result.hasProperty(defProp));
            assertTrue(result.hasProperty(relProp));

            Resource doc = result.getProperty(defProp).getObject().asResource();
            assertNotNull(doc);
            assertTrue(doc.hasProperty(schemaUrlProp, result.getModel().createResource("https://example.org/doc1")));
            assertTrue(doc.hasProperty(dctTitleProp));
            assertTrue(doc.hasProperty(dctDescProp));
            assertTrue(doc.hasProperty(RDF.type, result.getModel().createResource(DIGITALNI_OBJEKT)));

            Resource relDoc = result.getProperty(relProp).getObject().asResource();
            assertTrue(relDoc.hasProperty(schemaUrlProp, result.getModel().createResource("https://example.org/doc2")));
            assertFalse(relDoc.hasProperty(dctTitleProp));
            assertFalse(relDoc.hasProperty(dctDescProp));
        }

        // ========== A3. Privacy and governance for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldAddPrivacyProvisionWhenHasEliPattern() {
            // arrange
            setupBasicClassConcept("PrivateClass", "objekt");
            when(classConceptModel.getIdentifier()).thenReturn("PP-1");
            when(classConceptModel.getPrivacyProvisions())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/50"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property privacy = result.getModel().createProperty(
                    DEFAULT_NS + USTANOVENI_NEVEREJNOST
            );

            assertTrue(result.hasProperty(privacy));

            String uri = result.getProperty(privacy).getObject().asResource().getURI();
            assertTrue(uri.contains("2020/50"));
        }

        @Test
        void createSingleConcept_ShouldAddGovernanceProperties() {
            // arrange
            setupBasicClassConcept("GovernanceClass", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("GOV-1");
            when(classConceptModel.getSharingMethod()).thenReturn(List.of("sdileni"));
            when(classConceptModel.getAcquisitionMethod()).thenReturn("ziskani");
            when(classConceptModel.getContentType()).thenReturn("obsah");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI)
            ));
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI)
            ));
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(OFN_NAMESPACE + TYP_OBSAHU)
            ));
        }

        @Test
        void createSingleConcept_ShouldNotCreateBroaderConceptWhenInputIsEmpty() {
            // arrange
            setupBasicClassConcept("Class without broader", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("NO-BROADER");
            when(classConceptModel.getBroaderConcept()).thenReturn(List.of());

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.hasProperty(RDFS.subClassOf));
        }

        @Test
        void createSingleConcept_ShouldNotAddPrivacyProvisionWhenNoEliPattern() {
            // arrange
            setupBasicClassConcept("Class without privacy ELI", "objekt");
            when(classConceptModel.getIdentifier()).thenReturn("NO-PRIV-ELI");
            when(classConceptModel.getPrivacyProvisions())
                    .thenReturn(List.of("http://example.org/not-eli-format"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property privacy = result.getModel().createProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
            assertFalse(result.hasProperty(privacy));
        }

        @Test
        void createSingleConcept_ShouldIgnoreEmptyGovernanceValues() {
            // arrange
            setupBasicClassConcept("Class with empty governance", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("EMPTY-GOV");
            when(classConceptModel.getSharingMethod()).thenReturn(List.of("   "));
            when(classConceptModel.getAcquisitionMethod()).thenReturn("");
            when(classConceptModel.getContentType()).thenReturn(" ");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertFalse(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + ZPUSOB_SDILENI)
            ));
            assertFalse(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + ZPUSOB_ZISKANI)
            ));
            assertFalse(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + TYP_OBSAHU)
            ));
        }

        // ========== A4. ExactMatch handling for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldAddExactMatchIRIs() {
            // arrange
            setupBasicClassConcept("Class with exact match", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("EXACT-1");
            when(classConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/exact1", "http://example.org/exact2")
            );

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertNotNull(result);

            Property exactMatchProperty = result.getModel()
                    .createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

            assertTrue(result.hasProperty(exactMatchProperty));
            assertEquals(2, result.listProperties(exactMatchProperty).toList().size());
        }

        // --- A4.1 Mixed ExactMatch edge cases for ClassConcept ---

        @Test
        void createSingleConcept_ShouldIgnoreInvalidExactMatchIRIsAndKeepValidOnes() {
            // arrange
            setupBasicClassConcept("Class mixed exact", "subjekt");
            when(classConceptModel.getIdentifier()).thenReturn("CLASS-MIXED-EXACT");
            when(classConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/exact-valid", "not-a-valid-iri", "")
            );

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property exactMatchProperty = result.getModel()
                    .createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

            assertTrue(result.hasProperty(exactMatchProperty));
            assertEquals(1, result.listProperties(exactMatchProperty).toList().size());
            Resource matchRes = result.listProperties(exactMatchProperty)
                    .nextStatement().getObject().asResource();
            assertEquals("http://example.org/exact-valid", matchRes.getURI());
        }

        // --- A5. Code list dataset ---

        @Test
        void createSingleConcept_ShouldAddCodeListDatasetStructure() {
            // arrange
            setupBasicClassConcept("Code List Class", "subjekt");
            when(classConceptModel.getCodeListDataset())
                    .thenReturn("https://data.gov.cz/zdroj/datové-sady/test-dataset");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property instanceProp = result.getModel().createProperty(
                    OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
            assertTrue(result.hasProperty(instanceProp), "Should have instance-definovány-číselníkem property");

            Statement stmt = result.getProperty(instanceProp);
            assertTrue(stmt.getObject().isResource(), "Object should be a resource (blank node)");

            Resource codeListNode = stmt.getObject().asResource();
            Resource codeListType = result.getModel().createResource(OFN_NAMESPACE_LEGAL + CISELNIK);
            assertTrue(codeListNode.hasProperty(RDF.type, codeListType), "Blank node should be typed as číselník");

            Property datasetProp = result.getModel().createProperty(
                    OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
            assertTrue(codeListNode.hasProperty(datasetProp), "Blank node should have dataset property");

            String datasetUri = codeListNode.getProperty(datasetProp).getObject().asResource().getURI();
            assertEquals("https://data.gov.cz/zdroj/datové-sady/test-dataset", datasetUri);
        }

        @Test
        void createSingleConcept_ShouldNotAddCodeListDataset_WhenNull() {
            // arrange
            setupBasicClassConcept("No Dataset Class", "objekt");
            when(classConceptModel.getCodeListDataset()).thenReturn(null);

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property instanceProp = result.getModel().createProperty(
                    OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
            assertFalse(result.hasProperty(instanceProp));
        }
    }

    // ========== B. PropertyConcept Tests ==========

    @Nested
    class PropertyConceptTests {

        // --- B1. Range, domain and base PropertyConcept classification ---

        @Test
        void createSingleConcept_ShouldCreateDatatypePropertyWithXsdRange() {
            // arrange
            setupBasicPropertyConcept("Property name", "xsd:string");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-1");
            when(propertyConceptModel.getDomain()).thenReturn("TestDomain");
            when(propertyConceptModel.getSuperProperty()).thenReturn(List.of("SuperProperty"));
            when(propertyConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getPrivacyProvisions()).thenReturn(List.of(""));

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.hasProperty(RDFS.range));
            assertTrue(result.hasProperty(RDFS.domain));
        }

        @Test
        void createSingleConcept_ShouldCreateObjectPropertyWhenDatatypeIsNotXsdLiteral() {
            // arrange
            setupBasicPropertyConcept("Object property", "SomeOtherClass");
            when(propertyConceptModel.getIdentifier()).thenReturn("OBJ-PROP");
            when(propertyConceptModel.getIsPublic()).thenReturn(Boolean.FALSE);

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertNotNull(result);
            Resource range = result.getProperty(RDFS.range).getObject().asResource();
            assertEquals(RDFS.Literal.getURI(), range.getURI());
        }

        @Test
        void createSingleConcept_ShouldUseCustomNamespaceForPropertyConcept() {
            // arrange
            setupBasicPropertyConcept("CustomNsProperty", "xsd:string");
            when(propertyConceptModel.getOntologyGraphName()).thenReturn("https://example.org/custom-graph/");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-CUSTOM-NS");

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.getURI().startsWith("https://example.org/custom-graph/pojem/"));
        }

        @Test
        void createSingleConcept_ShouldUseProvidedUrisForDomainAndSuperProperty() {
            // arrange
            String domainIri = "http://example.org/domain-class";
            String superPropertyIri = "http://example.org/super-property";

            setupBasicPropertyConcept("PropertyWithUriDomainAndSuper", "xsd:string");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-URI-DOM-SUPER");
            when(propertyConceptModel.getDomain()).thenReturn(domainIri);
            when(propertyConceptModel.getSuperProperty()).thenReturn(List.of(superPropertyIri));

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertEquals(domainIri, result.getProperty(RDFS.domain).getObject().asResource().getURI());
            assertEquals(superPropertyIri, result.getProperty(RDFS.subPropertyOf).getObject().asResource().getURI());
        }

        // --- B2. Governance and PPDF ---

        @Test
        void createSingleConcept_ShouldAddGovernancePropertiesForPropertyConcept() {
            // arrange
            setupBasicPropertyConcept("Governed property", "xsd:string");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-GOV-1");
            when(propertyConceptModel.getDomain()).thenReturn("DomainForGovernedProperty");
            when(propertyConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getSharingMethod()).thenReturn(List.of("sdileni-property"));
            when(propertyConceptModel.getAcquisitionMethod()).thenReturn("ziskani-property");
            when(propertyConceptModel.getContentType()).thenReturn("obsah-property");

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertFalse(result.listProperties().toList().isEmpty());
        }

        @Test
        void createSingleConcept_ShouldUseLiteralRangeAndNoDomainWhenDatatypeAndDomainAreNull() {
            // arrange
            setupBasicPropertyConcept("Property without datatype and domain", null);
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-NO-DT-DOM");

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            Resource range = result.getProperty(RDFS.range).getObject().asResource();
            assertEquals(RDFS.Literal.getURI(), range.getURI());
            assertFalse(result.hasProperty(RDFS.domain));
        }

        // --- B3. ExactMatch and sources for PropertyConcept ---

        @Test
        void createSingleConcept_ShouldAddExactMatchForPropertyConcept() {
            // arrange
            setupBasicPropertyConcept("Property with exact match", "xsd:string");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-EXACT-1");
            when(propertyConceptModel.getDomain()).thenReturn("DomainForExactMatchProperty");
            when(propertyConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/propExact1", "http://example.org/propExact2")
            );

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            Property exactMatchProperty =
                    result.getModel().createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

            assertEquals(2, result.listProperties(exactMatchProperty).toList().size());
        }

        @Test
        void createSingleConcept_ShouldProcessLegalAndNonLegalSourcesForPropertyConcept() {
            // arrange
            setupBasicPropertyConcept("Property with sources", "xsd:string");
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-SOURCES-1");
            when(propertyConceptModel.getDomain()).thenReturn("SourceDomain");
            when(propertyConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2019/10"));
            when(propertyConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/5"));
            when(propertyConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel("Prop doc 1", null, "https://example.org/property-doc-1")));
            when(propertyConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel("Prop doc 2", null, "https://example.org/property-doc-2")));

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.listProperties().toList().isEmpty());
        }
    }

    // ========== C. RelationshipConcept Tests ==========

    @Nested
    class RelationshipConceptTests {

        // --- C1. Public/Private handling and base RelationshipConcept metadata ---

        @Test
        void createSingleConcept_ShouldCreatePrivateRelationshipWhenIsPublicNe() {
            // arrange
            setupBasicRelationshipConcept("Private relation", "DomainClass", "RangeClass");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-PRIVATE");
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.FALSE);
            when(relationshipConceptModel.getPrivacyProvisions()).thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/50"));

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ)
            ));
            assertFalse(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ)
            ));
        }

        // --- C1.1 Custom namespace behaviour ---

        @Test
        void createSingleConcept_ShouldUseCustomNamespaceWhenGraphNameProvided() {
            // arrange
            String customNamespace = "https://example.org/custom-graph/";
            setupBasicRelationshipConcept("Relation with custom ns", "DomainClass", "RangeClass");
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(customNamespace);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-CUSTOM-NS");
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.getURI().startsWith(customNamespace));
        }

        // --- C2. Domain, range and superRelation for RelationshipConcept ---

        @Test
        void createSingleConcept_ShouldUseProvidedUrisForDomainRangeAndSuperRelation() {
            // arrange
            String domainIri = "http://example.org/rel-domain";
            String rangeIri = "http://example.org/rel-range";
            String superRelationIri = "http://example.org/super-relation";

            setupBasicRelationshipConcept("Relation with URI domain/range/super", domainIri, rangeIri);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-URI-DOM-RANGE-SUPER");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(List.of(superRelationIri));
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);

            assertTrue(result.hasProperty(RDFS.domain), "Expected domain to be present");
            Resource domainResource = result.getProperty(RDFS.domain).getObject().asResource();
            assertEquals(domainIri, domainResource.getURI(), "Domain IRI must be used as-is");

            assertTrue(result.hasProperty(RDFS.range), "Expected range to be present");
            Resource rangeResource = result.getProperty(RDFS.range).getObject().asResource();
            assertEquals(rangeIri, rangeResource.getURI(), "Range IRI must be used as-is");

            assertTrue(result.hasProperty(RDFS.subPropertyOf), "Expected subPropertyOf to be present");
            Resource superRelResource = result.getProperty(RDFS.subPropertyOf).getObject().asResource();
            assertEquals(superRelationIri, superRelResource.getURI(), "Super relation IRI must be used as-is");
        }

        @Test
        void createSingleConcept_ShouldGenerateDomainRangeAndSuperRelationFromLabelsWhenNotUris() {
            // arrange
            setupBasicRelationshipConcept("Relation with label-based domain/range/super", "DomainLabel", "RangeLabel");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-LABEL-DOM-RANGE-SUPER");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(List.of("SuperRelationLabel"));
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);

            assertTrue(result.hasProperty(RDFS.domain), "Expected domain to be present");
            Resource domainResource = result.getProperty(RDFS.domain).getObject().asResource();
            assertTrue(domainResource.getURI().startsWith(DEFAULT_NS),
                    "Generated domain IRI should start with default namespace");

            assertTrue(result.hasProperty(RDFS.range), "Expected range to be present");
            Resource rangeResource = result.getProperty(RDFS.range).getObject().asResource();
            assertTrue(rangeResource.getURI().startsWith(DEFAULT_NS),
                    "Generated range IRI should start with default namespace");

            assertTrue(result.hasProperty(RDFS.subPropertyOf), "Expected subPropertyOf to be present");
            Resource superRelResource = result.getProperty(RDFS.subPropertyOf).getObject().asResource();
            assertTrue(superRelResource.getURI().startsWith(DEFAULT_NS),
                    "Generated super relation IRI should start with default namespace");
        }

        @Test
        void createSingleConcept_ShouldNotCreateDomainRangeAndSuperRelationWhenValuesAreBlank() {
            // arrange
            setupBasicRelationshipConcept("Relation with blank domain/range/super", "   ", "");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-BLANK-DOM-RANGE-SUPER");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(List.of(""));
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.hasProperty(RDFS.domain));
            assertFalse(result.hasProperty(RDFS.range));
            assertFalse(result.hasProperty(RDFS.subPropertyOf));
        }

        // --- C3. Governance rules for RelationshipConcept ---

        @Test
        void createSingleConcept_ShouldIgnoreEmptyGovernanceValuesForRelationship() {
            // arrange
            setupBasicRelationshipConcept("Relation with empty governance", "DomainClass", "RangeClass");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-EMPTY-GOV");
            when(relationshipConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(List.of("   "));
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn("");
            when(relationshipConceptModel.getContentType()).thenReturn(" ");

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.hasProperty(result.getModel().createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI)));
            assertFalse(result.hasProperty(result.getModel().createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI)));
            assertFalse(result.hasProperty(result.getModel().createProperty(OFN_NAMESPACE + TYP_OBSAHU)));
        }

        // --- C4. Sources and ExactMatch for RelationshipConcept ---

        @Test
        void createSingleConcept_ShouldAddExactMatchAndSourcesForRelationship() {
            // arrange
            setupBasicRelationshipConcept("Relation with sources", "DomainClass", "RangeClass");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-SOURCES-1");
            when(relationshipConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getPrivacyProvisions()).thenReturn(List.of(""));
            when(relationshipConceptModel.getSharingMethod()).thenReturn(List.of("sdileni-rel"));
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn("ziskani-rel");
            when(relationshipConceptModel.getContentType()).thenReturn("obsah-rel");
            when(relationshipConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2018/10"));
            when(relationshipConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/5"));
            when(relationshipConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel("Rel doc 1", null, "https://example.org/rel-doc-1")));
            when(relationshipConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of(new DigitalObjectModel("Rel doc 2", null, "https://example.org/rel-doc-2")));
            when(relationshipConceptModel.getExactMatch())
                    .thenReturn(List.of("http://example.org/relExact1", "http://example.org/relExact2"));

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);

            Property definingLegal = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property relatedLegal = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);
            Property definingNonLegal = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            Property relatedNonLegal = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

            assertTrue(result.hasProperty(definingLegal));
            assertTrue(result.hasProperty(relatedLegal));
            assertTrue(result.hasProperty(definingNonLegal));
            assertTrue(result.hasProperty(relatedNonLegal));

            Property exactMatchProperty =
                    result.getModel().createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

            assertTrue(result.hasProperty(exactMatchProperty));
            assertEquals(2, result.listProperties(exactMatchProperty).toList().size());
        }

        @Test
        void createSingleConcept_ShouldIgnoreLegalSourcesWithoutEliPatternForRelationshipConcept() {
            // arrange
            setupBasicRelationshipConcept("Relationship with mixed legal sources", "RelDomain", "RelRange");
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-MIXED-LEGAL");
            when(relationshipConceptModel.getIsPublic()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(
                    List.of(
                            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/5",
                            "http://example.org/not-eli-def"
                    )
            );
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(
                    List.of(
                            "http://example.org/not-eli-rel",
                            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2021/10"
                    )
            );

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            Property defProp = result.getModel().createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
            Property relProp = result.getModel().createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

            assertTrue(result.hasProperty(defProp));
            assertTrue(result.hasProperty(relProp));
            assertEquals(1, result.listProperties(defProp).toList().size());
            assertEquals(1, result.listProperties(relProp).toList().size());

            String defIri = result.listProperties(defProp)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();
            String relIri = result.listProperties(relProp)
                    .nextStatement()
                    .getObject()
                    .asResource()
                    .getURI();

            assertTrue(defIri.contains("2020/5"));
            assertTrue(relIri.contains("2021/10"));
        }
    }

    // ========== D. skos:inScheme — required by ReferencedConceptResolutionEngine / NKD ==========

    @Nested
    class InSchemeTests {

        private static final String GRAPH = "https://example.org/slovnik/test-slovnik";

        @Test
        void classConcept_emitsInSchemePointingAtOntologyGraphName() {
            setupBasicClassConcept("Some class", "subjekt");
            when(classConceptModel.getOntologyGraphName()).thenReturn(GRAPH);

            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            assertTrue(result.hasProperty(SKOS.inScheme,
                    result.getModel().createResource(GRAPH)),
                    "Class concept must carry skos:inScheme → ontologyGraphName");
        }

        @Test
        void propertyConcept_emitsInSchemePointingAtOntologyGraphName() {
            setupBasicPropertyConcept("Some property", "xsd:string");
            when(propertyConceptModel.getOntologyGraphName()).thenReturn(GRAPH);

            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            assertTrue(result.hasProperty(SKOS.inScheme,
                    result.getModel().createResource(GRAPH)),
                    "Property concept must carry skos:inScheme → ontologyGraphName");
        }

        @Test
        void relationshipConcept_emitsInSchemePointingAtOntologyGraphName() {
            setupBasicRelationshipConcept("Some relation", "DomainClass", "RangeClass");
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(GRAPH);

            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            assertTrue(result.hasProperty(SKOS.inScheme,
                    result.getModel().createResource(GRAPH)),
                    "Relationship concept must carry skos:inScheme → ontologyGraphName");
        }

        @Test
        void blankOntologyGraphName_doesNotEmitInScheme() {
            setupBasicClassConcept("Class without graph", "subjekt");
            when(classConceptModel.getOntologyGraphName()).thenReturn("   ");

            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            assertFalse(result.hasProperty(SKOS.inScheme),
                    "Blank ontologyGraphName must not produce a skos:inScheme triple");
        }
    }
}
