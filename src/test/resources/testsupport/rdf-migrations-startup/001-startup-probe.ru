# Test-only changeset for RdfMigrationStartupTest: makes one changeset pending.
INSERT { GRAPH ?graph { ?graph <https://example.org/probe> true } } WHERE { GRAPH ?graph { ?graph ?p ?o } }
