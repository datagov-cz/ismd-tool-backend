package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The open-data publication for admins: build one on demand (the request waits for it), list the stored
 * publications and download their files. ISMD serves no public copy; the files go to LKOD DIA by hand. The path {@code /api/admin/publikace/**} is registered in {@code SecurityConfig}'s
 * authenticated chain; the role is checked here.
 */
@RestController
@RequestMapping("/api/admin/publikace")
@RequiredArgsConstructor
public class PublicationAdminController {

    static final int MAX_PAGE_SIZE = 200;
    static final MediaType JSON_LD = MediaType.parseMediaType("application/ld+json;charset=UTF-8");

    private final PublicationService service;
    private final PublicationArchive archive;

    @Operation(summary = "Publikovat slovníky a diagramy jako otevřená data",
            description = "Sestaví a zveřejní slovniky.jsonld a diagramy.jsonld. Čeká na dokončení. Jen pro administrátory.")
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<PublicationRun>> run(@AuthenticationPrincipal SecurityUser user) {
        PublicationRun run = service.publish("ADMIN:" + (user != null ? user.getUsername() : "unknown"));
        return ResponseEntity.ok(ApiResponseDto.success(run, "Publikace dokončena"));
    }

    @Operation(summary = "Historie publikací",
            description = "Uložené publikace od nejnovější, jen metadata (bez obsahu souborů). Jen pro administrátory.")
    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<List<PublicationSummary>>> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResponseDto.success(archive.history(page, size), "Historie publikací"));
    }

    @Operation(summary = "Stažení souboru dřívější publikace",
            description = "Vrací uložený slovniky.jsonld nebo diagramy.jsonld publikace podle jejího id. "
                    + "410, pokud je obsah starší než uchovávané publikace. Jen pro administrátory.")
    @GetMapping("/{id}/{file}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> file(@PathVariable long id, @PathVariable String file) {
        PublicationArchive.ArchivedFile stored = archive.file(id, file);
        return ResponseEntity.ok()
                .contentType(JSON_LD)
                .eTag(stored.etag())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("publikace-" + id + "-" + file).build().toString())
                .body(stored.content());
    }
}
