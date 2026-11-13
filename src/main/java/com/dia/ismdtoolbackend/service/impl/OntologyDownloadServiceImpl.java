package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.ofn.OfnDates;
import com.dia.ismdtoolbackend.ofn.OfnVocabularyExporter;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
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
    private final OfnVocabularyExporter exporter;
    private final Semaphore fusekiSemaphore;
    private final int fusekiSemaphoreTimeout;

    @Override
    public String downloadOntology(Long ontologyId, String format) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyNotFoundException("Metadata slovníku nebyla nalezena");
        }

        OntologyMetadataEntity metadata = ontologyMetadataOpt.get();
        String graphName = metadata.getGraphName();
        OfnVocabularyExporter.Format ofnFormat = OfnVocabularyExporter.Format.of(format);

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
            try {
                if (model.isEmpty()) {
                    log.error("Ontology model is empty.");
                    throw new EmptyDataException("Slovník je prázdný, nebo nebyl nalezen.");
                }
                // aktualizováno is the metadata's last change, not a stored or uploaded value
                OfnDates.replaceLastChange(model, graphName, metadata.getUpdatedAt());
                return exporter.export(model, graphName, ofnFormat);
            } finally {
                model.close();
            }
        } finally {
            fusekiSemaphore.release();
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
}