package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.config.AsyncConfig;
import com.dia.validation.ValidationReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs the advisory validation of an uploaded vocabulary and records its outcome. */
@Component
@Slf4j
class UploadValidationRunner {

    private final ValidationClient validationClient;
    private final OntologyUploadTransactions transactions;
    private final Executor executor;

    UploadValidationRunner(ValidationClient validationClient, OntologyUploadTransactions transactions,
                           @Qualifier(AsyncConfig.UPLOAD_VALIDATION_EXECUTOR) Executor executor) {
        this.validationClient = validationClient;
        this.transactions = transactions;
        this.executor = executor;
    }

    /** Starts the validation on its own executor; the upload does not wait for it. */
    void start(String ontologyContent, String iri) {
        try {
            executor.execute(() -> {
                try {
                    requestAndSaveValidationReport(ontologyContent, iri);
                } catch (RuntimeException e) {
                    log.error("Async validation failed for ontology: {}", iri, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // The upload is committed; the validation is advisory, so a full queue only skips it.
            log.warn("Validation of ontology {} not started, the validation queue is full", iri);
        }
    }

    /**
     * Advisory post-upload validation: calls the validator (lenient — a validator outage must never
     * block ingest) and then persists the outcome. The HTTP call is deliberately OUTSIDE any DB
     * transaction so a slow/hung validator never holds a pooled connection; only the persistence is
     * transactional, in {@link OntologyUploadTransactions#saveValidationOutcome}.
     */
    void requestAndSaveValidationReport(String ontologyContent, String iri) {
        // Lenient client swallows outages/rejections and returns empty — never throws here.
        Optional<ValidationReport> report = validationClient.requestValidationLenient(ontologyContent, iri);
        try {
            transactions.saveValidationOutcome(iri, report.orElse(null));
        } catch (Exception e) {
            log.warn("Persisting validation outcome failed for ontology {}: {}", iri, e.getMessage(), e);
        }
    }
}