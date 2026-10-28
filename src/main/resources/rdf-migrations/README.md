# RDF migrations

SPARQL Update changesets for the ISMD vocabulary graphs in TDB2. `RdfMigrationRunner` applies the pending
ones at startup, like Liquibase does for PostgreSQL: after Liquibase, before the schedulers (outbox relay,
reconciler) start and before the server takes requests. A failure fails startup.

- **File:** `NNN-short-name.ru` (three digits, lower case). The id is the name without `.ru`; changesets run in id
  order. Start the file with `#` comment lines saying what it changes and why; they become the logged description.
- **Scope:** the update runs once per vocabulary graph (from `ismd_schema.ontologies.graph_name`), with `?graph`
  bound to that graph's IRI. Every changeset must use `?graph`. One request per graph, atomic per graph.
- **Idempotent:** a crash between the TDB2 update and the `rdf_migration_log` row reruns the changeset, and so
  does a failure on a later graph. Running a changeset twice must change nothing the second time.
- **Never edit an applied changeset.** Its SHA-256 is recorded; a changed file fails startup. Add a new one.
- **Before merging:** a test that runs the changeset on an in-memory dataset (old shapes in, OFN shapes out, a
  second run changes nothing), plus a run over the local graph dumps with gate counts before and after. There is
  no dry-run mode: changesets apply on the first startup in every environment.
- Switch: `rdf-migration.enabled` (`RDF_MIGRATION_ENABLED`), on by default; off only in test contexts.
