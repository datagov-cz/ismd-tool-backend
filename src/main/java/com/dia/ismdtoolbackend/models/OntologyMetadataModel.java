package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
