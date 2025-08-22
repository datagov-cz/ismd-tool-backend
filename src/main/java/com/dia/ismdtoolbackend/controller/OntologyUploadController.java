package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.riot.Lang;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static com.dia.constants.ConverterControllerConstants.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
@Slf4j
public class OntologyUploadController {

    private final OntologyUploadService ontologyUploadService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFromFile(
            @RequestParam MultipartFile file,
            @RequestParam(value = "graphName", required = false) String graphName
            ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("File is empty");
            }

            Lang rdfLang = ontologyUploadService.determineRDFFormat(file);
            if (rdfLang == null) {
                return ResponseEntity.badRequest().body("RDF Language is not supported");
            }

            if (graphName == null || graphName.trim().isEmpty()) {
                String fileName = file.getOriginalFilename();
                String baseName = fileName != null ? fileName.replaceAll("\\.[^.]+$", "") : "uploaded-ontology";
                graphName = "http://example.org/ontologies/" + baseName + "-" + System.currentTimeMillis();
            }

            OntologyMetadataDto savedOntology = ontologyUploadService.uploadFromFile(file, graphName, rdfLang);
            return ResponseEntity.ok().body(savedOntology);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
