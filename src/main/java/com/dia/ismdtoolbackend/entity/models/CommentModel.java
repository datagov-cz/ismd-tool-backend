package com.dia.ismdtoolbackend.entity.models;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class CommentModel {
    private String commentId;
    private String userId;
    private String comment;
}
