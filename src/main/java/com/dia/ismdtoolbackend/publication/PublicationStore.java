package com.dia.ismdtoolbackend.publication;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The published files on disk and the in-memory record of which publication is current.
 *
 * <p>Each publication is built in its own directory {@code <directory>/<epoch-millis>/}. It becomes current
 * when {@code current.json} is atomically replaced to name it, so readers always see both files of the
 * same run. Older directories are deleted afterwards; a request still streaming an old file keeps its open
 * handle. On startup {@code current.json} is read back, so a restart keeps the publication and its age.
 */
@Slf4j
@Component
public class PublicationStore {

    static final String MARKER = "current.json";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Path root;
    private final AtomicReference<Map<String, PublishedFile>> current = new AtomicReference<>(Map.of());

    public PublicationStore(PublicationConfig config) {
        this.root = config.getDirectory();
    }

    record Marker(String directory, String publishedAt, Map<String, Entry> files) {
    }

    record Entry(long size, String etag) {
    }

    @PostConstruct
    void load() {
        Path marker = root.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        try {
            Marker m = JSON.readValue(marker.toFile(), Marker.class);
            Map<String, PublishedFile> files = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> e : m.files().entrySet()) {
                Path path = root.resolve(m.directory()).resolve(e.getKey());
                if (!Files.isRegularFile(path) || Files.size(path) != e.getValue().size()) {
                    log.warn("Publication: {} missing or truncated, discarding the current publication", path);
                    return;
                }
                files.put(e.getKey(), new PublishedFile(e.getKey(), path, e.getValue().size(), e.getValue().etag(),
                        Instant.parse(m.publishedAt())));
            }
            current.set(Map.copyOf(files));
            log.info("Publication: loaded publication of {} from {}", m.publishedAt(), root);
        } catch (IOException | RuntimeException e) {
            log.warn("Publication: unreadable {}, starting without a publication", marker, e);
        }
    }

    public Optional<PublishedFile> file(String name) {
        return Optional.ofNullable(current.get().get(name));
    }

    /** The current publication's {@code publikováno}, or empty when nothing is published. */
    public Optional<Instant> publishedAt() {
        return current.get().values().stream().map(PublishedFile::publishedAt).findFirst();
    }

    /** Creates the empty directory a new publication is built in. */
    Path newBuildDirectory(Instant publishedAt) {
        Path dir = root.resolve(Long.toString(publishedAt.toEpochMilli()));
        try {
            Files.createDirectories(root);
            deleteRecursively(dir);
            return Files.createDirectory(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Makes the files built in {@code dir} current, then removes every other build directory. */
    void commit(Path dir, Instant publishedAt, Map<String, PublishedFile> files) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        files.forEach((name, f) -> entries.put(name, new Entry(f.size(), f.etag())));
        try {
            Path tmp = root.resolve(MARKER + ".tmp");
            JSON.writeValue(tmp.toFile(), new Marker(dir.getFileName().toString(), publishedAt.toString(), entries));
            Files.move(tmp, root.resolve(MARKER), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        current.set(Map.copyOf(files));
        removeBuildsOtherThan(dir);
    }

    /** Removes a build that will not be committed. */
    void discard(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("Publication: could not remove failed build {}", dir, e);
        }
    }

    private void removeBuildsOtherThan(Path keep) {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (!dir.equals(keep)) {
                    deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            log.warn("Publication: could not remove old builds in {}", root, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
