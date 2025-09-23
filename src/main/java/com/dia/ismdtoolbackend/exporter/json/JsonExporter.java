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
            log.info("SOURCE PROPERTY DEBUG: Starting OFN JSON export");
            log.info("SOURCE PROPERTY DEBUG: Input model has {} statements", model.size());

            // Log some sample statements to see what we're working with
            StmtIterator sampleIter = model.listStatements();
            int count = 0;
            while (sampleIter.hasNext() && count < 10) {
                Statement stmt = sampleIter.next();
                String predUri = stmt.getPredicate().getURI();
                if (predUri.contains("zdroj") || predUri.contains("ustanovení") || predUri.contains("legislative")) {
                    log.info("SOURCE PROPERTY DEBUG: Found source property in input model: {} -> {}", predUri, stmt.getObject());
                }
                count++;
            }

            OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, model);
            log.info("SOURCE PROPERTY DEBUG: Created OntModel with {} statements", ontModel.size());

            ModelStructure structure = modelAnalyzer.analyzeModel(model);
            log.info("SOURCE PROPERTY DEBUG: Model structure analysis complete - namespace: {}", structure.getEffectiveNamespace());

            ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);
            log.info("SOURCE PROPERTY DEBUG: Processed {} concepts", conceptData.getTotalConceptCount());

            String jsonResult = jsonFormatter.formatAsJson(structure, conceptData);
            log.info("SOURCE PROPERTY DEBUG: JSON formatting complete, result length: {} chars", jsonResult.length());

            // Log if source properties made it to the final result
            if (jsonResult.contains("nelegislativní-zdroj") || jsonResult.contains("ustanovení-právního-předpisu")) {
                log.info("SOURCE PROPERTY DEBUG: SUCCESS - Source properties found in final JSON output");
            } else {
                log.warn("SOURCE PROPERTY DEBUG: WARNING - No source properties found in final JSON output");
            }

            log.debug("OFN JSON export completed successfully");
            return jsonResult;

        } catch (Exception e) {
            log.error("Error during OFN JSON export: {}", e.getMessage(), e);
            throw new JsonExportException("Failed to export to OFN JSON format: " + e.getMessage(), e);
        }
    }
}
