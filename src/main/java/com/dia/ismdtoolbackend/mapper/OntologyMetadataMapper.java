package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
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
public interface OntologyMetadataMapper {

    @Mapping(target = "userId", source = "user", qualifiedByName = "userToUserId")
    @Mapping(target = "validationReportId", source = "validationReport", qualifiedByName = "validationReportToValidationReportId")
    OntologyMetadataEntity toEntity(OntologyMetadataModel dto);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "validationReport", source = "validationReportId", qualifiedByName = "validationReportIdToValidationReport")
    @Mapping(target = "comments", ignore = true)
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

    @Named("validationReportToValidationReportId")
    default Long validationReportToValidationReportId(ValidationReportDto validationReport) {
        return validationReport != null && validationReport.getId() != null ?
                validationReport.getId() : null;
    }

    @Named("validationReportIdToValidationReport")
    default ValidationReportDto validationReportIdToValidationReport(Long validationReportId) {
        ValidationReportDto validationReport = new ValidationReportDto();
        if (validationReportId != null) {
            validationReport.setId(validationReportId);
        }
        return validationReport;
    }
}