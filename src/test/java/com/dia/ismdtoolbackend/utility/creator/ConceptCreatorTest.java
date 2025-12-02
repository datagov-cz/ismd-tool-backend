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

    @Mock
    private NameModel nameModel;

    @Mock
    private DescriptionModel descriptionModel;

    @Mock
    private DefinitionModel definitionModel;

    @Mock
    private AltNameModel altNameModel;

    @BeforeEach
    void setUp() {
        // MockitoExtension handles mocks lifecycle
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

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Test class");
            when(nameModel.getLanguageTag()).thenReturn("cs");

            when(classConceptModel.getDescriptionModel()).thenReturn(descriptionModel);
            when(descriptionModel.getDescription()).thenReturn("Test description");
            when(descriptionModel.getLanguageTag()).thenReturn("cs");

            when(classConceptModel.getDefinitionModel()).thenReturn(definitionModel);
            when(definitionModel.getDefinition()).thenReturn("Test definition");
            when(definitionModel.getLanguageTag()).thenReturn("cs");

            when(classConceptModel.getAltNameModel()).thenReturn(List.of(altNameModel));
            when(altNameModel.getAltName()).thenReturn("Alt name");
            when(altNameModel.getLanguageTag()).thenReturn("cs");

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn("ano");
            when(classConceptModel.getPrivacyProvision()).thenReturn("");

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Child class");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("objekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn(null);

            when(classConceptModel.getBroaderConcept()).thenReturn("ParentOne; ParentTwo");

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Legal Source Class");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-1");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2021/12"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2022/100"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with defining only");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-DEF-ONLY");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2023/11"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of());

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with related only");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-REL-ONLY");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of());
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2024/7"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with both legal sources");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-BOTH-1");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2019/10"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2020/5"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with invalid legal sources");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("LEGAL-NO-ELI");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("http://example.org/not-eli-1"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("http://example.org/not-eli-2"));

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defining = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property related = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

            assertFalse(result.hasProperty(defining));
            assertFalse(result.hasProperty(related));
        }

        // ========== A2. Non-legal sources for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldProcessNonLegalSources() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("NonLegal");

            when(classConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("dummy-legal"));
            when(classConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("dummy-legal-2"));

            when(classConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of("https://example.org/doc1"));
            when(classConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of("https://example.org/doc2"));

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);
            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);
            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("NL-1");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property defProp = result.getModel().createProperty(
                    DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ
            );
            Property relProp = result.getModel().createProperty(
                    DEFAULT_NS + SOUVISEJICI_NELEGISLATIVNI_ZDROJ
            );

            assertTrue(result.hasProperty(defProp));
            assertTrue(result.hasProperty(relProp));

            Resource doc = result.getProperty(defProp).getObject().asResource();
            assertNotNull(doc);
            assertNotNull(doc.getURI());
        }

        // ========== A3. Privacy and governance for ClassConcept ==========

        @Test
        void createSingleConcept_ShouldAddPrivacyProvisionWhenHasEliPattern() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("PrivateClass");

            when(classConceptModel.getPrivacyProvision())
                    .thenReturn("https://eselpoint.cz/eli/cz/act/2020/50");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);
            when(classConceptModel.getType()).thenReturn("objekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);
            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("PP-1");

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("GovernanceClass");

            when(classConceptModel.getSharingMethod()).thenReturn("sdileni");
            when(classConceptModel.getAcquisitionMethod()).thenReturn("ziskani");
            when(classConceptModel.getContentType()).thenReturn("obsah");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);
            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);
            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("GOV-1");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + ZPUSOB_SDILENI)
            ));
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + ZPUSOB_ZISKANI)
            ));
            assertTrue(result.hasProperty(
                    result.getModel().createProperty(DEFAULT_NS + TYP_OBSAHU)
            ));
        }

        @Test
        void createSingleConcept_ShouldNotCreateBroaderConceptWhenInputIsEmpty() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class without broader");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(classConceptModel.getSharingMethod()).thenReturn(null);
            when(classConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(classConceptModel.getContentType()).thenReturn(null);

            when(classConceptModel.getBroaderConcept()).thenReturn("   ");

            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("NO-BROADER");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.hasProperty(RDFS.subClassOf));
        }

        @Test
        void createSingleConcept_ShouldNotAddPrivacyProvisionWhenNoEliPattern() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class without privacy ELI");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("objekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);

            when(classConceptModel.getPrivacyProvision())
                    .thenReturn("http://example.org/not-eli-format");

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
            when(classConceptModel.getIdentifier()).thenReturn("NO-PRIV-ELI");

            // act
            Resource result = conceptCreator.createSingleConcept(classConceptModel);

            // assert
            Property privacy = result.getModel().createProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
            assertFalse(result.hasProperty(privacy));
        }

        @Test
        void createSingleConcept_ShouldIgnoreEmptyGovernanceValues() {
            // arrange
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with empty governance");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

            when(classConceptModel.getAgendaCode()).thenReturn(null);
            when(classConceptModel.getAgendaSystemCode()).thenReturn(null);

            when(classConceptModel.getSharingMethod()).thenReturn("   ");
            when(classConceptModel.getAcquisitionMethod()).thenReturn("");
            when(classConceptModel.getContentType()).thenReturn(" ");

            when(classConceptModel.getBroaderConcept()).thenReturn(null);
            when(classConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(classConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(classConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(classConceptModel.getExactMatch()).thenReturn(null);
            when(classConceptModel.getIdentifier()).thenReturn("EMPTY-GOV");

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class with exact match");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);

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

            when(classConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/exact1", "http://example.org/exact2")
            );
            when(classConceptModel.getIdentifier()).thenReturn("EXACT-1");

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
            when(classConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
            when(classConceptModel.getOntologyGraphName()).thenReturn(null);

            when(classConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Class mixed exact");

            when(classConceptModel.getDescriptionModel()).thenReturn(null);
            when(classConceptModel.getDefinitionModel()).thenReturn(null);
            when(classConceptModel.getAltNameModel()).thenReturn(null);

            when(classConceptModel.getType()).thenReturn("subjekt");
            when(classConceptModel.getIsPublic()).thenReturn(null);
            when(classConceptModel.getPrivacyProvision()).thenReturn(null);
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

            when(classConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/exact-valid", "not-a-valid-iri", "")
            );
            when(classConceptModel.getIdentifier()).thenReturn("CLASS-MIXED-EXACT");

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
    }

    // ========== B. PropertyConcept Tests ==========

    @Nested
    class PropertyConceptTests {

        // --- B1. Range, domain and base PropertyConcept classification ---

        @Test
        void createSingleConcept_ShouldCreateDatatypePropertyWithXsdRange() {
            // arrange
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getOntologyGraphName()).thenReturn(null);

            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Property name");
            when(nameModel.getLanguageTag()).thenReturn("cs");

            when(propertyConceptModel.getDescriptionModel()).thenReturn(null);
            when(propertyConceptModel.getDefinitionModel()).thenReturn(null);
            when(propertyConceptModel.getAltNameModel()).thenReturn(null);

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
            when(propertyConceptModel.getDomain()).thenReturn("TestDomain");
            when(propertyConceptModel.getSuperProperty()).thenReturn("Super property");

            when(propertyConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getIsPublic()).thenReturn("ano");
            when(propertyConceptModel.getPrivacyProvision()).thenReturn("");

            when(propertyConceptModel.getAgendaCode()).thenReturn(null);
            when(propertyConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(propertyConceptModel.getSharingMethod()).thenReturn(null);
            when(propertyConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(propertyConceptModel.getContentType()).thenReturn(null);

            when(propertyConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(propertyConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(propertyConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(propertyConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(propertyConceptModel.getExactMatch()).thenReturn(null);
            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-1");

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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getOntologyGraphName()).thenReturn(null);

            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Object property");

            when(propertyConceptModel.getDataType()).thenReturn("SomeOtherClass");
            when(propertyConceptModel.getDomain()).thenReturn(null);
            when(propertyConceptModel.getSuperProperty()).thenReturn(null);

            when(propertyConceptModel.getIsPublic()).thenReturn("ne");
            when(propertyConceptModel.getIsInPPDF()).thenReturn(null);
            when(propertyConceptModel.getPrivacyProvision()).thenReturn(null);

            when(propertyConceptModel.getIdentifier()).thenReturn("OBJ-PROP");

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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getOntologyGraphName()).thenReturn("https://example.org/custom-graph/");

            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("CustomNsProperty");

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getOntologyGraphName()).thenReturn(null);

            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("PropertyWithUriDomainAndSuper");

            String domainIri = "http://example.org/domain-class";
            String superPropertyIri = "http://example.org/super-property";

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
            when(propertyConceptModel.getDomain()).thenReturn(domainIri);
            when(propertyConceptModel.getSuperProperty()).thenReturn(superPropertyIri);

            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-URI-DOM-SUPER");

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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Governed property");

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
            when(propertyConceptModel.getDomain()).thenReturn("DomainForGovernedProperty");

            when(propertyConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(propertyConceptModel.getIsPublic()).thenReturn("ano");

            when(propertyConceptModel.getSharingMethod()).thenReturn("sdileni-property");
            when(propertyConceptModel.getAcquisitionMethod()).thenReturn("ziskani-property");
            when(propertyConceptModel.getContentType()).thenReturn("obsah-property");

            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-GOV-1");

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertTrue(result.listProperties().toList().size() > 0);
        }

        @Test
        void createSingleConcept_ShouldUseLiteralRangeAndNoDomainWhenDatatypeAndDomainAreNull() {
            // arrange
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Property without datatype and domain");

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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Property with exact match");

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
            when(propertyConceptModel.getDomain()).thenReturn("DomainForExactMatchProperty");

            when(propertyConceptModel.getExactMatch()).thenReturn(
                    List.of("http://example.org/propExact1", "http://example.org/propExact2")
            );

            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-EXACT-1");

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
            when(propertyConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
            when(propertyConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Property with sources");

            when(propertyConceptModel.getDataType()).thenReturn("xsd:string");
            when(propertyConceptModel.getDomain()).thenReturn("SourceDomain");

            when(propertyConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2019/10"));
            when(propertyConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2021/5"));
            when(propertyConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of("https://example.org/property-doc-1"));
            when(propertyConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of("https://example.org/property-doc-2"));

            when(propertyConceptModel.getIdentifier()).thenReturn("PROP-SOURCES-1");

            // act
            Resource result = conceptCreator.createSingleConcept(propertyConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.listProperties().toList().size() > 0);
        }
    }

    // ========== C. RelationshipConcept Tests ==========

    @Nested
    class RelationshipConceptTests {

        // --- C1. Public/Private handling and base RelationshipConcept metadata ---

        @Test
        void createSingleConcept_ShouldCreatePrivateRelationshipWhenIsPublicNe() {
            // arrange
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Private relation");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            when(relationshipConceptModel.getDomain()).thenReturn("DomainClass");
            when(relationshipConceptModel.getRange()).thenReturn("RangeClass");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(null);

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ne");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(null);
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(relationshipConceptModel.getContentType()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-PRIVATE");

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertTrue(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + NEVEREJNY_UDAJ)
            ));
            assertFalse(result.hasProperty(
                    RDF.type,
                    result.getModel().createResource(OFN_NAMESPACE + VEREJNY_UDAJ)
            ));
        }

        // --- C1.1 Custom namespace behaviour ---

        @Test
        void createSingleConcept_ShouldUseCustomNamespaceWhenGraphNameProvided() {
            // arrange
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            String customNamespace = "https://example.org/custom-graph/";
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(customNamespace);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with custom ns");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            when(relationshipConceptModel.getDomain()).thenReturn("DomainClass");
            when(relationshipConceptModel.getRange()).thenReturn("RangeClass");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(null);

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(null);
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(relationshipConceptModel.getContentType()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-CUSTOM-NS");

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
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with URI domain/range/super");

            String domainIri = "http://example.org/rel-domain";
            String rangeIri = "http://example.org/rel-range";
            String superRelationIri = "http://example.org/super-relation";

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            when(relationshipConceptModel.getDomain()).thenReturn(domainIri);
            when(relationshipConceptModel.getRange()).thenReturn(rangeIri);
            when(relationshipConceptModel.getSuperRelation()).thenReturn(superRelationIri);

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(null);
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(relationshipConceptModel.getContentType()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-URI-DOM-RANGE-SUPER");

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
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with label-based domain/range/super");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            // domain, range and superRelation are labels, not IRIs
            when(relationshipConceptModel.getDomain()).thenReturn("DomainLabel");
            when(relationshipConceptModel.getRange()).thenReturn("RangeLabel");
            when(relationshipConceptModel.getSuperRelation()).thenReturn("SuperRelationLabel");

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(null);
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(relationshipConceptModel.getContentType()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-LABEL-DOM-RANGE-SUPER");

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
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with blank domain/range/super");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            // blank values should be treated as "do not create"
            when(relationshipConceptModel.getDomain()).thenReturn("   ");
            when(relationshipConceptModel.getRange()).thenReturn("");
            when(relationshipConceptModel.getSuperRelation()).thenReturn("  ");

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn(null);
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn(null);
            when(relationshipConceptModel.getContentType()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-BLANK-DOM-RANGE-SUPER");

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
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with empty governance");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            when(relationshipConceptModel.getDomain()).thenReturn("DomainClass");
            when(relationshipConceptModel.getRange()).thenReturn("RangeClass");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(null);

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);

            when(relationshipConceptModel.getSharingMethod()).thenReturn("   ");
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn("");
            when(relationshipConceptModel.getContentType()).thenReturn(" ");

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);

            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-EMPTY-GOV");

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);
            assertFalse(result.hasProperty(result.getModel().createProperty(DEFAULT_NS + ZPUSOB_SDILENI)));
            assertFalse(result.hasProperty(result.getModel().createProperty(DEFAULT_NS + ZPUSOB_ZISKANI)));
            assertFalse(result.hasProperty(result.getModel().createProperty(DEFAULT_NS + TYP_OBSAHU)));
        }

        // --- C4. Sources and ExactMatch for RelationshipConcept ---

        @Test
        void createSingleConcept_ShouldAddExactMatchAndSourcesForRelationship() {
            // arrange
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getOntologyGraphName()).thenReturn(null);

            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relation with sources");

            when(relationshipConceptModel.getDescriptionModel()).thenReturn(null);
            when(relationshipConceptModel.getDefinitionModel()).thenReturn(null);
            when(relationshipConceptModel.getAltNameModel()).thenReturn(null);

            when(relationshipConceptModel.getDomain()).thenReturn("DomainClass");
            when(relationshipConceptModel.getRange()).thenReturn("RangeClass");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(null);

            when(relationshipConceptModel.getIsInPPDF()).thenReturn(Boolean.TRUE);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn("");

            when(relationshipConceptModel.getAgendaCode()).thenReturn(null);
            when(relationshipConceptModel.getAgendaSystemCode()).thenReturn(null);
            when(relationshipConceptModel.getSharingMethod()).thenReturn("sdileni-rel");
            when(relationshipConceptModel.getAcquisitionMethod()).thenReturn("ziskani-rel");
            when(relationshipConceptModel.getContentType()).thenReturn("obsah-rel");

            when(relationshipConceptModel.getDefiningLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2018/10"));
            when(relationshipConceptModel.getRelatedLegalSource())
                    .thenReturn(List.of("https://eselpoint.cz/eli/cz/act/2020/5"));
            when(relationshipConceptModel.getDefiningNonLegalSource())
                    .thenReturn(List.of("https://example.org/rel-doc-1"));
            when(relationshipConceptModel.getRelatedNonLegalSource())
                    .thenReturn(List.of("https://example.org/rel-doc-2"));

            when(relationshipConceptModel.getExactMatch())
                    .thenReturn(List.of("http://example.org/relExact1", "http://example.org/relExact2"));
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-SOURCES-1");

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            assertNotNull(result);

            Property definingLegal = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property relatedLegal = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);
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
            when(relationshipConceptModel.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
            when(relationshipConceptModel.getNameModel()).thenReturn(nameModel);
            when(nameModel.getName()).thenReturn("Relationship with mixed legal sources");

            when(relationshipConceptModel.getDomain()).thenReturn("RelDomain");
            when(relationshipConceptModel.getRange()).thenReturn("RelRange");
            when(relationshipConceptModel.getSuperRelation()).thenReturn(null);
            when(relationshipConceptModel.getIsInPPDF()).thenReturn(null);
            when(relationshipConceptModel.getIsPublic()).thenReturn("ano");
            when(relationshipConceptModel.getPrivacyProvision()).thenReturn(null);

            when(relationshipConceptModel.getDefiningLegalSource()).thenReturn(
                    List.of(
                            "https://eselpoint.cz/eli/cz/act/2020/5",
                            "http://example.org/not-eli-def"
                    )
            );
            when(relationshipConceptModel.getRelatedLegalSource()).thenReturn(
                    List.of(
                            "http://example.org/not-eli-rel",
                            "https://eselpoint.cz/eli/cz/act/2021/10"
                    )
            );

            when(relationshipConceptModel.getDefiningNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getRelatedNonLegalSource()).thenReturn(null);
            when(relationshipConceptModel.getExactMatch()).thenReturn(null);
            when(relationshipConceptModel.getIdentifier()).thenReturn("REL-MIXED-LEGAL");

            // act
            Resource result = conceptCreator.createSingleConcept(relationshipConceptModel);

            // assert
            Property defProp = result.getModel().createProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            Property relProp = result.getModel().createProperty(DEFAULT_NS + SOUVISEJICI_USTANOVENI);

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
}
