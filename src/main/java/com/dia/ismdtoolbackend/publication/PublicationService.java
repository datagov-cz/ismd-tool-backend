package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.exception.PublicationFailedException;
import com.dia.ismdtoolbackend.exception.PublicationInProgressException;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Builds one publication on demand: snapshot PostgreSQL, apply the draft rule, write both files into a
 * temporary directory and store them in the archive, from which an admin downloads them. Runs at most once
 * at a time; a failed run stores nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationService {

    private final PublicationConfig config;
    private final PublicationSnapshotReader snapshotReader;
    private final VocabularyFileWriter vocabularyWriter;
    private final DiagramFileWriter diagramWriter;
    private final PublicationArchive archive;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    /**
     * Builds and stores a publication now.
     *
     * @param triggeredBy who started the run, recorded with it
     * @return the stored run
     * @throws PublicationInProgressException when another run is in progress
     * @throws PublicationFailedException     when the run fails; nothing is stored
     */
    public PublicationRun publish(String triggeredBy) {
        if (!running.tryLock()) {
            log.info("Publication by {}: a run is already in progress, skipping", triggeredBy);
            throw new PublicationInProgressException("Publikace již probíhá.");
        }
        log.info("Publication started by {}", triggeredBy);
        try {
            return build(triggeredBy);
        } finally {
            running.unlock();
        }
    }

    private PublicationRun build(String triggeredBy) {
        String contextUrl = config.getContextUrl();
        if (contextUrl == null || contextUrl.isBlank()) {
            log.error("Publication: ismd.publication.context-url is not set, nothing published");
            throw new PublicationFailedException("ismd.publication.context-url není nastavena.", null);
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
            dir = Files.createTempDirectory("ismd-publication-");
            boolean[] complete = {true};
            Map<String, BuiltFile> files = new LinkedHashMap<>();
            files.put(VocabularyFileWriter.FILE, writeFile(dir, VocabularyFileWriter.FILE,
                    out -> complete[0] = vocabularyWriter.write(published, header, out)));
            files.put(DiagramFileWriter.FILE, writeFile(dir, DiagramFileWriter.FILE,
                    out -> diagramWriter.write(published, header, out)));
            int vocabularies = snapshot.ontologies().size();
            int diagrams = snapshot.diagrams().size();
            long id = archive.save(new PublicationArchive.RunRecord(publishedAt, triggeredBy,
                    config.isIncludeDrafts(), complete[0], vocabularies, diagrams, contextUrl), files);
            log.info("Publication {} of {} stored: {} vocabularies ({}), {} diagrams", id, publishedAt,
                    vocabularies, complete[0] ? "complete" : "INCOMPLETE", diagrams);
            return new PublicationRun(id, publishedAt, vocabularies, diagrams, complete[0], config.isIncludeDrafts());
        } catch (IOException | RuntimeException e) {
            log.error("Publication of {} failed; nothing stored", publishedAt, e);
            throw new PublicationFailedException("Publikace selhala; nic nebylo uloženo.", e);
        } finally {
            if (dir != null) {
                deleteQuietly(dir);
            }
        }
    }

    private static BuiltFile writeFile(Path dir, String name, Consumer<OutputStream> writer) {
        Path path = dir.resolve(name);
        MessageDigest sha256 = sha256();
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(path), sha256)) {
            writer.accept(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return new BuiltFile(name, path, Files.size(path), "\"" + HexFormat.of().formatHex(sha256.digest()) + "\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            log.warn("Publication: could not remove the temporary build {}", dir, e);
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
