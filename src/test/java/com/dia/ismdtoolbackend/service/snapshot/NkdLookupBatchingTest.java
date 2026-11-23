package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NkdLookupBatchingTest {

    private final NkdSparqlClient client = mock(NkdSparqlClient.class);

    private static List<String> iris(int count) {
        return IntStream.range(0, count).mapToObj(i -> "https://example.org/pojem/" + i).toList();
    }

    @Test
    void asksInBatchesOfAtMostTheBatchSize() {
        when(client.findPublishedAmong(anyList())).thenReturn(Optional.of(Set.of()));

        NkdLookup.prefetchPublished(client, iris(NkdLookup.BATCH_SIZE * 2 + 1));

        verify(client, times(3)).findPublishedAmong(anyList());
        verify(client, never()).fetchPublishedConceptsWithRaw(anyList());
    }

    @Test
    void aRepeatedIriIsAskedOnce() {
        String iri = iris(1).get(0);
        when(client.findPublishedAmong(List.of(iri))).thenReturn(Optional.of(Set.of()));

        NkdLookup.prefetchPublished(client, List.of(iri, iri));

        verify(client).findPublishedAmong(List.of(iri));
    }

    @Test
    void aFailedCheckBatchLeavesOnlyItsOwnIrisUnanswered() {
        List<String> all = iris(NkdLookup.BATCH_SIZE + 1);
        String inFailedBatch = all.get(0);
        String inAnsweredBatch = all.get(NkdLookup.BATCH_SIZE);
        when(client.findPublishedAmong(all.subList(0, NkdLookup.BATCH_SIZE))).thenReturn(Optional.empty());
        when(client.findPublishedAmong(List.of(inAnsweredBatch))).thenReturn(Optional.of(Set.of(inAnsweredBatch)));
        when(client.fetchPublishedConceptsWithRaw(List.of(inAnsweredBatch))).thenReturn(Map.of());

        NkdLookup.PublishedCheck check = NkdLookup.prefetchPublished(client, all)
                .check(List.of(inFailedBatch, inAnsweredBatch));

        assertThat(check.published()).containsExactly(inAnsweredBatch);
        assertThat(check.unanswered()).containsExactly(inFailedBatch);
    }

    @Test
    void fetchesOnlyPublishedConcepts_inOneBatch_withDetailAndRaw() {
        List<String> all = iris(3);
        Model raw = ModelFactory.createDefaultModel();
        var concept = new NkdSparqlClient.PublishedConcept(null, "https://example.org/g");
        when(client.findPublishedAmong(anyList())).thenReturn(Optional.of(Set.of(all.get(0), all.get(2))));
        when(client.fetchPublishedConceptsWithRaw(List.of(all.get(0), all.get(2)))).thenReturn(Map.of(
                all.get(0), Optional.of(new NkdSparqlClient.PublishedConceptWithRaw(concept, raw)),
                all.get(2), Optional.empty()));

        NkdLookup lookup = NkdLookup.prefetchPublished(client, all);

        assertThat(lookup.concept(all.get(0)).orElseThrow().raw()).containsSame(raw);
        assertThat(lookup.concept(all.get(2)).orElseThrow().published()).isEmpty();
        assertThat(lookup.concept(all.get(1))).isEmpty();
    }

    @Test
    void aFailedFetchBatchKeepsTheCheckAndTheOtherBatches() {
        List<String> all = iris(NkdLookup.BATCH_SIZE + 1);
        String lastIri = all.get(NkdLookup.BATCH_SIZE);
        var concept = new NkdSparqlClient.PublishedConcept(null, null);
        when(client.findPublishedAmong(all.subList(0, NkdLookup.BATCH_SIZE)))
                .thenReturn(Optional.of(Set.copyOf(all.subList(0, NkdLookup.BATCH_SIZE))));
        when(client.findPublishedAmong(List.of(lastIri))).thenReturn(Optional.of(Set.of(lastIri)));
        when(client.fetchPublishedConceptsWithRaw(all.subList(0, NkdLookup.BATCH_SIZE)))
                .thenThrow(new SparqlEndpointUnavailableException("NKD", "down", null));
        when(client.fetchPublishedConceptsWithRaw(List.of(lastIri))).thenReturn(Map.of(lastIri, Optional.of(
                new NkdSparqlClient.PublishedConceptWithRaw(concept, ModelFactory.createDefaultModel()))));

        NkdLookup lookup = NkdLookup.prefetchPublished(client, all);

        assertThat(lookup.check(all).unanswered()).isEmpty();
        assertThat(lookup.concept(all.get(0))).isEmpty();
        assertThat(lookup.concept(lastIri)).isPresent();
    }

    @Test
    void nkdAnsweringNothingLeavesEveryIriUnanswered() {
        List<String> all = iris(2);
        when(client.findPublishedAmong(anyList())).thenReturn(Optional.empty());

        NkdLookup lookup = NkdLookup.prefetchPublished(client, all);

        assertThat(lookup.publishedAmong(all)).isEmpty();
        assertThat(lookup.check(all).unanswered()).containsExactlyInAnyOrderElementsOf(all);
    }
}
