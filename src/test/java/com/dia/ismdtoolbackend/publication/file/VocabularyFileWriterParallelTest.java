package com.dia.ismdtoolbackend.publication.file;

import com.dia.ismdtoolbackend.publication.PublicationConfig;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.service.OntologyDownloadService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Concurrent downloads, written strictly in id order with a bounded number of results in memory. */
class VocabularyFileWriterParallelTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final int VOCABULARIES = 12;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** Later ids answer sooner, so completion order is the reverse of id order. */
    private final OntologyDownloadService slowFirst = (id, format) -> {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Thread.sleep((VOCABULARIES - id) * 15L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
        if (id == 5) {
            throw new IllegalStateException("Fuseki down");
        }
        return "{\"iri\":\"https://x/" + id + "\",\"typ\":[\"Slovník\"]}";
    };

    @Test
    void writesInIdOrder_whileDownloadingConcurrently_andMarksOnlyTheFailedVocabulary() {
        Written written = write(slowFirst, 4, snapshot());

        assertThat(written.iris()).containsExactlyElementsOf(
                LongStream.rangeClosed(1, VOCABULARIES).mapToObj(i -> "https://x/" + i).toList());
        assertThat(maxInFlight.get()).as("downloads overlap, bounded by the parallelism").isBetween(2, 4);
        assertThat(written.complete()).isFalse();
        assertThat(written.status("https://x/5")).isEqualTo("FAILED");
        assertThat(written.status("https://x/6")).isEqualTo("OK");
    }

    @Test
    void parallelismOne_downloadsOneAtATime() {
        write(slowFirst, 1, snapshot());

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void conceptReadFailure_failsTheWrite() {
        PublicationSnapshot base = snapshot();
        PublicationSnapshot failing = new PublicationSnapshot() {
            public List<Ontology> ontologies() { return base.ontologies(); }
            public Ontology withConcepts(Ontology o) {
                if (o.id() == 3) {
                    throw new IllegalStateException("PostgreSQL down");
                }
                return o;
            }
            public void forEachDiagram(java.util.function.Consumer<PublicationSnapshot.Diagram> action) { }
            public Set<String> draftConceptIris() { return Set.of(); }
            public Set<String> excludedConceptIris() { return Set.of(); }
        };

        assertThatThrownBy(() -> write(slowFirst, 4, failing)).hasMessage("PostgreSQL down");
    }

    private static PublicationSnapshot snapshot() {
        List<Ontology> ontologies = new ArrayList<>();
        for (long id = 1; id <= VOCABULARIES; id++) {
            ontologies.add(new Ontology(id, "https://x/" + id, "s" + id, true, null, null, T, T, List.of()));
        }
        return PublicationSnapshot.of(ontologies, List.of(), Set.of());
    }

    private record Written(JsonNode json, boolean complete) {

        List<String> iris() {
            return StreamSupport.stream(json.get("@graph").spliterator(), false)
                    .filter(n -> n.has("stav_obsahu")).map(n -> n.get("iri").asString()).toList();
        }

        String status(String iri) {
            return StreamSupport.stream(json.get("@graph").spliterator(), false)
                    .filter(n -> iri.equals(n.path("iri").asString(""))).findFirst().orElseThrow()
                    .get("stav_obsahu").asString();
        }
    }

    private static Written write(OntologyDownloadService downloads, int parallelism, PublicationSnapshot snapshot) {
        PublicationConfig config = new PublicationConfig();
        config.setDownloadParallelism(parallelism);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean complete = new VocabularyFileWriter(downloads, config).write(snapshot,
                new PublicationHeader(Instant.parse("2026-11-19T10:00:00Z"), true, "https://x/kontext.jsonld",
                        "https://data.dia.gov.cz/zdroj/ismd/"), out);
        return new Written(JsonMapper.builder().build().readTree(out.toString(StandardCharsets.UTF_8)), complete);
    }
}
