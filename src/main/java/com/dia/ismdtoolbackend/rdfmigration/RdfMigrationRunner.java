package com.dia.ismdtoolbackend.rdfmigration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies pending RDF changesets at startup, like Liquibase does for PostgreSQL. It runs once every
 * singleton exists (Liquibase has run), and before the context refresh completes, so before the
 * schedulers (outbox relay, reconciler) start and before the web server accepts requests.
 *
 * <p>Each changeset is applied to every ISMD vocabulary graph, then recorded in
 * {@code rdf_migration_log}. TDB2 and PostgreSQL share no transaction: a crash after the TDB2 update
 * and before the row reruns the changeset, which is why every changeset must be idempotent. Any
 * failure, and any applied changeset whose file has changed, fails startup.
 */
@Slf4j
public class RdfMigrationRunner implements SmartInitializingSingleton {

    private final RdfChangesetLoader loader;
    private final RdfMigrationRepository history;
    private final RdfMigrationTarget target;
    private final Clock clock;

    public RdfMigrationRunner(RdfChangesetLoader loader, RdfMigrationRepository history,
                              RdfMigrationTarget target, Clock clock) {
        this.loader = loader;
        this.history = history;
        this.target = target;
        this.clock = clock;
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
        List<RdfChangeset> pending = changesets.stream().filter(c -> !applied.containsKey(c.id())).toList();
        if (pending.isEmpty()) {
            log.info("RDF migrations: {} changeset(s), none pending", changesets.size());
            return List.of();
        }

        List<String> graphs = target.graphNames();
        log.info("RDF migrations: applying {} pending changeset(s) to {} graph(s)", pending.size(), graphs.size());
        for (RdfChangeset changeset : pending) {
            apply(changeset, graphs);
        }
        return pending.stream().map(RdfChangeset::id).toList();
    }

    private void apply(RdfChangeset changeset, List<String> graphs) {
        long started = clock.millis();
        for (String graph : graphs) {
            try {
                target.update(changeset.boundTo(graph));
            } catch (RuntimeException e) {
                throw new IllegalStateException("RDF migration " + changeset.id() + " failed on graph " + graph
                        + ": " + e.getMessage(), e);
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
