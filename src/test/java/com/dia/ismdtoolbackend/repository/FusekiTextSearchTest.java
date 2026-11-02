package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.text.TextQuery;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FusekiTextSearch} against the production {@code fuseki-config.ttl} text dataset, assembled
 * in memory (Lucene directory {@code "mem"}, TDB2 swapped for {@code ja:MemoryDataset}), so the Lucene query string, the
 * diacritic folding and the role filter run for real.
 */
class FusekiTextSearchTest {

    private static final String GRAPH = Ttl.VOCABULARY;
    private static final String HIDDEN_GRAPH = "https://slovník.gov.cz/datový/skrytý-slovník/";
    private static final String RIDIC = Ttl.POJEM + "řidič";
    private static final String VOZIDLO = Ttl.POJEM + "vozidlo";
    private static final String RIDI = Ttl.POJEM + "řídí";
    private static final String JMENO = Ttl.POJEM + "jméno-řidiče";
    private static final String TEXT = "http://jena.apache.org/text#";

    private Dataset dataset;
    private final AtomicInteger connections = new AtomicInteger();
    private FusekiTextSearch search;

    static Dataset productionTextDataset() {
        TextQuery.init();
        Model config = ModelFactory.createDefaultModel();
        RDFParser.source("fuseki-config.ttl").base("http://config.test/").parse(config);
        Property directory = config.createProperty(TEXT, "directory");
        Property location = config.createProperty("http://jena.apache.org/2016/tdb#", "location");
        Resource index = config.listSubjectsWithProperty(directory).next();
        Resource tdb = config.listSubjectsWithProperty(location).next();
        index.removeAll(directory).addProperty(directory, "mem");
        tdb.removeProperties().addProperty(RDF.type,
                config.createResource("http://jena.hpl.hp.com/2005/11/Assembler#MemoryDataset"));
        Resource textDataset = config.listSubjectsWithProperty(RDF.type,
                config.createResource(TEXT + "TextDataset")).next();
        return (Dataset) Assembler.general().open(textDataset);
    }

    private FusekiTextSearch searchOver(Dataset ds, MockEnvironment environment) {
        Supplier<RDFConnection> factory = () -> {
            connections.incrementAndGet();
            return RDFConnection.connect(ds);
        };
        return new FusekiTextSearch(new FusekiSparqlExecutor(new Semaphore(1), 1000, factory), factory,
                environment);
    }

    @BeforeEach
    void setUp() {
        dataset = productionTextDataset();
        search = searchOver(dataset, new MockEnvironment());
        search.setAvailable(true);
        store(GRAPH, """
                pojem:řidič a skos:Concept, slovníky:třída, owl:Class ;
                    skos:prefLabel "Řidič"@cs ;
                    skos:altLabel "Šofér"@cs ;
                    dct:description "Osoba řídící vozidlo"@cs ;
                    skos:definition "Fyzická osoba, která řídí motorové vozidlo"@cs .
                pojem:vozidlo a skos:Concept ;
                    skos:altLabel "auto řidiče"@cs .
                pojem:řídí a skos:Concept, owl:ObjectProperty ;
                    skos:prefLabel "řídí řidič"@cs .
                pojem:jméno-řidiče a skos:Concept, slovníky:vlastnost ;
                    skos:prefLabel "jméno řidiče"@cs .
                """);
        store(HIDDEN_GRAPH, """
                <https://slovník.gov.cz/datový/skrytý-slovník/pojem/řidič> a skos:Concept ;
                    skos:prefLabel "Řidič"@cs .
                """);
    }

    private void store(String graph, String turtle) {
        Model model = Ttl.parse(turtle);
        Txn.executeWrite(dataset, () -> dataset.getNamedModel(graph).add(model));
    }

    private static Set<String> iris(List<Map<String, String>> rows) {
        return Set.copyOf(rows.stream().map(r -> r.get("resourceIri")).toList());
    }

    @Nested
    @DisplayName("probe")
    class Probe {

        @Test
        @DisplayName("a wired index answering zero rows marks text search available")
        void wiredIndexWithNoHit() {
            FusekiTextSearch fresh = searchOver(dataset, new MockEnvironment());

            fresh.probe("mem");

            assertThat(fresh.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("a wired index that finds the sentinel is also healthy")
        void wiredIndexWithHit() {
            // The probe queries the default graph.
            Model sentinel = Ttl.parse("pojem:sentinel skos:prefLabel \"__ismd_text_index_probe__\" .");
            Txn.executeWrite(dataset, () -> dataset.getDefaultModel().add(sentinel));
            FusekiTextSearch fresh = searchOver(dataset, new MockEnvironment());

            fresh.probe("mem");

            assertThat(fresh.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("a dataset without a text index is unavailable; junit profile continues")
        void missingIndexInJunitProfile() {
            MockEnvironment junit = new MockEnvironment();
            junit.setActiveProfiles("junit");
            FusekiTextSearch plain = searchOver(DatasetFactory.createTxnMem(), junit);
            plain.setAvailable(true);

            plain.probe("mem");

            assertThat(plain.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("a dataset without a text index fails boot outside the junit profile")
        void missingIndexOutsideJunitProfile() {
            MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles("production");
            FusekiTextSearch plain = searchOver(DatasetFactory.createTxnMem(), production);

            assertThatThrownBy(() -> plain.probe("mem"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("text index is not configured");
        }
    }

    @Nested
    @DisplayName("searchByText")
    class SearchByText {

        @Test
        @DisplayName("matches without diacritics and projects every field of the hit")
        void foldsDiacriticsAndProjectsFields() {
            List<Map<String, String>> rows = search.searchByText("Ridic", List.of(GRAPH), 10);

            Map<String, String> hit = rows.stream()
                    .filter(r -> RIDIC.equals(r.get("resourceIri"))).findFirst().orElseThrow();
            assertThat(hit)
                    .containsEntry("graphName", GRAPH)
                    .containsEntry("prefLabel", "Řidič")
                    .containsEntry("prefLabelLang", "cs")
                    .containsEntry("altLabel", "Šofér")
                    .containsEntry("description", "Osoba řídící vozidlo")
                    .containsEntry("definition", "Fyzická osoba, která řídí motorové vozidlo");
            assertThat(hit.get("types").split("\\|")).contains(
                    "http://www.w3.org/2004/02/skos/core#Concept",
                    "http://www.w3.org/2002/07/owl#Class");
        }

        @Test
        @DisplayName("a hit matched only by altLabel carries no label fields")
        void altLabelOnlyHitHasNoLabel() {
            Map<String, String> hit = search.searchByText("auto", List.of(GRAPH), 10).get(0);

            assertThat(hit).containsEntry("resourceIri", VOZIDLO)
                    .containsEntry("altLabel", "auto řidiče")
                    .doesNotContainKeys("prefLabel", "prefLabelLang", "description", "definition");
        }

        @Test
        @DisplayName("an untyped hit is still returned, without a types entry")
        void untypedHitHasEmptyTypes() {
            store(GRAPH, "pojem:netypovaný skos:prefLabel \"Netypovaný\"@cs .");

            Map<String, String> hit = search.searchByText("netypovany", List.of(GRAPH), 10).get(0);

            assertThat(hit).containsEntry("resourceIri", Ttl.POJEM + "netypovaný")
                    .containsEntry("graphName", GRAPH)
                    .doesNotContainKey("types");
        }

        @Test
        @DisplayName("a prefix of the last token matches")
        void prefixMatch() {
            assertThat(iris(search.searchByText("šof", List.of(GRAPH), 10))).containsExactly(RIDIC);
        }

        @Test
        @DisplayName("only the visible graphs are searched")
        void visibleGraphsOnly() {
            List<Map<String, String>> rows = search.searchByText("řidič", List.of(GRAPH), 10);

            assertThat(rows).extracting(r -> r.get("graphName")).containsOnly(GRAPH);
        }

        @Test
        @DisplayName("Lucene operators and quotes in the input are neutralised, not parsed")
        void luceneMetaCharactersNeutralised() {
            assertThat(search.searchByText("o'brien\\", List.of(GRAPH), 10)).isEmpty();
            assertThat(iris(search.searchByText("-(sofer)", List.of(GRAPH), 10))).containsExactly(RIDIC);
            assertThat(iris(search.searchByText("  \"[sofer]\"~  ", List.of(GRAPH), 10))).containsExactly(RIDIC);
        }

        @Test
        @DisplayName("the limit caps resources, not type triples")
        void limitCountsResources() {
            assertThat(search.searchByText("řidič", List.of(GRAPH), 1)).hasSize(1);
        }

        @Test
        @DisplayName("role filter accepts the OFN marker or the matching OWL type")
        void roleFilter() {
            assertThat(iris(search.searchByText("řidič", List.of(GRAPH), 10, ConceptType.TRIDA)))
                    .containsExactly(RIDIC);
            assertThat(iris(search.searchByText("řidič", List.of(GRAPH), 10, ConceptType.VLASTNOST)))
                    .containsExactly(JMENO);
            assertThat(iris(search.searchByText("řidič", List.of(GRAPH), 10, ConceptType.VZTAH)))
                    .containsExactly(RIDI);
        }

        @Test
        @DisplayName("KONCEPT is rejected as a role filter instead of widening the search")
        void konceptIsNotARole() {
            assertThatThrownBy(() -> search.searchByText("řidič", List.of(GRAPH), 10, ConceptType.KONCEPT))
                    .isInstanceOf(JenaTDB2Exception.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("filterByRelationTypes")
    class FilterByRelationTypes {

        @BeforeEach
        void relations() {
            store(GRAPH, """
                    pojem:řidič rdfs:subClassOf pojem:osoba ;
                        skos:exactMatch vsgov:řidič .
                    pojem:jméno-řidiče rdfs:domain pojem:řidič .
                    pojem:řídí rdfs:range pojem:vozidlo .
                    a104:řidič skos:exactMatch pojem:vozidlo .
                    """);
        }

        private Set<String> filter(RelationType... types) {
            return search.filterByRelationTypes(List.of(RIDIC, VOZIDLO, RIDI, JMENO, Ttl.POJEM + "osoba"),
                    List.of(types));
        }

        @Test
        @DisplayName("each relation type matches its own pattern")
        void eachPattern() {
            assertThat(filter(RelationType.SUBCLASS)).containsExactly(RIDIC);
            assertThat(filter(RelationType.SUPERCLASS)).containsExactly(Ttl.POJEM + "osoba");
            assertThat(filter(RelationType.EXACT_MATCH)).containsExactlyInAnyOrder(RIDIC, VOZIDLO);
            assertThat(filter(RelationType.PROPERTY_OF)).containsExactly(JMENO);
            assertThat(filter(RelationType.RELATIONSHIP_OF)).containsExactly(RIDI);
        }

        @Test
        @DisplayName("several relation types are a union")
        void union() {
            assertThat(filter(RelationType.PROPERTY_OF, RelationType.RELATIONSHIP_OF))
                    .containsExactlyInAnyOrder(JMENO, RIDI);
        }

        @Test
        @DisplayName("null or empty inputs return empty without querying")
        void guards() {
            assertThat(search.filterByRelationTypes(null, List.of(RelationType.SUBCLASS))).isEmpty();
            assertThat(search.filterByRelationTypes(List.of(), List.of(RelationType.SUBCLASS))).isEmpty();
            assertThat(search.filterByRelationTypes(List.of(RIDIC), null)).isEmpty();
            assertThat(search.filterByRelationTypes(List.of(RIDIC), List.of())).isEmpty();
            assertThat(connections).hasValue(0);
        }
    }
}
