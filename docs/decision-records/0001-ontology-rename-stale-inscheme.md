---
id: 0001
title: Ontology rename leaves concept skos:inScheme on the old scheme
status: applied
date: 2026-07-13
authors: Richard Koubek
tags: [ontology-editor, reconciler, rdf, data-repair]
supersedes: -
superseded_by: -
---

# 0001 — Ontology rename leaves concept `skos:inScheme` on the old scheme

## Summary

An ontology rename updated each concept's IRI and the ontology graph name but **copied every
concept's `skos:inScheme` object verbatim**, leaving it pointing at the pre-rename scheme. Such a
concept's IRI no longer prefix-matches its scheme, so `OWNED_CONCEPT_PATTERN` stops resolving it:
it becomes invisible to the resolver and upload gate, and the PG↔TDB2 reconciler mis-flags it as
`PG_MISSING_RDF`. Fixed in `OntologyEditor.updateAllConceptIRIs` (force `inScheme` → new ontology
IRI on rename) and repaired the one affected dev concept by hand.

## Context

On deployed **dev**, the reconciler admin report showed one persistent finding:

```json
"countsByCategory":{"RDF_ORPHAN":0,"PG_MISSING_RDF":1,"IRI_GRAPH_MISMATCH":0,
"GRAPH_ORPHAN":0,"SUSPECTED_RENAME":0,"EXCLUDED_NO_INSCHEME":0,"RDF_NOT_OWNED_RESOLVABLE":0}
```

The single mismatch:

```json
{"category":"PG_MISSING_RDF",
 "graphName":"https://slovník.gov.cz/testovací-slovník-test",
 "conceptIri":"https://slovník.gov.cz/testovací-slovník-test/pojem/test-jajsajajajajaja",
 "relatedIri":null,
 "detail":"PG row with no owned-resolvable RDF in ANY graph (failed delete, failed create, or lost inScheme)"}
```

Scan counts were **178 owned RDF / 179 PG** — a one-row PG surplus. The concept IRI sits under its
own graph's namespace, so it is not the known a3791 referenced-concept baseline noise (foreign IRIs
that fail `STRSTARTS`). In the UI the concept was reachable, but its linked property's resolved
`rdfs:domain` showed "Nelze najít pojem v NKD/ISMD".

## Investigation

1. **RDF present, not missing.** Querying Fuseki for all triples of the concept returned a full,
   rich concept (labels, definitions, types, ELI/RPP links). The `PG_MISSING_RDF` label was
   therefore not a real dual-write gap.

2. **The tell.** The concept's `skos:inScheme` pointed at
   `https://slovník.gov.cz/testovací-slovník-změna-test-test-123`, while the concept lives in graph
   `https://slovník.gov.cz/testovací-slovník-test` and its IRI is under
   `.../testovací-slovník-test/pojem/...`. The scheme in `inScheme` differs from the graph/IRI it
   resides in.

3. **The ownership gate.** `JenaTDB2Repository.OWNED_CONCEPT_PATTERN` is
   `?concept skos:inScheme ?scheme . FILTER(STRSTARTS(STR(?concept), STR(?scheme)))`. Because the
   concept IRI does **not** start with `.../testovací-slovník-změna-test-test-123`, the filter
   rejects it → the IRI never enters `ownedIriToGraphs` → the PG row is flagged `PG_MISSING_RDF`
   even though full RDF exists. The same gate backs the live resolver and the upload write-gate,
   which is why the UI `rdfs:domain` resolution failed too.

4. **Ruled out a live dual-write gap; suspected rename.** The stale scheme
   (`...-změna...`, "změna" = change) and the junk labels pointed at a rename that didn't propagate
   `inScheme`.

5. **Outbox audit trail.** Queried `outbox_entry` for the concept aggregate: 4 `UPSERT_CONCEPT`
   rows (seq 46/89/94/98, 2026-07-08 → 07-13), all **NKD-snapshot** writes (payloads carried
   `nkd-snapshot-of` copies of two a3791 concepts under this owner) — none touched the owner's own
   `inScheme`. A content search of `insert_triples` for the owner's `inScheme` returned **empty**.

6. **PG row settled the origin.** `concepts` row: `created_at` and `updated_at` both **2026-07-01**;
   oldest retained outbox row was **2026-07-07**. So the originating edit was **pruned** (7-day
   retention), not a coverage gap — the edit path *is* outboxed (`ConceptServiceImpl:204`). The
   **slug** `testovací-slovník-změna-test-test-123-test` fossilizes the pre-rename scheme name,
   confirming an ontology rename `...-změna-test-test-123` → `...-test` that updated graph/IRI but
   left `skos:inScheme` on the old scheme.

## Root cause

`OntologyEditor.updateAllConceptIRIs` (`src/main/java/com/dia/ismdtoolbackend/utility/editor/OntologyEditor.java`).
When relocating each concept to the new namespace, the outgoing-triples loop rebuilt every
statement with a **new subject, same predicate, same object** — copying the `skos:inScheme` object
verbatim. `renameOntologyIRI` only rewrites `inScheme` objects that **exactly equal** the specific
`oldIRI` of that rename; any concept whose `inScheme` differed (already stale, or trailing-delimiter
mismatch) was never repaired. Result: on rename, a concept keeps an `inScheme` that no longer
prefix-matches its own (relocated) IRI, permanently failing the ownership gate.

## Decision / Fix

**Code fix.** In `updateAllConceptIRIs`, force each relocated concept's `skos:inScheme` to the new
ontology IRI instead of copying the object:

```java
RDFNode newObject = stmt.getPredicate().equals(SKOS.inScheme)
        ? newScheme                     // model.getResource(newOntologyIRI)
        : stmt.getObject();
```

This makes rename self-healing: it prevents new orphans and, because the picked-up set is every
concept whose IRI prefix-matches the (new) namespace, cleans up existing stale schemes on the next
rename of that ontology.

Guarded by a new regression test `OntologyEditorTest#editOntology_ShouldRewriteConceptInScheme_WhenRenamed`
(E1b), which seeds a concept with `inScheme` → old ontology IRI and asserts it is rewritten to the
new IRI (and the stale one is gone) after rename.

**Data repair (dev, out-of-band).** The already-orphaned concept could not be fixed by the code
change alone (its IRI is under `-test` but scheme under `-změna-...`; it would only be picked up on
a *future* rename). Applied a targeted SPARQL update **directly to Fuseki, bypassing the outbox**
(acceptable for a one-off manual repair of a test concept):

```sparql
WITH <https://slovník.gov.cz/testovací-slovník-test>
DELETE { <…/pojem/test-jajsajajajajaja> skos:inScheme <…/testovací-slovník-změna-test-test-123> }
INSERT { <…/pojem/test-jajsajajajajaja> skos:inScheme <…/testovací-slovník-test> }
WHERE  { <…/pojem/test-jajsajajajajaja> skos:inScheme <…/testovací-slovník-změna-test-test-123> }
```

Repair, not delete: the concept is an NKD-snapshot **owner**, so deleting it would orphan its two
a3791 copies.

## Alternatives considered

- **Fix only in `renameOntologyIRI` (exact-match branch).** Rejected — it only catches `inScheme`
  objects equal to the exact `oldIRI`, which is precisely the branch that already fails for
  already-stale or delimiter-mismatched schemes. Forcing the object in `updateAllConceptIRIs` is
  robust to both.
- **Delete the orphaned concept instead of repairing.** Rejected — it owns materialized NKD
  snapshots; deletion would cascade orphans. Repair is non-destructive and doubles as verification.

## Verification

- `OntologyEditorTest` + related editor/rename suites: **55 tests, 0 failures** after the fix.
- Post-repair Fuseki read returned the corrected scheme
  (`https://slovník.gov.cz/testovací-slovník-test`).
- Reconciler re-run: `totalMismatches: 0`, `PG_MISSING_RDF: 0`, scan counts **179 owned RDF / 179
  PG** (surplus row gone). UI `rdfs:domain` resolution restored.

## Consequences & follow-ups

- **Origin un-auditable by design.** The originating edit (2026-07-01) was pruned by the 7-day
  outbox `done-retention`. Retention is being bumped to **30 days** so comparable origins stay
  visible. (30d covers 07-01 comfortably.)
- **Related but distinct bug still open.** This shares the `OWNED_CONCEPT_PATTERN` + `inScheme`
  seam with the NKD-snapshot `RDF_ORPHAN` guard bug (materialized copies carry NKD's foreign
  `inScheme`), but the mechanism differs (that is a copied *foreign* scheme; this is a *stale own*
  scheme after rename). Auto-repair must not ship before both are resolved.
- **Blast radius.** A per-graph `!STRSTARTS(concept, scheme)` sweep found only this one concept in
  the affected graph; an all-graphs sweep is the recommended way to size any remaining historical
  orphans from pre-fix renames.

## References

- Code: `src/main/java/com/dia/ismdtoolbackend/utility/editor/OntologyEditor.java`
  (`updateAllConceptIRIs`), `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java`
  (`OWNED_CONCEPT_PATTERN`)
- Test: `src/test/java/com/dia/ismdtoolbackend/utility/editor/OntologyEditorTest.java` (E1b)
- Docs: [`../PG_TDB2_CONSISTENCY.md`](../PG_TDB2_CONSISTENCY.md),
  [`../NKD_LOCAL_COPY_SNAPSHOT.md`](../NKD_LOCAL_COPY_SNAPSHOT.md)