package com.dia.ismdtoolbackend.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@Getter
@Setter
public class CommentModel {
    private Long id;
    private String userId;
    private String comment;
    private String ontologyIRI;
    private String conceptIRI;
    private LocalDateTime postedTime;
}
