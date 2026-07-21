package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@Data
public class ConceptMetadataModel {
    private Long id;
    private String slug;
    private ConceptType conceptType;
    private String conceptIri;
    private String graphName;
    private String ontologySlug;
    private String conceptName;
    private UserModel user;
    private Boolean isPublished;

    /**
     * Derived from {@link #isPublished} by the mapper — {@code WORKING_COPY} when this concept's own IRI
     * is in NKD, else {@code DRAFT}. {@link #isPublished} stays for back-compat.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ConceptSourceTag sourceTag;

    private Boolean inTezaurus;
    private List<CommentModel> comments;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}