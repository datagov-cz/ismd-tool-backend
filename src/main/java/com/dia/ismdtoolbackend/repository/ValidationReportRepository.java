package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.validation.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ValidationReportRepository extends JpaRepository<ValidationReportEntity, Long> {

    Optional<ValidationReportEntity> findByOntologyIri(String ontologyIri);

    @Query("SELECT v FROM ValidationReportEntity v WHERE v.ontologyIri = :ontologyIri AND v.isValid = false")
    Optional<ValidationReportEntity> findFailedValidationByOntologyId(@Param("ontologyIri") String ontologyIri);

    @Query("SELECT COUNT(v) FROM ValidationReportEntity v WHERE v.isValid = false")
    long countFailedValidations();

    @Modifying
    @Query("UPDATE OntologyMetadataEntity o SET o.validationReportId = :validationReportId WHERE o.id = :ontologyIri")
    int updateValidationReportId(@Param("ontologyIri") String ontologyIri, @Param("validationReportId") Long validationReportId);
}
