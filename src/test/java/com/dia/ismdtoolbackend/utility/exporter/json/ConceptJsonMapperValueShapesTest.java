package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.ontology.OntModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@link ConceptJsonMapper} treats each RDF value shape (IRI, literal, blank literal, blank
 * node, untagged literal) per field. The happy paths live in the {@code Concept*Test} classes.
 */
@DisplayName("ConceptJsonMapper - value shapes per field")
class ConceptJsonMapperValueShapesTest {

    private static final String CONCEPT = Ttl.POJEM + "x";
    private static final String HEADER = "ex: a owl:Ontology .\n";

    private final ConceptJsonMapper mapper = new ConceptJsonMapper();

    /** Maps {@code pojem:x}, a skos:Concept carrying {@code properties}, plus any extra Turtle. */
    private Map<String, Object> map(String properties, String extraTurtle) {
        OntModel model = Ttl.parseOnt(HEADER + "pojem:x a skos:Concept ; " + properties + " .\n" + extraTurtle);
        return mapper.processConceptByIri(model, new ModelAnalyzer().analyzeModel(model), CONCEPT);
    }

    private Map<String, Object> map(String properties) {
        return map(properties, "");
    }

    @Nested
    @DisplayName("labels")
    class Labels {

        @Test
        @DisplayName("skip empty values, default to cs, and collect same-language values into a list")
        void prefLabelValues() {
            Map<String, Object> result = map("skos:prefLabel \"\"@en, \"Bez jazyka\", \"Druhý\"@cs, \"Třetí\"@cs");

            assertThat(result.get(NAZEV)).isEqualTo(Map.of("cs", List.of("Bez jazyka", "Druhý", "Třetí")));
        }

        @Test
        @DisplayName("are omitted when every value is empty")
        void allEmptyOmitted() {
            Map<String, Object> result = map("""
                    skos:prefLabel "" ; skos:altLabel ""@cs ; skos:definition "" ; dct:description ""
                    """);

            assertThat(result).doesNotContainKeys(NAZEV, ALTERNATIVNI_NAZEV, DEFINICE, POPIS);
        }

        @Test
        @DisplayName("alternative names are always lists, untagged ones under cs")
        void alternativeNamesAreLists() {
            Map<String, Object> result = map("skos:altLabel \"Jediný\", \"\"@cs, \"Single\"@en");

            assertThat(result.get(ALTERNATIVNI_NAZEV))
                    .isEqualTo(Map.of("cs", List.of("Jediný"), "en", List.of("Single")));
        }

        @Test
        @DisplayName("several descriptions in one language become a list")
        void descriptionValues() {
            Map<String, Object> result = map("dct:description \"První\", \"\"@cs, \"Druhý\"@cs");

            assertThat(result.get(POPIS)).isEqualTo(Map.of("cs", List.of("První", "Druhý")));
        }

        @Test
        @DisplayName("an IRI-valued label fails the concept, and only that concept is left out of the export")
        void iriLabelFailsConcept() {
            OntModel model = Ttl.parseOnt(HEADER + """
                    pojem:x a skos:Concept ; skos:prefLabel ex:jiný .
                    pojem:y a skos:Concept ; skos:prefLabel "Y"@cs .
                    """);
            ModelStructure structure = new ModelAnalyzer().analyzeModel(model);

            assertThatThrownBy(() -> mapper.processConceptByIri(model, structure, CONCEPT))
                    .isInstanceOf(ModelProcessingException.class);
            assertThat(mapper.processAllConcepts(model, structure).getConcepts())
                    .extracting(c -> c.get("iri"))
                    .containsExactly(Ttl.POJEM + "y");
        }
    }

    @Nested
    @DisplayName("IRI lists (identifier, exact match, provisions)")
    class IriLists {

        @Test
        @DisplayName("keep IRIs and non-blank literals, drop blank literals")
        void irisAndLiterals() {
            Map<String, Object> result = map("""
                    dct:identifier <https://example.org/id/1>, "ID-1", "  " ;
                    skos:exactMatch <http://eurovoc.europa.eu/1>, "http://eurovoc.europa.eu/2", " " ;
                    slovníky:definující-ustanovení "§ 5", ""
                    """);

            assertThat(result.get(IDENTIFIKATOR)).asInstanceOf(LIST)
                    .containsExactlyInAnyOrder("https://example.org/id/1", "ID-1");
            assertThat(result.get(EKVIVALENTNI_POJEM)).asInstanceOf(LIST)
                    .containsExactlyInAnyOrder("http://eurovoc.europa.eu/1", "http://eurovoc.europa.eu/2");
            assertThat(result.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU)).isEqualTo(List.of("§ 5"));
        }

        @Test
        @DisplayName("are omitted when every value is blank")
        void allBlankOmitted() {
            Map<String, Object> result = map("dct:identifier \" \" ; skos:exactMatch \"\"");

            assertThat(result).doesNotContainKeys(IDENTIFIKATOR, EKVIVALENTNI_POJEM);
        }

        @Test
        @DisplayName("privacy provisions under both property IRIs merge without duplicates")
        void privacyProvisionsMerged() {
            Map<String, Object> result = map("""
                    l111:ustanovení-dokládající-neveřejnost-údaje <https://zakon.example.org/a> ;
                    l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost
                        <https://zakon.example.org/a>, <https://zakon.example.org/b>
                    """);

            assertThat(result.get(USTANOVENI_NEVEREJNOST)).asInstanceOf(LIST)
                    .containsExactlyInAnyOrder("https://zakon.example.org/a", "https://zakon.example.org/b");
        }
    }

    @Nested
    @DisplayName("non-legislative sources")
    class NonLegislativeSources {

        @Test
        @DisplayName("under the vocabulary namespace map each linked document, skipping literals and empty nodes")
        void vocabularyNamespaceProperty() {
            Map<String, Object> result = map("""
                    ex:definující-nelegislativní-zdroj <https://example.org/doc/1>, "literál", [ ]
                    """, """
                    <https://example.org/doc/1> dct:title "Norma"@cs .
                    """);

            assertThat(result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ)).isEqualTo(List.of(
                    Map.of("iri", "https://example.org/doc/1", NAZEV, Map.of("cs", "Norma"))));
        }

        @Test
        @DisplayName("under any other namespace are found by local name, also after #")
        void anyNamespaceByLocalName() {
            Map<String, Object> result = map("""
                    <https://example.org/onto#související-nelegislativní-zdroj> <https://example.org/doc/2>
                    """);

            assertThat(result.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ))
                    .isEqualTo(List.of(Map.of("iri", "https://example.org/doc/2")));
        }

        @Test
        @DisplayName("are omitted when every linked value is a literal or an empty node")
        void onlyLiteralsOmitted() {
            assertThat(map("slovníky:definující-nelegislativní-zdroj \"literál\", [ ]"))
                    .doesNotContainKey(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            assertThat(map("ex:definující-nelegislativní-zdroj \"literál\", [ ]"))
                    .doesNotContainKey(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        }

        @Test
        @DisplayName("ignore a predicate whose IRI ends with a separator")
        void predicateEndingWithSeparatorIgnored() {
            assertThat(map("<https://example.org/definující-nelegislativní-zdroj/> <https://example.org/doc/1>"))
                    .doesNotContainKey(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        }

        @Test
        @DisplayName("documents: untagged text under cs, blank and non-literal text dropped, url as IRI or literal")
        void documentFields() {
            Map<String, Object> result = map("""
                    slovníky:definující-nelegislativní-zdroj <https://example.org/doc/1>, <https://example.org/doc/2>,
                        <https://example.org/doc/3>
                    """, """
                    <https://example.org/doc/1> dct:title "Bez jazyka", " "@en, ex:jiný ;
                        dct:description "Popis", ""@en, ex:jiný ;
                        schema:url "https://example.org/files/1.pdf" .
                    <https://example.org/doc/2> dct:title " " ; dct:description " " ; schema:url "  " .
                    <https://example.org/doc/3> a do:digitální-objekt ; schema:url <https://example.org/files/3.pdf> .
                    """);

            assertThat(result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ)).asInstanceOf(LIST).containsExactlyInAnyOrder(
                    Map.of("iri", "https://example.org/doc/1",
                            NAZEV, Map.of("cs", "Bez jazyka"),
                            POPIS, Map.of("cs", "Popis"),
                            "url", "https://example.org/files/1.pdf"),
                    Map.of("iri", "https://example.org/doc/2"),
                    Map.of("iri", "https://example.org/doc/3", "typ", "Digitální objekt",
                            "url", "https://example.org/files/3.pdf"));
        }
    }

    @Nested
    @DisplayName("relations")
    class Relations {

        @Test
        @DisplayName("domain and range: IRIs kept, XSD ranges shortened, anonymous ones dropped")
        void domainAndRange() {
            assertThat(map("rdfs:domain pojem:osoba ; rdfs:range pojem:adresa"))
                    .containsEntry(DEFINICNI_OBOR, Ttl.POJEM + "osoba")
                    .containsEntry(OBOR_HODNOT, Ttl.POJEM + "adresa");
            assertThat(map("rdfs:range xsd:date")).containsEntry(OBOR_HODNOT, "xsd:date");
            assertThat(map("rdfs:domain [ a owl:Class ] ; rdfs:range [ a owl:Class ]"))
                    .doesNotContainKeys(DEFINICNI_OBOR, OBOR_HODNOT);
            assertThat(map("rdfs:domain \"osoba\" ; rdfs:range \"adresa\""))
                    .doesNotContainKeys(DEFINICNI_OBOR, OBOR_HODNOT);
        }

        @Test
        @DisplayName("hierarchies with only literal parents are omitted")
        void literalParentsOmitted() {
            assertThat(map("skos:broader \"rodič\" ; rdfs:subClassOf \"rodič\" ; rdfs:subPropertyOf \"rodič\""))
                    .doesNotContainKeys("nadřazený-pojem", NADRAZENA_TRIDA, NADRAZENY_VZTAH, NADRAZENA_VLASTNOST);
        }

        @Test
        @DisplayName("rdfs:subPropertyOf maps by role: vlastnost to nadřazená-vlastnost, other concepts nowhere")
        void subPropertyByRole() {
            OntModel model = Ttl.parseOnt(HEADER + """
                    pojem:jméno a slovníky:vlastnost ; rdfs:subPropertyOf pojem:označení .
                    pojem:x a skos:Concept ; rdfs:subPropertyOf pojem:označení .
                    """);
            ModelStructure structure = new ModelAnalyzer().analyzeModel(model);

            assertThat(mapper.processConceptByIri(model, structure, Ttl.POJEM + "jméno"))
                    .containsEntry(NADRAZENA_VLASTNOST, List.of(Ttl.POJEM + "označení"));
            assertThat(mapper.processConceptByIri(model, structure, CONCEPT))
                    .doesNotContainKeys(NADRAZENA_VLASTNOST, NADRAZENY_VZTAH);
        }
    }

    @Nested
    @DisplayName("governance and metadata")
    class Governance {

        @Test
        @DisplayName("sharing methods split on ';', trim, drop blanks and accept IRIs")
        void sharingMethods() {
            Map<String, Object> result = map("""
                    slovníky:má-způsob-sdílení-údaje " a ; ;b ", "  ", <https://example.org/ppdf>
                    """);

            assertThat(result.get(ZPUSOBY_SDILENI_ALT)).asInstanceOf(LIST)
                    .containsExactlyInAnyOrder("a", "b", "https://example.org/ppdf");
        }

        @Test
        @DisplayName("single-valued governance fields: IRIs kept, literals trimmed, blank values omitted")
        void singleValuedFields() {
            assertThat(map("""
                    slovníky:má-způsob-získání-údaje <https://example.org/vlastní> ;
                    slovníky:má-typ-obsahu-údaje "  osobní  "
                    """))
                    .containsEntry(ZPUSOB_ZISKANI_ALT, "https://example.org/vlastní")
                    .containsEntry(TYP_OBSAHU_ALT, "osobní");
            assertThat(map("slovníky:má-způsob-sdílení-údaje \" \" ; slovníky:má-typ-obsahu-údaje \" \""))
                    .doesNotContainKeys(ZPUSOBY_SDILENI_ALT, TYP_OBSAHU_ALT);
        }

        @Test
        @DisplayName("PPDF flag only from a literal; agenda and AIS from an IRI or a non-blank literal")
        void metadataFields() {
            assertThat(map("""
                    a104:je-sdílen-v-propojeném-datovém-fondu false ;
                    a104:údaje-jsou-v-ais "ISEO" ;
                    a104:sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy <https://rpp.example.org/A1>
                    """))
                    .containsEntry(JE_PPDF, false)
                    .containsEntry(AIS, "ISEO")
                    .containsEntry(AGENDA, "https://rpp.example.org/A1");
            assertThat(map("""
                    a104:je-sdílen-v-propojeném-datovém-fondu ex:ano ;
                    a104:údaje-jsou-v-ais " " ;
                    a104:sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy ""
                    """))
                    .doesNotContainKeys(JE_PPDF, AIS, AGENDA);
        }
    }

    @Nested
    @DisplayName("code list")
    class CodeList {

        @Test
        @DisplayName("needs a named číselník with an IRI dataset")
        void needsTypedCodeListWithDataset() {
            Map<String, Object> result = map("""
                    slovníky:má-instance-definované-číselníkem "literál", ex:netypovaný, ex:bez-sady, ex:sada-literál,
                        ex:číselník
                    """, """
                    ex:bez-sady a l111:číselník .
                    ex:sada-literál a l111:číselník ; l111:má-v-nkod-zastřešující-datovou-sadu "sada" .
                    ex:číselník a l111:číselník ; l111:má-v-nkod-zastřešující-datovou-sadu <https://data.gov.cz/sada/1> .
                    """);

            assertThat(result.get(INSTANCE_DEFINOVANY_CISELNIKEM)).isEqualTo(Map.of(
                    "iri", Ttl.VOCABULARY + "číselník",
                    "typ", CISELNIK_JSON_LD,
                    DATOVA_SADA_V_NKOD, "https://data.gov.cz/sada/1"));
        }

        @Test
        @DisplayName("is omitted when no linked číselník qualifies")
        void omittedWithoutQualifyingCodeList() {
            assertThat(map("slovníky:má-instance-definované-číselníkem ex:netypovaný"))
                    .doesNotContainKey(INSTANCE_DEFINOVANY_CISELNIKEM);
        }
    }

    @Test
    @DisplayName("rejects a resource that is not a concept")
    void nonConceptRejected() {
        OntModel model = Ttl.parseOnt(HEADER + "pojem:x a rdfs:Class .");

        assertThatThrownBy(() -> mapper.processConceptByIri(model, new ModelAnalyzer().analyzeModel(model), CONCEPT))
                .isInstanceOf(ModelProcessingException.class)
                .hasMessageContaining("not a valid concept");
    }

    private static final org.assertj.core.api.InstanceOfAssertFactory<List, org.assertj.core.api.ListAssert<Object>> LIST =
            org.assertj.core.api.InstanceOfAssertFactories.LIST;
}
