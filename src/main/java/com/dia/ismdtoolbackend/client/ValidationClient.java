package com.dia.ismdtoolbackend.client;

import com.dia.dto.CatalogRecordDto;
import com.dia.ismdtoolbackend.client.validation.ValidationCallExecutor;
import com.dia.ismdtoolbackend.config.ValidationServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.CatalogRecordRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ValidationRequestDto;
import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Client for the externally-deployed ISMD validator. All calls go through
 * {@link ValidationCallExecutor}, which applies the connect/read timeouts (via the dedicated
 * {@code validationRestClient}), a circuit breaker, and the unavailable-vs-rejected exception
 * mapping. Two surfaces:
 * <ul>
 *   <li>{@link #requestValidation} — STRICT: propagates 503 (validator down) / 400 (validator
 *       rejected the input) so the blocking {@code /validate} endpoint surfaces the real cause.</li>
 *   <li>{@link #requestValidationLenient} — LENIENT: returns empty on any failure, for the
 *       advisory upload path that must never block ingest.</li>
 * </ul>
 */
@Component
@Slf4j
public class ValidationClient {

    private final String validationServiceUrl;
    private final RestClient validationRestClient;
    private final ValidationCallExecutor callExecutor;

    public ValidationClient(ValidationServiceConfig validationServiceConfig,
                            RestClient validationRestClient,
                            ValidationCallExecutor callExecutor) {
        this.validationServiceUrl = validationServiceConfig.getUrl();
        this.validationRestClient = validationRestClient;
        this.callExecutor = callExecutor;
    }

    /**
     * Validate an ontology, propagating typed failures: a validator outage surfaces as
     * {@link ValidationServiceUnavailableException} (503); a validator rejection (4xx) surfaces
     * as {@code OntologyValidationException} (400) carrying the validator's own message. For the
     * blocking {@code /validate} endpoint.
     *
     * @return the report (present even when the validator returns an empty/2xx body would yield
     *         null — see {@link Optional#empty()} for that defensive case)
     */
    public Optional<ValidationReport> requestValidation(String ttlContent, String iri) {
        validateInputs(ttlContent, iri);
        log.debug("Requesting TTL validation for ontology: {}", iri);

        ValidationReportDto response = callExecutor.strict("validation of " + iri,
                () -> postValidate(ttlContent, iri));

        if (response != null) {
            log.info("Validation completed for ontology {}", iri);
            return Optional.of(response);
        }
        return Optional.empty();
    }

    /**
     * Advisory validation for the upload path: returns empty on ANY validator failure (down or
     * rejection) so a validator problem never blocks ontology ingest. The breaker still observes
     * outages. Input-precondition failures still throw (they are caller bugs, not validator state).
     */
    public Optional<ValidationReport> requestValidationLenient(String ttlContent, String iri) {
        validateInputs(ttlContent, iri);
        log.debug("Requesting advisory TTL validation for ontology: {}", iri);

        ValidationReportDto response = callExecutor.lenient("upload validation of " + iri,
                () -> postValidate(ttlContent, iri), null);

        return Optional.ofNullable(response);
    }

    private ValidationReportDto postValidate(String ttlContent, String iri) {
        ValidationRequestDto requestDto = new ValidationRequestDto(ttlContent, iri);
        return validationRestClient.post()
                .uri(validationServiceUrl + "/api/validator/validate")
                .header("Content-Type", "application/json")
                .body(requestDto)
                .retrieve()
                .body(ValidationReportDto.class);
    }

    private void validateInputs(String ttlContent, String iri) {
        if (ttlContent == null || ttlContent.trim().isEmpty()) {
            throw new IllegalArgumentException("TTL obsah nesmí být prázdný");
        }
        if (iri == null || iri.trim().isEmpty()) {
            throw new IllegalArgumentException("IRI slovníku nesmí být prázdné");
        }
    }

    public Optional<CatalogRecordDto> requestCatalogRecord(CatalogRecordRequestDto requestDto) {
        if (requestDto.getValidationReport() == null) {
            throw new IllegalArgumentException("Zpráva z kontroly nesmí být prázdná");
        }
        String ttlContent = requestDto.getTtlContent();
        if (ttlContent == null || ttlContent.trim().isEmpty()) {
            throw new IllegalArgumentException("TTL obsah nesmí být prázdný");
        }

        String iri = requestDto.getValidationReport().getOntologyIri();
        log.debug("Requesting catalog record for ontology: {}", iri);

        // Best-effort (lenient): a validator problem must not fail the caller. Routes through
        // the same timed client + breaker as validation.
        CatalogRecordDto response = callExecutor.lenient("catalog record for " + iri, () ->
                validationRestClient.post()
                        .uri(validationServiceUrl + "/api/validator/catalog-record")
                        .header("Content-Type", "application/json")
                        .body(requestDto)
                        .retrieve()
                        .body(CatalogRecordDto.class), null);

        return Optional.ofNullable(response);
    }
}