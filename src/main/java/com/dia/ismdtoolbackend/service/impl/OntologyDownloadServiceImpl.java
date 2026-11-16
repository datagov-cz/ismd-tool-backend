package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.JsonExportException;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFormatterUtil;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyDownloadServiceImpl implements OntologyDownloadService {

    private final String fusekiEndpoint;
    private final HttpClient fusekiHttpClient;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final JsonExporter jsonExporter;
    private final Semaphore fusekiSemaphore;
    private final int fusekiSemaphoreTimeout;

    @Override
    public String downloadOntology(Long ontologyId, String format) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyNotFoundException("Metadata slovníku nebyla nalezena");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();

        boolean acquired;
        try {
            acquired = fusekiSemaphore.tryAcquire(fusekiSemaphoreTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Fuseki connection", e);
        }
        if (!acquired) {
            throw new RuntimeException("Fuseki server is busy, try again later");
        }

        try (RDFConnection conn = RDFConnectionRemote.newBuilder()
                .destination(fusekiEndpoint)
                .gspEndpoint("data")
                .httpClient(fusekiHttpClient)
                .build()) {
            Model model = fetchGraph(conn, graphName);
            Model processedModel = null;
            try {
                if (model.isEmpty()) {
                    log.error("Ontology model is empty.");
                    throw new EmptyDataException("Slovník je prázdný, nebo nebyl nalezen.");
                }

                processedModel = applyAllOFNTransformations(model);
                model.close();
                model = null;

                String result;
                if ("json-ld".equalsIgnoreCase(format)) {
                    result = exportToOFNJson(processedModel);
                } else if ("ttl".equalsIgnoreCase(format)) {
                    StringWriter writer = new StringWriter();
                    processedModel.write(writer, "TTL");
                    result = writer.toString();
                } else {
                    log.error("Output format {} not supported.", format);
                    throw new IllegalArgumentException("Nepodporovaný formát: " + format);
                }

                return result;
            } finally {
                if (model != null) model.close();
                if (processedModel != null) processedModel.close();
            }
        } finally {
            fusekiSemaphore.release();
        }
    }

    private Model applyAllOFNTransformations(Model rawModel) {
        log.debug("Applying consolidated OFN transformations");

        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);
        try {
            Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);

            log.debug("OFN transformation complete: {} -> {} -> {} statements",
                    rawModel.size(), filteredModel.size(), ofnFormattedModel.size());

            return ofnFormattedModel;
        } finally {
            filteredModel.close();
        }
    }

    /** An unknown graph (GSP 404) reads as an empty model. */
    private Model fetchGraph(RDFConnection conn, String graphName) {
        try {
            return conn.fetch(graphName);
        } catch (HttpException e) {
            if (e.getStatusCode() == 404) {
                log.error("Graph {} not found in Fuseki", graphName);
                return ModelFactory.createDefaultModel();
            }
            throw e;
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