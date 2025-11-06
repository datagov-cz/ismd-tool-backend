package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.enums.OntologyLevel;
import com.dia.validation.ValidationReportDto;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Data
public class OntologyMetadataModel {
    private Long id;
    private String slug;
    private String graphName;
    private String name;
    private UserModel user;
    private Boolean isPublished;
    private ValidationReportDto validationReport;
    private OntologyLevel ontologyLevel;
    private List<CommentModel> comments;
}
