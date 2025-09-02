package com.dia.ismdtoolbackend.entity.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class CommentDto {
    private String commentId;
    private String userId;
    private String comment;
}
