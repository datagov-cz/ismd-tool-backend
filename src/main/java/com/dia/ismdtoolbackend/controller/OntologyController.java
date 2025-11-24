package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
@Slf4j
public class OntologyController {

    private final OntologyService ontologyService;
    private final OntologyUploadService ontologyUploadService;
    private final OntologyDownloadService ontologyDownloadService;
    private final ValidationService validationService;
    private final ValidationClient validationClient;

    @PostMapping(path="/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> uploadFromFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(name = "providedName", required = false) String providedName,
            @AuthenticationPrincipal SecurityUser securityUser) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);

        log.info(
                "Ontology upload requested, fileName: {}, providedName: {}, userId: {}",
                file.getOriginalFilename(),
                providedName,
                securityUser.getUserId()
        );

        OntologyMetadataModel savedOntology = ontologyUploadService.uploadFromFile(file, providedName, securityUser.getUserId());
        log.info("Ontology upload successful: {}", savedOntology);

        return ResponseEntity.ok().body(ApiResponseDto.success(savedOntology, "Slovník úspěšně nahrán: " + savedOntology.getGraphName()));
    }

    @DeleteMapping("/{ontologyId}/delete")
    @PreAuthorize("@ontologySecurityService.canModify(#ontologyId)")
    public ResponseEntity<ApiResponseDto<Void>> deleteOntology(
            @PathVariable Long ontologyId,
            @AuthenticationPrincipal SecurityUser securityUser) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);

        log.info(
                "Ontology delete requested, ontologyId: {}, userId: {}, isAdmin: {}",
                ontologyId,
                securityUser.getUserId(),
                securityUser.isAdmin()
        );

        ontologyService.deleteOntology(ontologyId);
        return ResponseEntity.ok(ApiResponseDto.success("Slovník úspěšně smazán."));
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> createOntology(
            @RequestBody OntologyCreateModel ontologyCreateModel,
            @AuthenticationPrincipal SecurityUser securityUser) {

        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);

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

    @PatchMapping("/{ontologyId}/edit")
    @PreAuthorize("@ontologySecurityService.canModify(#ontologyId)")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> editOntology(
            @RequestBody OntologyEditModel ontologyEditModel,
            @AuthenticationPrincipal SecurityUser securityUser,
            @PathVariable Long ontologyId
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info(
                "Ontology edit requested, ontologyIRI: {}, ontologyId: {}, userId: {}, isAdmin: {}",
                ontologyEditModel.getOntologyIRI(),
                ontologyId,
                securityUser.getUserId(),
                securityUser.isAdmin()
        );


        OntologyMetadataModel updatedOntology = ontologyService.editOntology(ontologyEditModel);
        log.info("Ontology edit successful: {}", updatedOntology);

        return ResponseEntity.ok().body(ApiResponseDto.success(updatedOntology, "Slovník úspěšně upraven: " + updatedOntology.getGraphName()));
    }

    @GetMapping("/{ontologyId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long ontologyId,
            @RequestParam String format
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology download requested, ontologyId: {}, format: {}", ontologyId, format);

        String content = ontologyDownloadService.downloadOntology(ontologyId, format);

        String filename = "ontology_" + ontologyId + "." + getFileExtension(format);
        String contentType = getContentType(format);

        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").contentType(MediaType.parseMediaType(contentType)).contentLength(resource.contentLength()).body(resource);
    }

    @GetMapping("/{slug}/detail")
    public ResponseEntity<GetOntologyDto> getOntologyDetail(@PathVariable String slug) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology detail requested, ontologyId: {}", slug);

        GetOntologyDto ontologyDto = ontologyService.getOntologyDetailModel(slug);

        return ResponseEntity.ok().body(ontologyDto);
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponseDto<List<OntologyMetadataModel>>> getOntologyList(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Boolean isPublished,
            @RequestParam(required = false) List<String> slugs
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);

        if (slugs != null && !slugs.isEmpty()) {
            log.info("Ontology list by slugs requested, slugs: {}", slugs);
            List<OntologyMetadataModel> ontologies = ontologyService.getBySlugs(slugs);
            return ResponseEntity.ok().body(ApiResponseDto.success(ontologies, "Žádost o seznam slovníků proběhla úspěšně."));
        }

        log.info("Ontology list requested, userId: {}, isPublished: {}", userId, isPublished);
        List<OntologyMetadataModel> ontologies = ontologyService.getAll(userId, isPublished);
        return ResponseEntity.ok().body(ApiResponseDto.success(ontologies, "Žádost o seznam slovníků proběhla úspěšně."));
    }

    @PostMapping("/validate")
    public ResponseEntity<ApiResponseDto<ValidationReport>> validateOntology(
            @RequestPart OntologyMetadataModel ontologyMetadata
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology validation requested, ontologyIRI: {}", ontologyMetadata.getGraphName());

        String ttlContent = ontologyService.getTtlContentFromOntology(ontologyMetadata);
        Optional<ValidationReport> validationReport = validationClient.requestValidation(ttlContent, ontologyMetadata.getGraphName());

        ValidationReport report = validationReport.orElseThrow(() -> {
            log.warn("Validation report not received for ontology: {}", ontologyMetadata.getGraphName());
            return new OntologyValidationException("Validace se nezdařila - validační služba nevrátila odpověď.");
        });

        return ResponseEntity.ok().body(ApiResponseDto.success(report, "Validace proběhla úspěšně."));
    }

    private String getFileExtension(String format) {
        return switch (format.toLowerCase()) {
            case "json-ld" -> "jsonld";
            case "ttl" -> "ttl";
            default -> "txt";
        };
    }

    private String getContentType(String format) {
        return switch (format.toLowerCase()) {
            case "json-ld" -> "application/ld+json";
            case "ttl" -> "text/turtle";
            default -> "text/plain";
        };
    }
}
