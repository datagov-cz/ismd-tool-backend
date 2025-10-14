package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;

public interface CommentService {
    CommentModel postComment(CommentCreateModel commentCreateModel, String userId);
}
