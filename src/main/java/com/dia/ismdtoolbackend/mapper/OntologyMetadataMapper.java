package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.OntologyMetadata;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OntologyMetadataMapper {
    OntologyMetadata toEntity(OntologyMetadataDto dto);
    OntologyMetadataDto toDto(OntologyMetadata entity);
}
