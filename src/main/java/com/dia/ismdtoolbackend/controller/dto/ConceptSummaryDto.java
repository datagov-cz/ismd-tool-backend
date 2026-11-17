package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** A concept as listed by the public concept list: its own row, without author or comments. */
@Data
public class ConceptSummaryDto {
    private Long id;
    private String slug;
    private ConceptType conceptType;
    private String conceptIri;
    private String graphName;
    private String ontologySlug;
    private String conceptName;
    private Boolean isPublished;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ConceptSourceTag sourceTag;
    private Boolean inTezaurus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
