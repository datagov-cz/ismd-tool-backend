package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OfnConformanceGateTest {

    private static final String NS = "https://slovník.gov.cz/test/pojem/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OfnConformanceGate gate = new OfnConformanceGate(OfnSpecification.bundled());

    @ParameterizedTest
    @ValueSource(strings = {"excel", "archi", "ea"})
    void validatorGoldenOutput_passesUnchanged(String name) throws IOException {
        ObjectNode document = read("ofn/golden/" + name + ".jsonld");

        GateResult result = gate.apply(document);

        assertThat(result.issues()).isEmpty();
        assertThat(result.document()).isEqualTo(document);
    }

    @Test
    void input_isNotModified() {
        ObjectNode document = vocabulary(třída("a").put("neznámé-pole", "x"));
        ObjectNode before = document.deepCopy();

        gate.apply(document);

        assertThat(document).isEqualTo(before);
    }

    @Test
    void keyNotInContext_isDropped() {
        GateResult result = gate.apply(vocabulary(třída("a").put("neznámé-pole", "x")));

        assertThat(concept(result, "a").has("neznámé-pole")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.conceptIri()).isEqualTo(NS + "a");
                    assertThat(issue.location()).isEqualTo("pojmy[a]/neznámé-pole");
                });
    }

    @Test
    void nonIriValue_inIdTerm_isDropped_otherValuesKept() {
        ObjectNode a = třída("a");
        a.putArray("ekvivalentní-pojem").add("není iri").add("https://example.org/b");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").get("ekvivalentní-pojem"))
                .containsExactly(mapper.getNodeFactory().textNode("https://example.org/b"));
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).hasSize(1);
    }

    @Test
    void singleValue_inSetTerm_isWrapped_singleItemArray_inSingleTerm_isUnwrapped() {
        ObjectNode a = třída("a").put("ekvivalentní-pojem", "https://example.org/b");
        a.putArray("agenda").add("https://example.org/agenda");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").get("ekvivalentní-pojem").isArray()).isTrue();
        assertThat(concept(result, "a").get("agenda").asText()).isEqualTo("https://example.org/agenda");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void wrongBooleanAndLanguageShapes_areDropped() {
        ObjectNode a = vlastnost("a", "b").put("je-sdílen-v-ppdf", "ano");
        a.putObject("definice").put("česky", "text");

        GateResult result = gate.apply(vocabulary(třída("b"), a));

        assertThat(concept(result, "a").has("je-sdílen-v-ppdf")).isFalse();
        assertThat(concept(result, "a").has("definice")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).hasSize(2);
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).isEmpty();
    }

    @Test
    void unknownType_isDropped() {
        ObjectNode a = třída("a");
        ((ArrayNode) a.get("typ")).add("Neexistující typ");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(types(concept(result, "a"))).doesNotContain("Neexistující typ");
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).hasSize(1);
    }

    @Test
    void roleMarkerType_isDropped() {
        ObjectNode a = třída("a");
        ((ArrayNode) a.get("typ")).add("slovníky:třída");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(types(concept(result, "a"))).doesNotContain("slovníky:třída");
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).hasSize(1);
    }

    @Test
    void vlastnostWithoutDomain_isIncomplete_andKept() {
        ObjectNode a = vlastnost("a", "b");
        a.remove("definiční-obor");

        GateResult result = gate.apply(vocabulary(třída("b"), a));

        assertThat(iris(result)).containsExactly(NS + "b", NS + "a");
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).isEmpty();
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.conceptIri()).isEqualTo(NS + "a");
                    assertThat(issue.message()).contains("chybí povinné pole definiční-obor");
                    assertThat(issue.kind().isCompleteness()).isTrue();
                });
    }

    @Test
    void nonPublicDataWithoutProvision_isIncomplete() {
        ObjectNode a = třída("a");
        ((ArrayNode) a.get("typ")).add("Neveřejný údaj");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(iris(result)).containsExactly(NS + "a");
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE))
                .singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("ustanovení-dokládající-neveřejnost-údaje"));
    }

    @Test
    void conceptMissingRequiredType_isExcluded() {
        ObjectNode a = třída("a");
        a.putArray("typ").add("Koncept").add("Třída"); // no "Pojem": a `contains` failure, not a missing field

        GateResult result = gate.apply(vocabulary(a));

        assertThat(iris(result)).isEmpty();
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).hasSize(1);
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED).get(0).kind().isCompleteness()).isFalse();
    }

    @Test
    void exclusion_cascades_throughReferences() {
        ObjectNode b = třída("b");
        b.putArray("typ").add("Koncept").add("Třída"); // b fails the schema
        ObjectNode a = vlastnost("a", "b");           // a's only domain is b
        ObjectNode c = třída("c");
        c.putArray("nadřazená-třída").add(NS + "b");  // optional reference to b

        GateResult result = gate.apply(vocabulary(b, a, c));

        assertThat(iris(result)).containsExactly(NS + "a", NS + "c");
        assertThat(concept(result, "a").has("definiční-obor")).isFalse();
        assertThat(concept(result, "c").has("nadřazená-třída")).isFalse();
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED))
                .extracting(ConformanceIssue::conceptIri)
                .containsExactly(NS + "b");
        assertThat(result.issuesOfKind(Kind.REFERENCE_REMOVED))
                .extracting(ConformanceIssue::conceptIri)
                .containsExactlyInAnyOrder(NS + "a", NS + "c");
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE))
                .extracting(ConformanceIssue::conceptIri)
                .containsExactly(NS + "a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"urn:isbn:978-80-7357-123-4", "https://slovník.gov.cz/jiný/pojem/řidič", "vsgov:osoba"})
    void anIriTheSchemaAccepts_isKept(String iri) {
        ObjectNode a = třída("a");
        a.putArray("ekvivalentní-pojem").add(iri);

        GateResult result = gate.apply(vocabulary(a));

        assertThat(result.issues()).isEmpty();
        assertThat(concept(result, "a").get("ekvivalentní-pojem")).extracting(JsonNode::asText).containsExactly(iri);
    }

    /** A value the schema would reject is dropped on its own; the concept is never excluded for it. */
    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/a|b", "111-2009:pojem", "not an iri", "relative/path"})
    void anIriTheSchemaRejects_isDroppedAndTheConceptKept(String value) {
        ObjectNode a = třída("a");
        a.putArray("ekvivalentní-pojem").add(value).add("https://example.org/ok");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").get("ekvivalentní-pojem")).extracting(JsonNode::asText)
                .containsExactly("https://example.org/ok");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.VALUE_DROPPED);
            assertThat(issue.value()).isEqualTo(value);
        });
    }

    @Test
    void aCodeListValue_followsTheFieldsPattern() {
        ObjectNode a = třída("a");
        a.putArray("způsoby-sdílení-údaje").add("způsoby-sdílení:nesdílené")
                .add("https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/nesdílené");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").get("způsoby-sdílení-údaje")).extracting(JsonNode::asText)
                .containsExactly("způsoby-sdílení:nesdílené");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.VALUE_DROPPED);
            assertThat(issue.message()).contains("neodpovídá vzoru");
        });
    }

    @Test
    void anIriWhereTheSchemaExpectsAnObject_isDroppedAndTheVocabularyStaysValid() {
        ObjectNode document = vocabulary(třída("a"));
        document.put("vytvořeno", "https://example.org/okamžik");

        GateResult result = gate.apply(document);

        assertThat(result.document().has("vytvořeno")).isFalse();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.VALUE_DROPPED);
            assertThat(issue.message()).contains("očekává objekt");
        });
    }

    @Test
    void aCompactReferenceToAnExcludedConcept_isRemoved() {
        String vsgov = "https://slovník.gov.cz/veřejný-sektor/pojem/";
        ObjectNode b = třída("b").put("iri", vsgov + "b");
        b.putArray("typ").add("Koncept").add("Třída");               // fails the schema
        ObjectNode c = třída("c");
        c.putArray("nadřazená-třída").add("vsgov:b").add(NS + "d");

        GateResult result = gate.apply(vocabulary(b, c, třída("d")));

        assertThat(concept(result, "c").get("nadřazená-třída")).extracting(JsonNode::asText)
                .containsExactly(NS + "d");
        assertThat(result.issuesOfKind(Kind.REFERENCE_REMOVED)).singleElement()
                .satisfies(issue -> assertThat(issue.value()).isEqualTo("vsgov:b"));
    }

    @Test
    void aFullReferenceToAnExcludedConceptWithACompactIri_isRemoved() {
        ObjectNode b = třída("b").put("iri", "vsgov:b");
        b.putArray("typ").add("Koncept").add("Třída");               // fails the schema
        ObjectNode c = třída("c");
        c.putArray("nadřazená-třída").add("https://slovník.gov.cz/veřejný-sektor/pojem/b");

        GateResult result = gate.apply(vocabulary(b, c));

        assertThat(concept(result, "c").has("nadřazená-třída")).isFalse();
        assertThat(result.issuesOfKind(Kind.REFERENCE_REMOVED)).hasSize(1);
    }

    @Test
    void conceptsWithoutAnIri_areExcludedOneByOne() {
        ObjectNode incomplete = třída("a");
        incomplete.remove("iri");                                      // only T3: kept as a draft
        ObjectNode invalid = třída("b");
        invalid.remove("iri");
        invalid.putArray("typ").add("Koncept").add("Třída");          // fails the schema
        ObjectNode complete = třída("c");

        GateResult result = gate.apply(vocabulary(incomplete, invalid, complete));

        assertThat(result.document().get("pojmy")).extracting(concept -> concept.get("název").get("cs").asText())
                .containsExactly("a", "c");
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).hasSize(1);
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE)).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("iri"));
    }

    @Test
    void conceptsSharingAnIri_areJudgedOneByOne() {
        ObjectNode invalid = třída("a");
        invalid.putArray("typ").add("Koncept").add("Třída");
        ObjectNode valid = třída("a");
        ObjectNode firstDraft = vlastnost("v", "a");
        firstDraft.remove("definiční-obor");
        ObjectNode secondDraft = vlastnost("v", "a");
        secondDraft.remove("obor-hodnot");

        GateResult result = gate.apply(vocabulary(invalid, valid, firstDraft, secondDraft));

        assertThat(iris(result)).containsExactly(NS + "a", NS + "v", NS + "v");
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).hasSize(1);
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE)).extracting(ConformanceIssue::message)
                .anySatisfy(message -> assertThat(message).contains("definiční-obor"))
                .anySatisfy(message -> assertThat(message).contains("obor-hodnot"))
                .hasSize(2);
    }

    @Test
    void aDraftRecheckedAfterLosingAReference_keepsOneIncompleteIssue() {
        ObjectNode b = třída("b");
        b.putArray("typ").add("Koncept").add("Třída");               // fails the schema
        ObjectNode a = vlastnost("a", "b");
        a.remove("obor-hodnot");                                       // incomplete before the cascade

        GateResult result = gate.apply(vocabulary(b, a));

        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE)).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("definiční-obor").contains("obor-hodnot"));
    }

    @Test
    void vocabularyWithoutName_isIncomplete() {
        ObjectNode document = vocabulary(třída("a"));
        document.remove("název");

        GateResult result = gate.apply(document);

        assertThat(result.issuesOfKind(Kind.VOCABULARY_INCOMPLETE)).hasSize(1);
        assertThat(result.issuesOfKind(Kind.VOCABULARY_INVALID)).isEmpty();
        assertThat(iris(result)).containsExactly(NS + "a");
    }

    @Test
    void vocabularyWithoutAllThreeTypes_isInvalid() {
        ObjectNode document = vocabulary(třída("a"));
        document.putArray("typ").add("Slovník").add("Tezaurus");

        GateResult result = gate.apply(document);

        assertThat(result.issuesOfKind(Kind.VOCABULARY_INVALID)).isNotEmpty();
    }

    @Test
    void validVocabulary_hasNoIssues() {
        assertThat(gate.apply(vocabulary(třída("a"))).issues()).isEmpty();
    }

    // ---------------------------------------------------------------- less common shapes

    @Test
    void languageMap_givenAsPlainText_isDropped() {
        ObjectNode a = třída("a").put("definice", "bez jazyka");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").has("definice")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("očekáván text s jazykem"));
    }

    @Test
    void languageArrays_keepNonBlankTexts_andDropEmptyLanguages() {
        ObjectNode a = třída("a");
        ObjectNode alt = a.putObject("alternativní-název");
        alt.putArray("cs").add("řidič").add(" ");
        alt.putArray("en").add("");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").get("alternativní-název").get("cs")).hasSize(1);
        assertThat(concept(result, "a").get("alternativní-název").has("en")).isFalse();
        assertThat(result.issues()).as("a blank text holds nothing, so dropping it is not an issue").isEmpty();
    }

    @Test
    void nonTextValuesInALanguageMap_areDroppedWithAnIssue() {
        ObjectNode a = třída("a");
        a.putObject("definice").put("cs", 123);
        ObjectNode alt = a.putObject("alternativní-název");
        alt.putArray("cs").add("řidič").add(true);
        alt.putObject("en").put("text", "driver");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").has("definice")).isFalse();
        assertThat(concept(result, "a").get("alternativní-název").get("cs")).hasSize(1);
        assertThat(concept(result, "a").get("alternativní-název").has("en")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED))
                .extracting(ConformanceIssue::location, ConformanceIssue::value)
                .containsExactlyInAnyOrder(
                        tuple("pojmy[a]/definice/cs", "123"),
                        tuple("pojmy[a]/alternativní-název/cs", "true"),
                        tuple("pojmy[a]/alternativní-název/en", null));
        assertThat(OfnWriteGate.blocking(result.issues())).as("a dropped value is T1").hasSize(3);
    }

    @Test
    void objectInPlainTerm_textInObjectTerm_numberInTextTerm_areDropped() {
        ObjectNode a = třída("a");
        a.putObject("agenda").put("iri", "https://example.org/a");
        a.putArray("definující-nelegislativní-zdroj").add("jen text");
        ObjectNode source = a.withArray("související-nelegislativní-zdroj").addObject();
        source.put("typ", "Digitální objekt").put("url", 42);

        GateResult result = gate.apply(vocabulary(a));

        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).extracting(ConformanceIssue::message)
                .anySatisfy(m -> assertThat(m).contains("nepovoluje vnořený objekt"))
                .anySatisfy(m -> assertThat(m).contains("očekává objekt"))
                .anySatisfy(m -> assertThat(m).contains("není text"));
    }

    @Test
    void twoValues_inASingleValueTerm_areDropped() {
        ObjectNode a = třída("a");
        a.putArray("agenda").add("https://example.org/a").add("https://example.org/b");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").has("agenda")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("jedinou hodnotu"));
    }

    @Test
    void nestedObject_leftEmpty_isRemoved_andNonTextType_isDropped() {
        ObjectNode a = třída("a");
        a.withArray("související-nelegislativní-zdroj").addObject().put("neznámé", "x");
        ((ArrayNode) a.get("typ")).add(7);

        GateResult result = gate.apply(vocabulary(a));

        assertThat(concept(result, "a").has("související-nelegislativní-zdroj")).isFalse();
        assertThat(result.issuesOfKind(Kind.VALUE_DROPPED)).hasSize(2);
    }

    @Test
    void conceptOfThePojemVariantOnly_passes() {
        ObjectNode a = mapper.createObjectNode();
        a.put("iri", NS + "a");
        a.putArray("typ").add("Pojem").add("Koncept");
        a.putObject("název").put("cs", "a");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(result.issues()).isEmpty();
    }

    @Test
    void conceptWhoseTypesNameNoVariant_isExcludedWithOneMessage() {
        ObjectNode a = třída("a");
        a.putArray("typ").add("Koncept");

        GateResult result = gate.apply(vocabulary(a, třída("b")));

        assertThat(iris(result)).containsExactly(NS + "b");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.CONCEPT_EXCLUDED);
            assertThat(issue.message()).isEqualTo("pojem nesplňuje OFN schéma: typ neodpovídá žádné variantě OFN"
                    + " (očekáván jeden z: " + String.join(", ",
                    OfnSpecification.bundled().conceptSchemasByType().keySet()) + ")");
        });
    }

    /** Schema failures are shown to users in Czech, like ISMD's own issue texts. */
    @Test
    void schemaFailures_areReportedInCzech() {
        ObjectNode document = vocabulary(třída("a"));
        document.putArray("typ").add("Slovník").add("Tezaurus");

        GateResult result = gate.apply(document);

        assertThat(result.issuesOfKind(Kind.VOCABULARY_INVALID)).extracting(ConformanceIssue::message)
                .isNotEmpty().allSatisfy(message -> assertThat(message).doesNotContain("must").contains("musí obsahovat"));
    }

    @Test
    void conceptWithoutTypes_isExcludedNotADraft() {
        ObjectNode a = třída("a");
        a.remove("typ");

        GateResult result = gate.apply(vocabulary(a));

        assertThat(result.document().get("pojmy")).isEmpty();
        assertThat(result.issuesOfKind(Kind.CONCEPT_EXCLUDED)).hasSize(1);
        assertThat(result.issuesOfKind(Kind.CONCEPT_INCOMPLETE)).isEmpty();
    }

    @Test
    void documentWithoutConceptList_hasOnlyVocabularyChecks() {
        ObjectNode document = vocabulary();
        document.remove("pojmy");

        assertThat(gate.apply(document).issues()).isEmpty();
    }

    // ---------------------------------------------------------------- builders

    private ObjectNode vocabulary(ObjectNode... concepts) {
        ObjectNode document = mapper.createObjectNode();
        document.put("@context", OfnSpecification.bundled().contextUrl());
        document.put("iri", "https://slovník.gov.cz/test");
        document.putArray("typ").add("Slovník").add("Tezaurus").add("Konceptuální model");
        document.putObject("název").put("cs", "Testovací slovník");
        ArrayNode pojmy = document.putArray("pojmy");
        for (ObjectNode concept : concepts) {
            pojmy.add(concept);
        }
        return document;
    }

    private ObjectNode třída(String name) {
        ObjectNode concept = mapper.createObjectNode();
        concept.put("iri", NS + name);
        concept.putArray("typ").add("Pojem").add("Koncept").add("Třída");
        concept.putObject("název").put("cs", name);
        return concept;
    }

    private ObjectNode vlastnost(String name, String domain) {
        ObjectNode concept = mapper.createObjectNode();
        concept.put("iri", NS + name);
        concept.putArray("typ").add("Pojem").add("Koncept").add("Vlastnost");
        concept.putObject("název").put("cs", name);
        concept.put("definiční-obor", NS + domain);
        concept.put("obor-hodnot", "xsd:string");
        return concept;
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> iris(GateResult result) {
        return StreamSupport.stream(result.document().get("pojmy").spliterator(), false)
                .map(concept -> concept.get("iri").asText())
                .toList();
    }

    private static JsonNode concept(GateResult result, String name) {
        return StreamSupport.stream(result.document().get("pojmy").spliterator(), false)
                .filter(concept -> concept.get("iri").asText().equals(NS + name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> types(JsonNode concept) {
        return StreamSupport.stream(concept.get("typ").spliterator(), false).map(JsonNode::asText).toList();
    }

    private ObjectNode read(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return (ObjectNode) mapper.readTree(in);
        }
    }
}
