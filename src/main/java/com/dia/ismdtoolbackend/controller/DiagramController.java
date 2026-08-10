package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.service.DiagramService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/diagram")
@RequiredArgsConstructor
@Slf4j
public class DiagramController {

    private final DiagramService diagramService;

    @Operation(
            summary = "Seznam diagramů",
            description = "Vrací odlehčený seznam všech diagramů (identita + počet uzlů), např. pro výběr diagramu. "
                    + "Vyžaduje oprávnění přihlášeného uživatele."
    )
    @GetMapping("/all")
    @PreAuthorize("@ontologySecurityService.canViewResource()")
    public ResponseEntity<ApiResponseDto<List<DiagramSummaryDto>>> getAllDiagrams(
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram list requested, userId: {}", securityUser.getUserId());

        List<DiagramSummaryDto> diagrams = diagramService.listAll();
        return ResponseEntity.ok().body(ApiResponseDto.success(diagrams, "Seznam diagramů byl úspěšně načten."));
    }

    @Operation(
            summary = "Načtení diagramu slovníku",
            description = "Vrací render-ready diagram slovníku — rozvržení spojené s živým obsahem pojmů, s aplikovanými "
                    + "překryvy (pending edits) a projektovanými hranami. Vyžaduje oprávnění přihlášeného uživatele."
    )
    @GetMapping("/{ontologySlug}/detail")
    @PreAuthorize("@ontologySecurityService.canViewResource()")
    public ResponseEntity<ApiResponseDto<DiagramDto>> getDiagram(
            @PathVariable String ontologySlug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram read requested, ontologySlug: {}, userId: {}", ontologySlug, securityUser.getUserId());

        DiagramDto diagram = diagramService.getDiagram(ontologySlug);
        return ResponseEntity.ok().body(ApiResponseDto.success(diagram, "Diagram byl úspěšně načten."));
    }

    @Operation(
            summary = "Uložení rozvržení diagramu",
            description = "Uloží rozvržení diagramu a překryvy pouze do databáze (bez zápisu do RDF). Sada uzlů je "
                    + "autoritativní pro členství na plátně — chybějící uzel je z plátna odebrán, nový je načten z živého RDF. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PutMapping("/{ontologySlug}/layout")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<DiagramDto>> saveLayout(
            @PathVariable String ontologySlug,
            @Valid @RequestBody DiagramLayoutDto layout,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram layout save requested, ontologySlug: {}, userId: {}", ontologySlug, securityUser.getUserId());

        DiagramDto diagram = diagramService.saveLayout(ontologySlug, layout);
        return ResponseEntity.ok().body(ApiResponseDto.success(diagram, "Rozvržení diagramu bylo úspěšně uloženo."));
    }

    @Operation(
            summary = "Uložení překryvu uzlu",
            description = "Uloží překryv (pending edit) jednoho uzlu do databáze. Cílový uzel je určen polem "
                    + "`nodeId` v těle požadavku. Tělo bez jakéhokoli pole překryvu (pouze `nodeId`) překryv "
                    + "zahodí. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PatchMapping("/{ontologySlug}/nodes/overlay")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<DiagramDto.Node>> stageOverlay(
            @PathVariable String ontologySlug,
            @Valid @RequestBody NodeOverlayDto overlay,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram overlay stage requested, ontologySlug: {}, nodeId: {}, userId: {}",
                ontologySlug, overlay.nodeId(), securityUser.getUserId());

        DiagramDto.Node node = diagramService.stageOverlay(ontologySlug, overlay.nodeId(), overlay);
        return ResponseEntity.ok().body(ApiResponseDto.success(node, "Překryv uzlu byl úspěšně uložen."));
    }

    @Operation(
            summary = "Převzetí (materializace) změn diagramu",
            description = "Aplikuje všechny čekající (pending) změny přes existující CRUD pojmů → outbox → RDF a po úspěchu vyčistí "
                    + "jednotlivé překryvy. Vrací výsledek po jednotlivých změnách (materializované, neúspěšné, zastaralé). "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{ontologySlug}/materialize")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<MaterializeResultDto>> materialize(
            @PathVariable String ontologySlug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram materialize requested, ontologySlug: {}, userId: {}", ontologySlug, securityUser.getUserId());

        MaterializeResultDto result = diagramService.materialize(ontologySlug);
        return ResponseEntity.ok().body(ApiResponseDto.success(result, "Změny diagramu byly převzaty."));
    }
}