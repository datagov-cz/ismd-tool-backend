package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class CommentCreateModel {
    private String ontologyIRI;
    private String conceptIRI;
    private String comment;
}
