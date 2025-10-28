package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
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
import java.util.UUID;

import static com.dia.constants.ConverterControllerConstants.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/ontology")
@RequiredArgsConstructor
@Slf4j
public class OntologyController {

    private final OntologyService ontologyService;
    private final OntologyUploadService ontologyUploadService;
    private final OntologyDownloadService ontologyDownloadService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> uploadFromFile(
            @RequestParam MultipartFile file,
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


        OntologyMetadataModel updatedOntology = ontologyService.editOntology(ontologyEditModel, ontologyId);
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

    @GetMapping("/{ontologyId}/detail")
    public ResponseEntity<OntologyDetailModel> getOntologyDetail(@PathVariable Long ontologyId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology detail requested, ontologyId: {}", ontologyId);

        OntologyDetailModel detailModel = ontologyService.getOntologyDetailModel(ontologyId);

        return ResponseEntity.ok().body(detailModel);
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
