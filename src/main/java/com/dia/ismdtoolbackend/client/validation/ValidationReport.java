package com.dia.ismdtoolbackend.client.validation;

import java.time.Instant;
import java.util.List;

public interface ValidationReport {
    Long getId();
    List<ValidationResult> getResults();
    Instant getTimestamp();
    String getOntologyIri();
}