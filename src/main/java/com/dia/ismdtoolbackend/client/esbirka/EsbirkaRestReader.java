package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cached whole-version and version-list reads. Separate from {@link EsbirkaRestClient} so Spring's
 * caching proxy intercepts the calls; {@code sync} makes concurrent cold loads of one key share a
 * single fetch. A missing document throws
 * {@link com.dia.ismdtoolbackend.exception.EsbirkaDocumentNotFoundException}, which is not cached.
 */
@Component
@ConditionalOnProperty(name = "esbirka.provider", havingValue = "rest")
class EsbirkaRestReader {

    private final EsbirkaRestTransport transport;
    /** Fetches pages 1..n of a version; the transport's rate gate still paces every request. */
    private final ExecutorService pageFetcher;

    EsbirkaRestReader(EsbirkaRestTransport transport, EsbirkaConfig config) {
        this.transport = transport;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pageFetcher = Executors.newFixedThreadPool(Math.max(1, config.getRest().getMaxInFlight()), runnable -> {
            Thread thread = new Thread(runnable, "esbirka-rest-page-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        pageFetcher.shutdownNow();
    }

    /**
     * Every fragment of a version, from all its pages: the first page carries the page count
     * ({@code pocetStranek}), the rest are fetched in parallel. There is no partial read: no
     * endpoint returns one fragment's text, and a page cannot be located from a fragment.
     */
    @Cacheable(cacheNames = "esbirkaVersionContent", key = "#versionStaleUrl", sync = true)
    public List<FragmentModel> versionContent(String versionStaleUrl, EsbirkaDeadline deadline) {
        EsbirkaRestDtos.FragmentPage first = page(versionStaleUrl, 0, deadline);
        List<EsbirkaRestDtos.FragmentPage> pages = new ArrayList<>();
        pages.add(first);
        List<CompletableFuture<EsbirkaRestDtos.FragmentPage>> rest = new ArrayList<>();
        for (int n = 1; n < first.pocetStranek(); n++) {
            int pageNumber = n;
            rest.add(CompletableFuture.supplyAsync(() -> page(versionStaleUrl, pageNumber, deadline), pageFetcher));
        }
        for (CompletableFuture<EsbirkaRestDtos.FragmentPage> future : rest) {
            pages.add(join(future));
        }
        return List.copyOf(EsbirkaRestMapper.toFragments(pages));
    }

    @Cacheable(cacheNames = "esbirkaRestVersions", key = "#lawStaleUrl", sync = true)
    public List<LawVersionModel> versions(String lawStaleUrl, EsbirkaDeadline deadline) {
        EsbirkaRestDtos.Historie historie = transport.get("historie",
                transport.uri("/dokumenty-sbirky/" + StaleUrls.encodePathSegment(lawStaleUrl) + "/historie"),
                EsbirkaRestDtos.Historie.class, deadline);
        return List.copyOf(EsbirkaRestMapper.toVersions(historie));
    }

    private EsbirkaRestDtos.FragmentPage page(String versionStaleUrl, int pageNumber, EsbirkaDeadline deadline) {
        URI uri = transport.uri("/dokumenty-sbirky/" + StaleUrls.encodePathSegment(versionStaleUrl)
                + "/fragmenty?cisloStranky=" + pageNumber);
        return transport.get("fragmenty", uri, EsbirkaRestDtos.FragmentPage.class, deadline);
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
