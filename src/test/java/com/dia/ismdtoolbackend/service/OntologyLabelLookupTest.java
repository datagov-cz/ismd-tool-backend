package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OntologyLabelLookup")
class OntologyLabelLookupTest {

    private static final String GRAPH_A = "https://slovník.gov.cz/pracovni-pomer";
    private static final String GRAPH_B = "https://slovník.gov.cz/adresy";

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @InjectMocks
    private OntologyLabelLookup lookup;

    private static Model modelWithLabels(String graphIri, Map<String, String> byLang) {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(graphIri);
        byLang.forEach((lang, value) -> ontology.addProperty(SKOS.prefLabel, model.createLiteral(value, lang)));
        return model;
    }

    @Test
    @DisplayName("Returns a language map keyed by graph IRI")
    void returnsLanguageMapPerGraph() {
        Model model = modelWithLabels(GRAPH_A, Map.of("cs", "Pracovní poměr", "en", "Employment"));
        when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(model);

        Map<String, Map<String, String>> result = lookup.labelsByGraph(List.of(GRAPH_A));

        assertThat(result).containsOnlyKeys(GRAPH_A);
        assertThat(result.get(GRAPH_A))
                .containsEntry("cs", "Pracovní poměr")
                .containsEntry("en", "Employment");
    }

    @Test
    @DisplayName("Fetches every graph in ONE call, not one per graph")
    void batchesIntoASingleFetch() {
        Model model = ModelFactory.createDefaultModel();
        model.add(modelWithLabels(GRAPH_A, Map.of("cs", "Pracovní poměr")));
        model.add(modelWithLabels(GRAPH_B, Map.of("cs", "Adresy")));
        when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(model);

        Map<String, Map<String, String>> result = lookup.labelsByGraph(List.of(GRAPH_A, GRAPH_B, GRAPH_A));

        assertThat(result).containsOnlyKeys(GRAPH_A, GRAPH_B);
        // Once for the page, and the duplicate GRAPH_A collapsed before the query.
        verify(jenaTDB2Repository).fetchMetadataProperties(List.of(GRAPH_A, GRAPH_B));
    }

    @Test
    @DisplayName("A graph with no prefLabel is absent, not mapped to an empty map")
    void graphWithoutLabelIsAbsent() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList()))
                .thenReturn(modelWithLabels(GRAPH_A, Map.of("cs", "Pracovní poměr")));

        Map<String, Map<String, String>> result = lookup.labelsByGraph(List.of(GRAPH_A, GRAPH_B));

        assertThat(result).containsOnlyKeys(GRAPH_A);
        assertThat(result.get(GRAPH_B)).isNull();
    }

    @Test
    @DisplayName("Fails soft when Fuseki throws — an empty map, not an exception")
    void failsSoftOnFusekiError() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList()))
                .thenThrow(new RuntimeException("Fuseki unreachable"));

        Map<String, Map<String, String>> result = lookup.labelsByGraph(List.of(GRAPH_A));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("No graphs means no query at all")
    void emptyInputSkipsTheQuery() {
        assertThat(lookup.labelsByGraph(List.of())).isEmpty();
        assertThat(lookup.labelsByGraph(null)).isEmpty();
        verify(jenaTDB2Repository, never()).fetchMetadataProperties(any(List.class));
    }

    @Test
    @DisplayName("labelFor returns null when the ontology carries no label")
    void labelForNullWhenAbsent() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList()))
                .thenReturn(ModelFactory.createDefaultModel());

        assertThat(lookup.labelFor(GRAPH_A)).isNull();
    }
}
