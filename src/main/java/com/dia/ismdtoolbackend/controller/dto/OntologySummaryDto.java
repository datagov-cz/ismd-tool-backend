package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/** A vocabulary as listed, created or edited: its own row plus name and description from RDF. */
@Data
public class OntologySummaryDto {
    private Long id;
    private String slug;
    private String graphName;
    private Map<String, String> name;
    private Map<String, String> popis;
    private Boolean isPublished;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ConceptSourceTag sourceTag;
    private Integer conceptCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OntologyValidationStatus lastValidationStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant lastValidationAt;
}
