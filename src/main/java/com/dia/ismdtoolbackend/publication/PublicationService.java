package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.publication.file.DiagramFileWriter;
import com.dia.ismdtoolbackend.publication.file.PublicationHeader;
import com.dia.ismdtoolbackend.publication.file.VocabularyFileWriter;
import com.dia.ismdtoolbackend.publication.snapshot.DraftFilter;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshotReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Builds and commits one publication: snapshot PostgreSQL, apply the draft rule, write both files into a
 * fresh build directory and make them current together. Runs at most once at a time; a failed run leaves
 * the previous publication current.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationService {

    private final PublicationConfig config;
    private final PublicationSnapshotReader snapshotReader;
    private final VocabularyFileWriter vocabularyWriter;
    private final DiagramFileWriter diagramWriter;
    private final PublicationStore store;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    /** Whether nothing is published yet, or the current publication is at least {@code interval} old. */
    public boolean isDue() {
        return store.publishedAt()
                .map(at -> !at.plus(config.getInterval()).isAfter(clock.instant()))
                .orElse(true);
    }

    /** Publishes when {@link #isDue()}; returns whether a new publication was committed. */
    public boolean publishIfDue() {
        return isDue() && publish();
    }

    /**
     * Builds and commits a publication now.
     *
     * @return true when committed; false when another run was in progress or this one failed
     */
    public boolean publish() {
        if (!running.tryLock()) {
            log.info("Publication: a run is already in progress, skipping");
            return false;
        }
        try {
            return build();
        } finally {
            running.unlock();
        }
    }

    private boolean build() {
        String contextUrl = config.getContextUrl();
        if (contextUrl == null || contextUrl.isBlank()) {
            log.error("Publication: ismd.publication.context-url is not set, nothing published");
            return false;
        }
        Instant publishedAt = clock.instant();
        Path dir = null;
        try {
            PublicationSnapshot snapshot = snapshotReader.read();
            if (!config.isIncludeDrafts()) {
                snapshot = DraftFilter.withoutDrafts(snapshot);
            }
            PublicationHeader header = new PublicationHeader(publishedAt, config.isIncludeDrafts(), contextUrl,
                    config.getIriBase());
            PublicationSnapshot published = snapshot;
            dir = store.newBuildDirectory(publishedAt);
            boolean[] complete = {true};
            Map<String, PublishedFile> files = new LinkedHashMap<>();
            files.put(VocabularyFileWriter.FILE, writeFile(dir, VocabularyFileWriter.FILE, publishedAt,
                    out -> complete[0] = vocabularyWriter.write(published, header, out)));
            files.put(DiagramFileWriter.FILE, writeFile(dir, DiagramFileWriter.FILE, publishedAt,
                    out -> diagramWriter.write(published, header, out)));
            store.commit(dir, publishedAt, files);
            log.info("Publication of {} committed: {} vocabularies ({}), {} diagrams", publishedAt,
                    snapshot.ontologies().size(), complete[0] ? "complete" : "INCOMPLETE", snapshot.diagrams().size());
            return true;
        } catch (RuntimeException e) {
            log.error("Publication of {} failed; the previous publication stays current", publishedAt, e);
            if (dir != null) {
                store.discard(dir);
            }
            return false;
        }
    }

    private static PublishedFile writeFile(Path dir, String name, Instant publishedAt, Consumer<OutputStream> writer) {
        Path path = dir.resolve(name);
        MessageDigest sha256 = sha256();
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(path), sha256)) {
            writer.accept(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return new PublishedFile(name, path, Files.size(path),
                    "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"", publishedAt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
