package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Name and description of an ontology from its RDF, and the fallbacks when RDF is missing or unreadable. */
@ExtendWith(MockitoExtension.class)
class OntologyMetadataEnricherTest {

    private static final String GRAPH = "https://slovník.gov.cz/agendový/test-slovnik";
    private static final Map<String, String> FALLBACK = Map.of("cs", "Test slovnik");

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;
    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyMetadataEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new OntologyMetadataEnricher(jenaTDB2Repository, new OntologyMetadataMapperImpl(), conceptMetadataRepository);
    }

    private static Model metadataOf(String graphName) {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(graphName);
        ontology.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");
        ontology.addProperty(SKOS.prefLabel, "Test vocabulary", "en");
        ontology.addProperty(SKOS.prefLabel, "   ", "de");
        ontology.addProperty(SKOS.prefLabel, model.createResource("https://x/not-a-literal"));
        ontology.addProperty(DCTerms.description, "Popis bez jazyka");
        return model;
    }

    // ---- single ontology ---------------------------------------------------------------------------

    @Test
    void namesAndDescriptionComeFromRdf_blankAndNonLiteralSkipped_untaggedFiledUnderCzech() {
        when(jenaTDB2Repository.fetchMetadataProperties(GRAPH)).thenReturn(metadataOf(GRAPH));
        OntologySummaryDto model = new OntologySummaryDto();

        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, GRAPH));

        assertThat(model.getName()).containsOnly(
                Map.entry("cs", "Testovací slovník"), Map.entry("en", "Test vocabulary"));
        assertThat(model.getPopis()).containsExactly(Map.entry("cs", "Popis bez jazyka"));
    }

    @Test
    void ontologyWithoutLabels_fallsBackToTheGraphNameAndKeepsNoDescription() {
        Model model = ModelFactory.createDefaultModel();
        model.createResource(GRAPH).addProperty(DCTerms.title, "Jen titulek");
        when(jenaTDB2Repository.fetchMetadataProperties(GRAPH)).thenReturn(model);
        OntologySummaryDto result = new OntologySummaryDto();

        enricher.enrichMetadataFromRDF(result, ontologyEntity(1L, GRAPH));

        assertThat(result.getName()).isEqualTo(FALLBACK);
        assertThat(result.getPopis()).isNull();
    }

    @Test
    void missingGraphName_isNotLookedUp() {
        OntologySummaryDto model = new OntologySummaryDto();

        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, null));
        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, ""));

        assertThat(model.getName()).isNull();
        verifyNoInteractions(jenaTDB2Repository);
    }

    @Test
    void failingRdfRead_fallsBackToTheGraphName() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologySummaryDto model = new OntologySummaryDto();

        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, GRAPH));

        assertThat(model.getName()).isEqualTo(FALLBACK);
    }

    @Test
    void failingRdfRead_keepsANameAlreadySet() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologySummaryDto model = new OntologySummaryDto();
        model.setName(Map.of("cs", "Z databáze"));

        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, GRAPH));

        assertThat(model.getName()).containsExactly(Map.entry("cs", "Z databáze"));
    }

    @Test
    void emptyModelName_isReplacedOnFailure() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologySummaryDto model = new OntologySummaryDto();
        model.setName(Map.of());

        enricher.enrichMetadataFromRDF(model, ontologyEntity(1L, GRAPH));

        assertThat(model.getName()).isEqualTo(FALLBACK);
    }

    @Test
    void unreadableModel_fallsBackOnlyWhenNoNameIsSet() {
        Model closed = metadataOf(GRAPH);
        closed.close();

        OntologySummaryDto unnamed = new OntologySummaryDto();
        enricher.enrichMetadataFromModel(unnamed, ontologyEntity(1L, GRAPH), closed);
        assertThat(unnamed.getName()).isEqualTo(FALLBACK);

        OntologySummaryDto emptyName = new OntologySummaryDto();
        emptyName.setName(Map.of());
        enricher.enrichMetadataFromModel(emptyName, ontologyEntity(1L, GRAPH), closed);
        assertThat(emptyName.getName()).isEqualTo(FALLBACK);

        OntologySummaryDto named = new OntologySummaryDto();
        named.setName(Map.of("cs", "Z databáze"));
        enricher.enrichMetadataFromModel(named, ontologyEntity(1L, GRAPH), closed);
        assertThat(named.getName()).containsExactly(Map.entry("cs", "Z databáze"));
    }

    @Test
    void graphNameWithoutAName_hasNoFallback() {
        assertThat(enricher.fallbackNameFromGraphName("")).isEmpty();
        assertThat(enricher.extractNameFromGraphName(null)).isNull();
        assertThat(enricher.extractNameFromGraphName("")).isEmpty();
        assertThat(enricher.extractNameFromGraphName("test-slovnik")).isEqualTo("Test slovnik");
    }

    // ---- page ----------------------------------------------------------------------------------------

    @Test
    void pageFetchesOnlyRealGraphsInOneCall_andCountsConceptsInOneQuery() {
        String other = "https://slovník.gov.cz/agendový/jiny-slovnik";
        Model batch = metadataOf(GRAPH);
        batch.add(metadataOf(other).listStatements());
        List<String> requested = new ArrayList<>();
        when(jenaTDB2Repository.fetchMetadataProperties(ArgumentMatchers.<List<String>>any()))
                .thenAnswer(inv -> {
                    requested.addAll(inv.getArgument(0));
                    return batch;
                });
        OntologyMetadataEntity first = ontologyEntity(1L, GRAPH);
        OntologyMetadataEntity second = ontologyEntity(2L, other);
        OntologyMetadataEntity noGraph = ontologyEntity(3L, null);
        OntologyMetadataEntity emptyGraph = ontologyEntity(4L, "");
        when(conceptMetadataRepository.countByGraphNameIn(List.of(GRAPH, other)))
                .thenReturn(List.<Object[]>of(new Object[]{GRAPH, 5L}));

        List<OntologySummaryDto> page = enricher.enrichEntitiesWithBatchMetadata(
                List.of(first, second, noGraph, emptyGraph));

        assertThat(requested).containsExactly(GRAPH, other);
        assertThat(page).hasSize(4);
        assertThat(page.get(0).getName()).containsEntry("cs", "Testovací slovník");
        // A graph without concept rows is absent from the grouped count and reads 0.
        assertThat(page).extracting(OntologySummaryDto::getConceptCount).containsExactly(5, 0, 0, 0);
        assertThat(page.get(2).getName()).isEmpty();
        verify(conceptMetadataRepository).countByGraphNameIn(anyList());
    }

    @Test
    void pageKeepsEveryLanguage() {
        String first = "http://example.org/o/1";
        String second = "http://example.org/o/2";
        Model batch = ModelFactory.createDefaultModel();
        batch.createResource(first).addProperty(SKOS.prefLabel, "Slovník 1");
        batch.createResource(second)
                .addProperty(SKOS.prefLabel, "Slovník 2", "cs")
                .addProperty(SKOS.prefLabel, "Vocabulary 2", "en")
                .addProperty(DCTerms.description, "Popis 2", "cs")
                .addProperty(DCTerms.description, "Description 2", "en");
        when(jenaTDB2Repository.fetchMetadataProperties(List.of(first, second))).thenReturn(batch);

        List<OntologySummaryDto> page = enricher.enrichEntitiesWithBatchMetadata(
                List.of(ontologyEntity(1L, first), ontologyEntity(2L, second), ontologyEntity(3L, null)));

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getName()).isEqualTo(Map.of("cs", "Slovník 1"));
        assertThat(page.get(1).getName()).isEqualTo(Map.of("cs", "Slovník 2", "en", "Vocabulary 2"));
        assertThat(page.get(1).getPopis()).isEqualTo(Map.of("cs", "Popis 2", "en", "Description 2"));
        // No graph name: no IRI to derive a fallback from, so the name map stays empty.
        assertThat(page.get(2).getName()).isEmpty();
    }

    @Test
    void pageWithTwoLabelsInOneLanguage_keepsOneOfThemAndTheOtherLanguages() {
        String graph = "http://example.org/o/1";
        Model batch = ModelFactory.createDefaultModel();
        batch.createResource(graph)
                .addProperty(SKOS.prefLabel, "Slovník", "cs")
                .addProperty(SKOS.prefLabel, "Slovník duplicitní", "cs")
                .addProperty(SKOS.prefLabel, "Vocabulary", "en");
        when(jenaTDB2Repository.fetchMetadataProperties(List.of(graph))).thenReturn(batch);

        OntologySummaryDto model = enricher.enrichEntitiesWithBatchMetadata(List.of(ontologyEntity(1L, graph))).get(0);

        assertThat(model.getName()).hasSize(2).containsEntry("en", "Vocabulary");
        assertThat(model.getName().get("cs")).startsWith("Slovník");
    }

    @Test
    void pageEntryWithoutLabels_fallsBackToTheGraphNameUnderCzech() {
        String graph = "http://example.org/muj-slovnik";
        Model batch = ModelFactory.createDefaultModel();
        batch.createResource("http://example.org/other").addProperty(SKOS.prefLabel, "Jiný");
        when(jenaTDB2Repository.fetchMetadataProperties(List.of(graph))).thenReturn(batch);

        OntologySummaryDto model = enricher.enrichEntitiesWithBatchMetadata(List.of(ontologyEntity(1L, graph))).get(0);

        assertThat(model.getName()).isEqualTo(Map.of("cs", "Muj slovnik"));
        assertThat(model.getPopis()).isNull();
    }

    @Test
    void emptyPage_readsNothing() {
        assertThat(enricher.enrichEntitiesWithBatchMetadata(List.of())).isEmpty();
        verifyNoInteractions(jenaTDB2Repository, conceptMetadataRepository);
    }
}
