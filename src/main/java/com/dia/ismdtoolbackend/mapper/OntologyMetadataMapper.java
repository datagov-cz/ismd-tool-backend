package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.OntologyDetailMetadataDto;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.models.UserModel;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OntologyMetadataMapper {

    @Mapping(target = "name", ignore = true)
    @Mapping(target = "popis", ignore = true)
    @Mapping(target = "conceptCount", ignore = true)
    @Mapping(target = "sourceTag", ignore = true)
    OntologySummaryDto toSummary(OntologyMetadataEntity entity);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "popis", ignore = true)
    @Mapping(target = "conceptCount", ignore = true)
    @Mapping(target = "sourceTag", ignore = true)
    @Mapping(target = "comments", ignore = true)
    @Mapping(target = "concepts", ignore = true)
    OntologyDetailMetadataDto toDetail(OntologyMetadataEntity entity);

    default OntologyRef toRef(OntologyMetadataEntity entity) {
        return new OntologyRef(entity.getId(), entity.getSlug(), entity.getGraphName(), entity.getIsPublished());
    }

    /**
     * Derives {@code sourceTag} from this ontology's own {@code isPublished} — never a rollup over its
     * concepts, which carry their own tags. A null flag stays null rather than defaulting to DRAFT.
     */
    @AfterMapping
    default void deriveSourceTag(OntologyMetadataEntity entity, @MappingTarget OntologySummaryDto model) {
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

    @Named("userIdToUser")
    default UserModel userIdToUser(String userId) {
        return userId != null ? new UserModel(userId) : null;
    }
}
