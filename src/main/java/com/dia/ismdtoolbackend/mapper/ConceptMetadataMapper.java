package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ConceptMetadataMapper {
    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "comments", ignore = true)
    @Mapping(target = "ontologySlug", source = "ontologyMetadata.slug")
    @Mapping(target = "sourceTag", ignore = true)
    ConceptMetadataModel toDto(ConceptMetadataEntity entity);

    /**
     * Derives {@code sourceTag} from {@code isPublished} on every {@code toDto}, so both {@code getAll} and
     * {@code getConceptDetail} carry it. A null flag stays null rather than defaulting to DRAFT — "not
     * recorded" and "confirmed local" are different claims.
     */
    @AfterMapping
    default void deriveSourceTag(ConceptMetadataEntity entity, @MappingTarget ConceptMetadataModel model) {
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