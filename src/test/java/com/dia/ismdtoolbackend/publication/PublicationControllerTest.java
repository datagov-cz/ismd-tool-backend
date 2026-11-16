package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

class PublicationControllerTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-07T02:00:00Z");
    private static final String CONTENT = "{\"@graph\":[\"slovníky\"]}";
    private static final String ETAG = "\"abc123\"";

    @TempDir Path dir;

    private final PublicationConfig config = new PublicationConfig();
    private PublicationStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        config.setDirectory(dir);
        config.setEnabled(true);
        store = new PublicationStore(config);
        mvc = MockMvcBuilders.standaloneSetup(new PublicationController(config, store)).build();
    }

    private void publish() throws Exception {
        Path build = store.newBuildDirectory(PUBLISHED);
        Map<String, PublishedFile> files = new LinkedHashMap<>();
        for (String name : new String[] {VocabularyFileWriter.FILE, DiagramFileWriter.FILE}) {
            Path path = Files.writeString(build.resolve(name), CONTENT, StandardCharsets.UTF_8);
            files.put(name, new PublishedFile(name, path, Files.size(path), ETAG, PUBLISHED));
        }
        store.commit(build, PUBLISHED, files);
    }

    @Test
    void get_streamsTheCurrentFile_withDistributionHeaders() throws Exception {
        publish();

        MvcResult result = mvc.perform(get("/api/publikace/slovniky.jsonld")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(CONTENT);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/ld+json;charset=UTF-8");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(ETAG);
        assertThat(result.getResponse().getHeader(HttpHeaders.LAST_MODIFIED))
                .isEqualTo("Wed, 07 Oct 2026 02:00:00 GMT");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"slovniky.jsonld\"");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH))
                .isEqualTo(Long.toString(CONTENT.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void head_answersHeadersWithoutBody_andNeverOpensTheFile() throws Exception {
        publish();
        Files.delete(store.file(DiagramFileWriter.FILE).orElseThrow().path());

        MvcResult result = mvc.perform(head("/api/publikace/diagramy.jsonld")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(ETAG);
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH))
                .isEqualTo(Long.toString(CONTENT.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void matchingEtag_answers304() throws Exception {
        publish();

        MvcResult result = mvc.perform(get("/api/publikace/slovniky.jsonld")
                .header(HttpHeaders.IF_NONE_MATCH, ETAG)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(304);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    void staleEtag_answersTheFile() throws Exception {
        publish();

        MvcResult result = mvc.perform(get("/api/publikace/slovniky.jsonld")
                .header(HttpHeaders.IF_NONE_MATCH, "\"older\"")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void beforeTheFirstPublication_answers503WithRetryAfter() throws Exception {
        MvcResult result = mvc.perform(get("/api/publikace/slovniky.jsonld")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("600");
    }

    @Test
    void disabled_answers404_evenWhenFilesExist() throws Exception {
        publish();
        config.setEnabled(false);

        assertThat(mvc.perform(get("/api/publikace/slovniky.jsonld")).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void unknownFile_answers404() throws Exception {
        publish();

        assertThat(mvc.perform(get("/api/publikace/current.json")).andReturn().getResponse().getStatus())
                .as("the marker beside the files is not served").isEqualTo(404);
    }
}
