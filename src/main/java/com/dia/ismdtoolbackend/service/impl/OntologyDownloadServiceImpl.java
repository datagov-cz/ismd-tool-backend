package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exporter.TurtleExporter;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyDownloadServiceImpl implements OntologyDownloadService {

    private final String fusekiEndpoint;
    private final OntologyMetadataRepository ontologyMetadataRepository;

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

            Model filteredModel = TurtleExporter.createFilteredModel(model);

            StringWriter writer = new StringWriter();
            if ("json-ld".equalsIgnoreCase(format)) {
                filteredModel.write(writer, "JSON-LD");
            } else if ("ttl".equalsIgnoreCase(format)) {
                filteredModel.write(writer, "TTL");
            } else {
                log.error("Output format {} not supported.", format);
                throw new IllegalArgumentException("Nepodporovaný formát: " + format);
            }

            return writer.toString();
        }
    }
}
