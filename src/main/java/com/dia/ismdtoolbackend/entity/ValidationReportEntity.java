package com.dia.ismdtoolbackend.entity;

import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "validation_reports")
@Getter
@Setter
@NoArgsConstructor
@Slf4j
public class ValidationReportEntity implements ValidationReport {
    @Id
    private Long id;

    @Column(name = "ontology_metadata_id")
    private Long ontologyMetadataId;

    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "results_json", columnDefinition = "text")
    private String resultsJson;

    @Column(name = "ontology_iri", columnDefinition = "text")
    private String getOntologyIri;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public ValidationReportEntity(ValidationReport report, Long ontologyMetadataId) {
        this.id = report.getId();
        this.ontologyMetadataId = ontologyMetadataId;
        this.timestamp = report.getTimestamp();
        this.resultsJson = convertResultsToJson(report.getResults());
    }

    @Override
    public List<ValidationResult> getResults() {
        return convertJsonToResults(this.resultsJson);
    }

    public String convertResultsToJson(List<ValidationResult> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert ValidationResults to JSON", e);
            return "[]";
        }
    }

    public List<ValidationResult> convertJsonToResults(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to convert JSON to ValidationResults", e);
            return List.of();
        }
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getOntologyIri() {
        return this.getOntologyIri;
    }
}