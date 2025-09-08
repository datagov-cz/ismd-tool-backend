package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ValidationReportRepository extends JpaRepository<ValidationReportEntity, Long> {

    Optional<ValidationReportEntity> findByOntologyId(Long id);
}
