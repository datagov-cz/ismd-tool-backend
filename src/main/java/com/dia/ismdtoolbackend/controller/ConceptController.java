package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

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
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", conceptCreateModel.getNamespace(), conceptCreateModel.getNameModel(), conceptCreateModel.getDescriptionModel(), userId);

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
        } catch (IllegalArgumentException | SecurityException e) {
            log.error("Client error creating concept: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating concept: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při vytváření pojmu."));
        }
    }

    @DeleteMapping("/{conceptId}/delete")
    public ResponseEntity<ApiResponseDto<Void>> deleteConcept(@PathVariable Long conceptId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology delete requested, ontologyId: {}", conceptId);

        try {
            conceptService.deleteConcept(conceptId);
            return ResponseEntity.ok(ApiResponseDto.success("Pojem úspěšně smazán."));
        } catch (org.apache.jena.ontology.OntologyException e) {
            if (e.getMessage().contains("nebyl nalezen")) {
                log.error("Concept not found: {}", conceptId);
                return ResponseEntity.status(404).body(ApiResponseDto.error(e.getMessage()));
            }
            log.error("Error deleting concept: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error deleting concept: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při mazání pojmu."));
        }
    }

    @PatchMapping("/edit")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> editConcept(
            @RequestBody ConceptEditModel conceptEditModel,
            @RequestParam String userId
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Concept edit requested, concept IRI: {}", conceptEditModel.getConceptIRI());

        try {
            if (userId == null || userId.trim().isEmpty()) {
                log.error("UserId is null or empty");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("ID uživatele je povinné."));
            }

            ConceptMetadataModel editedConceptModel = conceptService.editConcept(conceptEditModel);
            log.info("Concept edit successful: {}", editedConceptModel);

            return ResponseEntity.ok().body(ApiResponseDto.success(editedConceptModel, "Pojem úspěšně upraven: "));
        }catch (IllegalArgumentException | SecurityException e) {
            log.error("Client error editing concept: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error editing concept: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při úpravě pojmu."));
        }
    }
}
