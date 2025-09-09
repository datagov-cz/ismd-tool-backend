package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import org.apache.jena.riot.Lang;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface OntologyUploadService {
    Lang determineRDFFormat(MultipartFile file);
    OntologyMetadataDto uploadFromFile(MultipartFile file, String providedName, Lang rdfLang, String userId) throws IOException;
}
