package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Component;

/** Serializes a vocabulary's graph as Turtle. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyTurtleExport {

    private final JenaTDB2Repository jenaTDB2Repository;

    String getTtlContentFromOntology(OntologyMetadataModel ontologyMetadataModel) {
        try {
            String graphName = ontologyMetadataModel.getGraphName();

            if (graphName == null || graphName.isEmpty()) {
                log.error("Graph name is null or empty for ontology: {}", ontologyMetadataModel.getSlug());
                throw new OntologyException("Graph name is missing for the ontology");
            }

            log.info("Fetching TTL content for graph: {}", graphName);

            Model model = jenaTDB2Repository.fetchGraph(graphName);

            if (model == null || model.isEmpty()) {
                log.warn("Model is empty or null for graph: {}", graphName);
                throw new OntologyException("Ontology model not found or is empty");
            }

            java.io.StringWriter writer = new java.io.StringWriter();
            model.write(writer, "TTL");
            String ttlContent = writer.toString();

            log.info("Successfully retrieved TTL content for graph: {} ({} statements)",
                    graphName, model.size());

            return ttlContent;

        } catch (OntologyException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving TTL content from ontology: {}", e.getMessage(), e);
            throw new OntologyException("Failed to retrieve TTL content: " + e.getMessage());
        }
    }
}
