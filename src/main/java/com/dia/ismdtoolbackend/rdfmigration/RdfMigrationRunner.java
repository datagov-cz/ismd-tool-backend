package com.dia.ismdtoolbackend.rdfmigration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies pending RDF changesets at startup, like Liquibase does for PostgreSQL. It runs once every
 * singleton exists (Liquibase has run), and before the context refresh completes, so before the
 * schedulers (outbox relay, reconciler) start and before the web server accepts requests.
 *
 * <p>Before a pending changeset runs, the outbox must be settled (its rows hold pre-migration RDF the
 * relay would write back afterwards) and every vocabulary graph must exist in TDB2 (a missing or
 * differently spelled graph would match nothing and still count as migrated).
 *
 * <p>Each changeset is applied to every ISMD vocabulary graph, then recorded in
 * {@code rdf_migration_log}. TDB2 and PostgreSQL share no transaction: a crash after the TDB2 update
 * and before the row reruns the changeset, which is why every changeset must be idempotent. Any
 * failure, a timed-out update, and an applied changeset whose file has changed all fail startup.
 */
@Slf4j
public class RdfMigrationRunner implements SmartInitializingSingleton {

    private final RdfChangesetLoader loader;
    private final RdfMigrationRepository history;
    private final RdfMigrationTarget target;
    private final Clock clock;
    private final Duration updateTimeout;

    public RdfMigrationRunner(RdfChangesetLoader loader, RdfMigrationRepository history,
                              RdfMigrationTarget target, Clock clock, Duration updateTimeout) {
        this.loader = loader;
        this.history = history;
        this.target = target;
        this.clock = clock;
        this.updateTimeout = updateTimeout;
    }

    @Override
    public void afterSingletonsInstantiated() {
        migrate();
    }

    /** Applies the pending changesets. Returns the ids applied, in order. */
    public List<String> migrate() {
        List<RdfChangeset> changesets = loader.load();
        Map<String, RdfMigrationEntity> applied = history.findAll().stream()
                .collect(Collectors.toMap(RdfMigrationEntity::getId, Function.identity()));
        for (RdfChangeset changeset : changesets) {
            RdfMigrationEntity done = applied.get(changeset.id());
            if (done != null && !done.getChecksum().equals(changeset.checksum())) {
                throw new IllegalStateException("RDF migration " + changeset.id()
                        + " was changed after it was applied (checksum " + done.getChecksum() + " -> "
                        + changeset.checksum() + "). Add a new changeset instead.");
            }
        }
        Set<String> bundled = changesets.stream().map(RdfChangeset::id).collect(Collectors.toSet());
        applied.keySet().stream().filter(id -> !bundled.contains(id)).sorted().forEach(id ->
                log.warn("RDF migration {} is recorded as applied but its changeset file is missing", id));
        List<RdfChangeset> pending = changesets.stream().filter(c -> !applied.containsKey(c.id())).toList();
        if (pending.isEmpty()) {
            log.info("RDF migrations: {} changeset(s), none pending", changesets.size());
            return List.of();
        }

        List<String> graphs = checkPreconditions(pending);
        log.info("RDF migrations: applying {} pending changeset(s) to {} graph(s)", pending.size(), graphs.size());
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rdf-migration");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (RdfChangeset changeset : pending) {
                apply(changeset, graphs, executor);
            }
        } finally {
            executor.shutdownNow();
        }
        return pending.stream().map(RdfChangeset::id).toList();
    }

    private List<String> checkPreconditions(List<RdfChangeset> pending) {
        long unsettled = target.unsettledWrites();
        if (unsettled > 0) {
            throw new IllegalStateException("RDF migrations: " + unsettled + " outbox row(s) are PENDING or FAILED. "
                    + "They hold pre-migration RDF that would be written over the migrated data. Let the previous "
                    + "version drain the outbox (or resolve the FAILED rows), then start again. Pending changesets: "
                    + pending.stream().map(RdfChangeset::id).toList());
        }
        List<String> graphs = target.graphNames();
        Set<String> stored = new HashSet<>(target.storedGraphNames());
        List<String> missing = graphs.stream().filter(graph -> !stored.contains(graph)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("RDF migrations: vocabulary graph(s) in PostgreSQL but not in TDB2: "
                    + missing + ". Fix the graph names (or the data) first; a changeset would match nothing there.");
        }
        List<String> untracked = new ArrayList<>(stored);
        untracked.removeAll(new HashSet<>(graphs));
        if (!untracked.isEmpty()) {
            log.warn("RDF migrations: TDB2 graph(s) with no vocabulary in PostgreSQL are not migrated: {}", untracked);
        }
        return graphs;
    }

    private void apply(RdfChangeset changeset, List<String> graphs, ExecutorService executor) {
        long started = clock.millis();
        for (String graph : graphs) {
            String update = changeset.boundTo(graph);
            Future<?> running = executor.submit(() -> target.update(update));
            try {
                running.get(updateTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                running.cancel(true);
                throw new IllegalStateException("RDF migration " + changeset.id() + " timed out after "
                        + updateTimeout + " on graph " + graph + "; Fuseki may still finish it, so the "
                        + "changeset must be idempotent (it reruns on the next startup)", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("RDF migration " + changeset.id() + " failed on graph " + graph
                        + ": " + e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("RDF migration " + changeset.id() + " interrupted", e);
            }
        }
        RdfMigrationEntity row = new RdfMigrationEntity();
        row.setId(changeset.id());
        row.setChecksum(changeset.checksum());
        row.setDescription(changeset.description().length() > 1024
                ? changeset.description().substring(0, 1024) : changeset.description());
        row.setAppliedAt(LocalDateTime.now(clock));
        row.setGraphsProcessed(graphs.size());
        row.setExecutionMs(clock.millis() - started);
        history.save(row);
        log.info("RDF migration {} applied to {} graph(s) in {} ms", changeset.id(), graphs.size(), row.getExecutionMs());
    }
}
