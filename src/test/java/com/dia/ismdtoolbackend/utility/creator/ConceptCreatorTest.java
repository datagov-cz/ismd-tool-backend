package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Rdf;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Rdf.iris;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Rdf.literals;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Rdf.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The RDF {@link ConceptCreator} writes for a new concept of each type. */
class ConceptCreatorTest {

    private static final String GRAPH = "https://example.org/slovnik/test-slovnik";
    private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/";
    private static final String LEGACY_ELI = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/";
    private static final String ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/";
    private static final String CODE_LIST = "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String DATASET = "https://data.gov.cz/zdroj/datové-sady/test-dataset";

    private static final Property DEFINING_LEGAL = Rdf.property(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
    private static final Property RELATED_LEGAL = Rdf.property(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);
    private static final Property SHARING = Rdf.property(OFN_NAMESPACE + ZPUSOB_SDILENI);
    private static final Property ACQUISITION = Rdf.property(OFN_NAMESPACE + ZPUSOB_ZISKANI);
    private static final Property CONTENT_TYPE = Rdf.property(OFN_NAMESPACE + TYP_OBSAHU);
    private static final Property AGENDA = Rdf.property(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
    private static final Property AIS = Rdf.property(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);
    private static final Property PPDF = Rdf.property(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
    private static final Property NAMESPACE_PROVISION = Rdf.property(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
    private static final Property LEGAL_PROVISION = Rdf.property(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
    private static final Property HIERARCHY = Rdf.property(DEFAULT_NS + "nadřazená-třída");
    private static final Property CODE_LIST_LINK = Rdf.property(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

    private final ConceptCreator conceptCreator = new ConceptCreator();

    private static void assertModelIs(Resource created, String turtle) {
        Model actual = created.getModel();
        Model expected = Rdf.model(turtle);
        assertThat(actual.isIsomorphicWith(expected))
                .as("missing: %s%nextra: %s", expected.difference(actual), actual.difference(expected))
                .isTrue();
    }

    private static Map<String, String> byLang(String... langValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < langValuePairs.length; i += 2) {
            map.put(langValuePairs[i], langValuePairs[i + 1]);
        }
        return map;
    }

    @Nested
    class ClassConcept {

        @Test
        void fullClassIsWrittenAsTheseTriples() {
            ClassConceptModel model = ConceptModels.trida("Test class", m -> {
                m.setIdentifier("ID-123");
                m.setOntologyGraphName(GRAPH);
                m.setType("subjekt");
                m.setIsPublic(true);
                m.setPrivacyProvisions(List.of(""));
                m.setDescriptionModel(Texts.description("cs", "Test description"));
                m.setDefinitionModel(Texts.definition("cs", " Test definition "));
                m.setAltNameModel(Texts.altName("cs", "Alt name", "Druhý název"));
            });

            Resource created = conceptCreator.createSingleConcept(model);

            String iri = GRAPH + "/pojem/id-123";
            assertThat(created.getURI()).isEqualTo(iri);
            assertModelIs(created, """
                    <%s> a skos:Concept, ofn:pojem, ofn:třída, ofn:typ-subjektu-práva, l111:veřejný-údaj ;
                        skos:prefLabel "Test class"@cs ;
                        dct:description "Test description"@cs ;
                        skos:definition "Test definition"@cs ;
                        skos:altLabel "Alt name"@cs, "Druhý název"@cs ;
                        skos:inScheme <%s> .
                    """.formatted(iri, GRAPH));
        }

        @Test
        void objectTypeAndNameDerivedIriWithoutGraph() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Věc", m -> m.setType("objekt")));

            assertThat(created.getURI()).isEqualTo(DEFAULT_NS + "pojem/věc");
            assertThat(types(created.getModel(), created.getURI())).containsExactlyInAnyOrder(
                    SKOS.Concept.getURI(), OFN_NAMESPACE + POJEM, OFN_NAMESPACE + TRIDA, OFN_NAMESPACE + TOP);
            assertThat(created.hasProperty(SKOS.inScheme)).isFalse();
        }

        @Test
        void typeNamingNeitherSubjectNorObjectAddsNoLegalType() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Věc", m -> m.setType("jiný")));

            assertThat(types(created.getModel(), created.getURI())).containsExactlyInAnyOrder(
                    SKOS.Concept.getURI(), OFN_NAMESPACE + POJEM, OFN_NAMESPACE + TRIDA);
        }

        @Test
        void classWithoutATypeIsAPlainClass() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Věc"));

            assertThat(types(created.getModel(), created.getURI())).containsExactlyInAnyOrder(
                    SKOS.Concept.getURI(), OFN_NAMESPACE + POJEM, OFN_NAMESPACE + TRIDA);
        }

        @Test
        void identifierThatIsAnIriIsUsedAsTheConceptIri() {
            String custom = "https://example.org/vlastní/pojem/osoba";

            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Osoba", m -> {
                m.setType("subjekt");
                m.setIdentifier(custom);
            }));

            assertThat(created.getURI()).isEqualTo(custom);
        }

        @Test
        void nameWithoutCzechFallsBackToAnotherLanguageForTheIri() {
            ClassConceptModel model = ConceptModels.trida("x", m -> {
                m.setType("objekt");
                m.setNameModel(Texts.name(byLang("en", "Person", "de", " ")));
            });

            Resource created = conceptCreator.createSingleConcept(model);

            assertThat(created.getURI()).isEqualTo(DEFAULT_NS + "pojem/person");
            assertThat(literals(created.getModel(), created.getURI(), SKOS.prefLabel)).containsExactly("Person@en");
        }

        @Test
        void blankLanguageTagFallsBackToCzech() {
            ClassConceptModel model = ConceptModels.trida("x", m -> {
                m.setType("objekt");
                m.setNameModel(Texts.name(byLang("cs", "Osoba")));
                m.setDescriptionModel(Texts.description(byLang(" ", "Popis", "en", "")));
                m.setDefinitionModel(Texts.definition(byLang("", "Definice")));
                m.setAltNameModel(Texts.altNames(Map.of(" ", List.of("Alias", " "))));
            });

            Resource created = conceptCreator.createSingleConcept(model);

            assertModelIs(created, """
                    <%s> a skos:Concept, ofn:pojem, ofn:třída, ofn:typ-objektu-práva ;
                        skos:prefLabel "Osoba"@cs ; dct:description "Popis"@cs ;
                        skos:definition "Definice"@cs ; skos:altLabel "Alias"@cs .
                    """.formatted(DEFAULT_NS + "pojem/osoba"));
        }

        @Test
        void missingOrNullTextValuesWriteNoLabels() {
            Map<String, String> names = byLang("cs", "Osoba", "en", null);
            Map<String, List<String>> altNames = new LinkedHashMap<>();
            altNames.put("cs", null);
            altNames.put("en", java.util.Arrays.asList("Person", null));
            ClassConceptModel model = ConceptModels.trida("x", m -> {
                m.setType("objekt");
                m.setNameModel(Texts.name(names));
                m.setDescriptionModel(new com.dia.ismdtoolbackend.models.DescriptionModel());
                m.setDefinitionModel(Texts.definition(byLang("cs", null)));
                m.setAltNameModel(Texts.altNames(altNames));
            });

            Resource created = conceptCreator.createSingleConcept(model);

            assertModelIs(created, """
                    <%s> a skos:Concept, ofn:pojem, ofn:třída, ofn:typ-objektu-práva ;
                        skos:prefLabel "Osoba"@cs ; skos:altLabel "Person"@en .
                    """.formatted(DEFAULT_NS + "pojem/osoba"));
        }

        @Test
        void conceptWithoutANameGetsTheUnnamedIri() {
            ClassConceptModel model = ConceptModels.trida("x", m -> {
                m.setType("objekt");
                m.setNameModel(Texts.name(Map.of()));
            });

            Resource created = conceptCreator.createSingleConcept(model);

            assertThat(created.getURI()).isEqualTo(DEFAULT_NS + "pojem/unnamed");
            assertThat(created.hasProperty(SKOS.prefLabel)).isFalse();
        }

        @Test
        void broaderConceptsAreWrittenAsSubClassAndHierarchyLinks() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Dítě", m -> {
                m.setType("objekt");
                m.setBroaderConcept(List.of("Rodič jedna", "https://example.org/pojem/rodič-dva", " "));
            }));

            List<String> expected = List.of(DEFAULT_NS + "pojem/rodič-jedna", "https://example.org/pojem/rodič-dva");
            assertThat(iris(created, RDFS.subClassOf)).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(iris(created, HIERARCHY)).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        void emptyBroaderListWritesNoHierarchy() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setBroaderConcept(List.of());
            }));

            assertThat(created.hasProperty(RDFS.subClassOf)).isFalse();
        }

        @Test
        void governanceItemsAgendaAisAndPpdfAreWritten() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setSharingMethod(List.of("nesdílené", " "));
                m.setAcquisitionMethod("vlastní");
                m.setContentType("provozní");
                m.setAgendaCode("A123");
                m.setAgendaSystemCode("456");
                m.setIsInPPDF(true);
            }));

            assertThat(iris(created, SHARING)).containsExactly(ITEMS + "způsoby-sdílení-údajů/položky/nesdílené");
            assertThat(iris(created, ACQUISITION)).containsExactly(ITEMS + "způsoby-získání-údajů/položky/vlastní");
            assertThat(iris(created, CONTENT_TYPE)).containsExactly(ITEMS + "typy-obsahu-údajů/položky/provozní");
            assertThat(iris(created, AGENDA)).containsExactly("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A123");
            assertThat(iris(created, AIS)).containsExactly("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/456");
            assertThat(created.getProperty(PPDF).getBoolean()).isTrue();
        }

        @Test
        void blankOrInvalidGovernanceValuesWriteNothing() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setSharingMethod(List.of("   "));
                m.setAcquisitionMethod("");
                m.setContentType(" ");
                m.setAgendaCode("not-an-agenda");
                m.setAgendaSystemCode("not-an-ais");
            }));

            for (Property property : List.of(SHARING, ACQUISITION, CONTENT_TYPE, AGENDA, AIS, PPDF)) {
                assertThat(created.hasProperty(property)).as(property.getURI()).isFalse();
            }
        }

        @Test
        void codeListIsANamedSubjectWithItsTypeAndDataset() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Cíl", m -> {
                m.setType("objekt");
                m.setCodeListIri(CODE_LIST);
                m.setCodeListDataset(DATASET);
            }));

            assertModelIs(created, """
                    <%1$s> a skos:Concept, ofn:pojem, ofn:třída, ofn:typ-objektu-práva ;
                        skos:prefLabel "Cíl"@cs ;
                        ofn:%2$s <%3$s> .
                    <%3$s> a l111:%4$s ; l111:%5$s <%6$s> .
                    """.formatted(DEFAULT_NS + "pojem/cíl", MA_INSTANCE_DEFINOVANE_CISELNIKEM, CODE_LIST,
                    CISELNIK, MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU, DATASET));
        }

        @Test
        void percentEncodedDatasetIsStoredAsRawUtf8() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Cíl", m -> {
                m.setType("objekt");
                m.setCodeListIri(CODE_LIST);
                m.setCodeListDataset("https://data.gov.cz/zdroj/datov%C3%A9-sady/test-dataset");
            }));

            Resource codeList = created.getPropertyResourceValue(CODE_LIST_LINK);
            assertThat(iris(codeList, Rdf.property(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU)))
                    .containsExactly(DATASET);
        }

        @Test
        void datasetWithoutCodeListIriIsRejected() {
            ClassConceptModel model = ConceptModels.trida("Cíl", m -> {
                m.setType("objekt");
                m.setCodeListDataset(DATASET);
            });

            assertThatThrownBy(() -> conceptCreator.createSingleConcept(model))
                    .isExactlyInstanceOf(OntologyException.class)
                    .hasMessage("Je uvedena datová sada v NKOD, ale chybí IRI číselníku");
        }

        @Test
        void codeListIriWithoutDatasetIsRejected() {
            ClassConceptModel model = ConceptModels.trida("Cíl", m -> {
                m.setType("objekt");
                m.setCodeListIri(CODE_LIST);
            });

            assertThatThrownBy(() -> conceptCreator.createSingleConcept(model))
                    .isExactlyInstanceOf(OntologyException.class)
                    .hasMessage("Je uvedeno IRI číselníku, ale chybí IRI zastřešující datové sady v NKOD");
        }

        @Test
        void datasetOutsideNkodIsRejected() {
            ClassConceptModel model = ConceptModels.trida("Cíl", m -> {
                m.setType("objekt");
                m.setCodeListIri(CODE_LIST);
                m.setCodeListDataset("https://example.org/dataset");
            });

            assertThatThrownBy(() -> conceptCreator.createSingleConcept(model))
                    .isExactlyInstanceOf(OntologyException.class)
                    .hasMessage("Neplatná URL datové sady v NKOD: https://example.org/dataset");
        }

        @Test
        void conceptWithoutAConcreteTypeIsRejected() {
            ClassConceptModel untyped = new ClassConceptModel() {
                @Override
                public ConceptType getConceptTypeEnum() {
                    return ConceptType.KONCEPT;
                }
            };
            untyped.setNameModel(Texts.name("cs", "Pojem"));

            assertThatThrownBy(() -> conceptCreator.createSingleConcept(untyped))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Nelze vytvořit pojem bez konkrétního typu (TRIDA/VLASTNOST/VZTAH).");
        }
    }

    @Nested
    class Sources {

        @Test
        void legalSourcesAreStoredVerbatimPerRole() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setDefiningLegalSource(List.of(ELI + "2021/12"));
                m.setRelatedLegalSource(List.of(ELI + "2022/100"));
            }));

            assertThat(iris(created, DEFINING_LEGAL)).containsExactly(ELI + "2021/12");
            assertThat(iris(created, RELATED_LEGAL)).containsExactly(ELI + "2022/100");
        }

        @Test
        void legacyHostLegalSourcesAreCanonicalized() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setDefiningLegalSource(List.of(LEGACY_ELI + "2021/12"));
                m.setRelatedLegalSource(List.of(LEGACY_ELI + "2022/100"));
            }));

            assertThat(iris(created, DEFINING_LEGAL)).containsExactly(ELI + "2021/12");
            assertThat(iris(created, RELATED_LEGAL)).containsExactly(ELI + "2022/100");
        }

        @Test
        void nonEliAndBlankLegalSourcesAreSkipped() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vztah("Vztah", m -> {
                m.setDefiningLegalSource(List.of(ELI + "2020/5", "http://example.org/not-eli-def", " "));
                m.setRelatedLegalSource(List.of("http://example.org/not-eli-rel", ELI + "2021/10"));
            }));

            assertThat(iris(created, DEFINING_LEGAL)).containsExactly(ELI + "2020/5");
            assertThat(iris(created, RELATED_LEGAL)).containsExactly(ELI + "2021/10");
        }

        @Test
        void emptyLegalSourceListsWriteNothing() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setDefiningLegalSource(List.of());
                m.setRelatedLegalSource(List.of(ELI + "2024/7"));
            }));

            assertThat(created.hasProperty(DEFINING_LEGAL)).isFalse();
            assertThat(iris(created, RELATED_LEGAL)).containsExactly(ELI + "2024/7");
        }

        @Test
        void nonLegalSourcesAreDigitalObjectsAndInvalidUrlsAreSkipped() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setDefiningNonLegalSource(List.of(
                        new DigitalObjectModel("Doc A", "Popis A", "https://example.org/doc1"),
                        new DigitalObjectModel("Bez url", null, " "),
                        new DigitalObjectModel("Špatná url", null, "not a url")));
                m.setRelatedNonLegalSource(List.of(new DigitalObjectModel(null, " ", "https://example.org/doc2")));
            }));

            assertModelIs(created, """
                    <%s> a skos:Concept, ofn:pojem, ofn:třída, ofn:typ-subjektu-práva ;
                        skos:prefLabel "Třída"@cs ;
                        sl:%s [ a <%s> ; schema:url <https://example.org/doc1> ;
                                dct:title "Doc A"@cs ; dct:description "Popis A"@cs ] ;
                        sl:%s [ a <%s> ; schema:url <https://example.org/doc2> ] .
                    """.formatted(DEFAULT_NS + "pojem/třída", DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT,
                    SOUVISEJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT));
        }

        @Test
        void onlyValidExactMatchIrisAreWritten() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("subjekt");
                m.setExactMatch(List.of("http://example.org/exact-valid", "not-a-valid-iri", "", " http://example.org/two "));
            }));

            assertThat(iris(created, SKOS.exactMatch))
                    .containsExactlyInAnyOrder("http://example.org/exact-valid", "http://example.org/two");
        }
    }

    @Nested
    class PrivacyAndClassification {

        private static final String PROVISION = ELI + "2020/50";

        @Test
        void provisionWithoutClassificationIsKeptUnderTheVocabularyNamespaceOnly() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("objekt");
                m.setPrivacyProvisions(List.of(PROVISION));
            }));

            assertThat(iris(created, NAMESPACE_PROVISION)).containsExactly(PROVISION);
            assertThat(created.hasProperty(LEGAL_PROVISION)).isFalse();
            assertThat(types(created.getModel(), created.getURI()))
                    .doesNotContain(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ, OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        }

        @Test
        void nonPublicWithAProvisionIsNeverejnyUdajWithTheProvisionInBothForms() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vztah("Soukromý vztah", m -> {
                m.setIsPublic(false);
                m.setPrivacyProvisions(List.of(PROVISION, "http://example.org/not-eli"));
            }));

            assertThat(types(created.getModel(), created.getURI()))
                    .contains(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ)
                    .doesNotContain(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
            assertThat(iris(created, LEGAL_PROVISION)).containsExactly(PROVISION);
            assertThat(iris(created, NAMESPACE_PROVISION)).containsExactly(PROVISION);
        }

        @Test
        void nonPublicWithoutAValidProvisionHasNoClassification() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.trida("Třída", m -> {
                m.setType("objekt");
                m.setIsPublic(false);
                m.setPrivacyProvisions(List.of("http://example.org/not-eli-format"));
            }));

            assertThat(types(created.getModel(), created.getURI()))
                    .doesNotContain(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ, OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
            assertThat(created.hasProperty(NAMESPACE_PROVISION)).isFalse();
        }

        @Test
        void publicWithAProvisionIsNotVerejnyUdaj() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vlastnost("Vlastnost", m -> {
                m.setIsPublic(true);
                m.setPrivacyProvisions(List.of(PROVISION));
            }));

            assertThat(types(created.getModel(), created.getURI()))
                    .doesNotContain(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ, OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);
        }
    }

    @Nested
    class PropertyConcept {

        @Test
        void fullPropertyIsWrittenAsTheseTriples() {
            PropertyConceptModel model = ConceptModels.vlastnost("Hmotnost", m -> {
                m.setOntologyGraphName(GRAPH);
                m.setDataType("xsd:decimal");
                m.setDomain("Vozidlo");
                m.setSuperProperty(List.of("https://example.org/pojem/velicina", " "));
                m.setIsPublic(true);
            });

            Resource created = conceptCreator.createSingleConcept(model);

            String iri = GRAPH + "/pojem/hmotnost";
            assertModelIs(created, """
                    <%1$s> a skos:Concept, owl:DatatypeProperty, ofn:pojem, ofn:vlastnost, l111:veřejný-údaj ;
                        skos:prefLabel "Hmotnost"@cs ;
                        skos:inScheme <%2$s> ;
                        rdfs:domain <%2$s/pojem/vozidlo> ;
                        rdfs:range xsd:decimal ;
                        rdfs:subPropertyOf <https://example.org/pojem/velicina> .
                    """.formatted(iri, GRAPH));
        }

        @ParameterizedTest(name = "data type \"{0}\" → range {1}")
        @CsvSource(nullValues = "NULL", value = {
                "xsd:string, http://www.w3.org/2001/XMLSchema#string",
                "SomeOtherClass, http://www.w3.org/2000/01/rdf-schema#Literal",
                "xsd:notAType, http://www.w3.org/2000/01/rdf-schema#Literal",
                "https://example.org/typ, https://example.org/typ",
                "' ', http://www.w3.org/2000/01/rdf-schema#Literal",
                "NULL, http://www.w3.org/2000/01/rdf-schema#Literal"
        })
        void dataTypeBecomesTheRange(String dataType, String expectedRange) {
            Resource created = conceptCreator.createSingleConcept(
                    ConceptModels.vlastnost("Vlastnost", m -> m.setDataType(dataType)));

            assertThat(iris(created, RDFS.range)).containsExactly(expectedRange);
        }

        @Test
        void propertyIsNeverAlsoAnObjectProperty() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vlastnost("Vlastnost", m -> {
                m.setDataType("SomeOtherClass");
                m.setIsPublic(false);
            }));

            assertThat(types(created.getModel(), created.getURI()))
                    .contains(OFN_NAMESPACE + VLASTNOST, OWL2.DatatypeProperty.getURI())
                    .doesNotContain(OWL2.ObjectProperty.getURI(), OFN_NAMESPACE + VZTAH);
        }

        @Test
        void blankDomainIsNotWritten() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vlastnost("Vlastnost", m -> m.setDomain(" ")));

            assertThat(created.hasProperty(RDFS.domain)).isFalse();
        }

        @Test
        void governanceOfAPropertyIsWritten() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vlastnost("Vlastnost", m -> {
                m.setIsInPPDF(false);
                m.setSharingMethod(List.of("veřejně přístupné"));
                m.setAcquisitionMethod("jiných agend");
                m.setContentType("evidenční");
                m.setAgendaCode("7");
            }));

            assertThat(created.getProperty(PPDF).getBoolean()).isFalse();
            assertThat(iris(created, SHARING)).containsExactly(ITEMS + "způsoby-sdílení-údajů/položky/veřejně-přístupné");
            assertThat(iris(created, ACQUISITION)).containsExactly(ITEMS + "způsoby-získání-údajů/položky/jiných-agend");
            assertThat(iris(created, CONTENT_TYPE)).containsExactly(ITEMS + "typy-obsahu-údajů/položky/evidenční");
            assertThat(iris(created, AGENDA)).containsExactly("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A7");
        }
    }

    @Nested
    class RelationshipConcept {

        @Test
        void fullRelationshipIsWrittenAsTheseTriples() {
            RelationshipConceptModel model = ConceptModels.vztah("Řídí", m -> {
                m.setOntologyGraphName(GRAPH + "/");
                m.setDomain("https://example.org/pojem/řidič");
                m.setRange("Vozidlo");
                m.setSuperRelation(List.of("Ovládá"));
                m.setIsPublic(true);
            });

            Resource created = conceptCreator.createSingleConcept(model);

            assertModelIs(created, """
                    <%1$s/pojem/řídí> a skos:Concept, owl:ObjectProperty, ofn:pojem, ofn:vztah, l111:veřejný-údaj ;
                        skos:prefLabel "Řídí"@cs ;
                        skos:inScheme <%1$s/> ;
                        rdfs:domain <https://example.org/pojem/řidič> ;
                        rdfs:range <%1$s/pojem/vozidlo> ;
                        rdfs:subPropertyOf <%1$s/pojem/ovládá> .
                    """.formatted(GRAPH));
        }

        @Test
        void blankDomainRangeAndSuperRelationAreNotWritten() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vztah("Vztah", m -> {
                m.setDomain("   ");
                m.setRange("");
                m.setSuperRelation(List.of(""));
            }));

            assertThat(created.hasProperty(RDFS.domain)).isFalse();
            assertThat(created.hasProperty(RDFS.range)).isFalse();
            assertThat(created.hasProperty(RDFS.subPropertyOf)).isFalse();
        }

        @Test
        void invalidGraphNameFallsBackToTheDefaultNamespace() {
            Resource created = conceptCreator.createSingleConcept(ConceptModels.vztah("Vztah", m -> m.setOntologyGraphName("   ")));

            assertThat(created.getURI()).isEqualTo(DEFAULT_NS + "pojem/vztah");
            assertThat(created.hasProperty(SKOS.inScheme)).isFalse();
        }
    }
}
