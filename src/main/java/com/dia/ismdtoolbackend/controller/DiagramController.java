package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictResponseDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramCreateDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramRenameDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.service.DiagramService;
import com.dia.ismdtoolbackend.service.impl.DiagramConceptUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The diagram layer's REST surface. Paths nest the diagram under its ontology
 * ({@code /{ontologySlug}/{diagramId}/…}) so every write authorizes the slug through
 * {@code belongsToUserBySlug}. That check does not constrain the diagram id beside it, so the service also
 * asserts the diagram belongs to the named ontology — see {@code DiagramServiceImpl.requireDiagramOf}.
 */
@RestController
@RequestMapping("/api/diagram")
@RequiredArgsConstructor
@Slf4j
public class DiagramController {

    private final DiagramService diagramService;

    /**
     * Injected directly rather than routed through {@link DiagramService}: the usage read is
     * concept-addressed and shares none of that service's diagram-addressed machinery.
     */
    private final DiagramConceptUsageService conceptUsageService;

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
            summary = "Seznam diagramů slovníku",
            description = "Vrací diagramy jednoho slovníku (identita + počet uzlů), od nejstaršího. "
                    + "Slovník jich může mít více. Vyžaduje oprávnění přihlášeného uživatele."
    )
    @GetMapping("/{ontologySlug}/list")
    @PreAuthorize("@ontologySecurityService.canViewResource()")
    public ResponseEntity<ApiResponseDto<List<DiagramSummaryDto>>> listForOntology(
            @PathVariable String ontologySlug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram list requested for ontology {}, userId: {}", ontologySlug, securityUser.getUserId());

        List<DiagramSummaryDto> diagrams = diagramService.listForOntology(ontologySlug);
        return ResponseEntity.ok().body(ApiResponseDto.success(diagrams, "Seznam diagramů byl úspěšně načten."));
    }

    @Operation(
            summary = "Výskyt pojmu na diagramech",
            description = "Vrací diagramy, na jejichž plátně se pojem nachází, s odkazem (slug slovníku + id diagramu) "
                    + "a se strukturou pojmu tak, jak ji daný diagram zobrazuje — definiční obor, obor hodnot a "
                    + "hierarchie, vše resolvované. Pokrývá všechny tři podoby: třída jako uzel, vztah jako hrana, "
                    + "vlastnost jako řádek uvnitř své třídy. Pokud má diagram na pojmu rozpracovanou změnu, je "
                    + "zohledněna a diagram je označen `pending`. Prázdný seznam znamená, že pojem není na žádném "
                    + "plátně. Vyžaduje oprávnění přihlášeného uživatele."
    )
    @GetMapping("/usage/concept/{conceptSlug}")
    @PreAuthorize("@ontologySecurityService.canViewResource()")
    public ResponseEntity<ApiResponseDto<DiagramConceptUsageDto>> getConceptUsage(
            @PathVariable String conceptSlug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram concept usage requested, conceptSlug: {}, userId: {}",
                conceptSlug, securityUser.getUserId());

        DiagramConceptUsageDto usage = conceptUsageService.usageForSlug(conceptSlug);
        return ResponseEntity.ok().body(
                ApiResponseDto.success(usage, "Výskyt pojmu na diagramech byl úspěšně načten."));
    }

    @Operation(
            summary = "Vytvoření diagramu",
            description = "Vytvoří nový prázdný diagram slovníku. Slovník může mít více diagramů. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PostMapping("/{ontologySlug}/create")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<DiagramDto>> createDiagram(
            @PathVariable String ontologySlug,
            @Valid @RequestBody DiagramCreateDto request,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram create requested, ontologySlug: {}, userId: {}", ontologySlug, securityUser.getUserId());

        DiagramDto diagram = diagramService.createDiagram(ontologySlug, request.name());
        return ResponseEntity.ok().body(ApiResponseDto.success(diagram, "Diagram byl úspěšně vytvořen."));
    }

    @Operation(
            summary = "Smazání diagramu",
            description = "Smaže diagram včetně jeho rozvržení a rozpracovaných změn. Pojmy slovníku zůstávají "
                    + "nedotčeny. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @DeleteMapping("/{ontologySlug}/{diagramId}")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<Void>> deleteDiagram(
            @PathVariable String ontologySlug,
            @PathVariable Long diagramId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram delete requested, ontologySlug: {}, diagramId: {}, userId: {}",
                ontologySlug, diagramId, securityUser.getUserId());

        diagramService.deleteDiagram(ontologySlug, diagramId);
        return ResponseEntity.ok().body(ApiResponseDto.success(null, "Diagram byl úspěšně smazán."));
    }

    @Operation(
            summary = "Přejmenování diagramu",
            description = "Změní název diagramu. Název musí být neprázdný a v rámci slovníku jedinečný; "
                    + "přejmenování na stávající název je bez efektu. Nesahá na rozvržení ani na rozpracované "
                    + "změny. Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diagram byl přejmenován."),
            // The body comes from GlobalExceptionHandler, which springdoc does not walk; without this the
            // FE cannot generate a type for the name clash.
            @ApiResponse(responseCode = "409",
                    description = "Diagram s tímto názvem už ve slovníku existuje.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResponseDto.class)))
    })
    @PatchMapping("/{ontologySlug}/{diagramId}/rename")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<DiagramSummaryDto>> renameDiagram(
            @PathVariable String ontologySlug,
            @PathVariable Long diagramId,
            @Valid @RequestBody DiagramRenameDto request,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram rename requested, ontologySlug: {}, diagramId: {}, userId: {}",
                ontologySlug, diagramId, securityUser.getUserId());

        DiagramSummaryDto renamed = diagramService.renameDiagram(ontologySlug, diagramId, request.name());
        return ResponseEntity.ok().body(ApiResponseDto.success(renamed, "Diagram byl úspěšně přejmenován."));
    }

    @Operation(
            summary = "Načtení diagramu",
            description = "Vrací render-ready diagram — rozvržení spojené s živým obsahem pojmů, s aplikovanými "
                    + "overlayi (pending edits) a projektovanými hranami. Vyžaduje oprávnění přihlášeného uživatele."
    )
    @GetMapping("/{ontologySlug}/{diagramId}/detail")
    @PreAuthorize("@ontologySecurityService.canViewResource()")
    public ResponseEntity<ApiResponseDto<DiagramDto>> getDiagram(
            @PathVariable String ontologySlug,
            @PathVariable Long diagramId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram read requested, ontologySlug: {}, diagramId: {}, userId: {}",
                ontologySlug, diagramId, securityUser.getUserId());

        DiagramDto diagram = diagramService.getDiagram(ontologySlug, diagramId);
        return ResponseEntity.ok().body(ApiResponseDto.success(diagram, "Diagram byl úspěšně načten."));
    }

    @Operation(
            summary = "Uložení rozvržení diagramu",
            description = "Uloží rozvržení diagramu a overlaye pouze do databáze (bez zápisu do RDF). Sada uzlů je "
                    + "autoritativní pro členství na plátně — chybějící uzel je z plátna odebrán, nový je načten z živého RDF. "
                    + "Pole `overlays` je naopak přírůstkové: pojem, který v něm chybí, si svůj overlay ponechá; "
                    + "položka pouze s `conceptIri` overlay zahodí. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @PutMapping("/{ontologySlug}/{diagramId}/layout")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<DiagramDto>> saveLayout(
            @PathVariable String ontologySlug,
            @PathVariable Long diagramId,
            @Valid @RequestBody DiagramLayoutDto layout,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram layout save requested, ontologySlug: {}, diagramId: {}, userId: {}",
                ontologySlug, diagramId, securityUser.getUserId());

        DiagramDto diagram = diagramService.saveLayout(ontologySlug, diagramId, layout);
        return ResponseEntity.ok().body(ApiResponseDto.success(diagram, "Rozvržení diagramu bylo úspěšně uloženo."));
    }

    @Operation(
            summary = "Převzetí (materializace) změn diagramu",
            description = "Aplikuje všechny čekající (pending) změny přes existující CRUD pojmů → outbox → RDF a po úspěchu vyčistí "
                    + "jednotlivé overlaye. Vrací výsledek po jednotlivých změnách (materializované, neúspěšné, zastaralé). "
                    + "Pokud má na stejném pojmu rozpracovanou změnu i jiný diagram téhož slovníku, vrací 409 s přehledem "
                    + "kolizí. Parametr `onConflict` pojmenuje jedinou vítěznou stranu: `ACCEPT_MINE` převezme změny tohoto "
                    + "diagramu, `ACCEPT_THEIRS` spolu s `winnerDiagramId` změny uvedeného diagramu. Kolidující změny všech "
                    + "ostatních diagramů se zahodí — jedno rozhodnutí vyřeší kolizi napříč libovolným počtem diagramů. "
                    + "U `ACCEPT_THEIRS` se materializuje vítězný diagram, nikoli diagram v cestě. "
                    + "Zahození proběhne hned při rozhodnutí, tedy ještě před zápisem do RDF, a je nevratné i tehdy, "
                    + "když všechny následné změny selžou. Nekolidující rozpracované změny poražených diagramů zůstávají. "
                    + "Vyžaduje oprávnění vlastníka slovníku nebo administrátora."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Změny diagramu byly převzaty."),
            // The 409 body comes from GlobalExceptionHandler, which springdoc does not walk; without this
            // the conflict types are absent from the schema.
            @ApiResponse(responseCode = "409",
                    description = "Na některém pojmu má rozpracovanou změnu i jiný diagram téhož slovníku. "
                            + "Nic nebylo zapsáno; tělo obsahuje přehled kolizí.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DiagramConflictResponseDto.class)))
    })
    @PostMapping("/{ontologySlug}/{diagramId}/materialize")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#ontologySlug)")
    public ResponseEntity<ApiResponseDto<MaterializeResultDto>> materialize(
            @PathVariable String ontologySlug,
            @PathVariable Long diagramId,
            @RequestParam(required = false) DiagramService.ConflictResolution onConflict,
            @Parameter(description = "Diagram, jehož změny se převezmou. Povinné pro onConflict=ACCEPT_THEIRS.")
            @RequestParam(required = false) Long winnerDiagramId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Diagram materialize requested, ontologySlug: {}, diagramId: {}, onConflict: {}, "
                        + "winnerDiagramId: {}, userId: {}",
                ontologySlug, diagramId, onConflict, winnerDiagramId, securityUser.getUserId());

        MaterializeResultDto result =
                diagramService.materialize(ontologySlug, diagramId, onConflict, winnerDiagramId);
        return ResponseEntity.ok().body(ApiResponseDto.success(result, "Změny diagramu byly převzaty."));
    }
}
