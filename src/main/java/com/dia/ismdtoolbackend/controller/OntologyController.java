package com.dia.ismdtoolbackend.controller;

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
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.riot.Lang;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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

    @PostMapping("/upload")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> uploadFromFile(
            @RequestParam MultipartFile file,
            @RequestParam(name = "providedName", required = false) String providedName,
            @RequestParam String userId
    ) {
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
        } catch (IllegalArgumentException | SecurityException | OntologyException e) {
            log.error("Client error uploading ontology: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error uploading ontology: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při nahrávání slovníku."));
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
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> createOntology(
            @RequestBody OntologyCreateModel ontologyCreateModel,
            @RequestParam String userId
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology create requested, namespace: {}, name: {}, description: {}, userId: {}", ontologyCreateModel.getNamespace(), ontologyCreateModel.getNameModel(), ontologyCreateModel.getDescriptionModel().getDescription(), userId);

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

    @PatchMapping("/edit")
    public ResponseEntity<ApiResponseDto<OntologyMetadataModel>> editOntology(@RequestBody OntologyEditModel ontologyEditModel) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology edit requested, ontologyIRI: {}", ontologyEditModel.getOntologyIRI());

        try {
            OntologyMetadataModel updatedOntology = ontologyService.editOntology(ontologyEditModel);
            log.info("Ontology edit successful: {}", updatedOntology);

            return ResponseEntity.ok().body(ApiResponseDto.success(updatedOntology, "Slovník úspěšně upraven: " + updatedOntology.getGraphName()));
        } catch (org.apache.jena.ontology.OntologyException e) {
            if (e.getMessage().contains("nebyl nalezen")) {
                log.error("Ontology not found: {}", ontologyEditModel.getOntologyIRI());
                return ResponseEntity.status(404).body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("povinné")) {
                log.error("Validation error: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            if (e.getMessage().contains("Data pro úpravu slovníku jsou prázdná")) {
                log.error("Edit model validation failed: {}", e.getMessage());
                return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
            }
            log.error("Error editing ontology: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error editing ontology: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při úpravě slovníku."));
        }
    }

    @GetMapping("/{ontologyId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long ontologyId,
            @RequestParam String format
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology download requested, ontologyId: {}, format: {}", ontologyId, format);

        try {
            String content = ontologyDownloadService.downloadOntology(ontologyId, format);

            String filename = "ontology_" + ontologyId + "." + getFileExtension(format);
            String contentType = getContentType(format);

            ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));

            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").contentType(MediaType.parseMediaType(contentType)).contentLength(resource.contentLength()).body(resource);

        } catch (IllegalArgumentException e) {
            log.error("Invalid format: {}", format);
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                log.error("Ontology not found: {}", ontologyId);
                return ResponseEntity.notFound().build();
            }
            log.error("Error downloading ontology: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{ontologyId}/detail")
    public ResponseEntity<OntologyDetailModel> getOntologyDetail(@PathVariable Long ontologyId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Ontology detail requested, ontologyId: {}", ontologyId);

        try {
            OntologyDetailModel detailModel = ontologyService.getOntologyDetailModel(ontologyId);

            return ResponseEntity.ok().body(detailModel);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                log.error("Ontology not found: {}", ontologyId);
                return ResponseEntity.notFound().build();
            }
            log.error("Error creating ontology detail model: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
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
