package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistSnapshot;
import com.dia.ismdtoolbackend.utility.codelist.CodelistDistributionReader;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the NKOD codelist datasets in memory, each with the codelist IRI read from its current
 * distribution file.
 *
 * <p>Follows {@link NkodDatasetSnapshotHolder}: warm off the request path once ready, serve
 * stale on refresh failure, and refresh ahead of TTL expiry.
 *
 * <p>A refresh lists the catalogue, then reads every entry's distribution in parallel. The
 * catalogue part is all-or-nothing; the file part never fails the refresh. An entry whose file
 * can't be read keeps the previous snapshot's IRI when its download URLs are unchanged, so a
 * transient publisher outage doesn't unresolve it. Unresolved entries stay in the snapshot:
 * absence from it means "gone from the catalogue", never "file unreadable".
 */
@Component
@Slf4j
public class NkodCodelistSnapshotHolder {

    private final NkodSparqlClient client;
    private final CodelistDistributionReader reader;
    private final Clock clock;
    private final Duration ttl;
    private final int concurrency;
    private final Duration fileTimeout;
    private final ExecutorService fetchPool;

    private final AtomicReference<NkodCodelistSnapshot> current =
            new AtomicReference<>(NkodCodelistSnapshot.empty());
    private final Object refreshLock = new Object();

    public NkodCodelistSnapshotHolder(NkodSparqlClient client, CodelistDistributionReader reader,
                                      Clock clock, NkodConfig config) {
        this.client = client;
        this.reader = reader;
        this.clock = clock;
        this.ttl = Duration.ofHours(config.getCodelist().getTtlHours());
        this.concurrency = Math.max(1, config.getCodelist().getDistributionConcurrency());
        this.fileTimeout = Duration.ofMillis(config.getCodelist().getDistributionTimeoutMs());
        AtomicInteger threadNo = new AtomicInteger();
        this.fetchPool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "nkod-codelist-" + threadNo.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        fetchPool.shutdownNow();
    }

    /** Fresh snapshot, rebuilding synchronously if the current one is stale or empty. */
    public NkodCodelistSnapshot get() {
        NkodCodelistSnapshot snap = current.get();
        if (isFresh(snap)) {
            return snap;
        }
        return refreshUnderLock();
    }

    /** Current snapshot without triggering a rebuild; empty until the first refresh succeeds. */
    public NkodCodelistSnapshot peek() {
        return current.get();
    }

    /** Looks the dataset up in {@link #peek()}; never fetches. */
    public Optional<NkodCodelistEntry> findByDatasetIri(String datasetIri) {
        return peek().find(datasetIri);
    }

    /**
     * Builds off the request path once the app is ready. {@code @Async} keeps it off the boot
     * thread; a failure is swallowed and {@link #get()} or the scheduled refresh retry later.
     */
    @Async("snapshotExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!client.isEndpointConfigured()) {
            log.info("NKOD endpoint not configured; skipping codelist warm-up.");
            return;
        }
        try {
            log.info("NKOD codelist warm-up starting");
            forceRefresh();
        } catch (Exception e) {
            log.warn("NKOD codelist warm-up failed; will build lazily on first request. cause={}",
                    e.getMessage());
        }
    }

    /** Rebuilds ahead of TTL expiry, regardless of freshness. */
    @Scheduled(cron = "${nkod.codelist.refresh-cron:0 15 */12 * * *}")
    public void scheduledRefresh() {
        if (!client.isEndpointConfigured()) {
            return;
        }
        try {
            forceRefresh();
        } catch (Exception e) {
            log.warn("Scheduled NKOD codelist refresh failed; keeping existing snapshot. cause={}",
                    e.getMessage());
        }
    }

    private NkodCodelistSnapshot forceRefresh() {
        synchronized (refreshLock) {
            return attemptRefresh(current.get());
        }
    }

    private NkodCodelistSnapshot refreshUnderLock() {
        synchronized (refreshLock) {
            NkodCodelistSnapshot snap = current.get();
            if (isFresh(snap)) {
                return snap;
            }
            return attemptRefresh(snap);
        }
    }

    /**
     * Serves the existing snapshot when a refresh fails, so a flaky endpoint never evicts good
     * data. An empty catalogue result counts as a failure when there is data to keep.
     */
    private NkodCodelistSnapshot attemptRefresh(NkodCodelistSnapshot existing) {
        try {
            List<NkodCodelistEntry> listed = client.fetchCodelists();
            if (listed.isEmpty() && !existing.isEmpty()) {
                log.warn("NKOD codelist refresh returned no datasets; keeping snapshot loadedAt={}",
                        existing.getLoadedAt());
                return existing;
            }
            NkodCodelistSnapshot fresh = NkodCodelistSnapshot.of(clock.instant(), resolve(listed, existing));
            current.set(fresh);
            return fresh;
        } catch (SparqlEndpointUnavailableException e) {
            if (!existing.isEmpty()) {
                log.warn("NKOD codelist refresh failed; serving stale snapshot loadedAt={}. cause={}",
                        existing.getLoadedAt(), e.getMessage());
                return existing;
            }
            throw e;
        }
    }

    /**
     * Reads every entry's codelist IRI on the fetch pool. The whole phase is bounded — each
     * entry's URLs get one file timeout apiece, spread over the pool — and entries still running
     * at the bound are cancelled and treated as unreadable.
     */
    private List<NkodCodelistEntry> resolve(List<NkodCodelistEntry> listed, NkodCodelistSnapshot previous) {
        long t0 = System.currentTimeMillis();
        List<Callable<Optional<String>>> tasks = new ArrayList<>(listed.size());
        int urlCount = 0;
        for (NkodCodelistEntry entry : listed) {
            tasks.add(() -> readFirst(entry));
            urlCount += entry.downloadUrls().size();
        }
        long rounds = (urlCount + concurrency - 1) / concurrency + 1;
        long budgetMs = fileTimeout.toMillis() * rounds;

        List<Future<Optional<String>>> futures;
        try {
            futures = fetchPool.invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures = List.of();
        }

        List<NkodCodelistEntry> resolved = new ArrayList<>(listed.size());
        int read = 0;
        int carried = 0;
        for (int i = 0; i < listed.size(); i++) {
            NkodCodelistEntry entry = listed.get(i);
            Optional<String> iri = i < futures.size() ? outcome(futures.get(i), entry) : Optional.empty();
            if (iri.isPresent()) {
                read++;
                resolved.add(entry.withCodeListIri(iri.get()));
                continue;
            }
            String previousIri = carriedOver(entry, previous);
            if (previousIri != null) {
                carried++;
            }
            resolved.add(entry.withCodeListIri(previousIri));
        }
        log.info("NKOD codelist refresh: datasets={} resolved={} carriedOver={} unresolved={} in {} ms",
                listed.size(), read, carried, listed.size() - read - carried, System.currentTimeMillis() - t0);
        return resolved;
    }

    private Optional<String> readFirst(NkodCodelistEntry entry) {
        for (String url : entry.downloadUrls()) {
            if (Thread.currentThread().isInterrupted()) {
                return Optional.empty();
            }
            Optional<String> iri = reader.readCodeListIri(url);
            if (iri.isPresent()) {
                return iri;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> outcome(Future<Optional<String>> future, NkodCodelistEntry entry) {
        try {
            return future.get();
        } catch (CancellationException e) {
            log.warn("Codelist distribution read for {} timed out", entry.datasetIri());
        } catch (ExecutionException e) {
            log.warn("Codelist distribution read for {} failed: {}", entry.datasetIri(), e.getCause().toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** The previous IRI, only when the entry still points at the same distribution files. */
    private static String carriedOver(NkodCodelistEntry entry, NkodCodelistSnapshot previous) {
        return previous.find(entry.datasetIri())
                .filter(NkodCodelistEntry::isResolved)
                .filter(prev -> prev.downloadUrls().equals(entry.downloadUrls()))
                .map(NkodCodelistEntry::codeListIri)
                .orElse(null);
    }

    private boolean isFresh(NkodCodelistSnapshot snap) {
        return !snap.isEmpty() && !snap.isStale(ttl, clock);
    }
}
