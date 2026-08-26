package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.controller.dto.WorkingCopySyncRequestDto;
import com.dia.ismdtoolbackend.service.NkdSnapshotEndpointService;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/concept")
@RequiredArgsConstructor
@Slf4j
public class ConceptController {

    private final ConceptService conceptService;
    private final NkdSnapshotEndpointService nkdSnapshotEndpointService;

    @Operation(
            summary = "Vytvoření nového pojmu",
            description = "Vytvoří nový pojem (třídu, vlastnost nebo vztah) ve slovníku. Podporuje různé typy pojmů podle OFN standardů. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{slug}/create")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#slug)")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> createConcept(
            @Valid @RequestBody ConceptCreateModel conceptCreateModel,
            @PathVariable String slug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", conceptCreateModel.getNamespace(), conceptCreateModel.getNameModel(), conceptCreateModel.getDescriptionModel(), securityUser.getUserId());

        ConceptMetadataModel createdConcept = conceptService.createConcept(conceptCreateModel, securityUser.getUserId());
        log.info("Concept create successful: {}", createdConcept);

        return ResponseEntity.ok().body(ApiResponseDto.success(createdConcept, "Pojem úspěšně vytvořen: "));
    }

    @Operation(
            summary = "Hromadné vytvoření nových pojmů",
            description = "Vytvoří více nových pojmů (tříd, vlastností nebo vztahů) ve slovníku. "
                    + "Každý pojem používá stejný model a validaci jako endpoint pro vytvoření jednoho pojmu. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{slug}/create/bulk")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#slug)")
    public ResponseEntity<ApiResponseDto<List<ConceptMetadataModel>>> createConcepts(
            @NotEmpty @RequestBody List<@NotNull @Valid ConceptCreateModel> conceptCreateModels,
            @PathVariable String slug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Bulk concept create requested, count: {}, userId: {}",
                conceptCreateModels.size(), securityUser.getUserId());

        List<ConceptMetadataModel> createdConcepts = conceptCreateModels.stream()
                .map(conceptCreateModel -> conceptService.createConcept(
                        conceptCreateModel, securityUser.getUserId()))
                .toList();
        log.info("Bulk concept create successful, count: {}", createdConcepts.size());

        return ResponseEntity.ok().body(ApiResponseDto.success(createdConcepts, "Pojmy úspěšně vytvořeny: "));
    }

    @Operation(
            summary = "Smazání pojmu",
            description = "Smaže pojem z RDF úložiště i databáze včetně všech jeho vztahů. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @DeleteMapping("/{conceptId}/delete")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<Void>> deleteConcept(
            @PathVariable Long conceptId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Concept delete requested, conceptId: {}, userId: {}", conceptId, securityUser.getUserId());

        conceptService.deleteConcept(conceptId);
        log.info("Concept delete successful: {}", conceptId);

        return ResponseEntity.ok(ApiResponseDto.success("Pojem úspěšně smazán."));
    }

    @Operation(
            summary = "Úprava pojmu",
            description = "Umožňuje upravit existující pojem včetně jeho názvu, definice, vztahů a dalších vlastností. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PatchMapping("/{conceptId}/edit")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<ConceptMetadataModel>> editConcept(
            @Valid @RequestBody ConceptEditModel conceptEditModel,
            @AuthenticationPrincipal SecurityUser securityUser,
            @PathVariable Long conceptId
    ) {
        log.info("Concept edit requested, conceptId: {}, userId: {}", conceptId, securityUser.getUserId());

        ConceptMetadataModel editedConceptModel = conceptService.editConcept(conceptId, conceptEditModel);
        log.info("Concept edit successful: {}", editedConceptModel);

        return ResponseEntity.ok().body(ApiResponseDto.success(editedConceptModel, "Pojem úspěšně upraven: "));
    }

    @Operation(
            summary = "Seznam pojmů",
            description = "Vrací seznam pojmů s možností filtrování podle uživatele nebo stavu publikace. Veřejný endpoint."
    )
    @GetMapping("/list")
    public ResponseEntity<ApiResponseDto<List<ConceptMetadataModel>>> getConceptList(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Boolean isPublished
    ) {
        log.info("Concept list requested, userId: {}, isPublished: {}", userId, isPublished);

        List<ConceptMetadataModel> concepts = conceptService.getAll(userId, isPublished);
        return ResponseEntity.ok().body(ApiResponseDto.success(concepts, "Žádost o seznam pojmů proběhla úspěšně."));
    }

    @Operation(
            summary = "Detail pojmu",
            description = "Vrací kompletní detail pojmu včetně všech vlastností, vztahů, definic a dalších metadat. Obsahuje také informace o odchylkách od publikované verze. Veřejný endpoint."
    )
    @GetMapping("/{slug}/detail")
    public ResponseEntity<ApiResponseDto<GetConceptDto>> getConceptDetail(@PathVariable String slug) {
        log.info("Concept detail requested, conceptSlug: {}", slug);

        GetConceptDto conceptDto = conceptService.getConceptDetail(slug);
        return ResponseEntity.ok().body(ApiResponseDto.success(conceptDto, "Detail pojmu byl úspěšně načten."));
    }

    @Operation(
            summary = "Aktualizace lokální kopie NKD pojmu",
            description = "Znovu načte propojený publikovaný pojem z NKD, obnoví lokální kopii a vrátí přepočítanou odchylku. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{conceptId}/localcopy/{snapshotId}/update")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<LinkSnapshotDto>> updateLocalCopy(
        @PathVariable Long conceptId,
            @PathVariable Long snapshotId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Local-copy update requested, conceptId: {}, snapshotId: {}, userId: {}",
                conceptId, snapshotId, securityUser.getUserId());

        LinkSnapshotDto refreshed = nkdSnapshotEndpointService.updateSnapshot(conceptId, snapshotId);
        return ResponseEntity.ok().body(ApiResponseDto.success(refreshed, "Lokální kopie byla aktualizována."));
    }

    @Operation(
            summary = "Synchronizace pracovní kopie s publikovaným pojmem v NKD",
            description = "Převezme vybrané odlišné vlastnosti z NKD. Přijetí VŠECH odlišných vlastností "
                    + "ponechá pojem pracovní kopií; přijetí pouze NĚKTERÝCH pojem odpojí od NKD a změní jej "
                    + "na koncept. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{conceptId}/sync")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<GetConceptDto>> syncWorkingCopy(
            @PathVariable Long conceptId,
            @Valid @RequestBody WorkingCopySyncRequestDto request,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Working-copy sync requested, conceptId: {}, fields: {}, userId: {}",
                conceptId, request.getFieldsToAccept(), securityUser.getUserId());

        GetConceptDto synced = conceptService.syncWorkingCopy(conceptId, request.getFieldsToAccept());
        return ResponseEntity.ok().body(ApiResponseDto.success(synced, "Pracovní kopie byla synchronizována."));
    }

    @Operation(
            summary = "Odstranění lokální kopie NKD pojmu",
            description = "Zruší propojení na publikovaný pojem v NKD a odstraní jeho lokální kopii. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @DeleteMapping("/{conceptId}/localcopy/{snapshotId}")
    @PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")
    public ResponseEntity<ApiResponseDto<Void>> removeLocalCopy(
            @PathVariable Long conceptId,
            @PathVariable Long snapshotId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Local-copy remove requested, conceptId: {}, snapshotId: {}, userId: {}",
                conceptId, snapshotId, securityUser.getUserId());

        nkdSnapshotEndpointService.removeSnapshot(conceptId, snapshotId);
        return ResponseEntity.ok(ApiResponseDto.success("Lokální kopie byla odstraněna."));
    }
}
