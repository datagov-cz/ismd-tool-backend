package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.validation.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ValidationReportRepository extends JpaRepository<ValidationReportEntity, Long> {

    Optional<ValidationReportEntity> findByOntologyId(String ontologyId);

    @Query("SELECT v FROM ValidationReportEntity v WHERE v.ontologyId = :ontologyId AND v.isValid = false")
    Optional<ValidationReportEntity> findFailedValidationByOntologyId(@Param("ontologyId") String ontologyId);

    @Query("SELECT COUNT(v) FROM ValidationReportEntity v WHERE v.isValid = false")
    long countFailedValidations();

    @Modifying
    @Query("UPDATE OntologyMetadataEntity o SET o.validationReportId = :validationReportId WHERE o.id = :ontologyId")
    int updateValidationReportId(@Param("ontologyId") String ontologyId, @Param("validationReportId") Long validationReportId);
}
