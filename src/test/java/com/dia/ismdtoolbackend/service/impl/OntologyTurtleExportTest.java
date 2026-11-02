package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OntologyTurtleExportTest {

    private static final String GRAPH = "https://slovník.gov.cz/agendový/1/slovník";

    @Mock
    private JenaTDB2Repository tdb2;

    private OntologyTurtleExport export;

    @BeforeEach
    void setUp() {
        export = new OntologyTurtleExport(tdb2);
    }

    @Test
    void writesTheWholeGraphAsTurtle() {
        Model graph = ModelFactory.createDefaultModel();
        graph.createResource(GRAPH + "/pojem/zaměstnanec")
                .addProperty(RDF.type, SKOS.Concept)
                .addProperty(SKOS.prefLabel, "Zaměstnanec", "cs");
        when(tdb2.fetchGraph(GRAPH)).thenReturn(graph);

        String turtle = export.getTtlContentFromOntology(metadata(GRAPH));

        Model parsed = RDFParser.fromString(turtle, Lang.TURTLE).toModel();
        assertThat(parsed.isIsomorphicWith(graph)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingGraphNameIsRejectedWithoutReading(String graphName) {
        assertThatThrownBy(() -> export.getTtlContentFromOntology(metadata(graphName)))
                .isInstanceOf(OntologyException.class)
                .hasMessage("Graph name is missing for the ontology");
        verifyNoInteractions(tdb2);
    }

    @Test
    void emptyGraphIsNotFound() {
        when(tdb2.fetchGraph(GRAPH)).thenReturn(ModelFactory.createDefaultModel());

        assertThatThrownBy(() -> export.getTtlContentFromOntology(metadata(GRAPH)))
                .isInstanceOf(OntologyException.class)
                .hasMessage("Ontology model not found or is empty");
    }

    @Test
    void absentGraphIsNotFound() {
        when(tdb2.fetchGraph(GRAPH)).thenReturn(null);

        assertThatThrownBy(() -> export.getTtlContentFromOntology(metadata(GRAPH)))
                .isInstanceOf(OntologyException.class)
                .hasMessage("Ontology model not found or is empty");
    }

    @Test
    void storageFailureIsWrappedWithItsCause() {
        when(tdb2.fetchGraph(GRAPH)).thenThrow(new IllegalStateException("TDB2 locked"));

        assertThatThrownBy(() -> export.getTtlContentFromOntology(metadata(GRAPH)))
                .isInstanceOf(OntologyException.class)
                .hasMessage("Failed to retrieve TTL content: TDB2 locked");
    }

    private static OntologyMetadataModel metadata(String graphName) {
        OntologyMetadataModel metadata = new OntologyMetadataModel();
        metadata.setSlug("muj-slovnik");
        metadata.setGraphName(graphName);
        return metadata;
    }
}
