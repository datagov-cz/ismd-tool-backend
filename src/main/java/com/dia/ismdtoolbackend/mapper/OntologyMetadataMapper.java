package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.entity.dto.UserDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface OntologyMetadataMapper {

    @Mapping(target = "userId", source = "user", qualifiedByName = "userToUserId")
    @Mapping(target = "validationReportId", ignore = true)
    OntologyMetadataEntity toEntity(OntologyMetadataDto dto);

    @Mapping(target = "user", source = "userId", qualifiedByName = "userIdToUser")
    @Mapping(target = "validationReport", ignore = true)
    OntologyMetadataDto toDto(OntologyMetadataEntity entity);

    @Named("userToUserId")
    default String userToUserId(UserDto user) {
        return user != null ? user.getUserId() : null;
    }

    @Named("userIdToUser")
    default UserDto userIdToUser(String userId) {
        return userId != null ? new UserDto(userId) : null;
    }
}
