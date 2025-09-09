package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.entity.dto.UploadResponseDto;
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
    public ResponseEntity<UploadResponseDto> uploadFromFile(
            @RequestParam MultipartFile file,
            @RequestParam (name = "providedName", required = false) String providedName,
            @RequestParam String userId
            ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology upload requested, fileName: {}, providedName: {}, userId: {}", file.getOriginalFilename(), providedName, userId);

        try {
            if (file.isEmpty()) {
                log.error("Ontology upload file is empty");
                return ResponseEntity.badRequest().body(new UploadResponseDto(null, "Soubor je prázdný."));
            }

            Lang rdfLang = ontologyUploadService.determineRDFFormat(file);
            if (rdfLang == null) {
                log.error("Ontology RDF language is not supported");
                return ResponseEntity.badRequest().body(new UploadResponseDto(null, "RDF jazyk není podporován."));
            }

            OntologyMetadataDto savedOntology = ontologyUploadService.uploadFromFile(file, providedName, rdfLang, userId);
            log.info("Ontology upload successful: {}", savedOntology);

            return ResponseEntity.ok().body(new UploadResponseDto(savedOntology, requestId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new UploadResponseDto(null, e.getMessage()));
        }
    }
}
