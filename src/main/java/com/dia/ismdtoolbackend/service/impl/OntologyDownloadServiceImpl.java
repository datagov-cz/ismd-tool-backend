package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.JsonExportException;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exporter.json.JsonExporter;
import com.dia.ismdtoolbackend.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.exporter.turtle.TurtleFormatterUtil;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.Optional;

import static com.dia.constants.ArchiConstants.SLOVNIKY_NS;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyDownloadServiceImpl implements OntologyDownloadService {

    private final String fusekiEndpoint;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final JsonExporter jsonExporter;

    @Override
    public String downloadOntology(Long ontologyId, String format) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            return "Slovník nebyl nalezen";
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model model = conn.fetch(graphName);

            if (model.isEmpty()) {
                log.error("Ontology model is empty.");
                throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
            }

            Model processedModel = applyAllOFNTransformations(model);

            validateNoDuplicateTransformations(processedModel);

            StringWriter writer = new StringWriter();
            if ("json-ld".equalsIgnoreCase(format)) {
                return exportToOFNJson(processedModel);
            } else if ("ttl".equalsIgnoreCase(format)) {
                processedModel.write(writer, "TTL");
            } else {
                log.error("Output format {} not supported.", format);
                throw new IllegalArgumentException("Nepodporovaný formát: " + format);
            }

            return writer.toString();
        }
    }

    private Model applyAllOFNTransformations(Model rawModel) {
        log.debug("Applying consolidated OFN transformations");

        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);

        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);

        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                rawModel.size(), filteredModel.size(), ofnFormattedModel.size());

        return ofnFormattedModel;
    }

    private void validateNoDuplicateTransformations(Model model) {
        Property slovnikyPojem = model.getProperty(SLOVNIKY_NS + "pojem");

        StmtIterator iter = model.listStatements(null, RDF.type, slovnikyPojem);
        while (iter.hasNext()) {
            Resource subject = iter.next().getSubject();

            int count = 0;
            StmtIterator typeIter = model.listStatements(subject, RDF.type, slovnikyPojem);
            while (typeIter.hasNext()) {
                typeIter.next();
                count++;
            }

            if (count > 1) {
                log.warn("Duplicate type assertion detected for {}: {} times",
                        subject.getURI(), count);
            }
        }
    }

    private String exportToOFNJson(Model processedModel) {
        try {
            return jsonExporter.exportToJson(processedModel);
        } catch (Exception e) {
            log.error("Error exporting to OFN JSON format: {}", e.getMessage(), e);
            throw new JsonExportException("Chyba při exportu do JSON formátu: " + e.getMessage(), e);
        }
    }
}