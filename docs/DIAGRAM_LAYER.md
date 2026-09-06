# The Diagram Layer: Architecture & Design

> Status: **built, covered by the test suite** — entity/migration, service, controller and security are
> implemented. The single-diagram core was verified end to end against local Postgres + Fuseki on
> 2026-08-25; **many-diagrams-per-ontology, cross-diagram conflicts and foreign concepts are covered by
> integration tests against real Postgres but have not yet had that same live smoke run.**
> Czech version: [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md). FE/REST contract:
> [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md).
>
> ⚠ **Breaking FE change.** Every diagram path now carries a diagram id, and a diagram is created
> explicitly rather than appearing on first save. See the API contract's migration note.

A ReactFlow-based canvas that renders and edits an ISMD ontology visually — **many diagrams per ontology**, each a differently-scoped view of the same concepts — with a persistence model designed so a diagram *can never* silently become a divergent copy of your concept data.

## What problem this solves

A diagram is **both** a live picture of a real ISMD ontology **and** a working surface with its own CRUD. That combination is what makes people worry about "drift". This codebase already fought the "two stores holding copies of the same content" battle: the PG↔TDB2 dual-write with no shared transaction, the outbox, the reconciler, a documented baseline of known drift noise (see [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md)). A diagram that stored its own copy of concept content would reopen that exact problem class — a third store — on top of the one just closed.

## Governing principle

> **Content the diagram owns is always a *pending, structural edit to a real concept*, never a free-floating third copy.** The diagram owns three things: *layout* (positions, grouping, viewport), *references* to real concepts (by IRI), and a **pending-edit overlay** — staged, uncommitted structural changes to concepts that already exist and already have an IRI. There are no IRI-less sketch nodes; every node maps to a materialized concept. The overlay is a *diff waiting to be applied*, and an explicit **Převzít (materialize)** flushes it through the existing `/api/concept` CRUD → outbox → RDF, at which point the overlay is cleared and the live concept is again the single owner. A separate **Save** persists layout and the overlay to Postgres without touching RDF.

**Why this stays drift-safe.** The overlay does hold concept content — a deliberate, scoped exception to "never owns content" — but it is safe because it is:

1. **A diff, not a copy** — only *changed* structural fields, keyed to a real IRI, never a standalone concept.
2. **Explicitly transient** — its whole purpose is to be materialized and cleared; a populated overlay is a to-do, not a source of truth.
3. **Single-owner on read** — rendered content is `live concept ⊕ overlay`; once materialized, the overlay is empty and the live concept is the sole owner. No background sync, no diagram reconciler — the overlay is reconciled *by being materialized*.

## Two kinds of divergence — only one was ever dangerous

- **🟢 Layout divergence — safe, by design.** Where a box sits, what's collapsed, the viewport. RDF has no opinion on any of it. Diagram-only, independent, no sync ever.
- **🟡 Pending edits — intentional, bounded, self-healing.** A staged domain/range/hierarchy change not yet materialized. Diagram-owned content, but a keyed diff that exists to be materialized and is cleared on Převzít. It cannot silently persist as a shadow truth: the FE renders it as "N uncommitted changes" and Převzít is a deliberate user action.
- **🔴 A silent third copy — forbidden by construction.** A node holding a *standalone* copy of concept content that drifts with no owner. The overlay is never standalone (always keyed to a live IRI) and never permanent (Převzít empties it).

Everything else is **staleness**, handled at read time: a **dangling reference** (a node points at a concept deleted via normal CRUD → node marked `stale`) and a **coverage gap** (new concepts not yet on the canvas → the diagram is deliberately a subset view). Coverage is computed **on the frontend** — it already holds the full ontology concept list and the on-canvas node IRIs, so "which concepts are not on the canvas" is a client-side set-diff, not a server endpoint.

## The two actions

The canvas exposes exactly two backend-touching actions for content:

- **Save the diagram** (`PUT …/layout`) — persists layout *and* the staged structural edits not yet projected to the ontology. Postgres only; **never touches RDF.**
- **Materialize to the ontology** (`POST …/materialize`) — applies the staged edits to the ontology concepts via the existing `/api/concept` CRUD → outbox → RDF, then clears the overlay.

**Save is one endpoint, carrying both.** An earlier design split staging onto its own `PATCH …/nodes/overlay` call, one concept at a time. That was removed: the canvas holds its whole state client-side and already sends the full layout on every Save, so the split forced a round-trip per staged edit and created a second source of the diagram's version counter, which the FE then had to thread between two differently-shaped responses.

## Full replace vs. additive — the asymmetry inside one call

Within that single Save, the two halves of the payload have deliberately different semantics:

| Payload | Semantics | Omitted / `[]` |
|---|---|---|
| `nodes` | **full replace** — the array *is* canvas membership | canvas emptied (`nodes` itself is mandatory) |
| `edges` | **full replace** of the persisted waypoint set | all edges revert to default routing |
| `overlays` | **additive** — an entry stages or updates one concept | **staged edits untouched** |

**Why overlays cannot be a full replace.** Full replace requires that everything the client must echo back is visible in what a read returns. For overlays it is not:

- a VZTAH whose endpoint class is off-canvas is not projected as an edge, so its overlay never appears;
- a VLASTNOST whose domain class is off-canvas is not rendered as a row, likewise;
- a concept **deleted underneath the diagram** has no live entry at all, so its overlay is invisible — while remaining a deliberate, documented state that materialize reports as `skippedStale`.

In each case the client would be asked to echo back something it was never shown, and a full-replace Save would silently reap it. Worse, the canvas builds its Save body from ReactFlow state, where `overlays` is naturally absent entirely — under full replace that ordinary autosave would destroy **all** staged work.

So an entry stages or updates one concept, a concept absent from the array is untouched, and **discard is explicit**: an entry carrying only `conceptIri`. The cost is losing set-idempotence, which nothing in the client story needs.

## Where each write goes

Creating a concept and removing a node are immediate/local; **structural edits stage until materialized.** There is one node kind — every node references a materialized concept.

**Immediate — not staged:**

- **Create a concept from the canvas** → existing `POST /api/concept` create → outbox → RDF. A property or relationship may be created *without a domain* (still fully materialized, a real IRI); the domain is filled in later as a staged edit. (Note: a property always receives an `rdfs:range` — `Literal` by default — so only the *domain* can genuinely be absent.) The FE then places it on the canvas by including it in the next Save.
- **Add / remove a node from the canvas** → rides the Save's `nodes[]` full-replace: a node present is on the canvas, a node omitted is off it. **The concept is untouched** either way. There is no dedicated add/remove-node endpoint and no "delete concept" action on the diagram.

**Staged — Save keeps them in PG, materialize applies them to RDF.** The overlay stages exactly these structural edits, expressed as *end-state field values* on the affected concept(s) — not an op-log:

| # | User action | End-state edit | Concept-CRUD on Převzít |
|---|---|---|---|
| 1 | Switch a relationship's direction | swap the VZTAH's `domain` ⇄ `range` | 1 edit |
| 2 | Flip hierarchy direction (B⊐A → A⊐B) | drop the hierarchy link on A, add it on B | 2 edits — one per concept |
| 3 | Change hierarchy type (subclass ⇄ equivalent) | clear the subclass list, populate `exactMatch` (or reverse) | 1 edit |
| 4 | Change a property's parent class | change the VLASTNOST's `domain` (`rdfs:domain`) | 1 edit |
| 5 | Set the domain on a domainless property | fill the VLASTNOST's `domain` | 1 edit |
| 6 | Convert a relationship into a hierarchy | add the hierarchy link on the target class, then delete the VZTAH | 2 calls — **one unit, all-or-nothing** |
| 7 | Remove a property/relationship *from the canvas* | node-row removal only | none (not an RDF change) |

**Hierarchy on the canvas is class-only.** "Broader" between classes is `subClassOf` (`broaderConcept`). The property and relationship equivalents (`subPropertyOf`) are **not part of the diagram** — see "Edges are projections" below for why. "Equivalent" (op 3) means `skos:exactMatch`, an independent symmetric predicate — *not* a directed hierarchy and *not* a single "hierarchy type" toggle.

**The only RDF delete the diagram can cause is implicit** — op 6's VZTAH deletion, and only after its replacement hierarchy edge is successfully added. There is no free-standing "delete concept" affordance. Op 6 is offered only when nothing points its `domain`/`range` at the VZTAH (else deleting it would transitively cascade other concepts); otherwise the conversion surfaces a conflict.

**Op 6 adds a super-class; it never replaces the target class's hierarchy.** The concept edit model's `broaderConcept` is a *full replace*, so the applier reads the class's current `rdfs:subClassOf` set and passes the union. Without that merge the convert would silently drop every pre-existing super-class — unreported, and unrecoverable in-request because the VZTAH is deleted in the same transaction. Pinned by `op6_preservesTargetClassExistingBroaderConcepts`; the mirrored `nadřazená-třída` predicate is rewritten from the same merged set, so the two never diverge.

**Op 6 is applied last within a Převzít.** `CONVERT_TO_HIERARCHY` bumps its target class's `updatedAt`; if that class also carries its own overlay in the same run, applying op 6 first would move the fingerprint underneath it and produce a spurious `STALE_BASE`.

## Edges are projections, not content

Every concept is drawn in the shape that matches what it *is*. A **class** (TRIDA) is a node. A **relationship** (VZTAH) is an *edge* between its `rdfs:domain` and `rdfs:range` classes — one edge, carrying its own concept identity, because that is precisely what a relationship means. A **property** (VLASTNOST) has only a domain (its range is a literal datatype, so there is no second concept to connect to), and is therefore a *row inside* the class that owns it.

Crucially, this is a **rendering** decision, not an ownership one. All three remain first-class concepts with their own IRIs, their own `diagram_nodes` row, and their own overlay. Identity is always the concept IRI, which is why an `overlays[]` entry addresses a class, a relationship and a property identically — none of them needs to be a "node" to be staged.

Consequently **dragging an edge endpoint is a concept edit** (repointing an arrow updates the VZTAH's `range` overlay; dragging a property row to another class updates the VLASTNOST's `domain`), and **drawing a new relationship line is creating a VZTAH concept**. Edges never accumulate their own pending state; on read they are re-projected from `live ⊕ overlay`. The concept overlay is the single source of truth for domain/range/hierarchy.

**An edge persists nothing but its waypoints.** Existence, endpoints and kind are all derived, so `diagram_edges` stores only `(edge_key, segments_json)`. Storing endpoints would duplicate a projection and could silently contradict it — repoint a range and a saved endpoint still names the old class. That is the drift class this whole layer is built to prevent, so the columns do not exist.

**Incomplete concepts live off-canvas.** A VZTAH missing an endpoint, or a domainless VLASTNOST, is simply not drawn — there is nothing to attach it to. This costs nothing, because placement *is* completion: such a concept reaches the canvas by being dragged in from the ontology detail, and the drop supplies the missing endpoint. The edge/row model therefore never has to represent a half-built concept, which is the one thing the older node-per-concept model could express and this one cannot.

**This invisibility is exactly why overlays are additive.** An off-canvas endpoint means a staged overlay the canvas draws nowhere — see the asymmetry section above. The read closes the loop with **`pendingEdits[]`**, which lists *every* staged edit whether or not something renders its concept, so an edit is always reachable and always has one stable home. The `pendingEdit` on a node, edge or property row is a copy for the element that draws it, never the only copy: canvas membership changes constantly within a session, and a client must not have to re-derive which of four places owns an edit each time it does.

**Sub-property and sub-relation hierarchy is not rendered.** `rdfs:subPropertyOf` between two properties or two relationships would have to be drawn from a row to a row, or from a line to a line — neither endpoint is a node. Beyond the mechanics, what the user should see or do there is an open business question, so the diagram neither renders nor stages it; the relation stays fully supported in the normal concept editor. See `.planning/diagram-edge-model-REDESIGN.md`.

## Foreign concepts — referenced, never written

A canvas may place a concept from **another ISMD ontology, or from NKD**, so the user can draw a relationship from a concept they own to one they do not. Such a node is marked `is_foreign` and rendered read-only.

**The rule that makes this safe: every link the canvas can draw has its origin on a concept we own.** The triple is written in our graph and the foreign resource is never written. A VZTAH we own carries `rdfs:range` pointing at the foreign class; `rdfs:subClassOf` is written on our child; `skos:exactMatch` is asserted from our side. (`exactMatch` is symmetric in SKOS, so the converse is *entailed* — we assert our half and never write theirs.)

**The flag exempts placement only.** An overlay may never target a foreign concept, because materializing it would write another ontology's RDF. That is enforced at ingress and re-asserted at materialize (`FOREIGN_CONCEPT`), and it is what keeps the cross-tenant write guarantee intact. The flag is also a claim the server verifies both ways: a foreign IRI is accepted only on a node that sets it, and setting it on an own-graph concept is a 400.

**Reads fetch the foreign graphs too**, grouped one fetch per graph rather than per node — without that a foreign node renders label-less and `stale`, indistinguishable from a concept someone deleted. A foreign graph that fails to load degrades its nodes to `stale` rather than failing the whole read.

**Pointing `rdfs:range` at a *published NKD* concept is permitted for a VZTAH only**, and the target is snapshotted as a local copy (`RANGE_TARGET`) exactly like the other NKD links. `rdfs:domain` naming a published concept stays invalid for every type, and a VLASTNOST's `range` stays invalid because it names an XSD datatype, not a concept. See [`NKD_LOCAL_COPY_SNAPSHOT.md`](./NKD_LOCAL_COPY_SNAPSHOT.md).

## The PG entity model

Four entities, mirroring the `CommentEntity` pattern (FK to `ontologies.id`, pure PG, no outbox). Layout and the pending-edit overlay both live entirely in Postgres, in **separate tables with separate lifecycles**.

**`diagrams`** — one row per canvas, **many per ontology** (`@ManyToOne` FK → `OntologyMetadataEntity`, ON DELETE CASCADE), a `name` unique within its ontology, viewport pan/zoom, a `@Version` optimistic-lock column, and `@OneToMany` node/edge collections (cascade ALL, orphanRemoval). Aggregate helpers `addNode`/`addEdge`/`removeNode` keep callers on managed instances; `touch()` forces the `@Version` bump on node/edge-only changes. There is no diagram-owner column — ownership is the ontology's, one join away.

**`diagram_nodes`** — **layout only.** Every row references a materialized concept: `concept_iri` **NOT NULL**, `backing` (single-valued `ISMD_CONCEPT`, kept for forward-compat), position, `collapsed`, `parent_node_id`, `visible_properties_json`. An entity `@PrePersist`/`@PreUpdate` guard and a Postgres CHECK enforce `concept_iri` always present, and a unique index covers `(diagram_id, concept_iri)`.

A row means exactly one thing: **this concept is a box on the canvas.** Classes only — a VZTAH renders as an edge and a VLASTNOST as a row inside its class, so neither ever has a layout row.

**`diagram_pending_edits`** — **staged RDF intent, and nothing visual.** `pending_edit_json` (**NOT NULL** — a row exists only while there is an edit, so discarding deletes it rather than blanking it), `base_updated_at`, unique on `(diagram_id, concept_iri)`. **No position columns.**

Scoped to the **diagram**, with `ontology_metadata_id` kept alongside it. Each canvas stages independently, so two diagrams of one ontology may hold competing edits on the same concept — which is exactly the state [cross-diagram conflict detection](#cross-diagram-conflicts) exists to report. Scoping to the ontology instead would make that collision unrepresentable: the two would share a single row and one save would silently overwrite the other. The denormalized ontology id is what lets the conflict query find *sibling* diagrams cheaply.

> **This reverses an earlier decision.** While there was one diagram per ontology, staging was deliberately ontology-scoped — "a staged edit belongs to the concept, not to whichever canvas staged it" — and that was recorded as forward-compatible with many diagrams. It is not: the moment two canvases can stage, the shared row *is* the conflict, silently resolved by whoever saves last.

> **Why these are two tables.** They were once one row, and because canvas membership is "which node rows exist", that made staging an edit pin its concept to the canvas: removing a node — a purely visual act — was blocked by a purely semantic one. A concept with no box of its own had to fabricate a position (the layout columns are NOT NULL) and got anchored at the origin. Splitting them removes the origin fiction, lets the reap be a plain full replace, and makes "the diagram is a subset view" true in the schema rather than only in intent.

**`diagram_edges`** — `edge_key` (the projected edge id these waypoints belong to: a VZTAH's concept IRI, or the composite `edge|KIND|source|target` of a hierarchy link) and `segments_json`, unique per `(diagram_id, edge_key)`. **Waypoints only** — no endpoints, no kind, no content. A row whose edge no longer projects finds no match on read and is cleared by the next Save; nothing has to hunt down orphans.

The overlay content model (`DiagramPendingEdit`) is **structural-only**: `domain`, `range`, `broaderConcept` (`subClassOf`, TRIDA), `exactMatch`, a `convertToHierarchy` marker for op 6, and `baseUpdatedAt` (the stale-base fingerprint, server-stamped, never accepted on write). It deliberately excludes **label/name editing** — a name change renames the concept's IRI (relocating all its triples), which would strand the diagram node's IRI reference. Label editing stays in the normal concept editor, outside the diagram.

## Save-time reconciliation

`DiagramLayoutReconciler` applies one Save as **two independent halves**:

1. **`nodes[]` → `diagram_nodes`** — update matching rows in place, insert rows for new IRIs, then reap: any persisted row absent from the incoming set is removed. A plain full replace, no carve-outs.
2. **`overlays[]` → `diagram_pending_edits`** — for each entry, graph-check the concept, then upsert its staged edit, or delete the row for a discard (an entry carrying only `conceptIri`).

**Neither half constrains the other.** They share no row, so their order does not matter and neither can undo the other: a class can leave the canvas while keeping its staged edit, and staging an edit never puts a concept on the canvas. Removing a node carries no RDF intent — discarding is a separate, explicit instruction.

**Reap provisions from the persisted set, not the incoming one.** `diagram_nodes` has a plain unique on `(diagram_id, concept_iri)` and the collection is `orphanRemoval`, so if one Save ever produced a remove of a row and an insert for the same IRI, Hibernate would emit the INSERT before the DELETE and the constraint would fire. This is the same hazard the edge reconciler was already hardened against.

**Every overlay target is graph-checked on every Save**, not only when its row is provisioned — otherwise a concept that already has a row could receive a foreign-graph overlay unchecked.

### The stale-base fingerprint

`baseUpdatedAt` records the referenced concept's `updatedAt` at stage time, and materialize refuses to apply an overlay whose fingerprint no longer matches (`STALE_BASE`). The stamping rule is **on first appearance only**:

```
edit.baseUpdatedAt = (prior == null) ? now-fingerprint : prior.baseUpdatedAt
```

With overlays riding *every* Save, re-stamping on each Save would continually refresh the fingerprint and defeat the guard entirely — a concurrent concept edit would be absorbed instead of reported. Comparing the overlay's structural fields instead is worse in the other direction: after a `STALE_BASE`, a user who re-stages the *identical* value would compare equal, keep the stale fingerprint, and 409 forever with no action that clears it.

Stamping on first appearance makes recovery an explicit, reachable sequence: **discard, then stage again.** The discard sets `prior` to null, so the re-stage takes a fresh fingerprint and materialize succeeds. This is why the overlay model needs an explicit discard signal at all, and it is verified both ways — re-send alone still conflicts; discard-then-restage clears it.

## Materialize semantics

Materialize fans out **in-process** to the existing concept services (not via HTTP self-calls), so it reuses the existing validators and outbox. For each node with a non-empty overlay:

1. Resolve the node's `concept_iri` to the numeric concept id (the edit/delete services key by id). A missing row means the concept was deleted → reported as `skippedStale`.
2. Check the resolved concept belongs to the **diagram's own ontology graph**. The endpoints authorize the ontology slug, but concept IRIs travel inside the body, so an unscoped write would let any authenticated user edit — and via op 6 delete — another user's concepts. A foreign IRI is a rejected request (`FOREIGN_CONCEPT`, 400), not a stale reference. The same check applies to op 6's `addBroaderOn` and `broader`, which name concepts that need never appear on the canvas. The Save path enforces it at ingress too, so a foreign IRI is never persisted in the first place.
3. Check the concept has not been edited underneath the overlay since it was staged (the stale-base fingerprint above). If it moved, the change is reported as a conflict rather than silently clobbering the intervening edit. Every path that mutates a concept's RDF must stamp `updatedAt`, not just the concept editor — the NKD snapshot UPDATE/REMOVE endpoints and the owner warmer do so too (when they actually produce a delta), or the RDF would move while the fingerprint stayed frozen and this check would silently miss it.
4. Build a **field-scoped** edit carrying only the changed predicates and apply it. Field-scoped (not a full snapshot) is required because the display read model does not expose the `isPublic` boolean, and the edit path strips-then-conditionally-re-adds the public/private classification — a full-snapshot edit with a null `isPublic` would silently drop it. (The one edit helper that isn't null-safe for this is hardened accordingly.)

**Granularity:** per-change, partial-ok. A change spanning two concept-CRUD calls (op 6) is all-or-nothing — the second call is gated on the first, and the overlay is cleared only on full success; a failure leaves the whole change staged and reported. A flip (op 2) is two independent single-concept edits, reported separately.

## Cross-diagram conflicts

Because staging is per-diagram, two canvases of one ontology can hold competing intent for the same concept. Materialize therefore checks for that **before** applying anything.

**Why it cannot be left to STALE_BASE.** Applying one side moves the concept's `updatedAt` — the very fingerprint the other side's staged edit was stamped against — so the sibling would afterwards fail `STALE_BASE`, one concept at a time, with nothing in the response explaining what moved underneath it. Recovering would mean discard-then-restage per concept. Detecting the collision up front turns that into one decision.

**A conflict is "the same concept staged on two diagrams", not "staged with different values."** Equal values still collide, for the reason above: the first materialize bumps the fingerprint the second is pinned to. Comparing values would let an apparently-harmless pair through and produce a confusing 409 later instead of a clear one now.

**Detection runs before the per-change loop.** The changes are applied in their own `REQUIRES_NEW` transactions, so a check inside that loop would already have committed RDF for everything ahead of the collision. It runs in one transaction of its own, first; a refusal means nothing was written and both sides' staged work is untouched.

**Resolution is the user's, and explicit.** `POST …/materialize` with no `onConflict` reports the conflict and refuses (409). The client re-calls naming a side: `DISCARD_MINE` drops this diagram's conflicting edits, `DISCARD_THEIRS` drops the siblings'. Either way **only the contested concepts are discarded** — never a whole canvas's staged work, which is a far larger act than the user agreed to.

Discarding on a sibling is authorized because both diagrams belong to the one ontology the caller was already authorized against; the sibling rows are still re-read through that ontology scope rather than trusted from the request.

## Versioning

Because the diagram holds no *standalone* concept content, "diagram versioning" stays small. **Layout history** is a pure-PG concern (snapshot layout rows) — deferred; ship a single current layout first. **Staged edits** are transient by design and need no version history. **Concept/ontology versioning** already lives in the existing model (RDF, published-vs-draft, deviations) and the diagram inherits it for free by reading live content.

The `@Version` column is a concurrency guard, not history. It is enforced in the service rather than by JPA: the Save loads the diagram fresh inside its own transaction, so Hibernate would only ever compare the just-read version against itself. The **client's** version — the one it rendered from — is the only value carrying the "has anyone saved since?" signal. Because it belongs to the diagram and not to any node, no node in a response carries a version field.

## The PG write and the Fuseki read are never in one transaction

Every diagram endpoint returns a **fat** response: layout rows from PG joined to live concept content fetched from Fuseki. The obvious implementation — one `@Transactional` method doing both — is wrong on two counts.

**It holds a DB connection across an external HTTP call.** The Fuseki fetch goes through a semaphore whose acquire alone is allowed 30s, before any bytes move. The Hikari pool is 20. A slow or saturated Fuseki therefore doesn't just make diagram requests slow — it pins connections until the pool is empty and unrelated endpoints start failing.

**It rolls back a good write because a read failed.** The diagram layer *never writes RDF* — the only Fuseki calls in the service are `fetchGraph` reads, and they happen strictly after every PG write is done. So there is no dual-write to keep atomic here; the transaction was protecting the write against a failure that cannot corrupt it.

The service therefore splits each public method: a `@Transactional` step (`commitLayout` / `loadForRead`) does the PG work and returns a **detached snapshot** of everything the response needs — version, viewport, node rows, concept types and slugs. The Fuseki fetch and the assembly then run with no transaction open. The step is invoked through a `@Lazy` self-proxy: a direct `this.commitLayout(...)` would bypass the Spring proxy and silently run with no transaction at all.

**Order is write-then-read.** Read-first would avoid the failure mode below, but it pays a full graph fetch on every stale save just to throw it away — and the 409 is the *common* outcome on a shared canvas, not the rare one. It would also widen the window between the version check and the commit. Write-first keeps the cheap PG rejection first and makes the two failures distinguishable: a 409 means the write was refused, a readback failure means it landed.

The cost is a genuinely new state: **committed write, unrenderable response.** That is reported as `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) carrying the post-write version, so the FE reloads instead of retrying into a spurious 409. Returning a generic 500 there would be a lie — the user's change is saved, and a reload would show it. See [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md).

A 409 is clean in the other direction: the Save is refused before anything is written, so staged overlays are left exactly as they were.

## Export

PNG/SVG export is a **frontend** concern (`html-to-image` `toPng`/`toSvg` against the ReactFlow viewport, client-side). The backend has no pixel-accurate view of the canvas. A server-side export endpoint only makes sense for headless use (scheduled reports) — a separate, later feature.

---

*ISMD Tool · diagram layer · many diagrams per ontology · every node is a materialized concept · staged structural edits as a keyed transient overlay, per diagram · foreign concepts referenced, never written · one write endpoint: full-replace layout, additive overlays · Save (PG) vs. Převzít (RDF) · no third store*