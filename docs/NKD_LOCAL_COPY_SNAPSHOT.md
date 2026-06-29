# NKD Local Copy / Snapshot

> Tracks a **local copy** of a published NKD concept that a local concept *links* to (as a broader
> class, super property/relation, or exact match), so the link can be compared against the live NKD
> source and updated or dropped when they drift. Rides the PG↔TDB2 outbox — see
> [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md).

## What problem this solves

A local concept (whose own IRI is **not** in NKD) can link to a **published NKD concept owned by
someone else** — e.g. a local subclass whose broader class is an NKD concept. When that link is
made, the system takes a **snapshot** of the NKD concept (a tracked local copy), materializes it
into the owner's RDF graph so it renders in the graph view, and watches for **deviation** between
the stored copy and the live NKD source.

This is distinct from the existing `ConceptMetadataEntity.is_published` path. `is_published=true`
means a concept's *own* IRI exists in NKD (self-published) and is already deviation-tracked against
its NKD twin at the same IRI. The new case differs only in **who owns the IRI**: a foreign IRI
reached via a link. Conceptually both are "a local copy tracked against NKD," but `is_published` is
also load-bearing for visibility/search (`searchByText`, `findVisibleUnpublished`, the `/list`
endpoints), so it is left **untouched** — the snapshot model is purely additive.

### What the user can do

On the ontology detail view, each linked NKD concept surfaces as a card showing the deviation
status. When the copy deviates from live NKD, the user can:

- **Update** — re-snapshot (re-materialize) to accept the upstream change.
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
| `owning_concept_id` | FK → `concepts.id`, **not** db-cascade (the service cascades so Fuseki triples are cleaned in the same boundary) |
| `nkd_iri` | the link target (`VARCHAR(1024)` — Czech/percent-encoded IRIs exceed 255) |
| `graph_name` | denormalized owner graph where the copy is materialized |
| `origin` | `LINK_TARGET` (only value written this round) |
| `link_predicate` | the logical `SnapshotLinkType` token — **not** a raw predicate IRI (one logical type can write two predicates) |
| `snapshot_json` | serialized `ConceptDetailModel` of the NKD concept — the deviation-comparison payload |
| `materialized_triples` | the **exact N-Triples set** last written to TDB2 — the source of truth for the delete-set on re-materialize/remove |
| `last_deviation_status` / `last_checked_at` | cached deviation result + staleness clock |
| `snapshot_at` | when the copy was last taken |

Index `idx_nkd_snapshot_graph_iri` on `(graph_name, nkd_iri)` backs the refcount. Migration:
`v1/009-create-nkd-concept-snapshots.yaml`. `snapshot_json` / `materialized_triples` are `VARCHAR`
(unbounded), **not** `TEXT` — `TEXT` realizes as CLOB on H2 and fails `ddl-auto=validate` against the
entity's `columnDefinition="text"`.

> **`materialized_triples` vs `snapshot_json`** — two different things. `snapshot_json` is the NKD
> detail for *comparison*; `materialized_triples` is the exact RDF *written to the graph*. The
> delete-set when re-materializing or removing is read from `materialized_triples`, never recomputed
> from the lossy `snapshot_json` — otherwise NKD drift would leave stale triples behind.

---

## Components

| Component | Role |
|---|---|
| `NkdSnapshotService` (+ impl) | Core: create/refresh, evaluate deviation, remove, cascade. **Outbox-agnostic** — fills a caller-supplied `OwnerChangeSet`, never enqueues itself. |
| `NkdSnapshotMaterializer` | **Pure function**: NKD concept → materialized triple set. No TDB2 I/O, no outbox. |
| `NkdLinkDetector` | Shared detection: which link targets are allowed snapshot candidates vs forbidden domain/range targets; external-vs-owned check. |
| `NkdSnapshotWarmer` | `@Async("snapshotExecutor")`, **no transaction**: one batch NKD verify, delegates per owner. |
| `NkdSnapshotOwnerWarmer` | `@Transactional(REQUIRES_NEW)` per owner: owns the single owner-keyed outbox flush, isolated. |
| `NkdSnapshotEndpointService` (+ impl) | Backs the UPDATE/REMOVE endpoints; owns the owner-keyed flush. |
| `LinkSnapshotAssembler` | Builds `LinkSnapshotDto` from a cached row or a fresh deviation. |
| `OwnerChangeSet` | The `toRemove`/`toAdd` statement accumulator the service contributes to. |

---

## Write path: rides the owner's outbox aggregate

All TDB2 mutation goes through the **owning concept's outbox aggregate** — the same outbox row that
carries the owner's link triple also carries the materialized copy. This is the central design
decision and follows from how the outbox relay works:

> The relay's ordering gate (`existsEarlierUnappliedForAggregate`) is **per-aggregate only** — there
> is no enforced order between two distinct aggregates. If the copy were keyed on the NKD IRI as its
> own aggregate, it could apply before the owner's link triple, or a link removal could orphan it.
> Folding the copy into the **owner's** change set gives real per-aggregate ordering + apply
> atomicity for free: link triple and copy land together, in one `UPSERT_CONCEPT` row keyed on the
> owner IRI, never out of order.

So the service methods take a pre-computed `OwnerChangeSet` and **contribute** the materialized-copy
triples into it; the single owner-keyed `enqueueUpsert` at the call site flushes everything as one
aggregate. When `outbox.enabled=false`, the caller falls back to a direct
`JenaTDB2Repository.applyConceptDelta` of the same delta.

### Reconciler safety: the copy must not look "owned"

The reconciler enumerates *owned* subjects via `OWNED_CONCEPT_PATTERN`
(`?c skos:inScheme ?scheme . FILTER(STRSTARTS(STR(?c), STR(?scheme)))`) and `RDF_ORPHAN` is its one
auto-repairable (deletable) category. A concept **always** prefix-matches its own `inScheme`, so the
materializer **strips `skos:inScheme` entirely** from the copy. The pattern needs the triple to
exist, so without it the copy is never enumerated as owned and never flagged `RDF_ORPHAN`. Dropping
the RDF `inScheme` does not affect deviation detection — `snapshot_json` is built independently from
the NKD fetch. `assertNotOwnedBy` is kept as a defense-in-depth regression guard.

> This was a real bug caught only by the live smoke test. The original design preserved NKD's
> foreign `inScheme` on the theory that a foreign scheme never prefix-matches a local owner scheme —
> but the pattern compares a subject to **its own** scheme, not the owner's, so the copy was
> trivially "owned." Stripping `inScheme` is the fix.

Each copy also carries a provenance marker `<nkdIri> ismd:nkd-snapshot-of <ownerIri>`
(`https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of`, in ISMD's own namespace, so it can never
make the subject "owned"). The marker round-trips through `materialized_triples` and is cleaned on
removal.

### Shared NKD IRI: reference counting

Two local concepts in one graph can link the **same** NKD superclass, sharing one materialized copy.
`deleteConceptsFromGraph` deletes both `?nkd ?p ?o` and `?s ?p ?nkd`, so a naïve "delete the nkdIri"
would strip **every** owner's link to that superclass. Instead, removal is **refcount-gated** via
`countByGraphNameAndNkdIri`:

- A single owner's removal always drops **that owner's** outgoing link triple.
- The **shared copy** is dropped only when the **last** referencing concept's link is removed
  (count ≤ 1); otherwise it stays for the remaining referrers.

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
`warmOwner` (`@Transactional(REQUIRES_NEW)`), which materializes via the owner-aggregate outbox and
seeds `NO_DEVIATION`/`last_checked_at=now`. Per-owner failures are isolated and non-fatal — an NKD
outage just leaves the graph cold; the next view retries. The read **only triggers** the async
warmer; it never writes on the request thread (so concurrent GETs can't race the unique constraint
or the no-transaction assertion).

**Config:** `nkd.snapshot.deviation-ttl` (default `PT24H`), `AsyncConfig` (`@EnableAsync` +
`snapshotExecutor` thread pool, `snapshot-` prefix), `DeviationStatus.PENDING`.

---

## Lifecycle hooks

| Trigger | Where | Behavior |
|---|---|---|
| **Ontology detail (read)** | `OntologyServiceImpl` (`@Transactional(readOnly=true)`) | Read cached rows → DTOs; cold/stale/zero-row → `PENDING` + fire async `warmGraph`. No write in the GET. |
| **Async warm** | `NkdSnapshotWarmer` → `NkdSnapshotOwnerWarmer` | Materialize-on-warm; one owner-keyed `enqueueUpsert` per owner. |
| **Upload** | `OntologyController.uploadFromFile` | Fire `warmGraph` **after** the upload commits (NKD-independent; try/catch-guarded). Upload never calls NKD itself. |
| **Edit** | `ConceptServiceImpl.reconcileNkdLinks` (in-tx, synchronous) | Detect allowed published targets → snapshot (fold into the edit change set); reject domain/range→published (400); remove snapshots whose link the edit dropped. |
| **Upstream NKD deletion** | `NkdSnapshotEndpointServiceImpl` (UPDATE re-snapshot returns null) | Cascade: remove the owner→nkdIri link + (refcount-gated) copy; return a `CONCEPT_NOT_FOUND_IN_NKD` marker DTO. |
| **Concept delete** | `ConceptServiceImpl.deleteConcept` | `cascadeConceptDeletion` drops the PG rows and returns last-referrer NKD IRIs to sweep from the graph. |
| **Ontology delete** | `OntologyServiceImpl.deleteOntology` | `cascadeGraphDeletion` drops all PG rows; `DELETE_GRAPH` sweeps the copies. |

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

All built and validated. Full suite **1300/0**; ~22 snapshot-focused test files. A manual end-to-end
smoke test passed on **dev** (2026-06-23) with `outbox.enabled=true`, live NKD, and real Fuseki/PG —
driving the full lifecycle (edit→snapshot → ontology-detail surfacing → UPDATE → DELETE), confirming
one owner-keyed outbox row drains atomically and the reconciler reports **zero `RDF_ORPHAN`** for the
materialized copy (the first run flagged it and exposed the `inScheme` bug fixed above).

The `SELF_PUBLISHED` origin remains a reserved seam for a future unification of the snapshot and
`is_published` paths.