---
id: 0002
title: Retire TDB2 materialization of NKD local copies (Postgres becomes the sole home)
status: applied
date: 2026-07-14
authors: Richard Koubek
tags: [nkd-snapshot, rdf, reconciler, outbox, architecture]
supersedes: -
superseded_by: -
---

# 0002 — Retire TDB2 materialization of NKD local copies

## Summary

NKD local copies were written **twice**: into Postgres (`nkd_concept_snapshots.materialized_triples`)
and into the owner's TDB2 named graph, where they sat co-resident with owned concepts. That
co-location produced a recurring class of "copy leaks as an owned concept" bugs — the same defect
resurfacing at each new graph reader. An audit found **no production read path consumes the copy from
TDB2**, so the TDB2 write was pure duplication. It was removed; Postgres is now the copy's sole home.
Copies can no longer be mistaken for owned concepts because they are no longer in the graph at all.

**NOTE:** This is a pre-ship feature architecture change decision. Local copy RDF materialization did not reach test
nor production environment at all. Since most of the feature was already build and this is an architecture change 
rather than a finding-and-resolution change, it warrants its decision record for client scrutiny and as audit trail.

## Context

Copies were distinguishable from owned concepts only by RDF signals (`skos:inScheme` absent,
`ismd:nkd-snapshot-of` present), so **every** graph reader had to re-derive "is this mine?". Each
reader that forgot reintroduced the same bug:

- Copies leaked into the ontology detail `pojmy` array (`OntologyServiceImpl.getOntologyDetailModel`).
- The **same** leak appeared independently at a second entry point, `getIsmdConceptsByIri`
  (`MinimalConceptDto`) — two sites, one defect class, proof it recurs rather than being a one-off.
- The PG↔TDB2 reconciler flagged materialized copies as `RDF_ORPHAN` (observed on a local smoke run,
  2026-06-23). Since a future auto-repair phase would **delete** what it flags, shipping this design
  would have armed a data-loss path against the copies.
- Suspected further: TTL export emitting copies, and `NkdSnapshotWarmer` re-reading its own copies.

The immediate response (commit `541fbb0`, 2026-07-14) was surgical: a `NkdSnapshotTripleFilter` that
stripped `nkd-snapshot-of` subjects, called at both detail sites. That fixed the symptom while
institutionalizing per-site filtering — every future reader would have to remember the same call.

## Investigation

1. **Established the copy is genuinely duplicated.** The full copy already lives in PG
   (`NkdConceptSnapshotEntity.materializedTriples`); the TDB2 write is a second, redundant copy of the
   same triples.

2. **Audited every read path for a TDB2 dependency on the copy.** The audit came back **empty** — no
   production path reads the copy out of the graph:
   - Ontology-detail deviation runs on the **post-strip** model.
   - Single-concept detail fetches **live from NKD**.
   - Snapshot-entity deviation compares live NKD against the PG `snapshot_json`.
   - `linkSnapshots` are assembled from **PG rows**.

3. **Found the one real consumer, and got a product ruling.** TTL export emitted copies (it dumps the
   whole graph). Product accepted copies-out-of-TTL: an export is owned concepts plus link triples, and
   a consumer dereferences the NKD IRI for the target's content.

4. **Tested the alternative (separate snapshot graph) against the outbox model, and it failed.** An
   outbox op is **single-graph** (`OutboxEntry.graph_name`, `enqueueUpsert`, `applyConceptDelta`), so
   splitting copies into their own graph needs **two ops across two graphs**. The relay commits each
   independently → a partial-failure window (dangling link or orphaned copy) that today's single atomic
   op does not have. It also creates a new orphan class on delete: `DELETE_GRAPH` / `DELETE_CONCEPTS`
   are single-graph, so the snapshot graph would survive the owner's deletion.

5. **Confirmed graph separation would not even retire the workarounds.** Copies are shielded from the
   reconciler by the **ownership gate** `OWNED_CONCEPT_PATTERN`
   (`?c skos:inScheme ?scheme . FILTER(STRSTARTS(STR(?c), STR(?scheme)))`), not by which graph they sit
   in. Moving them to another graph leaves that gate — and therefore the `inScheme`-stripping and the
   `assertNotOwnedBy` guard — exactly as load-bearing as before.

6. **Located the write precisely.** Copy triples reached TDB2 through exactly one seam:
   `ownerChangeSet.toAdd/toRemove` in `NkdSnapshotServiceImpl` (the `materialize()` output, and
   `parse(materializedTriples)` on removal). The owner's **link** triples come from the editor/caller,
   not this service — so removing the copy write leaves links untouched.

## Root cause

Not a defect in one line but a structural choice: copies were materialized **into the owner's named
graph** (`NkdSnapshotServiceImpl` folded `materialize()` output into the owner change set), making
"this graph contains only concepts I own" false. Every consumer then had to reconstruct ownership from
RDF signals, and each one that didn't became a bug. The guard was also subtly wrong on its own terms:
`assertNotOwnedBy` checked the **owner's** scheme while the reconciler's `OWNED_CONCEPT_PATTERN`
checks the **concept's own** `inScheme` — two different conditions, which is why the guard did not
prevent the `RDF_ORPHAN` flagging.

## Decision / Fix

**Decision: Option B — stop writing copies to TDB2; keep the PG payload.** Chosen because it makes
"owner graph = owned concepts" true *by construction* rather than by discipline, and it needs no
outbox changes (the removal is a single-graph delete).

**Code fix** (commit `e6236b7`, net **−333 LOC**):

- `NkdSnapshotServiceImpl` no longer folds copy triples into the owner change set (deleted the
  `toAdd`/`toRemove` copy lines; refcount-gated `removeSnapshotCopyAndRow` → copy-free
  `removeSnapshotRow`).
- `cascadeConceptDeletion` → `void` (dropped the orphaned-IRI return);
  `ConceptServiceImpl.deleteConcept` drops the `orphanedNkdCopies` append.
- `NkdSnapshotMaterializer.assertNotOwnedBy` and its `ownerScheme` parameter **deleted**. The
  `inScheme` strip is **kept**, now purely for PG-payload stability — it has no reconciler
  significance.
- `NkdSnapshotTripleFilter`, its test, and both `removeSnapshotSubjects` call sites **deleted** —
  the surgical fix from `541fbb0` is retired three days after it shipped.
- `countByGraphNameAndNkdIri` removed (it was the refcount for the shared TDB2 copy, now dead).
- `materialize()` / `toNTriples` / `parse` **kept** — they still build the PG `materializedTriples`.
- Owner **link** triples still flow to TDB2 (editor + the `removeSnapshotAndLink` link branch).

**Data repair — none required.** Materialization never reached test or production, so no deployed
environment holds copy triples. Local development graphs built before the change are cleaned with a
one-off SPARQL delete against Fuseki — a single-graph operation, outbox-safe, and non-destructive since
the PG rows are the source of truth:

```sparql
DELETE WHERE { GRAPH ?g { ?s <https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of> ?o . ?s ?p ?v } }
```

## Alternatives considered

- **Option A — move copies to a separate TDB2 graph.** Rejected on the outbox evidence above: it needs
  two single-graph ops, introducing a partial-failure window the current atomic op does not have, plus
  a new orphan class on delete — and it retires none of the workarounds, because the shield is the
  ownership gate, not graph residency.
- **Option C — institutionalize per-site filtering** (keep `NkdSnapshotTripleFilter`, call it
  everywhere). Rejected: this is what `541fbb0` did. It patches the symptom and leaves the trap armed
  for the next reader; the two independent leak sites are the evidence it does not hold.

## Verification

- Full suite green at the time of the change: **1376/0** (4 skipped). Verified executably *before*
  removing the write via the deviation, ontology-detail, and concept-detail suites.
- **End-to-end run, 2026-07-20** (real Fuseki/PG, `outbox.enabled=true`): after linking, the owner
  graph holds the **link triple** and **no `nkd-snapshot-of` subject**; the PG row carries
  `materialized_triples`; `pojmy` no longer double-counts.
- **Closing invariants:** `0` copy subjects across all graphs; reconciler `totalMismatches: 0` with
  **`RDF_ORPHAN: 0`** over 598 owned RDF / 598 PG concepts in 2 graphs; outbox drained.

The `RDF_ORPHAN: 0` result is the acceptance signal: it proves the change **closed** the flagging bug
structurally rather than relocating it.

## Consequences & follow-ups

- **Auto-repair is unblocked on this axis.** Copies can no longer be enumerated as owned, so a future
  reconciler repair phase cannot delete them. (Record [[0001]] noted auto-repair must not ship until
  both `inScheme`-seam bugs were resolved; this resolves the snapshot half.)
- **TTL export contains no copy triples** — the shipped behavior, product-approved. An export is owned
  concepts plus link triples; consumers dereference the NKD IRI for a link target's content. (Nothing
  changes for existing consumers: the copy-emitting variant never shipped.)
- **The copy is now single-homed.** Losing a PG row loses the copy; there is no second replica in
  TDB2. This is intended (the row was always the source of truth) but it removes an accidental backup.
- **Open, unrelated to this decision:** a concept-detail **GET re-snapshots** link-target rows
  (`createOrRefreshSnapshot` records `NO_DEVIATION` by construction), so an upstream NKD change is
  adopted by a passive read. Not caused by this change, but it shares the surfacing path — worth its
  own decision.

## References

- Commits: `541fbb0` (surgical filter, superseded), `e6236b7` (this decision)
- Code: `src/main/java/com/dia/ismdtoolbackend/service/impl/NkdSnapshotServiceImpl.java`,
  `src/main/java/com/dia/ismdtoolbackend/utility/published/NkdSnapshotMaterializer.java`,
  `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java`
  (`OWNED_CONCEPT_PATTERN`)
- Docs: [`../NKD_LOCAL_COPY_SNAPSHOT.md`](../NKD_LOCAL_COPY_SNAPSHOT.md),
  [`../PG_TDB2_CONSISTENCY.md`](../PG_TDB2_CONSISTENCY.md)
- Related records: [`0001`](./0001-ontology-rename-stale-inscheme.md) (shares the
  `OWNED_CONCEPT_PATTERN` + `inScheme` seam; different mechanism — stale *own* scheme after rename vs
  copied *foreign* scheme)