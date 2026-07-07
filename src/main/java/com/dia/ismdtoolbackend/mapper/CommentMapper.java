package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;

@Mapper(componentModel = "spring")
public interface CommentMapper {
    // Comments now link by metadata id, not IRI string; the service resolves the request IRI to
    // the owning ontology/concept and sets the association, so the mapper ignores those targets.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "postedTime", source = "postedTime")
    @Mapping(target = "ontologyMetadata", ignore = true)
    @Mapping(target = "conceptMetadata", ignore = true)
    CommentEntity toEntity(CommentCreateModel commentCreateModel, String userId, LocalDateTime postedTime);

    // The wire contract still exposes ontologyIRI/conceptIRI; derive them back from the linked
    // metadata row so responses are unchanged for the FE.
    @Mapping(target = "ontologyIRI", expression = "java(ontologyIri(commentEntity.getOntologyMetadata()))")
    @Mapping(target = "conceptIRI", expression = "java(conceptIri(commentEntity.getConceptMetadata()))")
    CommentModel toDto(CommentEntity commentEntity);

    default String ontologyIri(OntologyMetadataEntity ontologyMetadata) {
        return ontologyMetadata == null ? null : ontologyMetadata.getGraphName();
    }

    default String conceptIri(ConceptMetadataEntity conceptMetadata) {
        return conceptMetadata == null ? null : conceptMetadata.getConceptIri();
    }
}
