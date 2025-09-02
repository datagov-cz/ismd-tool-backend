package com.dia.ismdtoolbackend.entity.dto;

import com.dia.ismdtoolbackend.enums.OntologyLevel;
import com.dia.validation.ValidationReportDto;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Data
public class OntologyMetadataDto {
    private Long id;
    private String graphName;
    private UserDto user;
    private Boolean isPublished;
    private ValidationReportDto validationReport;
    private OntologyLevel ontologyLevel;
    private List<CommentDto> comments;
}
