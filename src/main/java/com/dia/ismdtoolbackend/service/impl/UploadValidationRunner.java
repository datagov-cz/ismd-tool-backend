package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.client.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Runs the advisory validation of an uploaded vocabulary and records its outcome. */
@Component
@RequiredArgsConstructor
@Slf4j
class UploadValidationRunner {

    private final ValidationClient validationClient;
    private final OntologyUploadTransactions transactions;

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
