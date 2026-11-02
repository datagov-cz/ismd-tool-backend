package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

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
    @Mapping(target = "sourceTag", ignore = true)
    @Mapping(target = "rejectedConcepts", ignore = true)
    @Mapping(target = "correctedIris", ignore = true)
    OntologyMetadataModel toDto(OntologyMetadataEntity entity);

    /**
     * Derives {@code sourceTag} from this ontology's own {@code isPublished} — never a rollup over its
     * concepts, which carry their own tags. A null flag stays null rather than defaulting to DRAFT.
     */
    @AfterMapping
    default void deriveSourceTag(OntologyMetadataEntity entity, @MappingTarget OntologyMetadataModel model) {
        if (entity.getIsPublished() == null) {
            return;
        }
        model.setSourceTag(Boolean.TRUE.equals(entity.getIsPublished())
                ? ConceptSourceTag.WORKING_COPY
                : ConceptSourceTag.DRAFT);
    }

    /** Delegates to {@link CommentMapper#toDto}. */
    default CommentModel commentEntityToModel(CommentEntity entity) {
        return CommentMapper.INSTANCE.toDto(entity);
    }

    /** Delegates to {@link CommentMapper#toDtos}. */
    default List<CommentModel> commentEntitiesToModels(List<CommentEntity> entities) {
        return CommentMapper.INSTANCE.toDtos(entities);
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