package com.dia.ismdtoolbackend.ofn;

import com.dia.ismdtoolbackend.ofn.ConformanceIssue.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class OfnRdfReaderTest {

    private static final String V = "https://slovník.gov.cz/test";
    private static final String NS = V + "/pojem/";

    private final OfnSpecification specification = OfnSpecification.bundled();
    private final OfnRdfReader reader = new OfnRdfReader(specification, OfnIris.MARKERS);
    private final OfnConformanceGate gate = new OfnConformanceGate(specification, OfnIris.MARKERS);
    private final OfnRdf rdf = new OfnRdf(specification);

    // ------------------------------------------------------------ golden round trip

    @ParameterizedTest
    @ValueSource(strings = {"excel", "archi", "ea"})
    void validatorGoldenOutput_readsBackToTheSameGraph_withoutIssues(String name) throws IOException {
        Model source = ModelFactory.createDefaultModel();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ofn/golden/" + name + ".jsonld")) {
            OfnJsonLdParser.parse(in, null, source);
        }
        String vocabularyIri = source.listSubjectsWithProperty(RDF.type, source.createResource(OfnIris.SLOVNIK))
                .next().getURI();

        OfnRdfReader.Result result = reader.readVocabulary(source, vocabularyIri);

        assertThat(result.document().get("pojmy").size()).isGreaterThanOrEqualTo(40);
        assertThat(result.issues()).isEmpty();
        assertThat(gate.apply(result.document()).issues()).isEmpty();
        assertThat(rdf.toModel(result.document()).isIsomorphicWith(source)).isTrue();
    }

    // ------------------------------------------------------------ shapes

    @Test
    void conformantClass_hasNoIssues_andKeepsItsMarker() {
        Model model = vocabularyModel();
        Resource a = třída(model, "a");
        a.addProperty(RDF.type, model.createResource(OfnIris.MARKER_TRIDA));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).isEmpty();
        assertThat(texts(concept(result, "a").get("typ")))
                .containsExactly("Koncept", "Pojem", "Třída", "slovníky:třída");
        assertThat(rdf.toModel(result.document()).isIsomorphicWith(model)).isTrue();
    }

    @Test
    void unknownPredicate_isReported_withItsValue() {
        Model model = vocabularyModel();
        třída(model, "a").addProperty(model.createProperty(V + "/pojem/inTezaurus"), "true");

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.VALUE_DROPPED);
            assertThat(issue.conceptIri()).isEqualTo(NS + "a");
            assertThat(issue.message()).contains("není definována v kontextu OFN");
            assertThat(issue.value()).isEqualTo("true");
        });
    }

    @Test
    void ppdfAsString_isReported_asWrongDatatype() {
        Model model = vocabularyModel();
        třída(model, "a").addProperty(model.createProperty(OfnIris.JE_SDILEN_V_PPDF), "false");

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("xsd:string").contains("xsd:boolean"));
        assertThat(concept(result, "a").has("je-sdílen-v-ppdf")).isFalse();
    }

    @Test
    void typedBoolean_isWrittenAsJsonBoolean() {
        Model model = vocabularyModel();
        třída(model, "a").addLiteral(model.createProperty(OfnIris.JE_SDILEN_V_PPDF), model.createTypedLiteral(true));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).isEmpty();
        assertThat(concept(result, "a").get("je-sdílen-v-ppdf").isBoolean()).isTrue();
    }

    @Test
    void urlAsIri_isReported() {
        Model model = vocabularyModel();
        Resource source = model.createResource()
                .addProperty(RDF.type, model.createResource(OfnIris.DIGITALNI_OBJEKT))
                .addProperty(model.createProperty(OfnIris.SCHEMA_URL), model.createResource("https://example.org/"));
        třída(model, "a").addProperty(model.createProperty(OfnIris.DEFINUJICI_NELEGISLATIVNI_ZDROJ), source);

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.location()).isEqualTo("pojmy[a]/definující-nelegislativní-zdroj/url"));
    }

    @Test
    void labelWithoutLanguage_isReported() {
        Model model = vocabularyModel();
        třída(model, "a").addProperty(SKOS.definition, "bez jazyka");

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("očekáván text s jazykem"));
    }

    @Test
    void legacyBusinessTypeNamespace_isReported() {
        Model model = vocabularyModel();
        třída(model, "a").addProperty(RDF.type, model.createResource(OfnIris.LEGACY_TYP_OBJEKTU_PRAVA));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("typ"));
    }

    @Test
    void subPropertyOf_isNamedByTheConceptKind() {
        Model model = vocabularyModel();
        Resource vztah = concept(model, "v", OWL2.ObjectProperty);
        vztah.addProperty(RDFS.subPropertyOf, model.createResource(NS + "w"));
        Resource vlastnost = concept(model, "p", OWL2.DatatypeProperty);
        vlastnost.addProperty(RDFS.subPropertyOf, model.createResource(NS + "q"));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(concept(result, "v").has("nadřazený-vztah")).isTrue();
        assertThat(concept(result, "p").has("nadřazená-vlastnost")).isTrue();
    }

    @Test
    void xsdRangeAndGovernanceValues_useCompactIris() {
        Model model = vocabularyModel();
        Resource p = concept(model, "p", OWL2.DatatypeProperty);
        p.addProperty(RDFS.range, model.createResource(OfnIris.XSD + "date"));
        p.addProperty(model.createProperty(OfnIris.SLOVNIKY + "má-typ-obsahu-údaje"),
                model.createResource(OfnIris.TYPY_OBSAHU + "evidenční"));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(concept(result, "p").get("obor-hodnot").asText()).isEqualTo("xsd:date");
        assertThat(concept(result, "p").get("typ-obsahu-údaje").asText()).isEqualTo("typy-obsahu:evidenční");
    }

    @Test
    void wrongShapes_forIdTypedNestedAndLanguageTerms_areReported() {
        Model model = vocabularyModel();
        Resource a = třída(model, "a");
        a.addProperty(model.createProperty(OfnIris.A104 + "sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy"), "A104");
        a.addProperty(SKOS.exactMatch, model.createResource());
        a.addProperty(model.createProperty(OfnIris.JE_SDILEN_V_PPDF), model.createResource("https://example.org/ano"));
        a.addProperty(model.createProperty(OfnIris.SOUVISEJICI_NELEGISLATIVNI_ZDROJ), "jen text");
        a.addProperty(SKOS.altLabel, model.createResource("https://example.org/název"));
        a.addProperty(RDF.type, model.createTypedLiteral(1));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).extracting(ConformanceIssue::message)
                .anySatisfy(m -> assertThat(m).startsWith("hodnota není IRI"))
                .anySatisfy(m -> assertThat(m).contains("prázdný uzel"))
                .anySatisfy(m -> assertThat(m).startsWith("očekáván literál typu"))
                .anySatisfy(m -> assertThat(m).startsWith("očekáván objekt"))
                .anySatisfy(m -> assertThat(m).startsWith("očekáván text s jazykem"))
                .anySatisfy(m -> assertThat(m).startsWith("typ"));
    }

    @Test
    void booleanLexicalForms() {
        Model model = vocabularyModel();
        Property ppdf = model.createProperty(OfnIris.JE_SDILEN_V_PPDF);
        concept(model, "jedna", OWL2.Class).addLiteral(ppdf, model.createTypedLiteral("1", XSDDatatype.XSDboolean));
        concept(model, "nula", OWL2.Class).addLiteral(ppdf, model.createTypedLiteral("0", XSDDatatype.XSDboolean));
        concept(model, "ne", OWL2.Class).addLiteral(ppdf, model.createTypedLiteral(false));
        concept(model, "ano", OWL2.Class).addLiteral(ppdf, model.createTypedLiteral("ano", XSDDatatype.XSDboolean));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(concept(result, "jedna").get("je-sdílen-v-ppdf").asBoolean()).isTrue();
        assertThat(concept(result, "nula").get("je-sdílen-v-ppdf").asBoolean()).isFalse();
        assertThat(concept(result, "ne").get("je-sdílen-v-ppdf").asBoolean()).isFalse();
        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.message()).contains("neplatná logická hodnota"));
    }

    @Test
    void severalTextsInOneLanguage_andSeveralSingleValues_becomeArrays() {
        Model model = vocabularyModel();
        Resource a = třída(model, "a");
        a.addProperty(SKOS.definition, "první", "cs").addProperty(SKOS.definition, "druhá", "cs");
        a.addProperty(RDFS.range, model.createResource(NS + "x")).addProperty(RDFS.range, model.createResource(NS + "y"));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(concept(result, "a").get("definice").get("cs")).hasSize(2);
        assertThat(concept(result, "a").get("obor-hodnot")).hasSize(2);
    }

    @Test
    void digitalObjectWithIri_isANestedObject_andDatesStayObjects() {
        Model model = vocabularyModel();
        Resource source = model.createResource("https://example.org/zdroj")
                .addProperty(RDF.type, model.createResource(OfnIris.DIGITALNI_OBJEKT))
                .addProperty(model.createProperty(OfnIris.SCHEMA_URL), model.createTypedLiteral("https://u", XSDDatatype.XSDanyURI));
        třída(model, "a").addProperty(model.createProperty(OfnIris.DEFINUJICI_NELEGISLATIVNI_ZDROJ), source);

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).isEmpty();
        JsonNode nested = concept(result, "a").get("definující-nelegislativní-zdroj").get(0);
        assertThat(nested.get("iri").asText()).isEqualTo("https://example.org/zdroj");
        assertThat(nested.get("typ").asText()).isEqualTo("Digitální objekt");
        assertThat(result.document().get("vytvořeno").get("typ").asText()).isEqualTo("Časový okamžik");
        assertThat(rdf.toModel(result.document()).isIsomorphicWith(model)).isTrue();
    }

    @Test
    void readConcepts_reportsAConceptOfAnotherNamespace() {
        Model model = vocabularyModel();
        model.createResource("https://slovník.gov.cz/jiný/pojem/x")
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.inScheme, model.createResource(V));

        OfnRdfReader.Result result = reader.readConcepts(model, V, List.of("https://slovník.gov.cz/jiný/pojem/x"));

        assertThat(result.issues()).anySatisfy(issue -> assertThat(issue.message()).contains("neleží ve jmenném prostoru"));
    }

    // ------------------------------------------------------------ membership

    @Test
    void foreignConceptInScheme_isNotAMember() {
        Model model = vocabularyModel();
        Resource foreign = model.createResource("https://slovník.gov.cz/jiný/pojem/x")
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.inScheme, model.createResource(V));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.document().get("pojmy")).isEmpty();
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.CONCEPT_EXCLUDED);
            assertThat(issue.conceptIri()).isEqualTo(foreign.getURI());
        });
    }

    @Test
    void namespaceBoundary_isASlash() {
        Model model = vocabularyModel();
        model.createResource(V + "x/pojem/a")
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.inScheme, model.createResource(V));

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.document().get("pojmy")).isEmpty();
    }

    @Test
    void readConcepts_reportsAConceptOutsideTheVocabulary() {
        Model model = vocabularyModel();
        model.createResource(NS + "a").addProperty(RDF.type, SKOS.Concept);

        OfnRdfReader.Result result = reader.readConcepts(model, V, List.of(NS + "a"));

        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.CONCEPT_EXCLUDED);
            assertThat(issue.message()).contains("není zařazen do slovníku");
        });
    }

    @Test
    void otherSubjects_areLeftOut_withoutIssue() {
        Model model = vocabularyModel();
        model.createResource("https://slovník.gov.cz/jiný/pojem/adresa")
                .addProperty(RDF.type, OWL2.Class)
                .addProperty(RDFS.label, "adresa", "cs");

        OfnRdfReader.Result result = reader.readVocabulary(model, V);

        assertThat(result.issues()).isEmpty();
        assertThat(result.document().get("pojmy")).isEmpty();
    }

    // ------------------------------------------------------------ fixtures

    private Model vocabularyModel() {
        Model model = ModelFactory.createDefaultModel();
        Resource vocabulary = model.createResource(V)
                .addProperty(RDF.type, model.createResource(OfnIris.SLOVNIK))
                .addProperty(RDF.type, model.createResource(OfnIris.CONCEPT_SCHEME))
                .addProperty(RDF.type, model.createResource(OfnIris.ONTOLOGY))
                .addProperty(SKOS.prefLabel, "Test", "cs");
        Resource created = model.createResource()
                .addProperty(RDF.type, model.createResource(OfnIris.CASOVY_OKAMZIK))
                .addProperty(model.createProperty(OfnIris.DATUM_A_CAS),
                        model.createTypedLiteral("2026-10-28T10:00:00+01:00", XSDDatatype.XSDdateTimeStamp));
        vocabulary.addProperty(model.createProperty(OfnIris.OKAMZIK_VYTVORENI), created);
        return model;
    }

    private Resource třída(Model model, String name) {
        return concept(model, name, OWL2.Class);
    }

    private Resource concept(Model model, String name, Resource kind) {
        return model.createResource(NS + name)
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(RDF.type, model.createResource(OfnIris.POJEM))
                .addProperty(RDF.type, kind)
                .addProperty(SKOS.prefLabel, name, "cs")
                .addProperty(SKOS.inScheme, model.createResource(V));
    }

    private static JsonNode concept(OfnRdfReader.Result result, String name) {
        return StreamSupport.stream(result.document().get("pojmy").spliterator(), false)
                .filter(concept -> concept.get("iri").asText().equals(NS + name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
