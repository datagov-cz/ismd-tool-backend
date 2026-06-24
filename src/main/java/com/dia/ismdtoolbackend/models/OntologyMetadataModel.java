package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Data
public class OntologyMetadataModel {
    private Long id;
    private String slug;
    private String graphName;
    private String name;
    private UserModel user;
    private Boolean isPublished;
    private List<CommentModel> comments;
    private String popis;
    private List<ConceptMetadataModel> concepts;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer conceptCount;

    /**
     * Outcome of the most recent validation; null until first validated. Lets the FE badge an
     * ontology whose upload-time validation was skipped (validator down) and offer a manual
     * re-validation. {@code NON_NULL} so existing payloads are unchanged.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OntologyValidationStatus lastValidationStatus;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant lastValidationAt;
}
