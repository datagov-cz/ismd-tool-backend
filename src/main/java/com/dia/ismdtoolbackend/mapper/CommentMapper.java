package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;

@Mapper(componentModel = "spring")
public interface CommentMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "postedTime", source = "postedTime")
    CommentEntity toEntity(CommentCreateModel commentCreateModel, String userId, LocalDateTime postedTime);

    CommentModel toDto(CommentEntity commentEntity);
}
