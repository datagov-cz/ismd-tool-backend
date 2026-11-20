package com.dia.ismdtoolbackend.publication.file;

import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.publication.PublicationConfig;
import com.dia.ismdtoolbackend.publication.snapshot.DraftFilter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both published files, written from real exporter output and expanded through the real contexts: the
 * bundled OFN Slovníky context and the draft ISMD OD context in {@code pages/}.
 */
class PublicationFilesTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String ISMD = "https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/";
    private static final String BASE = "https://data.dia.gov.cz/zdroj/ismd/";
    private static final String CONTEXT_URL = "https://example.org/ismd-od/kontext.jsonld";

    private static final String VOCABULARY = Ttl.VOCABULARY;
    private static final String OSOBA = Ttl.POJEM + "osoba";
    private static final String SUBJEKT = Ttl.POJEM + "subjekt";
    private static final String ROZPRACOVANY = Ttl.POJEM + "rozpracovaný";
    private static final String EMPTY_IRI = "https://x/prazdny";
    private static final String FAILED_IRI = "https://x/selhavajici";

    private static final String CONTENT = """
            ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                skos:prefLabel "Testovací slovník"@cs .
            pojem:subjekt a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
                skos:prefLabel "Subjekt"@cs .
            pojem:rozpracovaný a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
                skos:prefLabel "Rozpracovaný"@cs .
            pojem:osoba a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
                skos:prefLabel "Osoba"@cs ;
                skos:broader pojem:subjekt, pojem:rozpracovaný ;
                rdfs:subClassOf pojem:rozpracovaný .
            """;

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 3, 2, 10, 15, 0, 123_000);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 9, 30, 14, 2, 11);
    private static final Instant PUBLISHED = Instant.parse("2026-10-07T01:00:00Z");

    private static JsonNode ofnContext;
    private static JsonNode ismdContext;

    @BeforeAll
    static void loadContexts() {
        Ttl.initJena();
        ofnContext = resource("/com/dia/context/json_ld_context.jsonld").get("@context");
        ismdContext = file(ISMD_CONTEXT_FILE).get("@context");
    }

    private final OntologyDownloadService downloads = (id, format) -> switch (id.intValue()) {
        case 1 -> new JsonExporter().exportToJson(Ttl.parseOnt(CONTENT));
        case 2 -> throw new EmptyDataException("prázdný");
        default -> throw new IllegalStateException("Fuseki down");
    };

    // ---- slovniky.jsonld ----

    @Test
    void vocabularies_mergeMetadata_dropDrafts_andReportFailedContent() {
        Written file = writeVocabularies(DraftFilter.withoutDrafts(snapshot()), false);

        assertThat(file.complete).as("one vocabulary failed").isFalse();
        ObjectNode vocabulary = graphNode(file.json, VOCABULARY);
        assertThat(vocabulary.get("typ").toString()).contains("Slovník");
        assertThat(vocabulary.get("název").get("cs").asString()).isEqualTo("Testovací slovník");
        assertThat(vocabulary.get("slug").asString()).isEqualTo("test-slovnik");
        assertThat(vocabulary.get("stav_obsahu").asString()).isEqualTo("OK");
        assertThat(vocabulary.get("stav_poslední_validace").asString()).isEqualTo("VALIDATED");
        assertThat(vocabulary.get("počet_pojmů").asInt()).isEqualTo(2);
        assertThat(vocabulary.get("vytvořeno_v_ismd").get("datum_a_čas").asString())
                .as("Prague offset, whole seconds").isEqualTo("2026-03-02T10:15:00+01:00");
        assertThat(vocabulary.get("aktualizováno_v_ismd").get("datum_a_čas").asString())
                .isEqualTo("2026-09-30T14:02:11+02:00");

        assertThat(iris(vocabulary.get("pojmy"))).containsExactlyInAnyOrder(SUBJEKT, OSOBA);
        ObjectNode osoba = concept(vocabulary, OSOBA);
        assertThat(osoba.get("slug").asString()).isEqualTo("osoba");
        assertThat(osoba.get("je_publikován").asBoolean()).isTrue();
        assertThat(osoba.has("je_v_tezauru")).isFalse();
        assertThat(osoba.toString()).as("references to the excluded draft are stripped").doesNotContain(ROZPRACOVANY);
        assertThat(osoba.toString()).contains(SUBJEKT);

        assertThat(graphNode(file.json, EMPTY_IRI).get("stav_obsahu").asString()).isEqualTo("EMPTY");
        assertThat(graphNode(file.json, FAILED_IRI).get("stav_obsahu").asString()).isEqualTo("FAILED");
        assertThat(graphNode(file.json, FAILED_IRI).has("pojmy")).isFalse();

        ObjectNode record = graphNode(file.json, BASE + "publikace/slovniky.jsonld");
        assertThat(record.get("úplná").asBoolean()).isFalse();
        assertThat(record.get("obsahuje_rozpracované").asBoolean()).isFalse();
        assertThat(record.get("publikováno").get("datum_a_čas").asString()).isEqualTo("2026-10-07T03:00:00+02:00");
        assertThat(file.json.get("@context").toString()).contains(CONTEXT_URL, "ofn.gov.cz/slovníky/2026-02-26");
    }

    @Test
    void vocabularies_withDrafts_keepDraftConceptsAndTheirReferences() {
        Written file = writeVocabularies(snapshot(), true);

        ObjectNode vocabulary = graphNode(file.json, VOCABULARY);
        assertThat(iris(vocabulary.get("pojmy"))).containsExactlyInAnyOrder(SUBJEKT, OSOBA, ROZPRACOVANY);
        assertThat(concept(vocabulary, ROZPRACOVANY).get("je_publikován").asBoolean()).isFalse();
        assertThat(concept(vocabulary, OSOBA).toString()).contains(ROZPRACOVANY);
    }

    @Test
    void vocabularies_conceptWithoutPostgresRow_droppedWithDraftsOff_keptWithDraftsOn() {
        Ontology withoutRow = new Ontology(1L, VOCABULARY, "test-slovnik", true, OntologyValidationStatus.VALIDATED,
                null, CREATED, UPDATED, List.of(concept(OSOBA, true), concept(SUBJEKT, true)));
        PublicationSnapshot snapshot = PublicationSnapshot.of(List.of(withoutRow), List.of(), Set.of());

        ObjectNode off = graphNode(writeVocabularies(DraftFilter.withoutDrafts(snapshot), false).json, VOCABULARY);
        assertThat(iris(off.get("pojmy"))).containsExactlyInAnyOrder(SUBJEKT, OSOBA);
        assertThat(concept(off, OSOBA).toString()).as("references to the unknown concept are stripped")
                .doesNotContain(ROZPRACOVANY).contains(SUBJEKT);
        assertThat(off.get("počet_pojmů").asInt()).isEqualTo(2);

        ObjectNode on = graphNode(writeVocabularies(snapshot, true).json, VOCABULARY);
        assertThat(iris(on.get("pojmy"))).containsExactlyInAnyOrder(SUBJEKT, OSOBA, ROZPRACOVANY);
        assertThat(concept(on, ROZPRACOVANY).has("je_publikován")).as("kept as downloaded").isFalse();
        assertThat(concept(on, ROZPRACOVANY).has("slug")).isFalse();
        assertThat(concept(on, OSOBA).toString()).contains(ROZPRACOVANY);
    }

    @Test
    void vocabularies_expandThroughBothContexts_toOfnAndIsmdTriples() {
        Written file = writeVocabularies(DraftFilter.withoutDrafts(snapshot()), false);
        assertEveryKeyIsDefined(file.json);

        Model rdf = expand(file.json);
        Resource osoba = rdf.createResource(OSOBA);
        Resource vocabulary = rdf.createResource(VOCABULARY);
        assertThat(rdf.contains(osoba, SKOS.prefLabel, rdf.createLiteral("Osoba", "cs"))).isTrue();
        assertThat(rdf.contains(osoba, SKOS.inScheme, vocabulary)).isTrue();
        assertThat(rdf.contains(osoba, RDFS.subClassOf, rdf.createResource(ROZPRACOVANY))).isFalse();
        assertThat(bool(rdf, osoba, "je-publikován")).isTrue();
        assertThat(object(rdf, vocabulary, "stav-obsahu").asLiteral().getString()).isEqualTo("OK");
        assertThat(object(rdf, vocabulary, "počet-pojmů").asLiteral().getInt()).isEqualTo(2);
        Resource created = object(rdf, vocabulary, "okamžik-vytvoření-v-ismd").asResource();
        assertThat(rdf.listObjectsOfProperty(created,
                rdf.createProperty("https://slovník.gov.cz/generický/čas/pojem/datum-a-čas")).next()
                .asLiteral().getLexicalForm()).isEqualTo("2026-03-02T10:15:00+01:00");
        Resource record = rdf.createResource(BASE + "publikace/slovniky.jsonld");
        assertThat(rdf.contains(record, RDF.type, rdf.createResource(ISMD + "publikace"))).isTrue();
        assertThat(bool(rdf, record, "je-úplná")).isFalse();
    }

    @Test
    void stripReferences_removesStringsAndListEntries_andEmptiedLists() {
        ObjectNode pojem = JSON.createObjectNode();
        pojem.put("iri", ROZPRACOVANY);
        pojem.put("obor-hodnot", ROZPRACOVANY);
        pojem.putArray("nadřazená-třída").add(ROZPRACOVANY);
        pojem.putArray("nadřazený-pojem").add(ROZPRACOVANY).add(SUBJEKT);

        VocabularyFileWriter.stripReferences(pojem, Set.of(ROZPRACOVANY));

        assertThat(pojem.get("iri").asString()).as("own iri is never touched").isEqualTo(ROZPRACOVANY);
        assertThat(pojem.has("obor-hodnot")).isFalse();
        assertThat(pojem.has("nadřazená-třída")).isFalse();
        assertThat(iris(pojem.get("nadřazený-pojem"))).containsExactly(SUBJEKT);
    }

    // ---- diagramy.jsonld ----

    @Test
    void diagrams_publishPositionsGroupingAndEdgeMembership() {
        UUID iriId = UUID.fromString("3f2c9a1e-5b7d-4c0a-9e8f-1a2b3c4d5e6f");
        Diagram routed = new Diagram(iriId, "Přehled", VOCABULARY, CREATED, UPDATED, 0.0, 10.0, 0.85,
                List.of(new Node(SUBJEKT, 1, 2, null, false), new Node(OSOBA, 3, 4, SUBJEKT, true)),
                List.of(new Edge("edge|SUBCLASS|" + OSOBA + "|" + SUBJEKT)));
        Diagram unsaved = new Diagram(UUID.randomUUID(), "Nový", VOCABULARY, CREATED, null, null, null, null,
                List.of(), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DiagramFileWriter().write(PublicationSnapshot.of(List.of(), List.of(routed, unsaved), Set.of()),
                header(true), out);
        ObjectNode json = (ObjectNode) JSON.readTree(out.toString(StandardCharsets.UTF_8));

        ObjectNode diagram = graphNode(json, BASE + "diagramy/" + iriId);
        assertThat(diagram.get("název_diagramu").asString()).isEqualTo("Přehled");
        assertThat(diagram.get("slovník").asString()).isEqualTo(VOCABULARY);
        assertThat(diagram.get("výřez").get("přiblížení").asDouble()).isEqualTo(0.85);
        JsonNode grouped = diagram.get("uzly").get(1);
        assertThat(grouped.get("seskupen_pod").asString()).isEqualTo(SUBJEKT);
        assertThat(grouped.get("cizí").asBoolean()).isTrue();
        assertThat(diagram.get("uzly").get(0).has("seskupen_pod")).as("absent, not null").isFalse();
        assertThat(graphNode(json, BASE + "diagramy/" + unsaved.iriId()).has("výřez")).isFalse();
        assertThat(graphNode(json, BASE + "publikace/diagramy.jsonld").get("úplná").asBoolean()).isTrue();
        assertEveryKeyIsDefined(json);

        Model rdf = expand(json);
        Resource d = rdf.createResource(BASE + "diagramy/" + iriId);
        assertThat(rdf.contains(d, RDF.type, rdf.createResource(ISMD + "diagram"))).isTrue();
        assertThat(object(rdf, d, "zobrazuje-slovník").asResource().getURI()).isEqualTo(VOCABULARY);
        Resource edge = object(rdf, d, "má-hranu").asResource();
        assertThat(object(rdf, edge, "klíč-hrany").asLiteral().getString())
                .isEqualTo("edge|SUBCLASS|" + OSOBA + "|" + SUBJEKT);
        Resource position = object(rdf, rdf.listSubjectsWithProperty(rdf.createProperty(ISMD + "zobrazuje-pojem"),
                rdf.createResource(SUBJEKT)).next(), "pozice").asResource();
        assertThat(object(rdf, position, "y").asLiteral().getDouble()).isEqualTo(2.0);
        Resource groupedNode = rdf.listSubjectsWithProperty(rdf.createProperty(ISMD + "zobrazuje-pojem"),
                rdf.createResource(OSOBA)).next();
        assertThat(object(rdf, groupedNode, "je-seskupen-pod").asResource().getURI()).isEqualTo(SUBJEKT);
        assertThat(json.toString()).as("removed terms are not published")
                .doesNotContain("sbalený", "zobrazené_vlastnosti", "body_zlomu");
    }

    // ---- fixtures and helpers ----

    private static PublicationSnapshot snapshot() {
        Ontology published = new Ontology(1L, VOCABULARY, "test-slovnik", true, OntologyValidationStatus.VALIDATED,
                Instant.parse("2026-09-30T12:02:15Z"), CREATED, UPDATED, List.of(
                concept(OSOBA, true), concept(SUBJEKT, true), concept(ROZPRACOVANY, false)));
        Ontology empty = new Ontology(2L, EMPTY_IRI, "prazdny", true, null, null, CREATED, null, List.of());
        Ontology failing = new Ontology(3L, FAILED_IRI, "selhavajici", true, null, null, CREATED, null, List.of());
        return PublicationSnapshot.of(List.of(published, empty, failing), List.of(), Set.of());
    }

    private static Concept concept(String iri, boolean published) {
        return new Concept(iri, iri.substring(iri.lastIndexOf('/') + 1), published, CREATED, UPDATED);
    }

    private static PublicationHeader header(boolean includesDrafts) {
        return new PublicationHeader(PUBLISHED, includesDrafts, CONTEXT_URL, BASE);
    }

    private record Written(ObjectNode json, boolean complete) {
    }

    private Written writeVocabularies(PublicationSnapshot snapshot, boolean includesDrafts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean complete = new VocabularyFileWriter(downloads, new PublicationConfig()).write(snapshot, header(includesDrafts), out);
        return new Written((ObjectNode) JSON.readTree(out.toString(StandardCharsets.UTF_8)), complete);
    }

    private static ObjectNode graphNode(ObjectNode file, String iri) {
        return (ObjectNode) StreamSupport.stream(file.get("@graph").spliterator(), false)
                .filter(n -> iri.equals(n.path("iri").asString(""))).findFirst()
                .orElseThrow(() -> new AssertionError("no graph node " + iri));
    }

    private static ObjectNode concept(ObjectNode vocabulary, String iri) {
        return (ObjectNode) StreamSupport.stream(vocabulary.get("pojmy").spliterator(), false)
                .filter(n -> iri.equals(n.path("iri").asString(""))).findFirst().orElseThrow();
    }

    private static List<String> iris(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.isString() ? n.asString() : n.get("iri").asString()).toList();
    }

    /** Expands with both contexts inlined in place of their URLs, so no network is touched. */
    private static Model expand(ObjectNode file) {
        ObjectNode document = file.deepCopy();
        ArrayNode contexts = document.putArray("@context");
        contexts.add(ofnContext);
        contexts.add(ismdContext);
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(JSON.writeValueAsString(document), Lang.JSONLD).parse(model);
        return model;
    }

    private static RDFNode object(Model rdf, Resource subject, String ismdTerm) {
        var it = rdf.listObjectsOfProperty(subject, rdf.createProperty(ISMD + ismdTerm));
        assertThat(it.hasNext()).as(subject + " has ismd:" + ismdTerm).isTrue();
        return it.next();
    }

    private static boolean bool(Model rdf, Resource subject, String ismdTerm) {
        Literal literal = object(rdf, subject, ismdTerm).asLiteral();
        return literal.getBoolean();
    }

    /**
     * Every key the writers emit is a term of one of the two contexts. JSON-LD silently drops undefined
     * terms, so expansion alone would not catch a misspelt key. Language-map keys are skipped.
     */
    private static void assertEveryKeyIsDefined(JsonNode file) {
        Set<String> defined = new HashSet<>();
        collectTerms(ofnContext, defined);
        collectTerms(ismdContext, defined);
        Set<String> undefined = new HashSet<>();
        collectKeys(file.get("@graph"), defined, undefined);
        assertThat(undefined).as("keys no context defines").isEmpty();
    }

    private static void collectTerms(JsonNode context, Set<String> terms) {
        context.properties().forEach(entry -> {
            terms.add(entry.getKey());
            JsonNode nested = entry.getValue().get("@context");
            if (nested != null && nested.isObject()) {
                collectTerms(nested, terms);
            }
        });
    }

    private static void collectKeys(JsonNode node, Set<String> defined, Set<String> undefined) {
        if (node.isArray()) {
            node.forEach(child -> collectKeys(child, defined, undefined));
        } else if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey();
                if (!key.startsWith("@") && !key.matches("[a-z]{2}") && !defined.contains(key)) {
                    undefined.add(key);
                }
                collectKeys(entry.getValue(), defined, undefined);
            });
        }
    }

    /** The published draft context, served by GitHub Pages from the repository's {@code pages/}. */
    private static final Path ISMD_CONTEXT_FILE = Path.of("pages", "ismd-otevřená-data", "draft", "kontext.jsonld");

    @Test
    void defaultContextUrl_pointsAtThePublishedDraftContext() {
        String pagesBase = "https://datagov-cz.github.io/ismd-tool-backend/";
        assertThat(PublicationConfig.DRAFT_CONTEXT_URL).startsWith(pagesBase);
        assertThat(Path.of("pages", PublicationConfig.DRAFT_CONTEXT_URL.substring(pagesBase.length()).split("/")))
                .isEqualTo(ISMD_CONTEXT_FILE).isRegularFile();
        assertThat(new PublicationConfig().getContextUrl()).isEqualTo(PublicationConfig.DRAFT_CONTEXT_URL);
    }

    private static JsonNode file(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode resource(String path) {
        try (InputStream in = PublicationFilesTest.class.getResourceAsStream(path)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
