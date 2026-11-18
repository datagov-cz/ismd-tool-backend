package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** The IRIs among {@code iris} NKD publishes, or empty when NKD did not answer. */
    Optional<Set<String>> publishedAmong(Collection<String> iris);

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
        return new Prefetched(published, concepts);
    }

    /** Asks NKD only which of {@code iris} are published; concept fetches have no answer. */
    static NkdLookup prefetchPublished(NkdSparqlClient client, Collection<String> iris) {
        return new Prefetched(new Live(client).publishedAmong(iris), Map.of());
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
        public Optional<Fetched> concept(String iri) {
            try {
                Optional<NkdSparqlClient.PublishedConcept> published = client.fetchPublishedConceptWithScheme(iri);
                Optional<Model> raw = published.isPresent() ? client.fetchPublishedConceptRaw(iri) : Optional.empty();
                return Optional.of(new Fetched(published, raw));
            } catch (RuntimeException e) {
                log.warn("NKD unavailable fetching {} — existing snapshot kept: {}", iri, e.getMessage());
                return Optional.empty();
            }
        }
    }

    /** {@code published} empty: NKD did not answer the prefetch. */
    record Prefetched(Optional<Set<String>> published, Map<String, Fetched> concepts) implements NkdLookup {

        @Override
        public Optional<Set<String>> publishedAmong(Collection<String> iris) {
            if (iris.isEmpty()) {
                return Optional.of(Set.of());
            }
            return published.map(answer -> {
                Set<String> out = new HashSet<>(iris);
                out.retainAll(answer);
                return out;
            });
        }

        @Override
        public Optional<Fetched> concept(String iri) {
            return Optional.ofNullable(concepts.get(iri));
        }
    }
}
