package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchCondition;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchRequest;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchResult;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.Suggestions;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search by act name: per query, the suggester ({@code /jednoducha-vyhledavani/nabidka}, the only
 * source matching alternative names such as "katastrální zákon") and a title search limited to acts
 * in force ({@code NAZEV_PRAVNIHO_AKTU OBSAHUJE}, which supplies the in-force status). The calls run in
 * parallel, each paced by the transport's rate gate; {@link EsbirkaLawNameRanking} merges and ranks.
 */
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaRestNameSearch {

    /** Rows asked from each source; upstream latency does not depend on it up to 100. */
    static final int ROWS_PER_SOURCE = 100;
    /** Two sources × at most two query variants. */
    private static final int MAX_CALLS = 4;

    private final EsbirkaRestTransport transport;
    private final ExecutorService calls;

    EsbirkaRestNameSearch(EsbirkaRestTransport transport) {
        this.transport = transport;
        AtomicInteger threadNumber = new AtomicInteger();
        this.calls = Executors.newFixedThreadPool(MAX_CALLS, runnable -> {
            Thread thread = new Thread(runnable, "esbirka-rest-name-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }

    List<LawModel> search(String q, int limit, EsbirkaDeadline deadline) {
        List<CompletableFuture<Suggestions>> suggested = new ArrayList<>();
        List<CompletableFuture<SearchResult>> titled = new ArrayList<>();
        for (String sent : EsbirkaLawNameRanking.queries(q)) {
            suggested.add(CompletableFuture.supplyAsync(() -> suggest(sent, deadline), calls));
            titled.add(CompletableFuture.supplyAsync(() -> titleSearch(sent, deadline), calls));
        }
        return EsbirkaLawNameRanking.rank(q, suggested.stream().map(EsbirkaRestNameSearch::join).toList(),
                titled.stream().map(EsbirkaRestNameSearch::join).toList(), limit);
    }

    static SearchRequest titleRequest(String q) {
        return new SearchRequest(0, ROWS_PER_SOURCE, null, List.of("PLATNE"),
                List.of(new SearchCondition("NAZEV_PRAVNIHO_AKTU", "OBSAHUJE", q, "AND")));
    }

    private Suggestions suggest(String q, EsbirkaDeadline deadline) {
        return transport.get("nabidka", transport.uri("/jednoducha-vyhledavani/nabidka?text="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20")
                        + "&maxPocet=" + ROWS_PER_SOURCE),
                Suggestions.class, deadline);
    }

    private SearchResult titleSearch(String q, EsbirkaDeadline deadline) {
        return transport.post(transport.uri("/rozsirena-vyhledavani"), titleRequest(q), SearchResult.class, deadline);
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}