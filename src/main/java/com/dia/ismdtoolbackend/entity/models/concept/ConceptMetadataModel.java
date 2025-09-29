package com.dia.ismdtoolbackend.entity.models.concept;

import com.dia.ismdtoolbackend.entity.models.CommentModel;
import com.dia.ismdtoolbackend.entity.models.UserModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.validation.ValidationReportDto;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Data
public class ConceptMetadataModel {
    private Long id;
    private String conceptName;
    private ConceptType conceptType;
    private UserModel user;
    private Boolean isPublished;
    private String inTezaurus;
    private ValidationReportDto validationReport;
    private List<CommentModel> comments;
}