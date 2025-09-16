package com.dia.ismdtoolbackend.exporter.json;

import com.dia.exceptions.JsonExportException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class JsonExporter {

    private final ModelAnalyzer modelAnalyzer;
    private final ConceptProcessor conceptProcessor;
    private final JsonFormatter jsonFormatter;

    public JsonExporter() {
        this.modelAnalyzer = new ModelAnalyzer();
        this.conceptProcessor = new ConceptProcessor();
        this.jsonFormatter = new JsonFormatter();
    }

    public String exportToJson(Model model) {
        try {
            log.debug("Starting OFN JSON export");

            OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, model);

            ModelStructure structure = modelAnalyzer.analyzeModel(model);

            ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

            String jsonResult = jsonFormatter.formatAsJson(structure, conceptData);

            log.debug("OFN JSON export completed successfully");
            return jsonResult;

        } catch (Exception e) {
            log.error("Error during OFN JSON export: {}", e.getMessage(), e);
            throw new JsonExportException("Failed to export to OFN JSON format: " + e.getMessage(), e);
        }
    }
}
