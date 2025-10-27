package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.controller.dto.ValidationRequestDto;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ValidationClient {

    @Value("${validation.service.url:http://localhost:8080}")
    private String validationServiceUrl;

    private final RestClient restClient;

    public Optional<ValidationReport> requestValidation(String ttlContent, String iri) {
        try {
            log.debug("Requesting TTL validation for ontology: {}", iri);

            if (ttlContent == null || ttlContent.trim().isEmpty()) {
                throw new IllegalArgumentException("TTL obsah nesmí být prázdný");
            }

            if (iri == null || iri.trim().isEmpty()) {
                throw new IllegalArgumentException("IRI slovníku nesmí být prázdné");
            }

            ValidationRequestDto requestDto = new ValidationRequestDto(ttlContent, iri);

            ValidationReportDto response = restClient.post()
                    .uri(validationServiceUrl + "/api/validator/validate")
                    .header("Content-Type", "application/json")
                    .body(requestDto)
                    .retrieve()
                    .body(ValidationReportDto.class);

            if (response != null) {
                log.info("Validation completed for ontology {}", iri);
                return Optional.of(response);
            }

            return Optional.empty();

        } catch (RestClientException e) {
            log.warn("Validation service unavailable for ontology {}: {}", iri, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error during validation for ontology {}: {}", iri, e.getMessage(), e);
            return Optional.empty();
        }
    }
}