package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Optional;
import java.util.Set;

/**
 * Serves the published open-data files, the NKOD distributions of ISMD. Public: a distribution must be
 * downloadable without signing in. Nothing is built here; a request only streams the current file.
 * {@code HEAD} is mapped explicitly and never opens the file, so it answers from the in-memory record.
 */
@RestController
@RequestMapping(PublicationController.BASE_PATH)
@RequiredArgsConstructor
@Tag(name = "Publikace", description = "Otevřená data ISMD: slovníky a diagramy v JSON-LD")
public class PublicationController {

    public static final String BASE_PATH = "/api/publikace";
    static final MediaType JSON_LD = MediaType.parseMediaType("application/ld+json;charset=UTF-8");
    static final String RETRY_AFTER_SECONDS = "600";
    /** The only files served; {@code SecurityConfig} and {@code CorsConfig} open exactly these paths. */
    public static final Set<String> FILES = Set.of(VocabularyFileWriter.FILE, DiagramFileWriter.FILE);

    private final PublicationConfig config;
    private final PublicationStore store;

    @Operation(summary = "Stažení publikovaného souboru",
            description = "Vrací poslední publikaci slovniky.jsonld nebo diagramy.jsonld. Veřejný endpoint.")
    @RequestMapping(value = "/{file}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> file(@PathVariable String file, ServletWebRequest request) {
        if (!config.isEnabled() || !FILES.contains(file)) {
            return ResponseEntity.notFound().build();
        }
        Optional<PublishedFile> published = store.file(file);
        if (published.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }
        PublishedFile f = published.get();
        if (request.checkNotModified(f.etag(), f.publishedAt().toEpochMilli())) {
            return null;
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(JSON_LD)
                .contentLength(f.size())
                .eTag(f.etag())
                .lastModified(f.publishedAt())
                .cacheControl(CacheControl.noCache().cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString());
        return HttpMethod.HEAD.equals(request.getHttpMethod())
                ? response.build()
                : response.body(new FileSystemResource(f.path()));
    }
}
