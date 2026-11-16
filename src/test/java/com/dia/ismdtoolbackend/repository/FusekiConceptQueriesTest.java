package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link FusekiConceptQueries} SPARQL executed against an in-memory dataset. */
class FusekiConceptQueriesTest {

    private static final String GRAPH = Ttl.VOCABULARY;
    private static final String OTHER_GRAPH = "https://slovník.gov.cz/datový/jiný-slovník/";
    private static final String OSOBA = Ttl.POJEM + "osoba";
    private static final String ADRESA = Ttl.POJEM + "adresa";
    private static final String SUBJEKT = Ttl.POJEM + "subjekt";
    private static final String UNSAFE = "https://example.org/a> } DROP ALL { <x";

    private final Dataset dataset = DatasetFactory.createTxnMem();
    private final AtomicInteger connections = new AtomicInteger();
    private FusekiConceptQueries queries;

    @BeforeEach
    void setUp() {
        queries = new FusekiConceptQueries(new FusekiSparqlExecutor(new Semaphore(1), 1000, () -> {
            connections.incrementAndGet();
            return RDFConnection.connect(dataset);
        }));
    }

    private void store(String graph, String turtle) {
        Model model = Ttl.parse(turtle);
        Txn.executeWrite(dataset, () -> dataset.addNamedModel(graph, model));
    }

    @Test
    @DisplayName("fetchExternalDomainMembers: an unsafe IRI returns an empty model without querying")
    void externalMembersUnsafeIri() {
        assertThat(queries.fetchExternalDomainMembers(UNSAFE).isEmpty()).isTrue();
        assertThat(connections).hasValue(0);
    }

    @Test
    @DisplayName("findRelatedConceptUris: domain and range members in the graph, blank nodes skipped")
    void relatedConceptsSkipBlankNodes() {
        store(GRAPH, """
                pojem:jméno rdfs:domain pojem:osoba .
                pojem:bydlí-v rdfs:range pojem:osoba .
                [] rdfs:domain pojem:osoba .
                """);
        store(OTHER_GRAPH, "<https://slovník.gov.cz/datový/jiný-slovník/pojem/věk> rdfs:domain pojem:osoba .");

        List<String> related = queries.findRelatedConceptUris(OSOBA, GRAPH);

        assertThat(related).containsExactlyInAnyOrder(Ttl.POJEM + "jméno", Ttl.POJEM + "bydlí-v");
    }

    @Test
    @DisplayName("fetchConceptLabels: invalid IRIs are dropped, the valid ones still answer")
    void conceptLabelsDropInvalidIris() {
        store(GRAPH, """
                pojem:osoba skos:prefLabel "Osoba"@cs ; skos:definition "Fyzická osoba"@cs .
                """);

        Model labels = queries.fetchConceptLabels(Arrays.asList(OSOBA, UNSAFE, "urn:x:relative"));

        assertThat(labels.getResource(OSOBA).getProperty(SKOS.prefLabel).getString()).isEqualTo("Osoba");
        assertThat(labels.getResource(OSOBA).getProperty(SKOS.definition).getString()).isEqualTo("Fyzická osoba");
    }

    @Test
    @DisplayName("fetchConceptLabels: only invalid IRIs return an empty model without querying")
    void conceptLabelsAllInvalid() {
        assertThat(queries.fetchConceptLabels(List.of(UNSAFE)).isEmpty()).isTrue();
        assertThat(queries.fetchConceptLabels(List.of()).isEmpty()).isTrue();
        assertThat(queries.fetchConceptLabels(null).isEmpty()).isTrue();
        assertThat(connections).hasValue(0);
    }

    @Test
    @DisplayName("fetchConceptHierarchy: null or unsafe input returns empty links without querying")
    void hierarchyGuards() {
        assertThat(queries.fetchConceptHierarchy(null, GRAPH)).isEqualTo(ConceptHierarchyLinks.empty());
        assertThat(queries.fetchConceptHierarchy(OSOBA, null)).isEqualTo(ConceptHierarchyLinks.empty());
        assertThat(queries.fetchConceptHierarchy(UNSAFE, GRAPH)).isEqualTo(ConceptHierarchyLinks.empty());
        assertThat(connections).hasValue(0);
    }

    @Test
    @DisplayName("fetchConceptHierarchy: subClassOf and broader merge without duplicates; literals and blanks ignored")
    void hierarchyCollectsDistinctIris() {
        store(GRAPH, """
                pojem:osoba rdfs:subClassOf pojem:subjekt, [] ;
                    skos:broader pojem:subjekt, "subjekt" ;
                    skos:exactMatch <https://slovník.gov.cz/veřejný-sektor/pojem/osoba>,
                                    <https://slovník.gov.cz/agendový/104/pojem/osoba> .
                """);

        ConceptHierarchyLinks links = queries.fetchConceptHierarchy(OSOBA, GRAPH);

        assertThat(links.broader()).containsExactly(SUBJEKT);
        assertThat(links.exactMatch()).containsExactlyInAnyOrder(
                "https://slovník.gov.cz/veřejný-sektor/pojem/osoba",
                "https://slovník.gov.cz/agendový/104/pojem/osoba");
    }

    @Test
    @DisplayName("fetchConceptHierarchy: links outside the concept's own graph are not read")
    void hierarchyIsGraphScoped() {
        store(GRAPH, "pojem:osoba a skos:Concept .");
        store(OTHER_GRAPH, "pojem:osoba rdfs:subClassOf pojem:subjekt ; skos:exactMatch pojem:adresa .");

        ConceptHierarchyLinks links = queries.fetchConceptHierarchy(OSOBA, GRAPH);

        assertThat(links.broader()).isEmpty();
        assertThat(links.exactMatch()).isEmpty();
    }

    @Test
    @DisplayName("fetchConceptResolutions: projects label and owning scheme; stray foreign inScheme ignored")
    void resolutionsUseOwningScheme() {
        store(GRAPH, """
                ex: skos:prefLabel "Testovací slovník"@cs .
                pojem:osoba skos:inScheme ex: ; skos:prefLabel "Osoba"@cs ; a slovníky:třída .
                pojem:adresa skos:inScheme <https://slovník.gov.cz/datový/jiný-slovník/> .
                """);

        Map<String, ResolvedConceptDto> out = queries.fetchConceptResolutions(List.of(OSOBA, ADRESA));

        assertThat(out).containsOnlyKeys(OSOBA);
        ResolvedConceptDto dto = out.get(OSOBA);
        assertThat(dto.conceptName()).containsEntry("cs", "Osoba");
        assertThat(dto.ontologyIri()).isEqualTo(GRAPH);
        assertThat(dto.ontologyName()).containsEntry("cs", "Testovací slovník");
        assertThat(dto.source()).isEqualTo(SearchSource.ISMD);
    }

    @Test
    @DisplayName("fetchConceptResolutions: invalid IRIs are dropped and the rest resolve")
    void resolutionsDropInvalidIris() {
        store(GRAPH, "pojem:osoba skos:inScheme ex: .");

        Map<String, ResolvedConceptDto> out = queries.fetchConceptResolutions(List.of(UNSAFE, OSOBA));

        assertThat(out).containsOnlyKeys(OSOBA);
    }

    @Test
    @DisplayName("fetchConceptResolutions: null, empty or all-invalid input returns empty without querying")
    void resolutionsGuards() {
        assertThat(queries.fetchConceptResolutions(null)).isEmpty();
        assertThat(queries.fetchConceptResolutions(List.of())).isEmpty();
        assertThat(queries.fetchConceptResolutions(List.of(UNSAFE))).isEmpty();
        assertThat(connections).hasValue(0);
    }
}
