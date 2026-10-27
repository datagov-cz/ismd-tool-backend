package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.OntologyUploadFileTooLargeException;
import com.dia.ismdtoolbackend.exception.OntologyUploadParseException;
import com.dia.ismdtoolbackend.exception.OntologyUploadParseTimeoutException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads an uploaded RDF file into a model, within the size and parse-time limits. */
@Component
@Slf4j
class UploadFileReader {

    private final String maxFileSizeConfig;
    private final int rdfParsingTimeoutSeconds;

    UploadFileReader(@Value("${spring.servlet.multipart.max-file-size:10MB}") String maxFileSizeConfig,
                     @Value("${rdf.parsing.timeout:60}") int rdfParsingTimeoutSeconds) {
        this.maxFileSizeConfig = maxFileSizeConfig;
        this.rdfParsingTimeoutSeconds = rdfParsingTimeoutSeconds;
    }

    Lang determineRDFFormat(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

            switch (extension) {
                case "ttl", "turtle":
                    return Lang.TURTLE;
                case "jsonld", "json-ld":
                    return Lang.JSONLD;
                default:
                    break;
            }
        }

        String contentType = file.getContentType();
        if (contentType != null) {
            if (contentType.contains("turtle")) {
                return Lang.TURTLE;
            } else if (contentType.contains("json")) {
                return Lang.JSONLD;
            }
        }
        return null;
    }

    /** Rejects an empty, oversized or unsupported file, then parses it. */
    OntModel read(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new EmptyFileException("Uploaded file is empty");
        }

        long maxBytes = DataSize.parse(maxFileSizeConfig).toBytes();
        if (file.getSize() > maxBytes) {
            throw new OntologyUploadFileTooLargeException(
                    String.format("Soubor překračuje maximální povolenou velikost (%d MB).", maxBytes / (1024 * 1024))
            );
        }

        Lang rdfLang = determineRDFFormat(file);
        if (rdfLang == null) {
            throw new UnsupportedRdfFormatException("Nepodporovaný RDF jazyk");
        }

        return getOntologyModel(file, rdfLang);
    }

    private OntModel getOntologyModel(MultipartFile file, Lang rdfLang) throws IOException {
        OntModel uploadedModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        byte[] fileBytes = file.getBytes();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes)) {
                    RDFDataMgr.read(uploadedModel, inputStream, rdfLang);
                    return null;
                }
            });
            future.get(rdfParsingTimeoutSeconds, TimeUnit.SECONDS);
            return uploadedModel;
        } catch (TimeoutException e) {
            log.error("RDF parsing timed out after {} seconds", rdfParsingTimeoutSeconds);
            throw new OntologyUploadParseTimeoutException("Zpracování RDF souboru překročilo časový limit (" + rdfParsingTimeoutSeconds + " s).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new OntologyUploadParseException("Chyba při zpracování RDF souboru: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OntologyUploadException("Zpracování RDF souboru bylo přerušeno.");
        } finally {
            executor.shutdownNow();
        }
    }

    String convertOntModelToTtl(OntModel model) throws RuntimeException {
        try {
            StringWriter writer = new StringWriter();
            model.write(writer, "TTL");
            return writer.toString();
        } catch (Exception e) {
            log.error("Failed to convert OntModel to TTL", e);
            throw new ConversionException("Failed to convert OntModel to TTL: " + e.getMessage(), e);
        }
    }
}
