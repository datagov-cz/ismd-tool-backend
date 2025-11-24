package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/concept")
@RequiredArgsConstructor
@Slf4j
public class ConceptController {

    private final ConceptService conceptService;

    @PostMapping("/{slug}/create")
    @PreAuthorize("@ontologySecurityService.canCreateConcept(#slug)")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> createConcept(
            @RequestBody ConceptCreateModel conceptCreateModel,
            @PathVariable String slug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", conceptCreateModel.getNamespace(), conceptCreateModel.getNameModel(), conceptCreateModel.getDescriptionModel(), securityUser.getUserId());

        ConceptMetadataModel createdConcept = conceptService.createConcept(conceptCreateModel, securityUser.getUserId());
        log.info("Concept create successful: {}", createdConcept);

        return ResponseEntity.ok().body(ApiResponseDto.success(createdConcept, "Pojem úspěšně vytvořen: "));
    }

    @DeleteMapping("/{conceptId}/delete")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<Void>> deleteConcept(
            @PathVariable Long conceptId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Concept delete requested, conceptId: {}, userId: {}", conceptId, securityUser.getUserId());

        conceptService.deleteConcept(conceptId);
        log.info("Concept delete successful: {}", conceptId);

        return ResponseEntity.ok(ApiResponseDto.success("Pojem úspěšně smazán."));
    }

    @PatchMapping("/{conceptId}/edit")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> editConcept(
            @RequestBody ConceptEditModel conceptEditModel,
            @AuthenticationPrincipal SecurityUser securityUser,
            @PathVariable Long conceptId
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Concept edit requested, concept IRI: {}, conceptId: {}, userId: {}", conceptEditModel.getConceptIRI(), conceptId, securityUser.getUserId());

        ConceptMetadataModel editedConceptModel = conceptService.editConcept(conceptEditModel);
        log.info("Concept edit successful: {}", editedConceptModel);

        return ResponseEntity.ok().body(ApiResponseDto.success(editedConceptModel, "Pojem úspěšně upraven: "));
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponseDto<List<ConceptMetadataModel>>> getConceptList(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Boolean isPublished
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Concept list requested, userId: {}, isPublished: {}", userId, isPublished);

        List<ConceptMetadataModel> concepts = conceptService.getAll(userId, isPublished);
        return ResponseEntity.ok().body(ApiResponseDto.success(concepts, "Žádost o seznam pojmů proběhla úspěšně."));
    }

    @GetMapping("/{slug}/detail")
    public ResponseEntity<GetConceptDto> getConceptDetail(@PathVariable String slug) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Concept detail requested, conceptSlug: {}", slug);

        GetConceptDto conceptDto = conceptService.getConceptDetail(slug);
        return ResponseEntity.ok().body(conceptDto);
    }
}
