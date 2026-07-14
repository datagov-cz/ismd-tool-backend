# NKD Local Copy / Snapshot

> Tracks a **local copy** of a published NKD concept that a local concept *links* to (as a broader
> class, super property/relation, or exact match), so the link can be compared against the live NKD
> source and updated or dropped when they drift. The copy lives **only in Postgres**; the owner's
> RDF graph holds just the link triple.

## What problem this solves

A local concept (whose own IRI is **not** in NKD) can link to a **published NKD concept owned by
someone else** — e.g. a local subclass whose broader class is an NKD concept. When that link is
made, the system takes a **snapshot** of the NKD concept (a tracked local copy, stored in Postgres)
and watches for **deviation** between the stored copy and the live NKD source. The owner's link
triple (e.g. `rdfs:subClassOf` → the NKD IRI) is written to the graph as usual; the copy's triples
are **not** — they are kept in the `materialized_triples` column, not in TDB2.

> **Why PG-only (design decision, 2026-07-14).** Earlier the copy triples were also materialized
> into the owner's TDB2 graph, co-resident with owned concepts. That co-location was a recurring bug
> source: every graph reader had to strip the copies (they leaked into `pojmy` and `MinimalConceptDto`),
> and the reconciler flagged them `RDF_ORPHAN`. An audit found **no read path needs the copy in TDB2**
> — deviation compares live NKD against the PG `snapshot_json`, and surfacing reads the PG rows — so
> the TDB2 copy was pure duplication. Dropping it makes "owner graph = owned concepts" true by
> construction. See `.planning/snapshot-graph-separation-DESIGN.md`.

This is distinct from the existing `ConceptMetadataEntity.is_published` path. `is_published=true`
means a concept's *own* IRI exists in NKD (self-published) and is already deviation-tracked against
its NKD twin at the same IRI. The new case differs only in **who owns the IRI**: a foreign IRI
reached via a link. Conceptually both are "a local copy tracked against NKD," but `is_published` is
also load-bearing for visibility/search (`searchByText`, `findVisibleUnpublished`, the `/list`
endpoints), so it is left **untouched** — the snapshot model is purely additive.

### What the user can do

On the ontology detail view, each linked NKD concept surfaces as a card showing the deviation
status. When the copy deviates from live NKD, the user can:

- **Update** — re-snapshot to accept the upstream change.
- **Remove** — drop the link (and the copy).

If NKD deletes the concept upstream, the link and copy are removed **automatically** on the next
evaluation.

### Allowed links

A link to a published NKD concept may only be made through:

| Link | RDF written | `SnapshotLinkType` |
|---|---|---|
| broader class (`broaderClass`, TŘÍDA) | `rdfs:subClassOf` + `nadřazená-třída` | `BROADER_CLASS` |
| super property (`superProperty`, VLASTNOST) | `rdfs:subPropertyOf` | `SUPER_PROPERTY` |
| super relation (`superRelation`, VZTAH) | `rdfs:subPropertyOf` | `SUPER_RELATION` |
| exact match (`exactMatch`, any type) | `skos:exactMatch` | `EXACT_MATCH` |

`domain` and `range` are **excluded** — `range` points at an XSD datatype, and a `domain` pointing
at a published concept is invalid input. Pointing one of those at a confirmed-published NKD IRI is
**hard-rejected (HTTP 400)** on the strict edit path. There is no concept-to-concept "related" link
in the codebase, so `relatedConcept` is not applicable.

> **Scope this round:** only `LINK_TARGET` snapshots are created. The `SnapshotOrigin.SELF_PUBLISHED`
> value and the `linkPredicate=null` branch are **reserved seams** for a future unification of the
> two paths — no SELF_PUBLISHED rows are written, and existing `is_published=true` concepts are not
> backfilled.

---

## Data model

### `nkd_concept_snapshots` (PG)

One read-only row per `(owning concept, NKD IRI)` link, unique on `(owning_concept_id, nkd_iri)`.

| Column | Purpose |
|---|---|
| `owning_concept_id` | FK → `concepts.id`, **not** db-cascade (the service cascades the rows explicitly) |
| `nkd_iri` | the link target (`VARCHAR(1024)` — Czech/percent-encoded IRIs exceed 255) |
| `graph_name` | denormalized owner graph the link belongs to |
| `origin` | `LINK_TARGET` (only value written this round) |
| `link_predicate` | the logical `SnapshotLinkType` token — **not** a raw predicate IRI (one logical type can write two predicates) |
| `snapshot_json` | serialized `ConceptDetailModel` of the NKD concept — the deviation-comparison payload |
| `materialized_triples` | the **exact N-Triples set** of the copy (the NKD concept's own triples, minus `skos:inScheme`, plus a provenance marker). Stored here only — this is the copy's sole home; nothing is written to TDB2. |
| `last_deviation_status` / `last_checked_at` | cached deviation result + staleness clock |
| `snapshot_at` | when the copy was last taken |

Index `idx_nkd_snapshot_graph_iri` on `(graph_name, nkd_iri)`. Migration:
`v1/009-create-nkd-concept-snapshots.yaml`. `snapshot_json` / `materialized_triples` are `VARCHAR`
(unbounded), **not** `TEXT` — `TEXT` realizes as CLOB on H2 and fails `ddl-auto=validate` against the
entity's `columnDefinition="text"`.

> **`materialized_triples` vs `snapshot_json`** — two different things. `snapshot_json` is the NKD
> detail for *comparison* (drives the deviation diff); `materialized_triples` is the copy's exact RDF,
> retained so the stored copy is a faithful, non-lossy record of the NKD concept. Neither is written
> to TDB2.

---

## Components

| Component | Role |
|---|---|
| `NkdSnapshotService` (+ impl) | Core: create/refresh (PG rows), evaluate deviation, remove, cascade. The copy is PG-only; the service contributes only owner **link** removals to the caller's `OwnerChangeSet`, never copy triples. |
| `NkdSnapshotMaterializer` | **Pure function**: NKD concept → triple set for the `materialized_triples` column. No TDB2 I/O, no outbox. |
| `NkdLinkDetector` | Shared detection: which link targets are allowed snapshot candidates vs forbidden domain/range targets; external-vs-owned check. |
| `NkdSnapshotWarmer` | `@Async("snapshotExecutor")`, **no transaction**: one batch NKD verify, delegates per owner. |
| `NkdSnapshotOwnerWarmer` | `@Transactional(REQUIRES_NEW)` per owner: writes the PG rows, isolated. (No TDB2 delta on the warm path — the flush is a guarded no-op.) |
| `NkdSnapshotEndpointService` (+ impl) | Backs the UPDATE/REMOVE endpoints; owns the owner-keyed flush of the **link** removal. |
| `LinkSnapshotAssembler` | Builds `LinkSnapshotDto` from a cached row or a fresh deviation. |
| `OwnerChangeSet` | The `toRemove`/`toAdd` statement accumulator; carries the owner's link triples only. |

---

## Write path: PG-only copy, link triple rides the owner's outbox aggregate

The copy is written to Postgres (`materialized_triples`) and **never** to TDB2. What reaches the
graph is only the owner's **link** triple (e.g. `rdfs:subClassOf` → the NKD IRI), produced by the
concept editor exactly like any other owner edge — plus its removal when a link is dropped.

The snapshot service is outbox-agnostic: it takes a pre-computed `OwnerChangeSet` and contributes
**only link removals** (when unlinking a target) into it. The owner's own edit/endpoint flow flushes
that change set as one owner-keyed `UPSERT_CONCEPT` aggregate (or, when `outbox.enabled=false`, a
direct `JenaTDB2Repository.applyConceptDelta`). No separate NKD-keyed aggregate exists, so there is
no cross-aggregate ordering hazard — the link triple is just part of the owner's normal change set.

### Reconciler safety: nothing to guard

Because the copy is never in TDB2, the reconciler's `OWNED_CONCEPT_PATTERN`
(`?c skos:inScheme ?scheme . FILTER(STRSTARTS(STR(?c), STR(?scheme)))`) can never see it, so it can
never be enumerated as owned or flagged `RDF_ORPHAN`. This is structural, not a workaround: it
retires the earlier `NkdSnapshotTripleFilter` (which had to strip copies out of every graph read) and
the `NkdSnapshotMaterializer.assertNotOwnedBy` guard.

The materializer still drops NKD's own `skos:inScheme` from the stored `materialized_triples` and
still adds a provenance marker `<nkdIri> ismd:nkd-snapshot-of <ownerIri>`
(`https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of`) — but these now only shape the **stored PG
payload**; they have no reconciler significance.

### Shared NKD IRI

Two local concepts in one graph can link the **same** NKD superclass — each gets its own PG snapshot
row (unique on `(owning_concept_id, nkd_iri)`). There is no shared TDB2 copy to refcount: unlinking or
deleting one concept simply drops **that concept's** PG row and **that owner's** link triple; the
other concept's row and link are untouched.

---

## Surfacing & async warming

`LinkSnapshots` surface on **ontology detail** (`GetOntologyDto.linkSnapshots`, a
`Map<ownerConceptIri, List<LinkSnapshotDto>>`), parallel to the existing `publishedConceptDeviations`
map. `GetConceptDto` does **not** carry them.

Ontology detail is heavy and snapshot deviation needs a live NKD fan-out, so **detail never blocks
on NKD — the snapshot row is the cache:**

- **Warm + fresh** (row exists, `last_checked_at` within TTL): read the cached row, **no NKD call**.
  The common path.
- **Cold** (link triples but no row — first load or upload-path gap) **or stale** (past TTL):
  return the entry with `status = PENDING` / `availableActions = []`, and fire **one async warmer**.
  The next load is warm. The FE shows a spinner and re-fetches.

The warmer (`warmGraph`, `@Async("snapshotExecutor")`, no transaction) detects each owner's external
NKD link-targets, does **one** batch `getPublishedResourcesList` verify, then delegates per owner to
`warmOwner` (`@Transactional(REQUIRES_NEW)`), which writes the PG snapshot rows and seeds
`NO_DEVIATION`/`last_checked_at=now`. (Copies are PG-only, so `warmOwner` produces no TDB2 delta.)
Per-owner failures are isolated and non-fatal — an NKD outage just leaves the graph cold; the next
view retries. The read **only triggers** the async warmer; it never writes on the request thread (so
concurrent GETs can't race the unique constraint or the no-transaction assertion).

**Config:** `nkd.snapshot.deviation-ttl` (default `PT24H`), `AsyncConfig` (`@EnableAsync` +
`snapshotExecutor` thread pool, `snapshot-` prefix), `DeviationStatus.PENDING`.

---

## Lifecycle hooks

| Trigger | Where | Behavior |
|---|---|---|
| **Ontology detail (read)** | `OntologyServiceImpl` (`@Transactional(readOnly=true)`) | Read cached rows → DTOs; cold/stale/zero-row → `PENDING` + fire async `warmGraph`. No write in the GET. |
| **Async warm** | `NkdSnapshotWarmer` → `NkdSnapshotOwnerWarmer` | Write PG snapshot rows per owner. No TDB2 delta (copy is PG-only). |
| **Upload** | `OntologyController.uploadFromFile` | Fire `warmGraph` **after** the upload commits (NKD-independent; try/catch-guarded). Upload never calls NKD itself. |
| **Edit** | `ConceptServiceImpl.reconcileNkdLinks` (in-tx, synchronous) | Detect allowed published targets → snapshot (PG row); reject domain/range→published (400); remove snapshots whose link the edit dropped (the link-edge removal folds into the edit change set). |
| **Upstream NKD deletion** | `NkdSnapshotEndpointServiceImpl` (UPDATE re-snapshot returns null) | Cascade: remove the owner→nkdIri link triple + the PG snapshot row; return a `CONCEPT_NOT_FOUND_IN_NKD` marker DTO. |
| **Concept delete** | `ConceptServiceImpl.deleteConcept` | `cascadeConceptDeletion` drops the PG rows. No copy triples in the graph to sweep. |
| **Ontology delete** | `OntologyServiceImpl.deleteOntology` | `cascadeGraphDeletion` drops all PG rows; `DELETE_GRAPH` removes the owner graph (no copies live there). |

### Edit-path enforcement (domain/range → published)

`ConceptEditValidator` is a dependency-free, synchronous class and **cannot** make the NKD call, so
the domain/range-points-at-published rejection lives in the **edit service** (`reconcileNkdLinks`),
where `getPublishedResourcesList` is already called.

> **Fail-open on NKD outage.** When NKD is unreachable the published-status is unknown, so
> enforcement is **fail-OPEN**: the edit is allowed and snapshotting is skipped. The deterministic
> HTTP 400 fires only on a **confirmed positive** published hit. The trade-off — a domain→published
> link can slip through during an outage — is accepted because fail-closed would let an NKD blip
> block valid edits.

For renames, the edit reconciles on the **new** IRI (relocated edges land there), but the outbox
**aggregate key is captured pre-edit** so the per-aggregate ordering invariant holds.

---

## API

Both endpoints are path-only (no request body), `@PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")`
(reuses the edit/delete expression), and registered in the `SecurityConfig` allowlist (else they 403
before `@PreAuthorize` runs). The service asserts `snapshot.owningConcept.id == conceptId` →
`OntologyValidationException` (400) on mismatch.

| Method | Path | Returns |
|---|---|---|
| `POST` | `/api/concept/{conceptId}/localcopy/{snapshotId}/update` | refreshed `LinkSnapshotDto` (re-snapshot + live deviation + recomputed actions) |
| `DELETE` | `/api/concept/{conceptId}/localcopy/{snapshotId}` | `ApiResponseDto` (Czech message) |

### `LinkSnapshotDto`

`snapshotId`, `owningConceptId`, `linkPredicate` (`SnapshotLinkType`), `origin` (`LINK_TARGET`),
`nkdConcept` (`{iri, label}` for navigation + display), `snapshotAt`, `lastCheckedAt`, hoisted
`status` (`DeviationStatus`), `deviation` (`PublishedConceptDeviationModel`), and `availableActions`
(`SnapshotAction{UPDATE, REMOVE}`). `@JsonInclude(NON_NULL)`.

`availableActions` derive from `status`:

| Status | Actions |
|---|---|
| `HAS_DEVIATIONS` | `[UPDATE, REMOVE]` |
| `NO_DEVIATION` | `[REMOVE]` |
| `ENDPOINT_UNAVAILABLE` / `QUERY_ERROR` | `[]` (can't trust the comparison) |
| `PENDING` | `[]` (warming in flight) |
| `CONCEPT_NOT_FOUND_IN_NKD` | never surfaced — triggers server-side removal |

> **Deviation-block label trap.** `deviation` is the same `PublishedConceptDeviationModel` the
> comparator produces, but for `LINK_TARGET` the field pair means something different:
> `localValue` = the **stored local copy**, `publishedValue` = **live NKD** — *not* "the user's own
> value." The backend keeps one comparison type; the FE relabels the columns keyed on
> `origin=LINK_TARGET` ("lokální kopie" / "NKD" instead of "vaše hodnota" / "publikováno").

---

## Status

All built and validated. Full suite green after the PG-only change (**1376/0**, 4 skipped).

**PG-only migration (2026-07-14):** the copy was moved out of TDB2 (see the design decision at the
top and `.planning/snapshot-graph-separation-DESIGN.md`). Existing copies already materialized into
owner graphs on dev/prod are removed by a one-off SPARQL delete run directly against Fuseki after
deploy — `DELETE WHERE { GRAPH ?g { ?s <…/nkd-snapshot-of> ?o . ?s ?p ?v } }`
(`.planning/snapshot-graph-separation-cleanup.sh`). The PG rows are the source of truth, so nothing
is lost. Verify with the reconciler dry-run: snapshot subjects no longer report `RDF_ORPHAN`.

The earlier end-to-end smoke test (dev, 2026-06-23, `outbox.enabled=true`, live NKD, real Fuseki/PG)
drove the full lifecycle (edit→snapshot → ontology-detail surfacing → UPDATE → DELETE). Re-run the
smoke test on the PG-only build to confirm: after linking, the graph holds the **link triple** but
**no `nkd-snapshot-of` subject**, the PG row carries `materialized_triples`, and `pojmy` no longer
double-counts.

The `SELF_PUBLISHED` origin remains a reserved seam for a future unification of the snapshot and
`is_published` paths.