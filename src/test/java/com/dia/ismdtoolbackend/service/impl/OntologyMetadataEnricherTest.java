package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
    private CommentRepository commentRepository;

    private OntologyMetadataEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new OntologyMetadataEnricher(jenaTDB2Repository, new OntologyMetadataMapperImpl(), commentRepository);
    }

    private static OntologyMetadataEntity entity(Long id, String graphName) {
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setId(id);
        entity.setGraphName(graphName);
        return entity;
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
        OntologyMetadataModel model = new OntologyMetadataModel();

        enricher.enrichMetadataFromRDF(model, entity(1L, GRAPH));

        assertThat(model.getName()).containsOnly(
                Map.entry("cs", "Testovací slovník"), Map.entry("en", "Test vocabulary"));
        assertThat(model.getPopis()).containsExactly(Map.entry("cs", "Popis bez jazyka"));
    }

    @Test
    void ontologyWithoutLabels_fallsBackToTheGraphNameAndKeepsNoDescription() {
        Model model = ModelFactory.createDefaultModel();
        model.createResource(GRAPH).addProperty(DCTerms.title, "Jen titulek");
        when(jenaTDB2Repository.fetchMetadataProperties(GRAPH)).thenReturn(model);
        OntologyMetadataModel result = new OntologyMetadataModel();

        enricher.enrichMetadataFromRDF(result, entity(1L, GRAPH));

        assertThat(result.getName()).isEqualTo(FALLBACK);
        assertThat(result.getPopis()).isNull();
    }

    @Test
    void missingGraphName_isNotLookedUp() {
        OntologyMetadataModel model = new OntologyMetadataModel();

        enricher.enrichMetadataFromRDF(model, entity(1L, null));
        enricher.enrichMetadataFromRDF(model, entity(1L, ""));

        assertThat(model.getName()).isNull();
        verifyNoInteractions(jenaTDB2Repository);
    }

    @Test
    void failingRdfRead_fallsBackToTheGraphName() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologyMetadataModel model = new OntologyMetadataModel();

        enricher.enrichMetadataFromRDF(model, entity(1L, GRAPH));

        assertThat(model.getName()).isEqualTo(FALLBACK);
    }

    @Test
    void failingRdfRead_keepsANameAlreadySet() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setName(Map.of("cs", "Z databáze"));

        enricher.enrichMetadataFromRDF(model, entity(1L, GRAPH));

        assertThat(model.getName()).containsExactly(Map.entry("cs", "Z databáze"));
    }

    @Test
    void emptyModelName_isReplacedOnFailure() {
        when(jenaTDB2Repository.fetchMetadataProperties(anyString())).thenThrow(new IllegalStateException("TDB2 down"));
        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setName(Map.of());

        enricher.enrichMetadataFromRDF(model, entity(1L, GRAPH));

        assertThat(model.getName()).isEqualTo(FALLBACK);
    }

    @Test
    void unreadableModel_fallsBackOnlyWhenNoNameIsSet() {
        Model closed = metadataOf(GRAPH);
        closed.close();

        OntologyMetadataModel unnamed = new OntologyMetadataModel();
        enricher.enrichMetadataFromModel(unnamed, entity(1L, GRAPH), closed);
        assertThat(unnamed.getName()).isEqualTo(FALLBACK);

        OntologyMetadataModel emptyName = new OntologyMetadataModel();
        emptyName.setName(Map.of());
        enricher.enrichMetadataFromModel(emptyName, entity(1L, GRAPH), closed);
        assertThat(emptyName.getName()).isEqualTo(FALLBACK);

        OntologyMetadataModel named = new OntologyMetadataModel();
        named.setName(Map.of("cs", "Z databáze"));
        enricher.enrichMetadataFromModel(named, entity(1L, GRAPH), closed);
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
    void pageFetchesOnlyRealGraphsInOneCall_andGroupsCommentsPerOntology() {
        String other = "https://slovník.gov.cz/agendový/jiny-slovnik";
        Model batch = metadataOf(GRAPH);
        batch.add(metadataOf(other).listStatements());
        List<String> requested = new ArrayList<>();
        when(jenaTDB2Repository.fetchMetadataProperties(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenAnswer(inv -> {
                    requested.addAll(inv.getArgument(0));
                    return batch;
                });
        OntologyMetadataEntity first = entity(1L, GRAPH);
        OntologyMetadataEntity second = entity(2L, other);
        OntologyMetadataEntity noGraph = entity(3L, null);
        OntologyMetadataEntity emptyGraph = entity(4L, "");
        when(commentRepository.findByOntologyMetadataIdIn(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(comment(first, "první"), comment(first, "druhý"), comment(second, "třetí")));

        List<OntologyMetadataModel> page = enricher.enrichEntitiesWithBatchMetadata(
                List.of(first, second, noGraph, emptyGraph));

        assertThat(requested).containsExactly(GRAPH, other);
        assertThat(page).hasSize(4);
        assertThat(page.get(0).getName()).containsEntry("cs", "Testovací slovník");
        assertThat(page.get(0).getComments()).hasSize(2);
        assertThat(page.get(1).getComments()).hasSize(1);
        assertThat(page.get(2).getName()).isEmpty();
        assertThat(page.get(2).getComments()).isEmpty();
        assertThat(page.get(3).getComments()).isEmpty();
    }

    @Test
    void emptyPage_readsNothing() {
        assertThat(enricher.enrichEntitiesWithBatchMetadata(List.of())).isEmpty();
        verifyNoInteractions(jenaTDB2Repository, commentRepository);
    }

    private static CommentEntity comment(OntologyMetadataEntity ontology, String text) {
        CommentEntity comment = new CommentEntity();
        comment.setOntologyMetadata(ontology);
        comment.setComment(text);
        return comment;
    }
}
