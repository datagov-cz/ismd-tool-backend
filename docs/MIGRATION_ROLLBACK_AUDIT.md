# Migration Rollback Audit

Reversibility of every Liquibase changeset, and the rule that keeps version rollback safe.

Audited 2026-09-07 against Liquibase 5.0.3.

## How migrations run today

Migrations run **in-process at application startup** (`spring-boot-starter-liquibase`). Deployment is
`az containerapp update --image <tag>` against an Azure Container App. There is no separate migration
step, so there is no existing hook where DevOps could invert a migration independently of the app.

Rolling back a version today means redeploying the previous image tag. Liquibase does not
un-apply anything on that path — the schema stays at the newer state and the older code runs
against it.

## Reversibility matrix

`auto-rollback` is Liquibase's own `Change.supportsRollback()` — whether it can invert the change
with no explicit `rollback:` block. Verified by direct probe against liquibase-core 5.0.3, not from
documentation.

| Change type | Used in | Auto-rollback | Old app version tolerates it |
|---|---|---|---|
| `createTable` | 002, 003, 004, 005, 007, 009-snapshots | ✅ yes | ✅ yes — unknown table is ignored |
| `createIndex` | 007, 008, 009-snapshots, 010, 011 | ✅ yes | ✅ yes — invisible to the app |
| `createSequence` | 007 | ✅ yes | ✅ yes |
| `addColumn` | 009-validation-status, 010 | ✅ yes | ✅ yes — nullable, unknown column ignored |
| `addForeignKeyConstraint` | 003, 009-snapshots, 010 | ✅ yes | ⚠️ only if old writes satisfy it |
| `addUniqueConstraint` | 002 | ✅ yes | ⚠️ only if old writes satisfy it |
| `sql` (raw) | 001, 006, 010 ×2 | ❌ no | depends on statement |
| `delete` (rows) | 010 | ❌ no | ✅ schema-wise, but **data is gone** |
| `dropColumn` | 010 ×2 | ❌ no | ❌ **breaks old code** |

### Per-file verdict

| Changelog | Reversible? |
|---|---|
| `001-create-schema` | Raw `CREATE SCHEMA IF NOT EXISTS`. No auto-rollback, but harmless to leave. |
| `002-create-ontologies` | ✅ Fully invertible. |
| `003-create-concepts` | ✅ Fully invertible. |
| `004-create-comments` | ✅ Fully invertible. |
| `005-create-validation-reports` | ✅ Fully invertible. |
| `006-add-unaccent-extension` | Raw `CREATE EXTENSION`. Not auto-invertible; harmless to leave in place. |
| `007-create-outbox` | ✅ Fully invertible. |
| `008-outbox-aggregate-index` | ✅ Fully invertible. |
| `009-add-ontology-validation-status` | ✅ Invertible; columns are nullable so old code is unaffected. |
| `009-create-nkd-concept-snapshots` | ✅ Fully invertible. |
| `010-comments-metadata-fk` | ❌ **IRREVERSIBLE.** See below. |
| `011-graph-name-indexes` | ✅ Fully invertible. |

### Why `010-comments-metadata-fk` is the hard case

It is the one changelog no rollback script can undo, for three independent reasons:

1. **`dropColumn ontologyiri` / `conceptiri`** — the old string locators are dropped. Liquibase
   reports `supportsRollback=false`. Even a hand-written rollback could only recreate *empty*
   columns; the IRI values are not stored anywhere else.
2. **Two backfill `UPDATE`s** — raw SQL, opaque to Liquibase, and the pre-update values are not
   captured.
3. **`delete` of orphaned comments** — rows are permanently removed with nothing recording them.

The only real recovery for a change of this shape is **point-in-time restore** of the Postgres
server, accepting data loss back to the restore point.

## The rule

> **A migration must never break the previous application version.**

This makes image rollback safe with no database action at all — which is exactly what
`az containerapp update --image <previous-tag>` already provides. It replaces "how do we un-apply
migrations" with "we never need to."

**Additive changes are always fine:** `createTable`, `createIndex`, `createSequence`, and *nullable*
`addColumn`. The previous version simply ignores what it does not know about.

**Destructive changes are split across two releases (expand/contract):**

- **Release N** — add the new column/table, dual-write, backfill. Additive only.
- **Release N+1** — once N is confirmed stable and you will not roll back past it, drop the old
  column in a follow-up PR.

Between N and N+1 the old column still exists, so rolling back to N−1 is safe. `010` should have
been two releases: add the FK columns and backfill in one, drop the IRI columns in the next.

### Rollback floors

When a destructive change genuinely cannot wait, mark the changelog with a waiver:

```yaml
# ROLLBACK-UNSAFE: <reason> — image rollback past this release requires PITR.
```

The CI gate then passes but prints a **ROLLBACK FLOOR** notice. DevOps must know that rolling back
past that release is a restore-from-backup operation, not an image swap.

## Enforcement

Enforced mechanically by `.github/scripts/check-migration-compat.sh`, wired into the PR workflow —
not left to review discipline. It inspects **only lines added by the PR**, so existing history never
re-trips it, and flags three classes — but only against **tables that already exist on the base
branch** (see "Table provenance" below):

1. Destructive structural change types (`dropColumn`, `dropTable`, `renameColumn`,
   `modifyDataType`, `addNotNullConstraint`, …).
2. Raw SQL containing `DROP`/`TRUNCATE`.
3. Irreversible data changes (`delete`, raw `UPDATE`/`DELETE FROM`).

YAML comments and `id:`/`author:`/`*Name:` keys are skipped, so prose like "drop the old column" or a
changeset id of `010-comments-drop-iri-columns` does not trip it.

Verified against real history:

| Commit | Content | Result |
|---|---|---|
| `efb2796` | `010` FK + dropColumn + backfill + delete | ❌ exit 1 (2 dropColumn, 3 data changes) |
| `6b159b6` | `011` graph-name indexes | ✅ exit 0 |
| `008d160` | `007`/`008` outbox createTable | ✅ exit 0 |
| `efb2796` + waiver | same, with `ROLLBACK-UNSAFE` | ✅ exit 0, flagged as rollback floor |

Run locally against the base branch:

```bash
.github/scripts/check-migration-compat.sh origin/dev
```

### Table provenance — why greenfield features are exempt

Only a table that **already exists on the base branch** can break the previous app version. The old
code has no knowledge of a table introduced by the PR under review, so dropping a column from it is
harmless. Without this rule, any new feature that iterates on its own schema across several
changelogs is flagged as destructive, and a gate that fires on every greenfield feature gets
switched off.

The rule is scoped by **table**, not deferred in time. Both halves hold in the same run:

- A PR touching a pre-existing table **fails pre-merge**. Verified: `010-comments-metadata-fk` still
  fails on `comments` (from `004`), while `009-create-nkd-concept-snapshots` is exempt for
  `nkd_concept_snapshots`, which that same PR created.
- The exemption **expires on its own at merge**. Once a feature lands, its tables are on the base
  branch, so the next PR to drop one of their columns is flagged like any other. Verified by
  replaying the identical `dropColumn` on `diagram_nodes` with the merged branch as base: exempt
  before, ❌ after.

Nothing here is retrospective — the check always runs pre-merge on the PR's own diff.

Verified on `feat/diagrams-req-44` (10 changelogs building the diagram feature): **46 findings → 0**,
with all 46 reclassified as exemptions on `diagrams`, `diagram_nodes`, `diagram_edges`, and
`diagram_pending_edits` — every one a table that branch creates. No pre-existing table was exempted.

A destructive change whose target table cannot be determined is treated as pre-existing, so an
unparseable changelog fails closed rather than slipping through.

## Fixed: `includeAll` ordering / duplicate `009-` prefix

`db.changelog-master.yaml` used `includeAll` over `db/changelog/v1/`, which derives execution order
from filename sort. Two files share the `009-` prefix:

- `009-add-ontology-validation-status.yaml`
- `009-create-nkd-concept-snapshots.yaml`

They are independent (one adds columns to `ontologies`, the other creates `nkd_concept_snapshots`),
so ordering was stable but incidental.

**Fixed by replacing `includeAll` with an explicit `include:` list — not by renaming the files.**

Renaming would have been the wrong fix. Liquibase identifies an applied changeset by
**`id` + `author` + `filename`**, and no changelog here sets `logicalFilePath`, so the physical
filename *is* part of the recorded identity. All current changelogs are already applied on deployed
dev and test. Renaming a file makes its changesets look new, and Liquibase re-runs them — survivable
here only because every changeset carries an `onFail: MARK_RAN` precondition, but it would write
spurious `DATABASECHANGELOG` rows and relies on those guards being correct.

The include list keeps every filename unchanged, so deployed `DATABASECHANGELOG` rows still match.
Verified: all **19 changesets** have identical `(id, author, filename)` triples and identical
execution order before and after the change — dev and test see zero re-runs.

`FullSchemaPostgresValidationTest` (all 12 changelogs against real Postgres via Testcontainers with
`ddl-auto=validate`) and `IsmdToolBackendApplicationTests` both pass on the new master.

**Add new changelogs to the end of the `include:` list.** This is the one maintenance cost of
dropping `includeAll`: a new file that is not listed will silently never run. Unique numeric
prefixes remain good practice.

## What is deliberately not built

**Liquibase `rollback:` blocks + `rollbackToTag`.** Given the rule above, DB rollback is not the
recovery path — image rollback is. Adding rollback blocks would imply a capability that silently
does not hold for `010`-class changes, which is worse than not offering it. If DevOps still wants it
later, note it needs a delivery mechanism: nothing currently runs Liquibase outside app startup.