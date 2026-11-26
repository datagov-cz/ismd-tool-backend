package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.ConversionException;
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
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.system.StreamRDFWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads an uploaded RDF file into a model, within the size and parse-time limits. */
@Component
@Slf4j
class UploadFileReader {

    private final String maxFileSizeConfig;
    private final int rdfParsingTimeoutSeconds;

    /** Bounded: a full pool rejects the upload (503) instead of starting more parsers. */
    private final ExecutorService parseExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread t = new Thread(runnable, "upload-parse");
                t.setDaemon(true);
                return t;
            });

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

    /**
     * Parses on a worker from a small shared pool, streaming the file. The request answers when the
     * deadline passes, and the parse stops then too: the input stream and the triple sink both throw past
     * the deadline, so no parser keeps running, and its half-built model is dropped.
     */
    private OntModel getOntologyModel(MultipartFile file, Lang rdfLang) throws IOException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(rdfParsingTimeoutSeconds);
        Future<OntModel> future = parseExecutor.submit(() -> parse(file, rdfLang, deadlineNanos));
        try {
            return future.get(rdfParsingTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw parseTimeout();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (ParseDeadline.isCause(cause)) {
                throw parseTimeout();
            }
            throw new OntologyUploadParseException("Chyba při zpracování RDF souboru: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new OntologyUploadException("Zpracování RDF souboru bylo přerušeno.");
        }
    }

    private static OntModel parse(MultipartFile file, Lang rdfLang, long deadlineNanos) throws IOException {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        StreamRDF sink = new StreamRDFWrapper(StreamRDFLib.graph(model.getGraph())) {
            @Override
            public void triple(Triple triple) {
                ParseDeadline.check(deadlineNanos);
                super.triple(triple);
            }
        };
        try (InputStream in = new ParseDeadline.Stream(file.getInputStream(), deadlineNanos)) {
            RDFParser.source(in).lang(rdfLang).parse(sink);
        }
        return model;
    }

    private OntologyUploadParseTimeoutException parseTimeout() {
        log.error("RDF parsing timed out after {} seconds", rdfParsingTimeoutSeconds);
        return new OntologyUploadParseTimeoutException(
                "Zpracování RDF souboru překročilo časový limit (" + rdfParsingTimeoutSeconds + " s).");
    }

    /** The parse deadline, enforced on the bytes read and on the triples emitted. */
    static final class ParseDeadline extends RuntimeException {

        private ParseDeadline() {
            super("RDF parse deadline passed", null, false, false);
        }

        static void check(long deadlineNanos) {
            if (System.nanoTime() - deadlineNanos > 0 || Thread.currentThread().isInterrupted()) {
                throw new ParseDeadline();
            }
        }

        static boolean isCause(Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof ParseDeadline) {
                    return true;
                }
            }
            return false;
        }

        static final class Stream extends FilterInputStream {
            private final long deadlineNanos;

            Stream(InputStream in, long deadlineNanos) {
                super(in);
                this.deadlineNanos = deadlineNanos;
            }

            @Override
            public int read() throws IOException {
                check(deadlineNanos);
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                check(deadlineNanos);
                return super.read(b, off, len);
            }
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
