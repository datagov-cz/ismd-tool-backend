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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    @DisplayName("Null and empty graph names are dropped; none left means no query")
    void blankGraphNamesDropped() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList()))
                .thenReturn(modelWithLabels(GRAPH_A, Map.of("cs", "Pracovní poměr")));

        assertThat(lookup.labelsByGraph(Arrays.asList(null, "", GRAPH_A))).containsOnlyKeys(GRAPH_A);
        verify(jenaTDB2Repository).fetchMetadataProperties(List.of(GRAPH_A));

        assertThat(lookup.labelsByGraph(Arrays.asList(null, ""))).isEmpty();
        verifyNoMoreInteractions(jenaTDB2Repository);
    }

    @Test
    @DisplayName("A null metadata model yields an empty map")
    void nullMetadataModel() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(null);

        assertThat(lookup.labelsByGraph(List.of(GRAPH_A))).isEmpty();
    }

    @Test
    @DisplayName("Untagged label keys under the default language; first per language wins; IRI and blank values skipped")
    void labelValueRules() {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(GRAPH_A);
        ontology.addProperty(SKOS.prefLabel, model.createResource(GRAPH_B));
        ontology.addProperty(SKOS.prefLabel, model.createLiteral("   ", "en"));
        ontology.addProperty(SKOS.prefLabel, model.createLiteral("Employment", "en"));
        ontology.addProperty(SKOS.prefLabel, "Pracovní poměr");
        when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(model);

        Map<String, String> labels = lookup.labelsByGraph(List.of(GRAPH_A)).get(GRAPH_A);

        assertThat(labels).containsOnly(
                Map.entry("en", "Employment"), Map.entry(DEFAULT_LANG, "Pracovní poměr"));
    }

    @Test
    @DisplayName("A graph whose only labels are skipped values is absent")
    void onlySkippedLabelsMeansAbsent() {
        Model model = ModelFactory.createDefaultModel();
        model.createResource(GRAPH_A).addProperty(SKOS.prefLabel, "");
        when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(model);

        assertThat(lookup.labelsByGraph(List.of(GRAPH_A))).isEmpty();
    }

    @Test
    @DisplayName("labelFor returns the language map of one graph")
    void labelForReturnsMap() {
        when(jenaTDB2Repository.fetchMetadataProperties(List.of(GRAPH_A)))
                .thenReturn(modelWithLabels(GRAPH_A, Map.of("cs", "Pracovní poměr")));

        assertThat(lookup.labelFor(GRAPH_A)).containsOnly(Map.entry("cs", "Pracovní poměr"));
    }

    @Test
    @DisplayName("labelFor with a null or empty graph returns null without a query")
    void labelForBlankGraph() {
        assertThat(lookup.labelFor(null)).isNull();
        assertThat(lookup.labelFor("")).isNull();
        verifyNoInteractions(jenaTDB2Repository);
    }

    @Test
    @DisplayName("labelFor returns null when the ontology carries no label")
    void labelForNullWhenAbsent() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyList()))
                .thenReturn(ModelFactory.createDefaultModel());

        assertThat(lookup.labelFor(GRAPH_A)).isNull();
    }
}
