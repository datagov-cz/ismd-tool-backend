package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Data
public class OntologyMetadataModel {
    private Long id;
    private String slug;
    private String graphName;
    private Map<String, String> name;
    private UserModel user;
    private Boolean isPublished;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ConceptSourceTag sourceTag;
    private List<CommentModel> comments;
    private Map<String, String> popis;
    private List<ConceptMetadataModel> concepts;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer conceptCount;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OntologyValidationStatus lastValidationStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant lastValidationAt;
}
