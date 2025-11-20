package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.mapper.ValidationReportMapper;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationServiceImpl implements ValidationService {

    private final ValidationReportRepository validationReportRepository;
    private final ValidationReportMapper validationReportMapper;

    @Override
    public void saveValidation(ValidationReport validationReport) {

    }
}
