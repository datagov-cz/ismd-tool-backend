# RDF migrations

SPARQL Update changesets for the ISMD vocabulary graphs in TDB2, next to the Liquibase changelog for
PostgreSQL (`../changelog/`). `RdfMigrationRunner` applies the pending
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
- **Preconditions** (checked only when something is pending; each failure stops startup before any graph is
  touched): the outbox has no PENDING or FAILED rows (they hold pre-migration RDF the relay would write over
  the migrated data), and every vocabulary graph in PostgreSQL exists in TDB2. TDB2 graphs with no vocabulary
  row are logged and left alone.
- **Deploy:** stop the old instance before starting the new one, so nothing writes old shapes while the
  migration runs, and let it drain the outbox first.
- `?graph` is substituted in the parsed update (Jena `UpdateTransformOps`), so it is never replaced inside a
  string literal or a comment; a file using it only there is rejected.
- Settings: `rdf-migration.enabled` (`RDF_MIGRATION_ENABLED`, on by default; off only in test contexts),
  `rdf-migration.update-timeout` (`RDF_MIGRATION_UPDATE_TIMEOUT`, default `PT30M`, per graph update; a
  timeout stops startup, and Fuseki may still finish the update, so idempotency matters here too).
