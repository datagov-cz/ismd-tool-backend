package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.entity.models.concept.ConceptCreateModel;

import com.dia.ismdtoolbackend.entity.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dia.constants.ConverterControllerConstants.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/concept")
@RequiredArgsConstructor
@Slf4j
public class ConceptController {

    private final ConceptService conceptService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> createConcept(@RequestBody ConceptCreateModel conceptCreateModel, @RequestParam String userId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", conceptCreateModel.getNamespace(), conceptCreateModel.getConceptName(), conceptCreateModel.getDescription(), userId);

        try {
            if (userId == null || userId.trim().isEmpty()) {
                log.error("UserId is null or empty");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("ID uživatele je povinné."));
            }

            ConceptMetadataModel createdConcept = conceptService.createConcept(conceptCreateModel, userId);
            log.info("Concept create successful: {}", createdConcept);

            return ResponseEntity.ok().body(ApiResponseDto.success(createdConcept, "Pojem úspěšně vytvořen: "));
        } catch (org.apache.jena.ontology.OntologyException e) {
            if (e.getMessage().contains("není platné")) {
                log.error("Invalid concept IRI: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("povinný")) {
                log.error("Validation error: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("Data pro vytvoření pojmu jsou prázdná")) {
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
            log.error("Error creating concept: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating concept: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při vytváření pojmu."));
        }
    }
}
