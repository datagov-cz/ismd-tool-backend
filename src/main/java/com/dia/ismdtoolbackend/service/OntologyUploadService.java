package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.exception.OntoloyUploadException;
import org.apache.jena.riot.Lang;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface OntologyUploadService {
    Lang determineRDFFormat(MultipartFile file);
    OntologyMetadataModel uploadFromFile(MultipartFile file, String providedName, Lang rdfLang, String userId) throws IOException, OntoloyUploadException;
}
