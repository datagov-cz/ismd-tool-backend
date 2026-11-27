package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The NKD answers a concept edit's link reconcile needs. A concept edit fetches them before its transaction
 * ({@link #prefetch}), so no NKD call runs while the row lock and the connection are held; other callers ask
 * NKD as they go ({@link #live}).
 *
 * <p>Fail-open either way: NKD trouble never fails a write. {@link #publishedAmong} reports a check NKD did not
 * answer as empty, so the caller can say it skipped; a concept that could not be fetched leaves its existing
 * snapshot as it is.
 */
public interface NkdLookup {

    /** Most IRIs in one batched NKD query. */
    int BATCH_SIZE = 200;

    /** The IRIs among {@code iris} NKD publishes, or empty when NKD did not answer for all of them. */
    Optional<Set<String>> publishedAmong(Collection<String> iris);

    /** What NKD said about each of {@code iris}, for callers that can act on a partial answer. */
    PublishedCheck check(Collection<String> iris);

    /** {@code unanswered}: the IRIs NKD gave no answer about. */
    record PublishedCheck(Set<String> published, Set<String> unanswered) {
    }

    /** NKD's copy of one concept, or empty when it could not be fetched. */
    Optional<Fetched> concept(String iri);

    /** {@code published} empty: NKD answered and has no such concept. {@code raw} empty: triples unavailable. */
    record Fetched(Optional<NkdSparqlClient.PublishedConcept> published, Optional<Model> raw) {
    }

    static NkdLookup live(NkdSparqlClient client) {
        return new Live(client);
    }

    /** Asks NKD about {@code iris} now and answers from memory afterwards; other IRIs have no answer. */
    static NkdLookup prefetch(NkdSparqlClient client, Collection<String> iris) {
        Live live = new Live(client);
        Optional<Set<String>> published = live.publishedAmong(iris);
        Map<String, Fetched> concepts = new HashMap<>();
        for (String iri : iris) {
            live.concept(iri).ifPresent(fetched -> concepts.put(iri, fetched));
        }
        return new Prefetched(Set.copyOf(iris), published, concepts);
    }

    /**
     * Asks NKD which of {@code iris} are published and fetches those, both in batches. A failed batch leaves
     * only its own IRIs unanswered.
     */
    static NkdLookup prefetchPublished(NkdSparqlClient client, Collection<String> iris) {
        Live live = new Live(client);
        Set<String> asked = new HashSet<>();
        Set<String> published = new HashSet<>();
        for (List<String> batch : batches(List.copyOf(new LinkedHashSet<>(iris)))) {
            live.publishedAmong(batch).ifPresent(found -> {
                asked.addAll(batch);
                published.addAll(found);
            });
        }
        Map<String, Fetched> concepts = new HashMap<>();
        for (List<String> batch : batches(iris.stream().distinct().filter(published::contains).toList())) {
            try {
                client.fetchPublishedConceptsWithRaw(batch).forEach((iri, concept) -> concepts.put(iri, new Fetched(
                        concept.map(NkdSparqlClient.PublishedConceptWithRaw::concept),
                        concept.map(NkdSparqlClient.PublishedConceptWithRaw::raw))));
            } catch (RuntimeException e) {
                log().warn("NKD unavailable fetching {} concept(s): {}", batch.size(), e.getMessage());
            }
        }
        return new Prefetched(Set.copyOf(asked), Optional.of(Set.copyOf(published)), concepts);
    }

    private static List<List<String>> batches(List<String> iris) {
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < iris.size(); from += BATCH_SIZE) {
            batches.add(iris.subList(from, Math.min(from + BATCH_SIZE, iris.size())));
        }
        return batches;
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(NkdLookup.class);
    }

    /** Knows nothing: every question answers as NKD not answering. */
    static NkdLookup none() {
        return new Prefetched(Set.of(), Optional.empty(), Map.of());
    }

    /**
     * Fetches NKD's copy of each of {@code iris} now, with its raw triples only where {@code needsRaw} says a
     * copy will be materialized from them. The published check is not asked: it answers as NKD not answering.
     */
    static NkdLookup prefetchConcepts(NkdSparqlClient client, Collection<String> iris, Predicate<String> needsRaw) {
        Live live = new Live(client);
        Map<String, Fetched> concepts = new HashMap<>();
        for (String iri : iris) {
            live.concept(iri, needsRaw.test(iri)).ifPresent(fetched -> concepts.put(iri, fetched));
        }
        return new Prefetched(Set.of(), Optional.empty(), concepts);
    }

    @Slf4j
    final class Live implements NkdLookup {
        private final NkdSparqlClient client;

        Live(NkdSparqlClient client) {
            this.client = client;
        }

        @Override
        public Optional<Set<String>> publishedAmong(Collection<String> iris) {
            if (iris.isEmpty()) {
                return Optional.of(Set.of());
            }
            try {
                return client.findPublishedAmong(List.copyOf(iris));
            } catch (RuntimeException e) {
                log.warn("NKD published-check failed: {}", e.getMessage());
                return Optional.empty();
            }
        }

        @Override
        public PublishedCheck check(Collection<String> iris) {
            return publishedAmong(iris)
                    .map(found -> new PublishedCheck(found, Set.<String>of()))
                    .orElseGet(() -> new PublishedCheck(Set.of(), Set.copyOf(iris)));
        }

        @Override
        public Optional<Fetched> concept(String iri) {
            return concept(iri, true);
        }

        Optional<Fetched> concept(String iri, boolean withRaw) {
            try {
                Optional<NkdSparqlClient.PublishedConcept> published = client.fetchPublishedConceptWithScheme(iri);
                Optional<Model> raw = withRaw && published.isPresent()
                        ? client.fetchPublishedConceptRaw(iri) : Optional.empty();
                return Optional.of(new Fetched(published, raw));
            } catch (RuntimeException e) {
                log.warn("NKD unavailable fetching {} — existing snapshot kept: {}", iri, e.getMessage());
                return Optional.empty();
            }
        }
    }

    /**
     * Answers from what was fetched. {@code asked}: the IRIs the published check covered; a question about any
     * other IRI answers as NKD not answering, never as "not published". {@code published} empty: NKD did not
     * answer the check.
     */
    record Prefetched(Set<String> asked, Optional<Set<String>> published, Map<String, Fetched> concepts)
            implements NkdLookup {

        @Override
        public Optional<Set<String>> publishedAmong(Collection<String> iris) {
            if (iris.isEmpty()) {
                return Optional.of(Set.of());
            }
            if (!asked.containsAll(iris)) {
                return Optional.empty();
            }
            return published.map(answer -> {
                Set<String> out = new HashSet<>(iris);
                out.retainAll(answer);
                return out;
            });
        }

        @Override
        public PublishedCheck check(Collection<String> iris) {
            if (published.isEmpty()) {
                return new PublishedCheck(Set.of(), Set.copyOf(iris));
            }
            Set<String> found = new HashSet<>(iris);
            found.retainAll(published.get());
            found.retainAll(asked);
            Set<String> unanswered = new HashSet<>(iris);
            unanswered.removeAll(asked);
            return new PublishedCheck(found, unanswered);
        }

        @Override
        public Optional<Fetched> concept(String iri) {
            return Optional.ofNullable(concepts.get(iri));
        }
    }
}
