package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
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
    private Boolean inTezaurus;
    private List<CommentModel> comments;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}