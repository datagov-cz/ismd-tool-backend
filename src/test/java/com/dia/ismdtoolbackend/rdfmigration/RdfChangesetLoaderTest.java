package com.dia.ismdtoolbackend.rdfmigration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdfChangesetLoaderTest {

    private static final String UPDATE = """
            # Adds a marker.
            # Second line.
            PREFIX ex: <https://example.org/>
            INSERT { GRAPH ?graph { ex:a ex:b ex:c } } WHERE { }
            """;

    @TempDir
    Path dir;

    private List<RdfChangeset> load() {
        return new RdfChangesetLoader("file:" + dir.toAbsolutePath()).load();
    }

    private void write(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text);
    }

    @Test
    void changesetsAreSortedByIdWithDescriptionAndChecksum() throws IOException {
        write("002-second.ru", UPDATE);
        write("001-first.ru", UPDATE);
        write("README.md", "not a changeset");

        List<RdfChangeset> changesets = load();

        assertThat(changesets).extracting(RdfChangeset::id).containsExactly("001-first", "002-second");
        assertThat(changesets.get(0).description()).isEqualTo("Adds a marker. Second line.");
        assertThat(changesets.get(0).checksum()).hasSize(64).isEqualTo(RdfChangesetLoader.sha256(UPDATE));
    }

    @Test
    void checksumIgnoresWindowsLineEndings() throws IOException {
        write("001-first.ru", UPDATE.replace("\n", "\r\n"));

        assertThat(load().get(0).checksum()).isEqualTo(RdfChangesetLoader.sha256(UPDATE));
    }

    @Test
    void boundToSetsTheGraph() throws IOException {
        write("001-first.ru", UPDATE);

        assertThat(load().get(0).boundTo("https://example.org/v"))
                .contains("GRAPH <https://example.org/v>")
                .doesNotContain("?graph");
    }

    @Test
    void emptyOrMissingLocationHasNoChangesets() {
        assertThat(load()).isEmpty();
        assertThat(new RdfChangesetLoader("classpath*:no-such-rdf-migrations").load()).isEmpty();
    }

    @Test
    void badFileNameIsRejected() throws IOException {
        write("1-first.ru", UPDATE);

        assertThatThrownBy(this::load).hasMessageContaining("NNN-name.ru");
    }

    @Test
    void changesetWithoutGraphVariableIsRejected() throws IOException {
        write("001-first.ru", "INSERT DATA { <https://example.org/a> <https://example.org/b> 1 }");

        assertThatThrownBy(this::load).hasMessageContaining("001-first never uses ?graph");
    }

    @Test
    void invalidSparqlIsRejected() throws IOException {
        write("001-first.ru", "INSERT { GRAPH ?graph { ?s ?p } } WHERE { }");

        assertThatThrownBy(this::load).hasMessageContaining("001-first is not valid SPARQL Update");
    }

    @Test
    void bundledChangesetsAllLoad() {
        assertThat(new RdfChangesetLoader(RdfChangesetLoader.DEFAULT_LOCATION).load())
                .allSatisfy(changeset -> assertThat(changeset.description()).isNotBlank());
    }
}
