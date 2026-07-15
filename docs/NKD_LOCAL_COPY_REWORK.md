# NKD local copy of a published resource — rework tracker

> **Temporary doc.** Tracks the A→B→C rework of the local-copy feature on
> `feat/local-snapshot-graph-separation`. When all phases land, its outcomes are folded into
> `NKD_LOCAL_COPY_SNAPSHOT.md` (the permanent doc) and **this file is deleted**.
> Plan of record: `.planning/nkd-working-copy-bugfix-source-tag-selective-sync-PLAN.md`.

## Scope

Three phases, shipped together as one branch:

| Phase | What | Status |
|---|---|---|
| **A** | Bugfix: stop rejecting a domain/range that points at a *locally-owned* working copy | **done** (2026-07-15) |
| **B** | Rename `SELF_PUBLISHED` → `WORKING_COPY`; derive + surface a `sourceTag` | in progress |
| **C** | Selective sync (partial accept) + sever | not started |

Prerequisite (already shipped on this branch, commits `e6236b7` / `2569710`): copies are
**PG-only**, never in TDB2. See `.planning/snapshot-graph-separation-DESIGN.md`.

## The two paths this rework unifies

The feature has always had two "local copy tracked against NKD" cases that differ only in **who
owns the IRI**:

| | **Link target** (built) | **Working copy** (this rework) |
|---|---|---|
| Whose IRI | someone else's, reached via a link | the owner's own IRI, also in NKD |
| Tracked by | `NkdConceptSnapshotEntity` (`origin=LINK_TARGET`) | `ConceptMetadataEntity.is_published=true` |
| Deviation | stored PG snapshot vs live NKD | live local concept vs live NKD |
| Accept model | accept-whole (all-or-nothing) | **selective** (per-field) — Phase C |
| Snapshot row | yes | **no** (none is created) |

`SnapshotOrigin.SELF_PUBLISHED` was reserved for the working-copy case but never implemented —
Phase B1 renames it to the accurate `WORKING_COPY`. **No rows carry it, so no data migration.**

## Terminology (fixed by this rework)

- **working copy** — a local concept whose *own* IRI is published in NKD. Not "self-published".
- **link target copy** — a PG snapshot of a *foreign* published NKD concept a local concept links to.
- **sever** — flip `is_published=false`, ending working-copy tracking. RDF/IRI unchanged.

## Decisions locked

1. **A → B → C sequenced, one branch.** B is the foundation for follow-on suggestions.
2. **Accept-all stays a working copy**; only a **partial** accept severs.
3. **Derive the source tag from `is_published`** — no new column, no migration.
4. **Concept-type change is never syncable** (TRIDA↔VLASTNOST↔VZTAH unsupported by `ConceptEditor`).
5. **`identifier` may be synced** — it derives from label + graph name, so a rename is legitimate.
6. **IRI collision after a sever is the validator module's concern** — out of scope here.

## Phase log

Each phase appends: what changed, what it means for the permanent doc, and what was verified.

### Phase A — bugfix (done, 2026-07-15)

**Problem.** `reconcileNkdLinks` (`ConceptServiceImpl:454`) decides "external" purely by
`!iri.startsWith(ownerGraphScheme)` (`NkdLinkDetector.isExternalUri:99`), then confirms "published"
against live NKD. Neither step asks whether the IRI is **locally owned**. A working copy carries its
NKD IRI, so it reads as external *and* published → an edit whose `rdfs:domain`/`rdfs:range` points at
it is wrongly rejected **HTTP 400**.

**Fix.** Filter locally-owned IRIs out of *both* paths in the service (keep `NkdLinkDetector` IO-free
per its javadoc):
- **reject path** — only ask NKD about `domainRangeTargets − locallyOwned`; skip the call when empty.
- **snapshot path** — exclude locally-owned from `allowedTargets`; a link to an owned working copy is
  a first-class local link, never a `LINK_TARGET` snapshot. The existing removal loop
  (`:475-483`) then tears down any stale row whose target is now owned.
- **same exclusion in `NkdSnapshotWarmer`** (`:77`) or the warmer and the edit hook churn against
  each other (create/remove ping-pong).

Fail-open is untouched: `publishedAmong` still returns `Set.of()` on any NKD exception, so the change
can only ever *reduce* rejections.

**As built.** `reconcileNkdLinks` now runs both detectors first, then subtracts one shared
`locallyOwnedAmong(...)` set from both paths — a single `findByConceptIriIn` over the union of
candidates, so the reject path and the snapshot path cost one query between them, not two. The warmer
needed **no** query at all: `warmGraphInternal` already loads every concept in the graph
(`findByGraphName`), so the owned-IRI set is built from data it holds.

**Deviation from the plan:** the plan put the ownership lookup only in the reject path and had the
snapshot path filter separately. Folding both into one lookup keeps the two paths from disagreeing
about what "owned" means, and the union query is strictly cheaper.

**Doc impact:** `NKD_LOCAL_COPY_SNAPSHOT.md` "Allowed links" says domain/range at a published concept
is hard-rejected — needs the carve-out that a *locally-owned* target is exempt.

### Phase B — rename + source tag (not started)

**B1.** `SnapshotOrigin.SELF_PUBLISHED` → `WORKING_COPY` + javadoc de-"self-published"-ing across
`enums/SnapshotOrigin`, `entity/NkdConceptSnapshotEntity`, `controller/dto/LinkSnapshotDto`,
`controller/dto/GetOntologyDto`. Zero rows affected; compile + snapshot suite is the proof.

**B2.** Derived `enums/ConceptSourceTag { DRAFT, WORKING_COPY }`, computed in the metadata mapper
(MapStruct `@AfterMapping`, so `getAll` and `getConceptDetail` both get it): `WORKING_COPY` iff
`is_published == true`. Exposed as `@JsonInclude(NON_NULL) sourceTag`; `isPublished` stays for
back-compat. Phase C's sever then flips the tag for free.

**Open question for impl:** does `OntologyMetadataModel.is_published` carry the same meaning as the
concept's? Confirm before surfacing `sourceTag` on the ontology.

**Doc impact when it lands:** the permanent doc's `SELF_PUBLISHED`-as-reserved-seam framing
(§"Scope this round" and §"Status") is obsolete — replace with the working-copy path as built.

### Phase C — selective sync + sever (not started)

`POST /api/concept/{conceptId}/sync`, `@PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")`,
**and a `SecurityConfig` allowlist entry** (else it 403s before `@PreAuthorize` runs).

Re-derive every accepted value from live NKD — **never trust client-supplied values**. Build a
`ConceptEditModel` of the concept's existing type carrying **only** accepted fields and route it
through the normal edit path, so validation/merge/rename semantics are identical.

Traps carried forward:
- **`isPublic`** — `updateDataClassification` (`ConceptFieldUpdaters:657`) removes both veřejný and
  neveřejný types up front and re-adds only on `Boolean.TRUE.equals(isPublic)`. Include it **only**
  when explicitly accepted, or the type is silently dropped. *(Verified 2026-07-15.)*
- **Outbox keying** — key the aggregate on the **pre-edit** `metadata.getConceptIri()`; a label-driven
  rename relocates the IRI mid-edit.
- **Sever atomicity** — `is_published=false` and the RDF delta commit in one boundary, mirroring
  `editConcept`.
- **Null early-return reuse** — Phase C leans on every `ConceptFieldUpdaters.update*` early-returning
  on null. ⚠ The plan cited method names that do not exist (`updateName` is `updateNameModel:57`);
  **re-verify the null guard on each updater C actually drives** before relying on it.

**Doc impact when it lands:** new API rows + a working-copy lifecycle section; the "read-only
deviation" framing of the `is_published` path becomes wrong.

## Final doc revision (do this last)

When C lands, revise `NKD_LOCAL_COPY_SNAPSHOT.md` to describe **both** copy paths as built:

- [ ] Retitle away from "Local Copy / Snapshot" toward the two named paths (§Terminology).
- [ ] Drop the `SELF_PUBLISHED`-reserved-seam framing (§"Scope this round", §"Status"); document
      `WORKING_COPY` as real.
- [ ] Add the working-copy path: source tag, selective sync, sever, and *no snapshot row*.
- [ ] Carve out the locally-owned exemption in §"Allowed links" (Phase A).
- [ ] Document `sourceTag` on the concept/ontology DTOs (Phase B2).
- [ ] Add the `/sync` endpoint to §API with the accept-all vs partial semantics.
- [ ] Note the IRI collision after a sever is the validator's concern (out of scope).
- [ ] Refresh §Status: final suite count + the dev smoke test result.
- [ ] **Fix the stale cleanup-script path** — the doc says `.planning/snapshot-graph-separation-cleanup.sh`,
      it actually lives at `src/main/resources/db/scripts/snapshot-graph-separation-cleanup.sh`.
- [ ] Delete this tracker and `.planning/snapshot-graph-separation-DESIGN.md`'s superseded §5.

## Verification ledger

Filled in as phases land — this is the evidence the rework is done, not the task list.

| Check | Phase | Result |
|---|---|---|
| Owned domain/range → no 400 **and** NKD not queried | A | ✅ `editConcept_domainPointsAtLocallyOwnedWorkingCopy_notRejectedAndNkdNotQueried` |
| Foreign + published domain/range → still 400 | A | ✅ pre-existing `editConcept_domainPointsAtPublishedNkd_rejectedAs400` still green |
| Mixed → 400 names only the foreign IRIs | A | ✅ `editConcept_domainMixesOwnedAndForeignPublished_rejectsOnlyForeign` (also asserts the owned IRI is never sent to NKD) |
| Link to an owned target → no snapshot created | A | ✅ `editConcept_superclassIsLocallyOwnedWorkingCopy_notSnapshotted` |
| Target became owned → stale snapshot torn down | A | ✅ `editConcept_linkTargetBecameOwned_staleSnapshotRemoved` |
| NKD outage, only-foreign candidates → fail-open (no 400) | A | ✅ pre-existing `editConcept_domainPointsAtNkd_nkdOutage_failsOpen` still green |
| Warmer does not churn against the edit hook on owned targets | A | ✅ `warmGraph_targetIsLocallyOwnedWorkingCopy_notSnapshotted` |
| ⚠ Unit-level only — no live NKD/Fuseki run yet (see dev smoke test below) | A | pending |
| Working copy → `sourceTag=WORKING_COPY`; plain draft → `DRAFT` | B | — |
| Enum rename compiles; snapshot suite green | B | — |
| Partial accept → only those fields change in Fuseki | C | — |
| Partial accept → `is_published=false`, tag flips to `DRAFT`, deviation gone | C | — |
| Accept-all → matches NKD, `is_published` stays `true`, tag stays `WORKING_COPY` | C | — |
| Type/`typ` never offered as an accepted field | C | — |
| Full suite (baseline **1379/0**, 4 skipped as of 2026-07-15) | all | ✅ **1384/0**, 4 skipped after A (+5) |
| Dev smoke test, `outbox.enabled=true`, live NKD, real Fuseki/PG | all | — |
| Reconciler dry-run: zero `RDF_ORPHAN` for the synced concept | all | — |