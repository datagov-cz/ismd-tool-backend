package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationSummary;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * The open-data publication for admins: start one (it builds in the background), list the publication
 * runs and download their files. ISMD serves no public copy; the files go to LKOD DIA by hand. The path
 * {@code /api/admin/publikace/**} is registered in {@code SecurityConfig}'s authenticated chain; the role
 * is checked here.
 */
@RestController
@RequestMapping("/api/admin/publikace")
@RequiredArgsConstructor
public class PublicationAdminController {

    static final int MAX_PAGE_SIZE = 200;
    static final MediaType JSON_LD = MediaType.parseMediaType("application/ld+json;charset=UTF-8");
    static final MediaType GZIP = MediaType.parseMediaType("application/gzip");
    static final String GZIP_FORMAT = "gzip";

    private final PublicationService service;
    private final PublicationArchive archive;

    @Operation(summary = "Publikovat slovníky a diagramy jako otevřená data",
            description = "Spustí sestavení slovniky.jsonld a diagramy.jsonld na pozadí a vrátí 202 s během ve stavu "
                    + "RUNNING; průběh ukazuje historie. S wait=true čeká na dokončení a vrátí 200. "
                    + "Jen pro administrátory.")
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<PublicationRun>> run(@AuthenticationPrincipal SecurityUser user,
                                                              @RequestParam(defaultValue = "false") boolean wait) {
        PublicationRun run = service.publish("ADMIN:" + (user != null ? user.getUsername() : "unknown"), wait);
        return wait
                ? ResponseEntity.ok(ApiResponseDto.success(run, "Publikace dokončena"))
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponseDto.success(run, "Publikace spuštěna"));
    }

    @Operation(summary = "Historie publikací",
            description = "Běhy publikace od nejnovějšího se stavem RUNNING, STORED nebo FAILED, jen metadata "
                    + "(bez obsahu souborů). Jen pro administrátory.")
    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponseDto<List<PublicationSummary>>> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResponseDto.success(archive.history(page, size), "Historie publikací"));
    }

    @Operation(summary = "Stažení souboru dřívější publikace",
            description = "Vrací uložený slovniky.jsonld nebo diagramy.jsonld publikace podle jejího id: s "
                    + "Accept-Encoding: gzip komprimovaně (Content-Encoding: gzip), jinak nekomprimovaně; "
                    + "s format=gzip jako soubor .gz. 410, pokud je obsah starší než uchovávané publikace. "
                    + "Jen pro administrátory.")
    @GetMapping("/{id}/{file}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InputStreamResource> file(
            @PathVariable long id, @PathVariable String file,
            @RequestParam(required = false) String format,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {
        if (format != null && !GZIP_FORMAT.equals(format)) {
            throw new IllegalArgumentException("Parametr 'format' má neplatnou hodnotu; povolena je jen hodnota gzip.");
        }
        PublicationArchive.ArchivedFile stored = archive.file(id, file);
        String filename = "publikace-" + id + "-" + file;
        if (format != null) {
            return ResponseEntity.ok()
                    .contentType(GZIP)
                    .contentLength(stored.gzip().length)
                    .eTag(stored.etag())
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename + ".gz"))
                    .body(new InputStreamResource(new ByteArrayInputStream(stored.gzip())));
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(JSON_LD)
                .eTag(stored.etag())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename));
        if (acceptsGzip(acceptEncoding)) {
            return response
                    .header(HttpHeaders.CONTENT_ENCODING, GZIP_FORMAT)
                    .contentLength(stored.gzip().length)
                    .body(new InputStreamResource(new ByteArrayInputStream(stored.gzip())));
        }
        return response.contentLength(stored.size()).body(new InputStreamResource(stored.openContent()));
    }

    /** Whether {@code Accept-Encoding} lists gzip (or x-gzip) with a non-zero quality. */
    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String coding : acceptEncoding.split(",")) {
            String[] parts = coding.split(";");
            String name = parts[0].trim();
            if (!name.equalsIgnoreCase(GZIP_FORMAT) && !name.equalsIgnoreCase("x-gzip")) {
                continue;
            }
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                String parameter = parts[i].trim();
                if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        quality = Double.parseDouble(parameter.substring(2).trim());
                    } catch (NumberFormatException e) {
                        quality = 0;
                    }
                }
            }
            if (quality > 0) {
                return true;
            }
        }
        return false;
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }
}
