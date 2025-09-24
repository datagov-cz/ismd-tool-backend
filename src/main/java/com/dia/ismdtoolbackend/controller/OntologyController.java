package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.entity.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.entity.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.riot.Lang;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static com.dia.constants.ConverterControllerConstants.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
@Slf4j
public class OntologyController {

    private final OntologyService ontologyService;
    private final OntologyUploadService ontologyUploadService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> uploadFromFile(@RequestParam MultipartFile file, @RequestParam(name = "providedName", required = false) String providedName, @RequestParam String userId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology upload requested, fileName: {}, providedName: {}, userId: {}", file.getOriginalFilename(), providedName, userId);

        try {
            if (file.isEmpty()) {
                log.error("Ontology upload file is empty");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("Soubor je prázdný."));
            }

            Lang rdfLang = ontologyUploadService.determineRDFFormat(file);
            if (rdfLang == null) {
                log.error("Ontology RDF language is not supported");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("RDF jazyk není podporován."));
            }

            OntologyMetadataModel savedOntology = ontologyUploadService.uploadFromFile(file, providedName, rdfLang, userId);
            log.info("Ontology upload successful: {}", savedOntology);

            return ResponseEntity.ok().body(ApiResponseDto.success(savedOntology, "Slovník úspěšně nahrán: " + savedOntology.getGraphName()));
        } catch (Exception e) {
            log.error("Error uploading ontology: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{ontologyId}/delete")
    public ResponseEntity<ApiResponseDto<Void>> deleteOntology(@PathVariable Long ontologyId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology delete requested, ontologyId: {}", ontologyId);

        try {
            ontologyService.deleteOntology(ontologyId);
            return ResponseEntity.ok(ApiResponseDto.success("Slovník úspěšně smazán."));
        } catch (org.apache.jena.ontology.OntologyException e) {
            if (e.getMessage().contains("nebyl nalezen")) {
                log.error("Ontology not found: {}", ontologyId);
                return ResponseEntity.status(404).body(ApiResponseDto.error(e.getMessage()));
            }
            log.error("Error deleting ontology: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error deleting ontology: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při mazání slovníku."));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> createOntology(@RequestBody OntologyCreateModel ontologyCreateModel, @RequestParam String userId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", ontologyCreateModel.getNamespace(), ontologyCreateModel.getName(), ontologyCreateModel.getDescription(), userId);

        try {
            if (userId == null || userId.trim().isEmpty()) {
                log.error("UserId is null or empty");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("ID uživatele je povinné."));
            }

            OntologyMetadataModel createdOntology = ontologyService.createOntology(ontologyCreateModel, userId);
            log.info("Ontology create successful: {}", createdOntology);

            return ResponseEntity.ok().body(ApiResponseDto.success(createdOntology, "Slovník úspěšně vytvořen: " + createdOntology.getGraphName()));
        } catch (org.apache.jena.ontology.OntologyException e) {
            if (e.getMessage().contains("není platné")) {
                log.error("Invalid ontology IRI: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("povinný")) {
                log.error("Validation error: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("Data pro vytvoření slovníku jsou prázdná")) {
                log.error("Create model validation failed: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("může obsahovat pouze písmena")) {
                log.error("Name validation failed: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("Nepodařilo se uložit")) {
                log.error("Storage error: {}", e.getMessage());
                return ResponseEntity.status(500).body(ApiResponseDto.error(e.getMessage()));
            }
            log.error("Error creating ontology: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating ontology: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při vytváření slovníku."));
        }
    }
}
