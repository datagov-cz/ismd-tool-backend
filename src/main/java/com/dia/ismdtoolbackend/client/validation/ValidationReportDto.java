package com.dia.ismdtoolbackend.client.validation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ValidationReportDto implements ValidationReport {
    private List<ValidationResult> results;
    private Instant timestamp;
    private String ontologyIri;

    public ValidationReportDto(List<ValidationResult> results,
                               String ontologyIri, Instant timestamp) {
        this.results = results;
        this.ontologyIri = ontologyIri;
        this.timestamp = timestamp;
    }

    public ValidationReportDto(List<ValidationResult> results, Instant timestamp) {
        this.results = results;
        this.timestamp = timestamp;
    }

    @Override
    public Long getId() {
        return UUID.randomUUID().getMostSignificantBits();
    }

    @Override
    public List<ValidationResult> getResults() {
        return results;
    }

    @Override
    public String getOntologyIri() {
        return ontologyIri;
    }
}