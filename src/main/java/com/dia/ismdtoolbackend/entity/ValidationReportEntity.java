package com.dia.ismdtoolbackend.entity;

import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationResult;
import com.dia.validation.ValidationSeverity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "validation_reports")
@Getter
@Setter
@NoArgsConstructor
public class ValidationReportEntity implements ValidationReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ontology_iri")
    private String ontologyIri;

    @Column(name = "is_valid")
    private Boolean isValid;

    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "results_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ValidationResult> results;

    @Column(name = "error_count")
    private Long errorCount;

    @Column(name = "warning_count")
    private Long warningCount;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "summary")
    private String summary;

    public ValidationReportEntity(ValidationReport report) {
        this.ontologyIri = report.getOntologyIri();
        this.isValid = report.isValid();
        this.timestamp = report.getTimestamp();
        this.results = report.getResults();

        this.errorCount = report.getErrorCount();
        this.warningCount = report.getWarningCount();
        this.totalCount = report.getResults().size();
        this.summary = report.getSummary();
    }

    @Override
    public List<ValidationResult> getResults() {
        return results;
    }

    @Override
    public boolean isValid() {
        return isValid != null ? isValid : false;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getOntologyIri() {
        return ontologyIri;
    }

    @Override
    public List<ValidationResult> getErrors() {
        if (results == null) return List.of();
        return results.stream()
                .filter(r -> r.severity() == ValidationSeverity.ERROR)
                .toList();
    }

    @Override
    public List<ValidationResult> getWarnings() {
        if (results == null) return List.of();
        return results.stream()
                .filter(r -> r.severity() == ValidationSeverity.WARNING)
                .toList();
    }

    @Override
    public long getErrorCount() {
        return errorCount != null ? errorCount : 0L;
    }

    @Override
    public long getWarningCount() {
        return warningCount != null ? warningCount : 0L;
    }

    @Override
    public boolean hasErrors() {
        return getErrorCount() > 0;
    }

    @Override
    public String getSummary() {
        return summary != null ? summary : "No summary available";
    }
}
