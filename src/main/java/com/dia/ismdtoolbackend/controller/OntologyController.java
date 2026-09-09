package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.dto.CatalogRecordDto;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.config.ValidationConfig;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.CatalogRecordRequestDto;
import com.dia.ismdtoolbackend.controller.dto.CatalogRequestDto;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.ValidationErrorSummaryDto;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.OntologyDownloadBlockedException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;
import com.dia.validation.ValidationResult;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
@Slf4j
public class OntologyController {

    private static final Set<String> SUPPORTED_DOWNLOAD_FORMATS = Set.of("ttl", "json-ld");

    private final OntologyService ontologyService;
    private final OntologyUploadService ontologyUploadService;
    private final OntologyDownloadService ontologyDownloadService;
    private final ValidationService validationService;
    private final ValidationClient validationClient;
    private final ValidationConfig validationConfig;
    private final NkdSnapshotWarmer nkdSnapshotWarmer;

    @Operation(
            summary = "Nahrání slovníku ze souboru",
            description = "Umožňuje nahrát a importovat slovník z RDF souboru (Turtle, JSON-LD). Soubor je validován a uložen do RDF úložiště. Vyžaduje autentizaci."
    )
    @PostMapping(path="/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> uploadFromFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "normalizeMode", required = false) NormalizeMode normalizeMode,
            @RequestParam(value = "conceptsToNormalize", required = false) List<String> conceptsToNormalize,
            @AuthenticationPrincipal SecurityUser securityUser) throws IOException {

        log.info(
                "Ontology upload requested, fileName: {}, normalizeMode: {}, userId: {}",
                file.getOriginalFilename(),
                normalizeMode,
                securityUser.getUserId()
        );

        OntologyMetadataModel savedOntology = ontologyUploadService.uploadFromFile(
                file, securityUser.getUserId(), normalizeMode, conceptsToNormalize);
        log.info("Ontology upload successful: {}", savedOntology.getGraphName());

        // Post-commit, NKD-independent: warm NKD local-copy snapshots for any published-concept links
        // off the request thread. If NKD is down the graph stays cold and first detail-view heals it.
        // Guarded: a saturated executor (TaskRejectedException) must never fail an already-committed upload.
        // warmGraphNow, not warmGraph: the graph just changed, so the read-path scan throttle must not
        // suppress this scan.
        try {
            nkdSnapshotWarmer.warmGraphNow(savedOntology.getGraphName());
        } catch (Exception e) {
            log.warn("Could not trigger NKD snapshot warming for uploaded graph {}: {}",
                    savedOntology.getGraphName(), e.getMessage());
        }

        return ResponseEntity.ok().body(ApiResponseDto.success(savedOntology, "Slovník úspěšně nahrán: " + savedOntology.getGraphName()));
    }

    @Operation(
            summary = "Smazání slovníku",
            description = "Smaže slovník a všechna jeho data z RDF úložiště i databáze. Vyžaduje oprávnění vlastníka nebo administrátora."
    )
    @DeleteMapping("/{ontologyId}/delete")
    @PreAuthorize("@ontologySecurityService.canModify(#ontologyId)")
    public ResponseEntity<ApiResponseDto<Void>> deleteOntology(
            @PathVariable Long ontologyId,
            @AuthenticationPrincipal SecurityUser securityUser) {

        log.info(
                "Ontology delete requested, ontologyId: {}, userId: {}, isAdmin: {}",
                ontologyId,
                securityUser.getUserId(),
                securityUser.isAdmin()
        );

        ontologyService.deleteOntology(ontologyId);
        return ResponseEntity.ok(ApiResponseDto.success("Slovník úspěšně smazán."));
    }

    @Operation(
            summary = "Ověření IRI nového slovníku",
            description = "Sestaví IRI z názvu a namespace stejným způsobem jako vytvoření slovníku. "
                    + "Vrací valid a available; dostupnost ověřuje pouze v lokální databázi. "
                    + "Neplatné IRI má oba příznaky false. Nic neukládá ani nerezervuje. Vyžaduje autentizaci."
    )
    @PostMapping("/check-iri")
    public ResponseEntity<ApiResponseDto<OntologyIriCheckResponseDto>> checkIri(
            @Valid @RequestBody OntologyIriCheckRequestDto request) {
        return ResponseEntity.ok(ApiResponseDto.success(ontologyService.checkIri(request), "Kontrola IRI dokončena."));
    }

    @Operation(
            summary = "Vytvoření nového slovníku",
            description = "Vytvoří nový prázdný slovník s definovaným jmenným prostorem, názvem a popisem. Slovník je uložen do RDF úložiště. Vyžaduje autentizaci."
    )
    @PostMapping("/create")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> createOntology(
            @Valid @RequestBody OntologyCreateModel ontologyCreateModel,
            @AuthenticationPrincipal SecurityUser securityUser) {


        log.info(
                "Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}",
                ontologyCreateModel.getNamespace(),
                ontologyCreateModel.getNameModel(),
                ontologyCreateModel.getDescriptionModel(),
                securityUser.getUserId()
        );

        OntologyMetadataModel createdOntology = ontologyService.createOntology(ontologyCreateModel, securityUser.getUserId());
        log.info("Ontology create successful: {}", createdOntology);

        return ResponseEntity.ok().body(ApiResponseDto.success(createdOntology, "Slovník úspěšně vytvořen: " + createdOntology.getGraphName()));
    }

    @Operation(
            summary = "Vytvoření slovníku s vybranými pojmy",
            description = "Přijme nový slovník a vybrané pojmy. Sestaví finální IRI a převede refs na vazby. "
                    + "Používá stejné validace a RDF tvorbu jako běžné vytvoření. "
                    + "Obsazené IRI slovníku vrací 409. Nic nepřidává do existujícího slovníku. Vyžaduje autentizaci."
    )
    @PostMapping("/create-with-concepts")
    public ResponseEntity<ApiResponseDto<OntologyCreateWithConceptsResponseDto>> createWithConcepts(
            @Valid @RequestBody OntologyCreateWithConceptsRequestDto request,
            @AuthenticationPrincipal SecurityUser securityUser) {
        return ResponseEntity.status(201).body(ApiResponseDto.success(
                ontologyService.createWithConcepts(request, securityUser.getUserId()), "Slovník a pojmy byly vytvořeny."));
    }

    @Operation(
            summary = "Úprava slovníku",
            description = "Umožňuje upravit metadata slovníku (název, popis) v různých jazycích. Vyžaduje oprávnění vlastníka nebo administrátora."
    )
    @PatchMapping("/{ontologyId}/edit")
    @PreAuthorize("@ontologySecurityService.canModify(#ontologyId)")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> editOntology(
            @RequestBody OntologyEditModel ontologyEditModel,
            @AuthenticationPrincipal SecurityUser securityUser,
            @PathVariable Long ontologyId
    ) {
        log.info(
                "Ontology edit requested, ontologyId: {}, userId: {}, isAdmin: {}",
                ontologyId,
                securityUser.getUserId(),
                securityUser.isAdmin()
        );


        OntologyMetadataModel updatedOntology = ontologyService.editOntology(ontologyId, ontologyEditModel);
        log.info("Ontology edit successful: {}", updatedOntology);

        return ResponseEntity.ok().body(ApiResponseDto.success(updatedOntology, "Slovník úspěšně upraven: " + updatedOntology.getGraphName()));
    }

    @Operation(
            summary = "Stažení slovníku",
            description = "Umožňuje stáhnout slovník v požadovaném formátu (TTL, JSON-LD). Pokud je povoleno omezení stahování slovníků s chybami, "
                    + "slovníky s validačními chybami nelze stáhnout a endpoint vrací 400 s kódem ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION "
                    + "a seznamem blokujících chyb. Veřejný endpoint."
    )
    @GetMapping("/{ontologyId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long ontologyId,
            @RequestParam String format
    ) {
        log.info("Ontology download requested, ontologyId: {}, format: {}", ontologyId, format);

        String normalizedFormat = format == null ? "" : format.toLowerCase();
        if (!SUPPORTED_DOWNLOAD_FORMATS.contains(normalizedFormat)) {
            throw new IllegalArgumentException("Nepodporovaný formát: " + format
                    + ". Podporované formáty: " + String.join(", ", SUPPORTED_DOWNLOAD_FORMATS) + ".");
        }

        if (!validationConfig.isEnableOntologyViolationDownload()) {
            OntologyMetadataModel metadata = ontologyService.getOntologyMetadata(ontologyId);
            ValidationReportDto validationReport = validationService.getValidationReport(metadata);
            if (validationReport != null) {
                List<ValidationResult> errors = validationReport.getResults().stream()
                        .filter(ValidationResult::isError)
                        .toList();
                if (!errors.isEmpty()) {
                    throw new OntologyDownloadBlockedException(
                            metadata.getGraphName(),
                            errors.size(),
                            errors.stream()
                                    .limit(OntologyDownloadBlockedException.MAX_LISTED_ERRORS)
                                    .map(OntologyController::toErrorSummary)
                                    .toList());
                }
            }
        }

        String content = ontologyDownloadService.downloadOntology(ontologyId, normalizedFormat);

        String filename = "ontology_" + ontologyId + "." + getFileExtension(normalizedFormat);
        String contentType = getContentType(normalizedFormat);

        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").contentType(MediaType.parseMediaType(contentType)).contentLength(resource.contentLength()).body(resource);
    }

    @Operation(
            summary = "Detail slovníku",
            description = "Vrací kompletní detail slovníku včetně všech pojmů, jejich vztahů a metadat. Obsahuje také informace o odchylkách od publikované verze, pokud existuje. Veřejný endpoint."
    )
    @GetMapping("/{slug}/detail")
    public ResponseEntity<ApiResponseDto<GetOntologyDto>> getOntologyDetail(@PathVariable String slug) {
        log.info("Ontology detail requested, ontologyId: {}", slug);

        GetOntologyDto ontologyDto = ontologyService.getOntologyDetailModel(slug);

        return ResponseEntity.ok().body(ApiResponseDto.success(ontologyDto, "Detail slovníku byl úspěšně načten."));
    }

    @Operation(
            summary = "Zpráva z poslední kontroly slovníku",
            description = "Vrací výsledky poslední uložené kontroly slovníku. Zpráva se ukládá při nahrání slovníku a při ručním spuštění kontroly; nevzniká automaticky při úpravě pojmů. "
                    + "Slovník, který dosud nebyl zkontrolován, vrací prázdný seznam výsledků. Veřejný endpoint."
    )
    @GetMapping("/{slug}/validation-report")
    public ResponseEntity<ApiResponseDto<ValidationReportDto>> getValidationReport(@PathVariable String slug) {
        log.info("Ontology validation report requested, slug: {}", slug);

        OntologyMetadataModel ontologyMetadata = ontologyService.getOntologyMetadataBySlug(slug);
        ValidationReportDto report = validationService.getValidationReportOrEmpty(ontologyMetadata);

        return ResponseEntity.ok().body(ApiResponseDto.success(report, "Zpráva z kontroly slovníku byla úspěšně načtena."));
    }

    @Operation(
            summary = "Minimalistický seznam pojmů slovníku podle IRI",
            description = "Vrací minimalistický seznam pojmů slovníku (iri, slug, název) podle IRI slovníku. " +
                    "Parametr source určuje zdroj: ISMD (lokální úložiště – položky obsahují slug pro navigaci) " +
                    "nebo NKD (Národní katalog dat – položky obsahují pouze IRI). Veřejný endpoint."
    )
    @GetMapping("/concepts")
    public ResponseEntity<ApiResponseDto<List<MinimalConceptDto>>> getConceptsByIri(
            @Parameter(description = "IRI slovníku", required = true)
            @RequestParam String iri,
            @Parameter(description = "Zdroj: ISMD nebo NKD", required = true)
            @RequestParam SearchSource source
    ) {
        log.info("Ontology concepts requested, iri: {}, source: {}", iri, source);

        List<MinimalConceptDto> concepts = ontologyService.getConceptsByIri(iri, source);

        return ResponseEntity.ok().body(ApiResponseDto.success(concepts, "Seznam pojmů byl úspěšně načten."));
    }

    @Operation(
            summary = "Seznam slovníků",
            description = "Vrací seznam slovníků s možností filtrování podle uživatele, stavu publikace nebo konkrétních slugů. Veřejný endpoint."
    )
    @GetMapping("/list")
    public ResponseEntity<ApiResponseDto<List<OntologyMetadataModel>>> getOntologyList(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Boolean isPublished,
            @RequestParam(required = false) List<String> slugs
    ) {

        if (slugs != null && !slugs.isEmpty()) {
            log.info("Ontology list by slugs requested, slugs: {}", slugs);
            List<OntologyMetadataModel> ontologies = ontologyService.getBySlugs(slugs);
            return ResponseEntity.ok().body(ApiResponseDto.success(ontologies, "Žádost o seznam slovníků proběhla úspěšně."));
        }

        log.info("Ontology list requested, userId: {}, isPublished: {}", userId, isPublished);
        List<OntologyMetadataModel> ontologies = ontologyService.getAll(userId, isPublished);
        return ResponseEntity.ok().body(ApiResponseDto.success(ontologies, "Žádost o seznam slovníků proběhla úspěšně."));
    }

    @Operation(
            summary = "Validace slovníku",
            description = "Spustí validaci slovníku proti pravidlům SHACL/SKOS prostřednictvím externí validační služby. Výsledky validace jsou uloženy do databáze. Vyžaduje oprávnění vlastníka nebo administrátora."
    )
    @PostMapping("{slug}/validate")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#slug)")
    public ResponseEntity<ApiResponseDto<ValidationReport>> validateOntology(
            @RequestBody OntologyMetadataModel ontologyMetadata,
            @PathVariable String slug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Ontology validation requested, slug: {}, ontologyIRI: {}", slug, ontologyMetadata.getGraphName());

        String ttlContent = ontologyService.getTtlContentFromOntology(ontologyMetadata);

        Optional<ValidationReport> validationReport = validationClient.requestValidation(ttlContent, ontologyMetadata.getGraphName());

        ValidationReport report = validationReport.orElseThrow(() -> {
            log.warn("Validation report not received for ontology: {}", ontologyMetadata.getGraphName());
            return new ValidationServiceUnavailableException("Validační služba",
                    "Validační služba nevrátila odpověď.");
        });
        validationService.saveValidationReport(report, ontologyMetadata, securityUser.getUserId());

        return ResponseEntity.ok().body(ApiResponseDto.success(report, "Validace proběhla úspěšně."));
    }

    @Operation(
            summary = "Žádost o katalogizační záznam",
            description = "Vyžádá katalogizační záznam slovníku z validační služby na základě RDF dat a výsledků validace. Vyžaduje oprávnění vlastníka nebo administrátora."
    )
    @Hidden
    @Deprecated(forRemoval = true)
    @PostMapping("{slug}/catalog-record")
    @PreAuthorize("@ontologySecurityService.belongsToUserBySlug(#slug)")
    public ResponseEntity<ApiResponseDto<CatalogRecordDto>> requestCatalogRecord(
            @RequestBody CatalogRequestDto catalogRequestDto,
            @PathVariable String slug,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        log.info("Ontology catalog record requested for user: {}, ontologyIRI: {}, slug: {}",
                securityUser.getUsername(),
                catalogRequestDto.getOntologyMetadata().getGraphName(),
                slug
        );

        String ttlContent = ontologyService.getTtlContentFromOntology(catalogRequestDto.getOntologyMetadata());

        CatalogRecordRequestDto request = new CatalogRecordRequestDto();
        request.setTtlContent(ttlContent);
        request.setValidationReport(catalogRequestDto.getValidationReport());

        Optional<CatalogRecordDto> catalogRecordDto = validationClient.requestCatalogRecord(request);

        CatalogRecordDto catalogRecord = catalogRecordDto.orElseThrow(() -> {
            log.warn("Catalog record not received for ontology: {}", catalogRequestDto.getOntologyMetadata().getGraphName());
            return new OntologyValidationException("Žádost o katalogizační záznam se nezdařila - validační služba nevrátila odpověď, nebo je nedostupná.");
        });

        return ResponseEntity.ok().body(ApiResponseDto.success(catalogRecord, "Žádost o katalogizační záznam proběhla úspěšně."));
    }

    private static ValidationErrorSummaryDto toErrorSummary(ValidationResult result) {
        return new ValidationErrorSummaryDto(
                result.ruleName(),
                result.message(),
                result.focusNodeUri(),
                result.getFocusNodeName(),
                result.resultPathUri());
    }

    private String getFileExtension(String format) {
        return switch (format) {
            case "json-ld" -> "jsonld";
            case "ttl" -> "ttl";
            default -> throw new IllegalStateException("Unreachable: format validated by SUPPORTED_DOWNLOAD_FORMATS");
        };
    }

    private String getContentType(String format) {
        return switch (format) {
            case "json-ld" -> "application/ld+json";
            case "ttl" -> "text/turtle";
            default -> throw new IllegalStateException("Unreachable: format validated by SUPPORTED_DOWNLOAD_FORMATS");
        };
    }
}
