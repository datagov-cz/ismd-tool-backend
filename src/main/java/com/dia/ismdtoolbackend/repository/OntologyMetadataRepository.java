package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OntologyMetadataRepository extends JpaRepository<OntologyMetadataEntity, Long> {

    Optional<OntologyMetadataEntity> findByGraphName(String graphName);

    @Modifying
    @Transactional
    @Query("UPDATE OntologyMetadataEntity o SET o.validationReportId = :validationReportId WHERE o.id = :iri")
    int updateValidationReportId(@Param("iri") String iri, @Param("validationReportId") Long validationReportId);
}
