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
| **B** | Rename `SELF_PUBLISHED` → `WORKING_COPY`; derive + surface a `sourceTag` | **done** (2026-07-15) |
| **C** | Selective sync (partial accept) + sever | **done** (2026-07-15) |
| **D** | UI data model: tag the deviation, surface source ref + link snapshots on concept detail | **done** (2026-07-15) |

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
7. **`sourceTag` describes its own IRI, never its contents** (confirmed with the user 2026-07-15).
   It goes on **both** concept and ontology. An ontology is `WORKING_COPY` because the *ontology's own
   graph IRI* is in NKD; a working-copy ontology legitimately holds a mix of working-copy and draft
   concepts, since Phase C's sever flips one concept at a time. That mix is a valid state — **not** a
   desync to reconcile, and the ontology tag is **not** a rollup over its concepts.
8. **A working copy gets no snapshot row of itself** (confirmed with the user 2026-07-15). Its own
   deviation is computed live (`checkPublishedConcept` vs its NKD twin at the same IRI); nothing is
   stored, so a sever has nothing to delete — it is `is_published=false` and nothing more.
9. **Snapshots are keyed on the link TARGET, never on the linker.** `reconcileNkdLinks` never asks
   whether the *owner* is a working copy; it asks of each target: external? locally owned? published?
   So a working copy that links a foreign published NKD concept gets a `LINK_TARGET` row **for that
   target**, exactly as a draft concept would — same case, not a special one. A working copy can
   therefore carry both deviation kinds at once, and they are independent claims about two IRIs:
   *"am I still in sync with my own twin?"* (live, no row) and *"is my linked superclass still in sync
   with its source?"* (row-backed).
10. **The test is ownership, not location.** "Links a concept outside its own graph" is the *old*
    (buggy) scheme-prefix test — Phase A subtracts locally-owned IRIs on top of it. A working copy
    linking a published concept **we also own** (same graph, or a working copy in another of our
    ontologies) gets **no row**: we already have it, a copy would be pure duplication. Only
    *not-locally-owned* published targets are snapshotted.

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

### Phase B — rename + source tag (done, 2026-07-15)

**B1.** `SnapshotOrigin.SELF_PUBLISHED` → `WORKING_COPY` + javadoc de-"self-published"-ing across
`enums/SnapshotOrigin`, `entity/NkdConceptSnapshotEntity`, `controller/dto/LinkSnapshotDto`,
`controller/dto/GetOntologyDto`. Zero rows affected; compile + snapshot suite is the proof.

**B2.** Derived `enums/ConceptSourceTag { DRAFT, WORKING_COPY }`, computed in the metadata mapper
(MapStruct `@AfterMapping`, so `getAll` and `getConceptDetail` both get it): `WORKING_COPY` iff
`is_published == true`. Exposed as `@JsonInclude(NON_NULL) sourceTag`; `isPublished` stays for
back-compat. Phase C's sever then flips the tag for free.

**Open question — RESOLVED.** Does `OntologyMetadataModel.is_published` carry the same meaning as the
concept's? **Yes.** Upload sets both by the same test against the same list: the ontology via
`publishedConceptIris.contains(graphName)` (`OntologyUploadServiceImpl:431`), the concept via
`.contains(conceptIri)` (`:474`) — both mean "my own IRI is in NKD." `sourceTag` is therefore derived on
**both**, per locked decision 7.

**As built.**
- `enums/ConceptSourceTag { DRAFT, WORKING_COPY }` — its javadoc carries the describes-its-own-IRI
  invariant so nobody later "fixes" a working-copy ontology holding draft concepts.
- `@AfterMapping deriveSourceTag` on **both** `ConceptMetadataMapper` and `OntologyMetadataMapper`
  (verified in the generated `*MapperImpl`s). Every `toDto` caller gets it without opting in —
  `getAll` (`ConceptServiceImpl:244`), `getConceptDetail` (`:289`), ontology detail.
- **A null `is_published` stays null**, it does not default to `DRAFT` — "not recorded" and "confirmed
  local" are different claims, and `NON_NULL` omits the field rather than asserting the wrong one.
- Swept four javadocs in `NkdConceptSnapshotEntity` that the Option-B commit missed (class-level x2,
  `owningConcept`, `graphName`, `materializedTriples`) — all still claimed the copy reaches TDB2.

**Doc impact:** the permanent doc's `SELF_PUBLISHED`-as-reserved-seam framing (§"Scope this round" and
§"Status") is obsolete — replace with the working-copy path as built.

### Phase C — selective sync + sever (done, 2026-07-15)

**Two deviation use cases, two different accept models** (specified by the user 2026-07-15). Both can
apply to the *same* concept at once — they are claims about different IRIs (decision 9).

| | **UC1 — link target** | **UC2 — working copy** |
|---|---|---|
| Who deviates | the linked foreign NKD concept vs its NKD source | the concept itself vs its own NKD twin |
| Backed by | `NkdConceptSnapshotEntity` (`LINK_TARGET`) | nothing stored — computed live (decision 8) |
| Deviation surfaces on | `GetOntologyDto.linkSnapshots` | `publishedConceptDeviations` |
| Accept model | **all-or-nothing** | **selective** |
| Outcomes | accept ALL (re-snapshot, back in sync) · REMOVE (drop copy **+ the linking property**) | sync ALL (stays `WORKING_COPY`) · sync SOME (→ `DRAFT`, tracking ends) · delete the whole concept |
| Endpoints | `POST /localcopy/{snapshotId}/update` · `DELETE /localcopy/{snapshotId}` — **already built** | **new** `POST /{conceptId}/sync` · existing `DELETE /api/concept/{id}` |

**UC1 needs no new code.** The two endpoints already implement accept-all / remove; Phase C only
verifies them and documents the pairing.

**UC2 — the new `/sync` endpoint.** `POST /api/concept/{conceptId}/sync`,
`@PreAuthorize("@ontologySecurityService.canModifyConcept(#conceptId)")`, **plus a `SecurityConfig`
allowlist entry** (else it 403s before `@PreAuthorize` runs). The FE sends **only** the characteristics
the user wants to sync.

Re-derive every accepted value from live NKD — **never trust client-supplied values**. Build a
`ConceptEditModel` of the concept's existing type carrying **only** the accepted fields and route it
through the normal edit path, so validation/merge/rename semantics are identical.

**Sever = `is_published=false`, nothing more** (decision 8): no row exists to delete. Then
`checkPublishedConcept` early-returns, the deviation disappears, and `sourceTag` flips to `DRAFT` —
all from the one flag. Partial accept severs; accept-all does not (still a faithful working copy).

> **A sever must NOT touch that concept's `LINK_TARGET` rows.** It says "I no longer track my own NKD
> twin" — it says nothing about whether my linked superclass still tracks its source (decision 9).
> Those rows survive and `reconcileNkdLinks` keeps managing them on their own terms.

**UC2 delete** is the existing `DELETE /api/concept/{id}`: it already drops the concept's RDF +
metadata, and `cascadeConceptDeletion` already drops its snapshot rows. Verify, don't build.

**Null early-return reuse — VERIFIED, with one fatal exception (2026-07-15).** Phase C's core idea is
"build a `ConceptEditModel` with only the accepted fields, leave the rest null, let the normal edit path
no-op on them." An audit of every `ConceptFieldUpdaters.update*` confirms the null guard holds
**everywhere except `updateDataClassification`** (`:657`), which has **no null guard at all** and is
called **unconditionally** by all three type editors (`ClassConceptTypeEditor:34`,
`PropertyConceptTypeEditor:39`, `RelationshipConceptTypeEditor:39`).

It strips the existing veřejný/neveřejný `rdf:type` up front (`:663-668`), then re-adds only on
`Boolean.TRUE.equals(isPublic)` (`:676`) or `Boolean.FALSE.equals(isPublic)` (`:680`). **A null takes
neither branch** — so the type is removed and never restored. Confirmed by direct probe against the real
updater: `removed=true, reAdded=false`.

> **Consequence: a naive partial sync silently destroys data.** Any `/sync` that does not accept
> `isPublic` would drop the concept's veřejný/neveřejný classification. The plan's "all others left
> `null`" instruction is therefore **wrong as written** for this one field. Phase C must **carry the
> concept's CURRENT `isPublic` + `privacyProvisions` through unchanged** when they are not among the
> accepted fields — a null is not a safe "don't touch" signal here. This also means the existing
> full-snapshot edit trap (memory `concept_edit_ispublic_not_null_safe`) has the same root cause.

**As built.**
- `utility/published/WorkingCopySyncFields` — the single source of truth for the field vocabulary: which
  deviation keys exist, which are acceptable, and how each one's live-NKD value applies to a
  `ConceptEditModel`. Without it the deviation model, the request DTO and the edit model drift in three
  places. A field deviates **iff** its `PropertyDeviation` is non-null (the comparator only ever emits
  `isDifferent(true)`), which is how deviating fields are enumerated and counted.
- `controller/dto/WorkingCopySyncRequestDto` — `fieldsToAccept` only, **never values**.
- `ConceptServiceImpl.syncWorkingCopy` — re-derives the deviation from live NKD, validates the keys,
  builds a type-correct edit model, routes through `editConcept` (inheriting its lock, aggregate keying,
  validation and reconcile), then severs iff `accepted < deviating`.
- `POST /api/concept/{conceptId}/sync` + `SecurityConfig` allowlist entry.
- **`isPublic`/`privacyProvisions` are carried through at their CURRENT values** (read from the graph via
  the same `hasProperty(RDF.type, verejny)` check the updater uses, so the two cannot disagree) whenever
  the user did not accept them. This is the fix for the data-loss trap above.

**Decisions made during the build:**
1. **Alt names are not syncable.** The deviation carries `Map<String, Object>` (a language may hold
   several alt labels) but `AltNameModel` holds one string per language, so accepting would silently drop
   every label after the first. The key still shows in the deviation; it just cannot be accepted.
   See **§Deferred: alt-name sync** below.
2. **A mismatched hierarchy key no-ops instead of throwing.** `nadřazená-třída` on a VLASTNOST would have
   been a `ClassCastException`; it is now a no-op, so a bad key cannot take the request down.
3. **The sever re-reads the post-edit row** rather than reusing the pre-edit instance — `editConcept` may
   relocate the IRI, and the flag must land on the post-edit row in the same transaction.
4. **The slug is a safe handle across the edit.** `updateMetadataFromEditResult` only ever changes
   `conceptIri`/`conceptName`/`inTezaurus` — never the slug — so `getConceptDetail(slug)` is valid
   after the sync.

Traps handled:
- **Outbox keying** — inherited from `editConcept`, which keys on the pre-edit IRI. Not re-implemented.
- **Sever atomicity** — `syncWorkingCopy` is `@Transactional`; the flag flip and the RDF delta commit in
  one boundary.

**Doc impact:** new API rows + a working-copy lifecycle section; the "read-only deviation" framing of the
`is_published` path becomes wrong.

### Phase D — UI data model (done, 2026-07-15)

Three asks about giving the UI what it needs to act on a deviation. **What the code already does**
(verified 2026-07-15) narrowed each one:

| Ask | Finding | Work |
|---|---|---|
| "snapshotId needed but never surfaced" | `LinkSnapshotDto.snapshotId` **exists** and both assembler paths set it from `s.getId()` — but only on **ontology** detail. `GetConceptDto` carries **no** `linkSnapshots` at all. | Add `linkSnapshots` to concept detail. The `// TODO id is required…` on `NkdConceptSnapshotEntity` is **stale** → delete. |
| "separate models for the two deviations" | One `PublishedConceptDeviationModel` serves both, so `localValue`/`publishedValue` mean different things per case — the FE relabels keyed on `origin`. | **One model + a source tag** (below). |
| "add label + IRI of the deviation SOURCE" | UC1 **already has** it (`NkdConceptRefDto` on `LinkSnapshotDto`). UC2 has **nothing** — the working-copy deviation carries no source ref, so the UI cannot navigate to the NKD twin. | Add `source` to the deviation model, populated in both cases. |

**Decision: one model carrying a source tag — NOT two models** (user, 2026-07-15, after reconsidering an
initial "full split"). Both cases call the *same* `ConceptDeviationComparator.compareConceptDetails()`
over the same 23 fields: the comparison is genuinely identical, only the *interpretation* of the value
pair differs. Splitting the model would fork 23 `compareAndSetX` methods that must then stay in sync
forever (a new characteristic, or an `areDifferent()` fix, would have to land in both) — the per-site
recurrence tax `.planning/snapshot-graph-separation-DESIGN.md` §2 argues against.
`PublishedOntologyDeviationModel` already forked this way; that is precedent, not a reason to repeat it.

**Shipped shape** — `PublishedConceptDeviationModel` gains two fields:
- `origin` — reuses **`SnapshotOrigin`** (`LINK_TARGET` / `WORKING_COPY`); no new enum, and Phase B just
  renamed it to be accurate for exactly this. Tells the FE what the value pair means.
- `source` — `NkdConceptRefDto {iri, label}` of the NKD published resource, for navigation. **Populated
  uniformly on every deviation**, both cases, so the block is self-describing wherever it is embedded.

**`localValue`/`publishedValue` keep their names** (user decision): per-field diffs stay byte-identical,
only the envelope gains fields. Purely additive — nothing the FE reads today changes.

> **Known duplication (accepted):** for UC1 on ontology detail the same `{iri, label}` appears twice —
> once as `LinkSnapshotDto.nkdConcept`, once as the nested `deviation.source`. Harmless, and the price of
> one uniform code path over "populate it only where it's missing."

**As built.**
- `PublishedConceptDeviationModel` gains `origin` + `source`. `ConceptDeviationComparator` gains a
  4-arg `compareConceptDetails(local, published, origin, source)` overload that delegates to the
  existing 2-arg comparison and stamps the envelope — **the 23-field logic is untouched and unforked**.
  The source label is taken from the **NKD** side (`publishedConcept`), never the local one.
- All **three** comparison call sites now stamp: `ConceptServiceImpl.checkPublishedConcept` and
  `PublishedResourceUtil.checkSingleConceptDeviation` (both `WORKING_COPY`, source = the concept's own
  IRI) and `NkdSnapshotServiceImpl.computeDeviation` (`snapshot.getOrigin()`, source = `nkdIri`).
- **Error envelopes stamp too.** `ENDPOINT_UNAVAILABLE` / `CONCEPT_NOT_FOUND_IN_NKD` / `QUERY_ERROR`
  previously carried only a status; they now carry `origin` + `source` (IRI, no label — it lives on the
  NKD concept we could not fetch), so the FE can render the card even when NKD is down.
- `GetConceptDto.linkSnapshots` (`NON_NULL`, a flat `List` — already scoped to one owner, unlike the
  ontology's map). `ConceptServiceImpl.surfaceLinkSnapshots` mirrors the ontology path: reuses
  `LinkSnapshotAssembler`, the snapshot row is the cache (no NKD call), cold/stale → `PENDING` + async
  warm, and it is try/caught so a snapshot problem never costs the user their concept page.
- Deleted the stale `// TODO id is required…` on `NkdConceptSnapshotEntity.id` — Phase D disproved it.

**Behaviour change worth knowing:** concept detail can now fire the async warmer (it previously never
did). Guarded by `!rows.isEmpty()`, so a concept with no snapshot rows costs nothing — unlike ontology
detail, this path does **not** warm on a zero-row cold start, because a single concept's page should not
trigger a whole-graph scan.

**Test-mock breakage (caught by the suite):** two pre-existing tests stubbed the 2-arg
`compareConceptDetails`, which production no longer calls — one failed loudly, and two `never()`
verifications would have passed **vacuously** against a method that is no longer invoked. All updated to
the 4-arg form and now assert the tag.

## Deferred: alt-name sync (revisit — large blast radius)

`alternativní-název` is the one deviating field the user cannot accept, and closing that gap is **not** a
Phase C-sized change.

**The mismatch.** NKD/detail model alt names as `Map<String, Object>` — one language key may hold *a
string or a list*, because a concept can carry several `skos:altLabel`s per language. `AltNameModel`
holds `Map<String, String>`: exactly one label per language. There is no lossless mapping in that
direction, so any "coerce and move on" fix silently drops labels — the same failure class as the
`isPublic` trap, just quieter.

**Why it is expensive.** `AltNameModel` is not internal to the sync path — it is on the **public
create/edit API contract**:

| Touch point | Why it hurts |
|---|---|
| `models/concept/AltNameModel` | the shape itself |
| `models/concept/ConceptEditModel` + `ConceptCreateModel` | **request bodies** — changing the shape is an FE-breaking API change |
| `utility/editor/ConceptFieldUpdaters.updateAltNameModel` | writes one literal per language; must become per-language multi-value |
| `utility/creator/ConceptCreator` | same on the create path |
| `utility/published/WorkingCopySyncFields` | the sync mapping this would unblock |

~17 references in `src/main`, ~38 in `src/test`. The FE sends `altNameModel` today, so the shape change
needs a coordinated FE cut-over (or a tolerant-reader migration accepting both shapes).

**Options when revisited:** (a) widen `AltNameModel` to `Map<String, List<String>>` and migrate the FE;
(b) keep the API shape and add a sync-only multi-value path; (c) accept lossiness deliberately, with the
drop surfaced to the user rather than silent. **Not decided.** Until then the key stays visible in the
deviation and unacceptable in `/sync`, which is honest: the user sees the drift and cannot silently
destroy data by "fixing" it.

## Final doc revision (do this last)

When C lands, revise `NKD_LOCAL_COPY_SNAPSHOT.md` to describe **both** copy paths as built:

- [ ] Retitle away from "Local Copy / Snapshot" toward the two named paths (§Terminology).
- [ ] Drop the `SELF_PUBLISHED`-reserved-seam framing (§"Scope this round", §"Status"); document
      `WORKING_COPY` as real.
- [ ] Add the working-copy path: source tag, selective sync, sever, and *no snapshot row*.
- [ ] Carve out the locally-owned exemption in §"Allowed links" (Phase A).
- [ ] Document `sourceTag` on the concept **and ontology** DTOs (Phase B2), including the
      describes-its-own-IRI invariant (decision 7) — a working-copy ontology may hold draft concepts.
- [ ] Add the `/sync` endpoint to §API with the accept-all vs partial semantics, and document the two
      use cases side by side (UC1 all-or-nothing vs UC2 selective) — they are the feature's core shape.
- [ ] Record the non-syncable fields and **why**: `typ` (no cross-type conversion) and
      `alternativní-název` (lossy `Map<String,Object>` → `AltNameModel`).
- [ ] Document the deviation envelope (Phase D): `origin` + `source` on
      `PublishedConceptDeviationModel`, incl. on error envelopes; note that one model serves both cases
      and `origin` is what disambiguates `localValue`.
- [ ] Update §Surfacing: `linkSnapshots` is now on **concept** detail too (flat list), not only ontology
      detail — the doc currently says "`GetConceptDto` does **not** carry them."
- [ ] Carry over the alt-name deferral (§Deferred) as a known limitation.
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
| Working copy → `sourceTag=WORKING_COPY`; plain draft → `DRAFT` | B | ✅ `SourceTagDerivationTest` (concept + ontology, real generated mappers) |
| Null `is_published` → tag omitted, not defaulted to DRAFT | B | ✅ `concept_nullPublished_tagOmitted` / `ontology_nullPublished_tagOmitted` |
| Working-copy ontology may hold draft concepts (decision 7) | B | ✅ `workingCopyOntology_mayHoldDraftConcepts` |
| Enum rename compiles; no `SELF_PUBLISHED` stragglers in `src/` | B | ✅ grep clean; full suite green |
| ⚠ `sourceTag` not yet confirmed on a live JSON payload (FE contract) | B | pending dev smoke test |
| Only accepted fields are applied; the rest stay null | C | ✅ `WorkingCopySyncFieldsTest.apply_onlyTouchesTheAcceptedField` |
| Accepted values come from NKD, never the client | C | ✅ `apply_takesTheNkdValue_notTheLocalOne` + service re-derives from `fetchPublishedConcept` |
| Type/`typ` never offered as an accepted field | C | ✅ `deviatingKeys_typeNeverOffered` + `validateAcceptedKeys` rejects it |
| Alt name never offered (lossy mapping) | C | ✅ `deviatingKeys_altNameNeverOffered` |
| **Null `isPublic` strips the classification (the trap)** | C | ✅ pinned by `DataClassificationCarryThroughTest.nullIsPublic_stripsClassification_theTrapPhaseCMustAvoid` |
| **Carrying the current `isPublic` preserves it (the fix)** | C | ✅ `carriedThroughIsPublic_preservesClassification` |
| Range → `dataType` for VLASTNOST, `range` for VZTAH | C | ✅ `apply_rangeMapsToDataTypeForProperty_butRangeForRelationship` |
| Mismatched hierarchy key no-ops, never throws | C | ✅ `apply_hierarchyKeyOnWrongType_isNoOpNotCrash` |
| ⚠ Partial accept → `is_published=false`, tag→`DRAFT`, deviation gone | C | **not unit-tested** — `syncWorkingCopy`'s own branch logic needs a live/integration run |
| ⚠ Accept-all → `is_published` stays `true`, tag stays `WORKING_COPY` | C | **not unit-tested** — same |
| ⚠ Only the accepted fields actually change in Fuseki | C | **not unit-tested** — needs the dev smoke test |
| ⚠ A sever leaves the concept's `LINK_TARGET` rows intact (decision 9) | C | **not tested** — assert during the smoke test |
| UC1 accept-all / remove endpoints still behave | C | pre-existing endpoints untouched; suite green |
| UC2 delete = existing `DELETE /api/concept/{id}` | C | not re-verified this round — assert during the smoke test |
| Working-copy deviation stamped `WORKING_COPY` + source | D | ✅ `DeviationSourceTagTest.workingCopy_stampsOriginAndSource` |
| Link-target deviation stamped `LINK_TARGET` + source | D | ✅ `linkTarget_stampsOriginAndSource` |
| Source label comes from the NKD side, not the local one | D | ✅ both tests assert the published-side label |
| Unnamed NKD concept → label null, IRI still present | D | ✅ `unnamedNkdConcept_sourceLabelIsNull_iriStillPresent` |
| Stamping does not disturb the per-field diffs (additive) | D | ✅ `stamping_doesNotDisturbTheFieldDiff` |
| ⚠ `linkSnapshots` actually appears on concept detail JSON | D | **not unit-tested** — needs the dev smoke test |
| ⚠ Concept detail warms only when rows exist (no zero-row scan) | D | **not unit-tested** — same |
| Full suite (baseline **1379/0**, 4 skipped as of 2026-07-15) | all | ✅ A: **1384/0** (+5) · B: **1391/0** (+7) · C: **1403/0** (+12) · D: **1407/0** (+4), 4 skipped |
| Dev smoke test, `outbox.enabled=true`, live NKD, real Fuseki/PG | all | ▶ in progress 2026-07-18 (local stack) — see §Smoke-test run below |
| Reconciler dry-run: zero `RDF_ORPHAN` for the synced concept | all | — pending Phase 5 |

### Smoke-test run — 2026-07-18 (local stack, `slovník-registru-sčítacích-obvodů-a-budov.json-ld`)

Clean-DB start; `outbox.enabled=true`; live NKD; real Fuseki/PG. Fixture upload minted **381 owned
working copies** (149 TRIDA / 51 VLASTNOST / 181 VZTAH), all `is_published=true`, plus **119
`LINK_TARGET` snapshots** for their references into NKD číselníky.

**Phase 0 — baseline:** ✅ all clean — cleanup `count`=0 TDB2 copies, `nkd_concept_snapshots`=0,
concepts/ontologies=0, Fuseki triples=0.

**Phase 1 — upload:**

| # | Check | Result |
|---|---|---|
| 1.1 | Upload wall-clock | ✅ HTTP 200 in 2.7s, no timeout |
| 1.2 | `GET /api/concept/list` → `sourceTag` | ✅ 381/381 `WORKING_COPY` (all published) — **live JSON `sourceTag` contract confirmed (B)** |
| 1.3 | Ontology detail → `ontologyMetadata.sourceTag` | ✅ `WORKING_COPY`, `isPublished=true` — matches PG (B) |
| 1.4 | Working-copy ontology holding drafts | — not exercised: this file produced 0 draft concepts |
| 1.5 | cleanup `count` (TDB2 leak) | ✅ **0** — upload materialised no copies into TDB2 |
| 1.6 | `nkd_concept_snapshots GROUP BY origin` | ✅ **119 `LINK_TARGET`, 0 `WORKING_COPY`** — decision 8 holds; stop-condition clear |

> **Note (minor, FE-facing — root cause found):** the **upload-response DTO** returned `isPublished:false` /
> `sourceTag:"DRAFT"` for the ontology, while PG and both read endpoints report `is_published=true` /
> `WORKING_COPY`. Not async — the flag flips synchronously, but on a **different entity instance**:
> `createOntologyMetadataEntity` sets `is_published=false` (`OntologyUploadServiceImpl:391`) and returns
> that object; `extractAndSaveConceptMetadata` re-fetches by id and flips it to `true` on the *re-fetched*
> instance (`:447`/`:452`), which is what persists. The returned object is a stale sibling → the response
> DTO. Reads are authoritative and correct; only the immediate upload response is wrong. Fix: propagate the
> flag onto the returned `metadata`, or re-read after all writes. Cosmetic; not a rework invariant.
>
> **Fix applied 2026-07-18** (`OntologyUploadServiceImpl`): `extractAndSaveConceptMetadata` now returns the
> publish decision and the caller mirrors it onto the returned model. Verified live on the a103 upload —
> response now shows `isPublished:true`. **Residual:** the DTO's derived `sourceTag` still shows `DRAFT` in
> the upload response (derived separately from `isPublished`, not recomputed post-flip). Read endpoints show
> the correct `WORKING_COPY`. Left as-is — cosmetic, one surface, reads authoritative.

**Phase 2 — Phase A bugfix, live** (needed a 2nd owned vocabulary under a different scheme; uploaded
`a103-…json-ld`, scheme `atom.cuzk.cz/a103`, 219 owned concepts. The single-ontology fixture structurally
cannot produce an "external-scheme yet locally-owned" domain target — every own concept shares one scheme):

| # | Step | Result |
|---|---|---|
| 2.1 | PATCH VZTAH (registru graph) `domain` = owned a103 concept (external scheme, locally owned) | ✅ **HTTP 200**, not 400 (0.13s — NKD never queried); domain change verified in Fuseki — **A (live)** |
| 2.2 | `nkd_concept_snapshots` for that owner + any `atom.cuzk.cz/a103` target | ✅ **0 rows** — owned target not snapshotted as a foreign copy — **A: owned → no snapshot** |
| 2.3 | PATCH `domain` = foreign published NKD IRI (`…/datový/číselníky/pojem/číselník`) | ✅ **HTTP 400** (0.23s — NKD *was* queried), message names exactly that IRI — **A: regression guard** |

> Fixture restored (VZTAH id=1 domain → original `…/pojem/byt`) after the run.
> **Payload note:** the full-snapshot edit rejects `acquisitionMethod` set to the *resolved* `…/položky/vlastní`
> IRI ("Neplatná hodnota pro způsob získání") — the edit validator expects a different value than the detail
> echoes back. Sidestepped by omitting the field; flagged as a possible detail↔edit round-trip mismatch (FE-facing).

**Phase 3 — UC2 selective sync (untested branch).** NKD is third-party/unwritable, so deviations are
manufactured against a **local Fuseki `nkd-simulation` in-memory service** the app's `nkd.sparql.endpoint`
points at (see `.planning/phase3-nkd-simulation-PREP.md`). Same `NkdSparqlClient` code path, controllable
data. Fixture: `region-soudržnosti` (concept id=568, a103 TRIDA, working copy; carries název/definice/popis).
Wiring proven: with an identical twin → `NO_DEVIATION`; a concept absent from the sim → `CONCEPT_NOT_FOUND_IN_NKD`.

*3a — deviation surfaces (drifted `definice` in the sim):*

| # | Check | Result |
|---|---|---|
| 3.1 | `publishedConceptDeviationModel.status` | ✅ **HAS_DEVIATIONS**, `definice` present with local vs published values |
| 3.2 | `.origin` | ✅ **WORKING_COPY** (live) — **D** |
| 3.3 | `.source` | ✅ `{iri, label:"Region soudržnosti"}`, label from the NKD/sim side — **D** |
| 3.4 | `typ`/`alternativní-název` | deferred — not drifting here; the "not acceptable" half verified at 3.18 (sync guard) |

> **⚠ Finding (perf, real — not a test artifact):** the **cold** first read of a concept detail after an app
> restart hangs **~20–40s** (`region-soudržnosti`, repeatedly); warm reads are 0.04–0.07s. Cause is the
> cold-cache referenced-concept enrichment + deviation fetch firing external SPARQL on first load (sim + RPP +
> eSbirka), likely serialized by the NKD client's 4-way concurrency limiter. Real NKD would incur the same.
> Self-clearing per concept. Mitigation during the run: pre-warm with one throwaway read before timed checks.
> Worth a follow-up perf look (async warm on detail, or widen the limiter).

*3b — accept-ALL does NOT sever* (only `definice` drifting → accepting it is accept-all):

| # | Check | Result |
|---|---|---|
| 3.5 | `POST /568/sync {fieldsToAccept:["definice"]}` | ✅ **200** |
| 3.6 | `is_published` after | ✅ **still `true`** — sever branch did NOT fire on accept-all — ⚠row **closed** |
| 3.7 | `sourceTag` | ✅ **still `WORKING_COPY`** — ⚠row **closed** |
| 3.8 | deviation after | ✅ **NO_DEVIATION** — cleared |
| — | local `definice` in Fuseki after | ✅ now = the sim's value ("ZMĚNĚNÁ DEFINICE V NKD…") — accepted value taken from the NKD/sim side, not the client |

> Note: after an active `/sync` the deviation cleared with **no restart** (the write path re-derives from live
> sim and refills the cache); the stale-cache hang only bit the *passive* drift setup (3a). Good sign for FE UX.

*3c — partial accept DOES sever* (fixture switched to id=537 `rozsah-členění`, a working copy that ALSO has a
LINK_TARGET row — snapshot id=150 → `…/datový/číselníky/pojem/položka-číselníku` — so 3.15 is non-vacuous.
Drifted `definice`+`popis`, accepted only `definice`):

| # | Check | Result |
|---|---|---|
| 3.9 | `POST /537/sync {["definice"]}` (1 of 2) | ✅ **200** |
| 3.10 | accepted `definice` in Fuseki | ✅ = sim value ("NKD-DEFINICE-537") — only the accepted field changed |
| 3.11 | **un**accepted `popis` in Fuseki | ✅ **unchanged** (still local "LAU 2…") — ⚠row **closed** |
| 3.12 | `is_published` | ✅ **`false`** — partial → **severed** — ⚠row **closed** |
| 3.13 | `sourceTag` | ✅ **`DRAFT`** — ⚠row **closed** |
| 3.14 | deviation block | ✅ **gone** (NULL) — ⚠row **closed** |
| 3.15 | LINK_TARGET snapshot 150 intact (decision 9) | ⚠ **row DELETED — but this is a SIM ARTIFACT, not a real bug (see below)** |

> **3.15 analysis — decision 9 NOT actually violated.** Root cause pinned: `syncWorkingCopy` applies accepted
> fields via `editConcept` (`ConceptServiceImpl:271-272`), which runs `reconcileNkdLinks`. That re-checks
> whether the link target `položka-číselníku` is still published in "NKD" — but the **sim only contains id=537's
> own twin, not the link target** (`ASK` against sim = false). So the publication-check returns 0 → the target
> drops from `allowedTargets` → the existing "target no longer published → tear down snapshot" loop fires
> (same path as `editConcept_linkTargetBecameOwned_staleSnapshotRemoved`). Against **real NKD**,
> `položka-číselníku` IS published → it stays in `allowedTargets` → snapshot survives → **decision 9 holds**.
> The RDF link triple (`subClassOf položka-číselníku`) survived the sever — only the tracking row was torn down,
> and only because the sim is incomplete. **To prove 3.15 cleanly, re-run with the link target ALSO seeded into
> the sim** (below). This also surfaces a real design note: a `/sync` re-runs full link reconciliation, so an
> NKD outage during sync could transiently tear down snapshots (fail-open covers reject, but the removal loop
> runs on an empty published-set) — worth a follow-up look.

*3.15 re-test — decision 9 proven cleanly* (fresh fixture id=6 `číselník-53…`, snapshot id=1 →
`…/datový/číselníky/pojem/číselník`; this time the **link target was ALSO seeded into the sim** as a published
concept, then drifted `popis`+`název` and accepted only `popis`):

| # | Check | Result |
|---|---|---|
| — | `is_published` after partial sync | ✅ **false** — severed |
| **3.15** | LINK_TARGET snapshot id=1 after sever | ✅ **INTACT** (origin `LINK_TARGET`, target `…/číselník`) — **decision 9 holds** — ⚠row **closed** |

> **Conclusion:** the sever branch never touches LINK_TARGET rows on its own. Snapshots are only torn down when
> the link target genuinely reads as no-longer-published (the existing stale-target removal path). With a
> complete NKD (target published), a sever leaves them intact. The 3c deletion was a sim-completeness artifact,
> now excluded. **Real design note stands:** `/sync`'s `editConcept` sub-call re-runs link reconciliation and
> its removal loop executes even when the published-set is empty — a genuine NKD outage mid-sync could tear down
> a snapshot; fail-open guards the *reject* path but not the *teardown*. Follow-up candidate.

*3e — sync guard rails* (order matters: `syncWorkingCopy` checks HAS_DEVIATIONS *before* `validateAcceptedKeys`,
so 3.18 needs a genuinely-deviating fixture — used id=8 `plocha-ostatních-prostor-bytu` drifted on `popis`):

| # | Step | Result |
|---|---|---|
| 3.17 | `/sync` on a draft (severed id=6) | ✅ **400** "Pojem není pracovní kopií…" |
| 3.18a | `/sync {["typ"]}` on a deviating WC | ✅ **400** "Typ pojmu nelze synchronizovat — ISMD nepodporuje převod mezi typy pojmů." |
| 3.18b | `/sync {["alternativní-název"]}` | ✅ **400** "Neznámé nebo nesynchronizovatelné vlastnosti: [alternativní-název]" |
| 3.19 | `/sync` on a WC with no deviation (id=8 pre-drift) | ✅ **400** "Pojem se neliší…" |
| 3.20 | `/sync` unauthenticated | ✅ **401** (not 404) — SecurityConfig allowlist entry proven |
| 3.4 | `typ`/`alternativní-název` present-but-not-acceptable | ✅ closed via 3.18 — both keys are hard-rejected — **C** |

*3d — the isPublic data-loss trap, live* (fixture id=2 `pořadové-číslo-bytu`, a **`veřejný-údaj`** working
copy; drifted `popis`, accepted it — a NON-isPublic field):

| # | Step | Result |
|---|---|---|
| 3.16 | `POST /2/sync {["popis"]}`; then ASK Fuseki for the `veřejný-údaj` `rdf:type` | ✅ **classification SURVIVES** — `veřejný-údaj` still present after sync; `popis` was written (proves the RDF was actually re-derived) — **C: the fix holds live** |

> The trap (`concept_edit_ispublic_not_null_safe`): `updateDataClassification` has no null guard; a sync that
> re-writes the concept for an unrelated field would strip the veřejný/neveřejný `rdf:type` unless `isPublic` is
> carried through. Asserted in **Fuseki** (not the DTO) — the trap is a dropped `rdf:type`. Phase C carries it
> through; live-confirmed clean.

**Phase 3 COMPLETE** — 3a/3b/3c/3d/3e all green. Every ⚠ sync-branch row in the ledger closed (sever
one-way direction correct both ways, only-accepted-fields, decision 9, isPublic trap, guard rails, live
origin/source stamping). NKD simulated via local Fuseki `nkd-simulation` service; identical code path.