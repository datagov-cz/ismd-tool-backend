package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring")
public interface OntologyMetadataMapper {

    @Mapping(target = "userId", source = "user", qualifiedByName = "userToUserId")
    @Mapping(target = "concepts", ignore = true)
    OntologyMetadataEntity toEntity(OntologyMetadataModel dto);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "comments", ignore = true)
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "popis", ignore = true)
    @Mapping(target = "concepts", ignore = true)
    @Mapping(target = "conceptCount", ignore = true)
    OntologyMetadataModel toDto(OntologyMetadataEntity entity);

    default CommentModel commentEntityToModel(CommentEntity entity) {
        if (entity == null) {
            return null;
        }
        CommentModel model = new CommentModel();
        model.setId(entity.getId());
        model.setUserId(entity.getUserId());
        model.setComment(entity.getComment());
        model.setOntologyIRI(entity.getOntologyIRI());
        model.setConceptIRI(entity.getConceptIRI());
        model.setPostedTime(entity.getPostedTime());
        return model;
    }

    default List<CommentModel> commentEntitiesToModels(List<CommentEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream()
                .map(this::commentEntityToModel)
                .toList();
    }

    @Named("userToUserId")
    default String userToUserId(UserModel user) {
        return user != null ? user.getUserId() : null;
    }

    @Named("userIdToUser")
    default UserModel userIdToUser(String userId) {
        return userId != null ? new UserModel(userId) : null;
    }
}