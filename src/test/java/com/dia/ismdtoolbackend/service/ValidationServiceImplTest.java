package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.exception.ValidationException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.impl.ValidationServiceImpl;
import com.dia.validation.ValidationReportDto;
import com.dia.validation.ValidationResult;
import com.dia.validation.ValidationSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationServiceImplTest {

    private static final Long ONTOLOGY_ID = 1L;
    private static final String GRAPH_NAME = "http://example.org/test-ontology";

    @Mock
    private ValidationReportRepository validationReportRepository;

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @InjectMocks
    private ValidationServiceImpl validationService;

    private OntologyMetadataModel ontologyMetadata;

    @BeforeEach
    void setUp() {
        ontologyMetadata = new OntologyMetadataModel();
        ontologyMetadata.setId(ONTOLOGY_ID);
        ontologyMetadata.setGraphName(GRAPH_NAME);
    }

    private ValidationReportEntity storedReport(Instant timestamp) {
        ValidationReportEntity entity = new ValidationReportEntity();
        entity.setId(42L);
        entity.setOntologyMetadataId(ONTOLOGY_ID);
        entity.setTimestamp(timestamp);
        entity.setResultsJson(entity.convertResultsToJson(List.of(new ValidationResult(
                ValidationSeverity.ERROR,
                "Chybí název pojmu",
                "rule-name-required",
                GRAPH_NAME + "/pojem/1",
                "http://www.w3.org/2004/02/skos/core#prefLabel",
                null))));
        return entity;
    }

    @Test
    void getValidationReport_ReturnsNullWhenNeverValidated() {
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.empty());

        assertNull(validationService.getValidationReport(ontologyMetadata));
    }

    @Test
    void getValidationReport_MapsStoredReport() {
        Instant timestamp = Instant.parse("2026-08-24T10:15:30Z");
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID))
                .thenReturn(Optional.of(storedReport(timestamp)));

        ValidationReportDto report = validationService.getValidationReport(ontologyMetadata);

        assertNotNull(report);
        assertEquals(1, report.getResults().size());
        assertEquals(ValidationSeverity.ERROR, report.getResults().get(0).severity());
        assertEquals(GRAPH_NAME, report.getOntologyIri());
        assertEquals(timestamp, report.getTimestamp());
    }

    /** Never validated is a state, not an error — the read path gets an empty report, not null. */
    @Test
    void getValidationReportOrEmpty_ReturnsEmptyReportWhenNeverValidated() {
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.empty());

        ValidationReportDto report = validationService.getValidationReportOrEmpty(ontologyMetadata);

        assertNotNull(report);
        assertTrue(report.getResults().isEmpty());
        assertEquals(GRAPH_NAME, report.getOntologyIri());
        assertNull(report.getTimestamp());
    }

    @Test
    void getValidationReportOrEmpty_PassesStoredReportThrough() {
        Instant timestamp = Instant.parse("2026-08-24T10:15:30Z");
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID))
                .thenReturn(Optional.of(storedReport(timestamp)));

        ValidationReportDto report = validationService.getValidationReportOrEmpty(ontologyMetadata);

        assertEquals(1, report.getResults().size());
        assertEquals(timestamp, report.getTimestamp());
    }

    @Test
    void saveValidationReport_replacesThePreviousReportAndStoresEveryField() {
        ValidationReportEntity previous = storedReport(Instant.parse("2026-01-01T00:00:00Z"));
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.of(previous));
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());
        ValidationReportEntity incoming = storedReport(Instant.parse("2026-08-24T10:15:30Z"));
        incoming.setId(43L);

        validationService.saveValidationReport(incoming, ontologyMetadata, "user-1");

        verify(validationReportRepository).delete(previous);
        ArgumentCaptor<ValidationReportEntity> saved = ArgumentCaptor.forClass(ValidationReportEntity.class);
        verify(validationReportRepository).save(saved.capture());
        ValidationReportEntity stored = saved.getValue();
        assertThat(stored.getId()).isEqualTo(43L);
        assertThat(stored.getUserId()).isEqualTo("user-1");
        assertThat(stored.getTimestamp()).isEqualTo(Instant.parse("2026-08-24T10:15:30Z"));
        assertThat(stored.getOntologyMetadataId()).isEqualTo(ONTOLOGY_ID);
        assertThat(stored.getOntologyIri()).isEqualTo(GRAPH_NAME);
        assertThat(stored.getResults()).isEqualTo(incoming.getResults());
    }

    @Test
    void saveValidationReport_marksTheOntologyValidated() {
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.empty());
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setLastValidationStatus(OntologyValidationStatus.SKIPPED_UNAVAILABLE);
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontology));
        Instant before = Instant.now();

        validationService.saveValidationReport(storedReport(Instant.now()), ontologyMetadata, "user-1");

        verify(validationReportRepository, never()).delete(any());
        verify(ontologyMetadataRepository).save(ontology);
        assertThat(ontology.getLastValidationStatus()).isEqualTo(OntologyValidationStatus.VALIDATED);
        assertThat(ontology.getLastValidationAt()).isAfterOrEqualTo(before);
    }

    @Test
    void saveValidationReport_wrapsAStorageFailure() {
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.empty());
        IllegalStateException cause = new IllegalStateException("DB down");
        when(validationReportRepository.save(any())).thenThrow(cause);

        assertThatThrownBy(() -> validationService.saveValidationReport(storedReport(Instant.now()), ontologyMetadata, "u"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Během ukládání zprávy z kontroly došlo k chybě")
                .hasCause(cause);
        verify(ontologyMetadataRepository, never()).save(any());
    }
}
