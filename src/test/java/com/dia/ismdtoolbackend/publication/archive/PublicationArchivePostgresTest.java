package com.dia.ismdtoolbackend.publication.archive;

import com.dia.ismdtoolbackend.exception.PublicationFilePrunedException;
import com.dia.ismdtoolbackend.publication.BuiltFile;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive.RunRecord;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The archive against real Postgres: bytea round trip, retention of content vs metadata, history. */
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
    @Autowired private JdbcTemplate jdbc;

    @TempDir Path dir;

    @Test
    void save_thenFile_returnsTheExactContent() throws IOException {
        long id = archive.save(run(T0), files("slovníky ✓", "diagramy"));

        PublicationArchive.ArchivedFile file = archive.file(id, "slovniky.jsonld");

        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("slovníky ✓");
        assertThat(file.etag()).isEqualTo("\"etag-slovniky.jsonld\"");
        assertThat(jdbc.queryForObject("select triggered_by from ismd_schema.publication where id = ?",
                String.class, id)).isEqualTo("ADMIN:test");
    }

    @Test
    void olderThanTheRetention_keepsMetadata_butDropsContent() throws IOException {
        long first = archive.save(run(T0), files("a1", "b1"));
        long second = archive.save(run(T0.plusSeconds(60)), files("a2", "b2"));
        long third = archive.save(run(T0.plusSeconds(120)), files("a3", "b3"));

        assertThatThrownBy(() -> archive.file(first, "slovniky.jsonld"))
                .isInstanceOf(PublicationFilePrunedException.class);
        assertThat(archive.file(second, "slovniky.jsonld").content()).isEqualTo("a2".getBytes(StandardCharsets.UTF_8));
        assertThat(archive.file(third, "diagramy.jsonld").content()).isEqualTo("b3".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("select count(*) from ismd_schema.publication", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from ismd_schema.publication_file", Integer.class)).isEqualTo(6);
    }

    @Test
    void history_listsNewestFirst_withFileMetadata_andWhetherContentIsStillStored() throws IOException {
        long first = archive.save(run(T0), files("a1", "b1"));
        archive.save(run(T0.plusSeconds(60)), files("a2", "b2"));
        long third = archive.save(run(T0.plusSeconds(120)), files("a3", "b3"));

        List<PublicationSummary> history = archive.history(0, 10);

        assertThat(history).extracting(PublicationSummary::publishedAt)
                .containsExactly(T0.plusSeconds(120), T0.plusSeconds(60), T0);
        PublicationSummary newest = history.get(0);
        assertThat(newest.id()).isEqualTo(third);
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
        archive.save(run(T0), files("a1", "b1"));
        archive.save(run(T0.plusSeconds(60)), files("a2", "b2"));

        assertThat(archive.history(0, 1)).extracting(PublicationSummary::publishedAt).containsExactly(T0.plusSeconds(60));
        assertThat(archive.history(1, 1)).extracting(PublicationSummary::publishedAt).containsExactly(T0);
        assertThat(archive.history(2, 1)).isEmpty();
    }

    @Test
    void unknownPublicationOrFile_isNotFound() throws IOException {
        long id = archive.save(run(T0), files("a", "b"));

        assertThatThrownBy(() -> archive.file(id, "current.json")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> archive.file(id + 1, "slovniky.jsonld")).isInstanceOf(EntityNotFoundException.class);
    }

    private static RunRecord run(Instant at) {
        return new RunRecord(at, "ADMIN:test", true, true, 1, 0, "https://example.org/kontext.jsonld");
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
