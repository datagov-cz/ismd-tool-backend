# PG ↔ TDB2 Consistency: Outbox & Reconciler

> Status: both features ship **dark** (disabled by default). Enabling either is an opt-in,
> per-environment decision. Czech version: [`pg-tdb2-consistency.cs.md`](./pg-tdb2-consistency.cs.md).

## What problem this solves

Concept and ontology writes are **dual-writes** to two stores that share **no transaction**:

- **PostgreSQL** holds metadata (`ConceptMetadataEntity` / `OntologyMetadataEntity`: id, slug,
  `conceptIri`, `graphName`, `conceptType`, `userId`, `isPublished`) via JPA `@Transactional`.
- **TDB2 via Fuseki (HTTP)** holds the RDF model. Fuseki is **not** enlisted in the JPA
  transaction — a single SPARQL request is the only atomic unit available over the wire.

Because there is no shared transaction (and TDB2-over-Fuseki-HTTP offers no XA/2-phase commit), a
crash or error between the two writes can leave them **out of sync**: RDF written but the PG row
rolled back (orphan RDF), or a PG row pointing at RDF that a failed delete left behind.

Two complementary mechanisms address this:

| Feature | Role | When it acts |
|---|---|---|
| **Outbox** | **Prevention** on the write path | At write time — makes the RDF write recoverable so it can't silently diverge from the PG commit |
| **Reconciler** | **Detection** (and, later, repair) | On a schedule / on demand — finds drift that already exists, including from the one write path the outbox does not cover (upload) |

The outbox is the primary defence; the reconciler is the safety net behind it (and the only thing
that catches pre-existing / upload-path drift).

---

# Part 1 — Outbox

## Operator summary

The transactional outbox makes the TDB2 side of a concept/ontology write **durable and
retryable**. Instead of writing to Fuseki directly inside the request, the write is recorded as a
row in the `ismd_schema.outbox_entry` table **in the same Postgres transaction** as the metadata.
A **relay** then applies that row to Fuseki:

- **Hot path:** an after-commit *nudge* drains the new row to Fuseki immediately, so reads-after-write
  still work.
- **Backstop:** a scheduled relay (`outbox.relay-cron`, every 10s by default) re-drains anything a
  crash left behind.

A row that fails to apply is retried up to `outbox.max-attempts`, then marked **FAILED** and surfaced
on the admin API for a human to retry. DONE rows are kept as an audit trail and pruned after
`outbox.done-retention`.

When `outbox.enabled=false` (default), the outboxed write sites fall back to the **legacy direct
write** — deploying the code changes nothing until an environment opts in.

**Covered write sites (4):** concept create, concept edit (incl. rename), concept delete, ontology
delete. **Not covered:** ontology **upload** (still a direct write — this is the path the reconciler
backstops).

## Row lifecycle

```
PENDING ──(relay applies to Fuseki)──▶ DONE ──(prune after done-retention)──▶ removed
   │
   └──(apply fails, attempts++ up to max-attempts)──▶ FAILED ──(admin retry)──▶ PENDING
```

- Rows are ordered per **aggregate** (the concept/graph) by a `seq`; the relay applies a row only
  after all earlier rows for the same aggregate are applied, so two edits of one concept can't
  apply out of order.
- A FAILED row **blocks its aggregate** until retried — by design, so a bad write doesn't get
  skipped.

## Configuration

Prefix `outbox.*` (bound in `OutboxConfig`). All values are env-overridable in
`application.properties`.

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `outbox.enabled` | `OUTBOX_ENABLED` | `false` | Master switch. `false` → legacy direct write, no relay, no behavior change. |
| `outbox.relay-cron` | `OUTBOX_RELAY_CRON` | `*/10 * * * * *` | Backstop drain schedule (Spring 6-field cron). The hot path is the after-commit nudge; this only catches crash-left rows. |
| `outbox.max-attempts` | `OUTBOX_MAX_ATTEMPTS` | `10` | Apply attempts before a row is marked FAILED (and blocks its aggregate). |
| `outbox.batch-size` | `OUTBOX_BATCH_SIZE` | `100` | Max rows claimed per relay drain pass. |
| `outbox.done-retention` | `OUTBOX_DONE_RETENTION` | `P7D` | How long DONE rows are kept (ISO-8601 duration) before the prune removes them. |
| `outbox.prune-cron` | `OUTBOX_PRUNE_CRON` | `0 30 3 * * *` | DONE-row retention prune schedule (Spring 6-field cron). |

**Related — connection pool.** The after-commit nudge briefly holds **two** pool connections per
writer (the business connection plus the `REQUIRES_NEW` drain). Size Hikari with headroom:
`spring.datasource.hikari.maximum-pool-size` (`HIKARI_MAX_POOL_SIZE`, default `20` in dev/production).

## Admin API

Base path: `/api/admin/outbox` (remember the app **context path** `/popisujeme`, so the full path
is `/popisujeme/api/admin/outbox/...`). All endpoints require the **ADMIN** role.

| Method & path | Purpose | Response |
|---|---|---|
| `GET /status` | Queue health | `OutboxStatusDto`: `pending`, `failed`, `done`, `oldestPendingCreatedAt` |
| `GET /failed` | List FAILED rows (no triple payloads) | `OutboxEntryDto[]`: id, operation, aggregateIri, graphName, status, attempts, lastError, createdAt, claimedAt, seq |
| `POST /drain` | Force a drain pass now | `Integer` — rows applied |
| `POST /retry/{id}` | Reset a FAILED row to PENDING | `200` on success; **`409`** if the row is missing or not FAILED |

Example (status):

```bash
curl -s "http://localhost:8081/popisujeme/api/admin/outbox/status" \
  -H "Authorization: Bearer $TOKEN" | jq .data
# → { "pending": 0, "failed": 0, "done": 12, "oldestPendingCreatedAt": null }
```

## Runbook

- **Healthy:** `pending` drains to 0 within a tick, `failed` = 0, `oldestPendingCreatedAt` stays
  null/recent.
- **Relay stuck / disabled:** `oldestPendingCreatedAt` keeps aging. Check `outbox.enabled`, that the
  scheduler is running, and Fuseki reachability. `POST /drain` forces a pass.
- **A row is FAILED:** `GET /failed` shows id + `lastError`. Fix the underlying cause (usually Fuseki
  down or a bad payload), bring Fuseki back, then `POST /retry/{id}` → `POST /drain`. The aggregate
  is blocked until you do.
- **DONE rows piling up:** expected within the retention window; the prune (`outbox.prune-cron`)
  removes them past `outbox.done-retention`.

---

# Part 2 — Reconciler

## Operator summary

The reconciler is a **detection-only** consistency check (it does **not** mutate either store yet).
It treats **Postgres as the source of truth**, enumerates the **owned** concept subjects in TDB2,
compares them against the PG metadata rows, and **reports drift** by category. Run it:

- **Scheduled:** `reconciler.cron` (03:00 daily by default), gated by `reconciler.enabled`
  (default off — deploying does not start scanning).
- **On demand:** the admin endpoint works **regardless of `reconciler.enabled`**.

It is the safety net behind the outbox and the only mechanism that catches **upload-path** and
**pre-existing** drift.

### What "owned" means (the key rule)

A concept is **owned** by the scheme it declares iff it carries `skos:inScheme ?scheme` **and** its
IRI is a string prefix-match of that scheme (`STRSTARTS(conceptIri, scheme)`). This is the single
shared predicate (`JenaTDB2Repository.OWNED_CONCEPT_PATTERN`) used by the live resolver, the upload
write-gate, and the reconciler — one source of truth, so the reconciler can never invent orphans the
resolver wouldn't.

Consequence: **referenced/external concepts** (e.g. an NKD `adresa` a relationship points at) appear
only as triple *objects*, never as owned subjects → never flagged. The same is true for foreign-IRI
concepts whose `inScheme` points at a scheme their IRI doesn't prefix-match.

### Drift categories

| Category | Meaning | Disposition |
|---|---|---|
| `RDF_ORPHAN` | Owned RDF subject in Fuseki, no PG row | The only **auto-repairable** category (future phase) |
| `PG_MISSING_RDF` | PG row whose IRI is not owned-resolvable in **any** graph | Report-only (two causes: failed delete vs failed create) |
| `IRI_GRAPH_MISMATCH` | PG IRI owned-resolvable, but not in its declared `graphName` (or `graphName` is null) | Report-only |
| `GRAPH_ORPHAN` | Fuseki graph holds owned subjects but no ontology row references it | Report-only (deletable only in a future gated phase) |
| `SUSPECTED_RENAME` | An `RDF_ORPHAN`(new IRI) + `PG_MISSING_RDF`(old IRI) pair that looks like one half-finished rename | Report-only; both IRIs excluded from any future repair |
| `EXCLUDED_NO_INSCHEME` | In-namespace subject with no `inScheme` (deliberately excluded / legacy) | Informational; *not emitted yet* |
| `RDF_NOT_OWNED_RESOLVABLE` | PG IRI has triples but isn't ownership-resolvable (e.g. lost `inScheme`) | Report-only; *not emitted yet* |

> **Detection-only today.** No category is repaired. Only `RDF_ORPHAN` is *ever* auto-repairable, and
> only after the repair-safety machinery (quarantine, pre-delete audit, distributed lock) is built —
> see [Roadmap](#roadmap--out-of-scope-today).

## Configuration

Prefix `reconciler.*` (bound in `ReconcilerConfig`).

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `reconciler.enabled` | `RECONCILER_ENABLED` | `false` | Master switch for the **scheduled** run. The admin endpoint works regardless. |
| `reconciler.cron` | `RECONCILER_CRON` | `0 0 3 * * *` | Scheduled-run schedule (Spring 6-field cron). Default 03:00 daily. |
| `reconciler.max-concepts` | `RECONCILER_MAX_CONCEPTS` | `200000` | Safety cap: abort a run with a clear error rather than OOM if PG concept rows exceed this. `0` = unlimited. |

> There is intentionally **no `published-only` flag**: draft concepts (`isPublished=false`) carry
> full owned RDF + a PG row and are in scope identically to published ones.

## Admin API

Base path: `/api/admin/reconciler` (full: `/popisujeme/api/admin/reconciler/...`). All endpoints
require the **ADMIN** role.

| Method & path | Purpose | Response |
|---|---|---|
| `POST /run` | Run a detection scan now (no repair) | `ReconciliationReportDto`; **`409`** if a run is already in progress |
| `GET /report` | Last completed report (in-memory; lost on restart) | `ReconciliationReportDto`, or null message if none since startup |

`ReconciliationReportDto`: `startedAt`, `finishedAt`, `triggeredBy`, `graphsScanned`,
`ownedRdfConceptsScanned`, `pgConceptsScanned`, `totalMismatches`,
`countsByCategory` (all categories, including zeros), `mismatches[]` (each: `category`, `graphName`,
`conceptIri`, `relatedIri`, `detail`).

Example (run a scan):

```bash
curl -s -X POST "http://localhost:8081/popisujeme/api/admin/reconciler/run" \
  -H "Authorization: Bearer $TOKEN" | jq '.data.countsByCategory'
```

## Runbook

- **Clean:** `totalMismatches` = 0, or only known pre-existing findings (e.g. upload-path
  `PG_MISSING_RDF`).
- **`PG_MISSING_RDF`:** report-only. Two root causes the detail can't fully disambiguate — a **failed
  delete** (RDF gone, PG row leaked; the common one) or a **failed create** (PG committed, RDF never
  written). Resolve manually; do **not** assume "reproject toward TDB2" — for a failed delete the
  correct fix may be deleting the PG row.
- **`RDF_ORPHAN`:** owned RDF with no PG row. Today: report-only. If you see one immediately after a
  write, re-run — it may be a transient (PG commit landed just after the scan's PG snapshot).
- **`SUSPECTED_RENAME`:** a likely half-finished rename; both IRIs are reported together. Report-only.
- **`409` on `POST /run`:** a scheduled or another manual run holds the guard. Use `GET /report`.
- **Run aborts with a max-concepts error:** the dataset exceeds `reconciler.max-concepts`. Raise the
  cap (or page the snapshot) before relying on the reports.

---

# Architecture & design notes (for developers)

## Why an outbox at all

You cannot make a Postgres commit atomic with a Fuseki HTTP write: TDB2 is its own transaction
domain with no XA/JTA enlistment, and over Fuseki HTTP only a single SPARQL request is atomic. The
transactional-outbox pattern sidesteps this by writing the *intent* (the RDF delta) into the same PG
transaction as the metadata, then applying it asynchronously with retries. The PG commit is the
single point of truth for "did this write happen"; the relay guarantees the RDF eventually matches.

Key correctness properties:

- **Per-aggregate ordering.** Rows carry a monotonic `seq`; the relay applies a row only when no
  earlier unapplied row exists for the same aggregate. On the outbox write path, the concept row is
  locked (`findWithLockById`, `PESSIMISTIC_WRITE`) so two concurrent edits of the same concept can't
  enqueue inverted rows.
- **Rename keys on the pre-edit IRI.** An edit's outbox row uses the concept's IRI *before* the
  edit, so a create-then-rename share one aggregate and order correctly.
- **Idempotent apply.** A row's apply is a concept-scoped delete + insert, so re-applying is safe.
- **Atomic failure.** A bad payload (e.g. corrupt N-Triples) marks **only that row** FAILED — it
  doesn't roll back earlier rows' DONE marks or wedge the queue silently.

## Reconciler detection model

- **Read order is unconditionally TDB2-first, PG-last** (it does not vary with `outbox.enabled`). The
  benefit of this order depends on the active write mode, and it is only ever a minor optimization —
  not a correctness mechanism:
  - **Direct write (outbox off):** writes are TDB2-first, then the PG commit. The in-flight window is
    "RDF written, PG not yet committed" → a transient false `RDF_ORPHAN`. Reading PG *last* makes that
    late commit most likely to be visible, shrinking the window. This is the case the order was chosen
    for.
  - **Outbox (outbox on):** the order inverts — PG (metadata + outbox row) commits first, the relay
    applies TDB2 afterward. The in-flight window becomes "PG committed, TDB2 not yet applied" → a
    transient false `PG_MISSING_RDF`, not an orphan. For *this* race, reading TDB2 last would help, so
    TDB2-first is mildly counterproductive — but in practice the after-commit nudge lands TDB2 within
    milliseconds of the commit, so the window is tiny unless the nudge fails and the row waits for the
    backstop relay.
  - In **both** modes the residual race is harmless in detection-only mode (any false finding
    self-heals on the next run) and is what the future quarantine (age-before-act) closes regardless
    of read direction.
- **Single PG snapshot.** `PgMetadataSnapshot` loads concepts + ontologies in one
  `@Transactional(readOnly=true)` so the two reads are one consistent view; it lives in its own bean
  so the proxy actually applies. A `max-concepts` count-guard aborts before materializing a huge
  table into heap.
- **Set-based sweep.** Owned IRIs → graphs is built once; RDF→PG and PG→RDF comparisons are set
  operations, not per-row queries.
- **Rename pairing (R1 guard).** Before finalizing orphans, an `RDF_ORPHAN`(new) is paired with a
  `PG_MISSING_RDF`(old) into one `SUSPECTED_RENAME` when they share a graph, the IRIs differ only in
  the `/pojem/<name>` tail, and the labels match — so a future repair never deletes a freshly-renamed
  concept. (Known limitation: a rename that *also* changes the label can slip this heuristic — a hard
  gate before auto-repair ships; see the roadmap.)

## Persistence

- **Outbox:** `ismd_schema.outbox_entry` + `outbox_seq` (Liquibase `007-create-outbox.yaml`); index
  `(aggregate_iri, seq)` for the ordering gate (`008-outbox-aggregate-index.yaml`). DONE rows double
  as a write-path audit trail until pruned.
- **Reconciler:** none yet — the last report is held in memory (`volatile`, lost on restart). The
  repair phase will add `reconciler_orphan_candidate` / `reconciler_repair_audit` / `reconciler_run`.

## Security

Both admin paths (`/api/admin/outbox/**`, `/api/admin/reconciler/**`) are registered in
`SecurityConfig`'s authenticated chain **and** guarded by `@PreAuthorize("hasRole('ADMIN')")`. The
allowlist entry is required — without it the request hits `denyAll()` (403) before `@PreAuthorize`
runs.

## Interaction between the two

When both are enabled, run the reconciler periodically to confirm the outbox isn't leaking. Any new
finding beyond the known pre-existing baseline — especially `RDF_ORPHAN` or `SUSPECTED_RENAME` —
indicates a write-path leak to investigate. Before auto-repair is ever enabled,
`reconciler.min-orphan-age` (future property) must exceed the relay's worst-case drain latency, or
the reconciler could try to repair a row the relay is about to apply.

## Roadmap / out of scope today

- **Outbox:** enable in production, watch for a week, then remove the now-dead direct-write branches
  and the create-path `rollbackTDB2Data` compensator (keep the upload path's cleanup).
- **Reconciler auto-repair:** gated on quarantine (age-before-delete), pre-delete audit dump,
  persisted run/candidate tables, a distributed lock if multi-instance, and closing the
  label-changed-rename gap. Only `RDF_ORPHAN` will ever be auto-repaired; everything else stays
  report-only. See `.planning/pg-tdb2-reconciler-PLAN-ADDENDUM.md` (R1–R13).
- **Referenced-concept PG rows:** the reconciler currently reports them as `PG_MISSING_RDF`
  (foreign-IRI rows that fail the ownership rule). Proper handling is tied to the planned
  published/draft-concept refactor.
