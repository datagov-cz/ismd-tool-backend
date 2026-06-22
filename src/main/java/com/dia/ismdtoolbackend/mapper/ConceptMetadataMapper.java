package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.validation.ValidationReportDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface ConceptMetadataMapper {

    @Mapping(target = "userId", source = "user", qualifiedByName = "userToUserId")
    @Mapping(target = "ontologyMetadata", ignore = true)
    ConceptMetadataEntity toEntity(ConceptMetadataModel dto);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "comments", ignore = true)
    @Mapping(target = "ontologySlug", source = "ontologyMetadata.slug")
    ConceptMetadataModel toDto(ConceptMetadataEntity entity);

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
                .collect(Collectors.toList());
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