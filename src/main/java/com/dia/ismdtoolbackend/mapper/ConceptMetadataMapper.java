package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.entity.models.UserModel;
import com.dia.ismdtoolbackend.entity.models.CommentModel;
import com.dia.validation.ValidationReportDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ConceptMetadataMapper {

    @Mapping(target = "userId", source = "user", qualifiedByName = "userToUserId")
    @Mapping(target = "validationReportId", source = "validationReport", qualifiedByName = "validationReportToValidationReportId")
    @Mapping(target = "commentsJson", source = "comments", qualifiedByName = "commentsToCommentsJson")
    ConceptMetadataEntity toEntity(ConceptMetadataModel dto);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "validationReport", source = "validationReportId", qualifiedByName = "validationReportIdToValidationReport")
    @Mapping(target = "comments", source = "commentsJson", qualifiedByName = "commentsJsonToComments")
    ConceptMetadataModel toDto(ConceptMetadataEntity entity);

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

    @Named("commentsToCommentsJson")
    default String commentsToCommentsJson(List<CommentModel> comments) {
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(comments);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Named("commentsJsonToComments")
    default List<CommentModel> commentsJsonToComments(String commentsJson) {
        if (commentsJson == null || commentsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(commentsJson, new TypeReference<List<CommentModel>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}