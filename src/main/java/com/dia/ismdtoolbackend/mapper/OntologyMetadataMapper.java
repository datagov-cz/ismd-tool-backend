package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import org.mapstruct.Mapper;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Mapper(componentModel = "spring")
public interface OntologyMetadataMapper {

    OntologyMetadataEntity toEntity(OntologyMetadataDto dto);
    OntologyMetadataDto toDto(OntologyMetadataEntity entity);
}
