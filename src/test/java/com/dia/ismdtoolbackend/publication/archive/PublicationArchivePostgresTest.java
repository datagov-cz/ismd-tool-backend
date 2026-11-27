package com.dia.ismdtoolbackend.publication.archive;

import com.dia.ismdtoolbackend.exception.PublicationFilePrunedException;
import com.dia.ismdtoolbackend.publication.BuiltFile;
import com.dia.ismdtoolbackend.publication.PublicationRun;
import com.dia.ismdtoolbackend.publication.PublicationService;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunResult;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunStart;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The archive against real Postgres: run status (changeset 026), bytea round trip, retention of content vs
 * metadata, history, and a whole run through the service and its executor.
 */
@SpringBootTest
@ActiveProfiles("junit")
@TestPropertySource(properties = {
        "ismd.publication.retain-files=2",
        "spring.security.oauth2.client.registration.keycloak.client-id=test-client-id",
        "spring.security.oauth2.client.registration.keycloak.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.keycloak.provider=keycloak-test",
        "spring.security.oauth2.client.provider.keycloak-test.authorization-uri=http://localhost/auth",
        "spring.security.oauth2.client.provider.keycloak-test.token-uri=http://localhost/token",
        "spring.security.oauth2.client.provider.keycloak-test.user-info-uri=http://localhost/userinfo",
        "spring.security.oauth2.client.provider.keycloak-test.jwk-set-uri=http://localhost/jwks",
        "spring.security.oauth2.client.provider.keycloak-test.user-name-attribute=preferred_username",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks"
})
class PublicationArchivePostgresTest extends PostgresIntegrationTestBase {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired private PublicationArchive archive;
    @Autowired private PublicationService service;
    @Autowired private JdbcTemplate jdbc;

    @TempDir Path dir;

    @Test
    void save_thenFile_returnsTheExactContent_gzippedAsStored() throws IOException {
        long id = save(T0, files("slovníky ✓", "diagramy"));

        PublicationArchive.ArchivedFile file = archive.file(id, "slovniky.jsonld");

        assertThat(content(file)).isEqualTo("slovníky ✓");
        assertThat(file.size()).isEqualTo("slovníky ✓".getBytes(StandardCharsets.UTF_8).length);
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(file.gzip()))) {
            assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).as("stored as gzip")
                    .isEqualTo("slovníky ✓");
        }
        assertThat(file.etag()).isEqualTo("\"etag-slovniky.jsonld\"");
        assertThat(jdbc.queryForObject("select triggered_by from ismd_schema.publication where id = ?",
                String.class, id)).isEqualTo("ADMIN:test");
    }

    @Test
    void aRun_isRecordedRunning_thenStoredWithItsCounts() throws IOException {
        long id = archive.begin(start(T0));

        assertThat(row(id)).containsEntry("status", "RUNNING").containsEntry("complete", false)
                .containsEntry("vocabularies", 0);
        assertThat(archive.history(0, 10)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(PublicationStatus.RUNNING);
            assertThat(p.files()).isEmpty();
        });

        archive.store(id, new RunResult(true, 3, 2), files("a", "b"));

        assertThat(row(id)).containsEntry("status", "STORED").containsEntry("complete", true)
                .containsEntry("vocabularies", 3).containsEntry("diagrams", 2);
        assertThat(row(id).get("error")).isNull();
    }

    @Test
    void aFailedRun_keepsItsErrorAndHasNoFiles() {
        long id = archive.begin(start(T0));

        archive.fail(id, "x".repeat(2000));

        PublicationSummary failed = archive.history(0, 10).get(0);
        assertThat(failed.status()).isEqualTo(PublicationStatus.FAILED);
        assertThat(failed.error()).as("cut to the column").hasSize(PublicationEntity.ERROR_LENGTH);
        assertThat(failed.files()).isEmpty();
    }

    @Test
    void startupRecovery_failsOnlyRunningRuns() throws IOException {
        long stored = save(T0, files("a", "b"));
        long running = archive.begin(start(T0.plusSeconds(60)));

        assertThat(archive.failInterrupted("přerušeno restartem")).isEqualTo(1);

        assertThat(row(running)).containsEntry("status", "FAILED").containsEntry("error", "přerušeno restartem");
        assertThat(row(stored)).containsEntry("status", "STORED");
    }

    @Test
    void retention_countsOnlyStoredRuns_andNeverTouchesARunningOne() throws IOException {
        long first = save(T0, files("a1", "b1"));
        long second = save(T0.plusSeconds(60), files("a2", "b2"));
        long failed = archive.begin(start(T0.plusSeconds(90)));
        archive.fail(failed, "chyba");
        long running = archive.begin(start(T0.plusSeconds(100)));
        long third = save(T0.plusSeconds(120), files("a3", "b3"));

        assertThatThrownBy(() -> archive.file(first, "slovniky.jsonld"))
                .isInstanceOf(PublicationFilePrunedException.class);
        assertThat(content(archive.file(second, "slovniky.jsonld")))
                .as("the second newest STORED run keeps its content; RUNNING and FAILED take no slot")
                .isEqualTo("a2");
        assertThat(content(archive.file(third, "slovniky.jsonld"))).isEqualTo("a3");
        assertThat(row(running)).containsEntry("status", "RUNNING");
        assertThat(row(failed)).containsEntry("status", "FAILED");
    }

    @Test
    void changeset026_backfillsRowsAsStored_andIndexesConceptsByVocabulary() {
        jdbc.update("""
                insert into ismd_schema.publication (published_at, triggered_by, include_drafts, complete,
                    vocabularies, diagrams, context_url) values (now(), 'ADMIN:old', true, true, 1, 0, 'https://x')""");

        assertThat(jdbc.queryForObject(
                "select status from ismd_schema.publication where triggered_by = 'ADMIN:old'", String.class))
                .isEqualTo("STORED");
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'ismd_schema' and table_name = 'publication' and column_name = 'status'""",
                String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'ismd_schema' and tablename = 'concepts'
                  and indexname = 'idx_concepts_ontology_metadata_id'""", Integer.class)).isEqualTo(1);
    }

    @Test
    void aWholeRun_inTheBackground_endsStored() throws InterruptedException {
        PublicationRun started = service.publish("ADMIN:test", false);

        assertThat(started.status()).isEqualTo(PublicationStatus.RUNNING);
        String status = null;
        for (int i = 0; i < 250 && !"STORED".equals(status); i++) {
            Thread.sleep(20);
            status = (String) row(started.id()).get("status");
        }
        assertThat(status).isEqualTo("STORED");
        assertThat(archive.history(0, 10).get(0).files()).extracting(PublicationSummary.FileSummary::name)
                .containsExactly("diagramy.jsonld", "slovniky.jsonld");

        PublicationRun waited = waitForLock();
        assertThat(waited.status()).isEqualTo(PublicationStatus.STORED);
        assertThat(row(waited.id())).containsEntry("status", "STORED");
    }

    /** The background run releases the lock just after storing. */
    private PublicationRun waitForLock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                return service.publish("ADMIN:test", true);
            } catch (com.dia.ismdtoolbackend.exception.PublicationInProgressException e) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("the lock was never released");
    }

    @Test
    void history_listsNewestFirst_withFileMetadata_andWhetherContentIsStillStored() throws IOException {
        long first = save(T0, files("a1", "b1"));
        save(T0.plusSeconds(60), files("a2", "b2"));
        long third = save(T0.plusSeconds(120), files("a3", "b3"));

        List<PublicationSummary> history = archive.history(0, 10);

        assertThat(history).extracting(PublicationSummary::publishedAt)
                .containsExactly(T0.plusSeconds(120), T0.plusSeconds(60), T0);
        PublicationSummary newest = history.get(0);
        assertThat(newest.id()).isEqualTo(third);
        assertThat(newest.status()).isEqualTo(PublicationStatus.STORED);
        assertThat(newest.triggeredBy()).isEqualTo("ADMIN:test");
        assertThat(newest.files()).containsExactly(
                new PublicationSummary.FileSummary("diagramy.jsonld", 2, "\"etag-diagramy.jsonld\"", true),
                new PublicationSummary.FileSummary("slovniky.jsonld", 2, "\"etag-slovniky.jsonld\"", true));
        PublicationSummary oldest = history.get(history.size() - 1);
        assertThat(oldest.id()).isEqualTo(first);
        assertThat(oldest.files()).as("past the retention of 2: metadata kept, content gone")
                .hasSize(2).allMatch(f -> !f.stored() && f.size() == 2);
    }

    @Test
    void history_pages() throws IOException {
        save(T0, files("a1", "b1"));
        save(T0.plusSeconds(60), files("a2", "b2"));

        assertThat(archive.history(0, 1)).extracting(PublicationSummary::publishedAt).containsExactly(T0.plusSeconds(60));
        assertThat(archive.history(1, 1)).extracting(PublicationSummary::publishedAt).containsExactly(T0);
        assertThat(archive.history(2, 1)).isEmpty();
    }

    @Test
    void unknownPublicationOrFile_isNotFound() throws IOException {
        long id = save(T0, files("a", "b"));

        assertThatThrownBy(() -> archive.file(id, "current.json")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> archive.file(id + 1, "slovniky.jsonld")).isInstanceOf(EntityNotFoundException.class);
    }

    private static RunStart start(Instant at) {
        return new RunStart(at, "ADMIN:test", true, "https://example.org/kontext.jsonld");
    }

    private long save(Instant at, Map<String, BuiltFile> files) {
        long id = archive.begin(start(at));
        archive.store(id, new RunResult(true, 1, 0), files);
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "select status, error, complete, vocabularies, diagrams from ismd_schema.publication where id = ?", id);
    }

    private static String content(PublicationArchive.ArchivedFile file) throws IOException {
        try (InputStream in = file.openContent()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, BuiltFile> files(String vocabularies, String diagrams) throws IOException {
        Path build = Files.createTempDirectory(dir, "build");
        Map<String, BuiltFile> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Map.of("slovniky.jsonld", vocabularies, "diagramy.jsonld", diagrams).entrySet()) {
            Path path = Files.writeString(build.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            files.put(e.getKey(), new BuiltFile(e.getKey(), path, Files.size(path), "\"etag-" + e.getKey() + "\""));
        }
        return files;
    }
}
