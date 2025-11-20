package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.validation.ValidationReport;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ValidationReportMapper {

    ValidationReportEntity toEntity(ValidationReport validationReport);
}
