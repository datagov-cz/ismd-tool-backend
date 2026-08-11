# The Diagram Layer: Architecture & Design

> Status: **built** — entity/migration, service, controller, and security are implemented and covered by the
> test suite. Remaining before release: an end-to-end run against dev Postgres + Fuseki. Czech version:
> [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md). FE/REST contract: [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md).

A ReactFlow-based canvas that renders and edits an ISMD ontology visually — one canonical diagram per ontology — with a persistence model designed so the diagram *can never* silently become a divergent copy of your concept data.

## What problem this solves

A diagram is **both** a live picture of a real ISMD ontology **and** a working surface with its own CRUD. That combination is what makes people worry about "drift". This codebase already fought the "two stores holding copies of the same content" battle: the PG↔TDB2 dual-write with no shared transaction, the outbox, the reconciler, a documented baseline of known drift noise (see [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md)). A diagram that stored its own copy of concept content would reopen that exact problem class — a third store — on top of the one just closed.

## Governing principle

> **Content the diagram owns is always a *pending, structural edit to a real concept*, never a free-floating third copy.** The diagram owns three things: *layout* (positions, grouping, viewport), *references* to real concepts (by IRI), and a **pending-edit overlay** — staged, uncommitted structural changes to concepts that already exist and already have an IRI. There are no IRI-less sketch nodes; every node maps to a materialized concept. The overlay is a *diff waiting to be applied*, and an explicit **Převzít (materialize)** flushes it through the existing `/api/concept` CRUD → outbox → RDF, at which point the overlay is cleared and the live concept is again the single owner. A separate **Save** persists the overlay to Postgres without touching RDF.

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

- **Save the diagram** — persists layout *and* the staged structural edits not yet projected to the ontology. Postgres only; **never touches RDF.**
- **Materialize to the ontology** — applies the staged edits to the ontology concepts via the existing `/api/concept` CRUD → outbox → RDF, then clears the overlay.

## Where each write goes

Creating a concept and removing a node are immediate/local; **structural edits stage until materialized.** There is one node kind — every node references a materialized concept.

**Immediate — not staged:**

- **Create a concept from the canvas** → existing `POST /api/concept` create → outbox → RDF. A property or relationship may be created *without a domain* (still fully materialized, a real IRI); the domain is filled in later as a staged edit. (Note: a property always receives an `rdfs:range` — `Literal` by default — so only the *domain* can genuinely be absent.) The FE then places it on the canvas by including it in the next layout save.
- **Add / remove a node from the canvas** → ride the **layout save** (`PUT …/layout`, an idempotent full-replace): a node present is on the canvas, a node omitted is off it. **The concept is untouched** either way. There is no dedicated add/remove-node endpoint and no "delete concept" action on the diagram.

**Staged — Save keeps them in PG, materialize applies them to RDF.** The overlay stages exactly these structural edits, expressed as *end-state field values* on the affected node(s) — not an op-log:

| # | User action | End-state edit | Concept-CRUD on Převzít |
|---|---|---|---|
| 1 | Switch a relationship's direction | swap the VZTAH's `domain` ⇄ `range` | 1 edit |
| 2 | Flip hierarchy direction (B⊐A → A⊐B) | drop the hierarchy link on A, add it on B | 2 edits — **one unit, all-or-nothing** |
| 3 | Change hierarchy type (subclass ⇄ equivalent) | clear the subclass list, populate `exactMatch` (or reverse) | 1 edit |
| 4 | Change a property's parent class | change the VLASTNOST's `domain` (`rdfs:domain`) | 1 edit |
| 5 | Set the domain on a domainless property | fill the VLASTNOST's `domain` | 1 edit |
| 6 | Convert a relationship into a hierarchy | add the hierarchy link on the target class, then delete the VZTAH | 2 calls — **one unit, all-or-nothing** |
| 7 | Remove a property/relationship *from the canvas* | node-row removal only | none (not an RDF change) |

**Hierarchy is type-specific.** "Broader" is three different predicates: a class uses `subClassOf` (`broaderConcept`), a property uses `subPropertyOf` (`superProperty`), a relationship uses `subPropertyOf` (`superRelation`). The overlay carries the field matching the node's concept type. "Equivalent" (op 3) means `skos:exactMatch`, an independent symmetric predicate — *not* a directed hierarchy and *not* a single "hierarchy type" toggle.

**The only RDF delete the diagram can cause is implicit** — op 6's VZTAH deletion, and only after its replacement hierarchy edge is successfully added. There is no free-standing "delete concept" affordance. Op 6 is offered only when nothing points its `domain`/`range` at the VZTAH (else deleting it would transitively cascade other concepts); otherwise the conversion surfaces a conflict.

**Op 6 adds a super-class; it never replaces the target class's hierarchy.** The concept edit model's `broaderConcept` is a *full replace*, so the applier reads the class's current `rdfs:subClassOf` set and passes the union. Without that merge the convert would silently drop every pre-existing super-class — unreported, and unrecoverable in-request because the VZTAH is deleted in the same transaction. Pinned by `op6_preservesTargetClassExistingBroaderConcepts`; the mirrored `nadřazená-třída` predicate is rewritten from the same merged set, so the two never diverge.

## Edges are projections, not content

A relationship (VZTAH) is itself a concept — a node. Its `rdfs:domain`/`rdfs:range` are fields on that node, staged in that node's overlay. The `DOMAIN`/`RANGE` edges drawn from the VZTAH node to its endpoint classes are the *visual rendering* of those fields. A class property (VLASTNOST) is likewise a node, linked to its owning class by a `DOMAIN` edge from the property node to the class node.

Consequently **dragging an edge is a node edit** (repointing a `RANGE` endpoint updates the VZTAH node's `range` overlay), and **drawing a new relationship line is creating a VZTAH concept** (a node operation). Edges never accumulate their own pending state; on read they are re-projected from `live ⊕ overlay`. The node overlay is the single source of truth for domain/range/hierarchy.

## The PG entity model

Three entities in two-plus-one tables, mirroring the `CommentEntity` pattern (FK to `ontologies.id`, pure PG, no outbox). Layout and the pending-edit overlay both live entirely in Postgres.

**`diagrams`** — one canonical diagram per ontology (`@OneToOne` unique FK → `OntologyMetadataEntity`, ON DELETE CASCADE), viewport pan/zoom, a `@Version` optimistic-lock column, and `@OneToMany` node/edge collections (cascade ALL, orphanRemoval). Aggregate helpers `addNode`/`addEdge`/`removeNode` keep callers on managed instances; `touch()` forces the `@Version` bump on node/edge-only changes.

**`diagram_nodes`** — every row references a materialized concept: `concept_iri` **NOT NULL**, `backing` (single-valued `ISMD_CONCEPT`, kept for forward-compat), position, `collapsed`/`hidden`, `parent_node_id`, and `pending_edit_json` — **nullable**; non-null holds the structural overlay diff. `pending_edit_json` **coexists with** `concept_iri` (it is a diff, not a substitute). An entity `@PrePersist`/`@PreUpdate` guard and a Postgres CHECK enforce `concept_iri` always present.

**`diagram_edges`** — endpoints (`source_node_id`/`target_node_id`, both FK-indexed and cascade-deleting), `edge_kind`, and nullable handle anchors. Endpoints + kind only; **no content**.

The overlay content model (`DiagramPendingEdit`) is **structural-only**: `domain`, `range`, the type-resolved hierarchy field (`broaderConcept` / `superProperty` / `superRelation`), `exactMatch`, and a `convertToHierarchy` marker for op 6. It deliberately excludes **label/name editing** — a name change renames the concept's IRI (relocating all its triples), which would strand the diagram node's IRI reference. Label editing stays in the normal concept editor, outside the diagram.

## Materialize semantics

Materialize fans out **in-process** to the existing concept services (not via HTTP self-calls), so it reuses the existing validators and outbox. For each node with a non-empty overlay:

1. Resolve the node's `concept_iri` to the numeric concept id (the edit/delete services key by id). A missing row means the concept was deleted → reported as `skippedStale`.
2. Check the resolved concept belongs to the **diagram's own ontology graph**. The endpoints authorize the ontology slug, but concept IRIs travel inside the body, so an unscoped write would let any authenticated user edit — and via op 6 delete — another user's concepts. A foreign IRI is a rejected request (`FOREIGN_CONCEPT`, 400), not a stale reference. The same check applies to op 6's `addBroaderOn` and `broader`, which name concepts that need never appear on the canvas. `PUT …/layout` enforces it at ingress too, so a foreign IRI is never persisted as a node in the first place.
3. Check the concept has not been edited underneath the overlay since it was staged (a stale-base fingerprint on the concept's `updatedAt`). If it moved, the change is reported as a conflict rather than silently clobbering the intervening edit. Every path that mutates a concept's RDF must stamp `updatedAt`, not just the concept editor — the NKD snapshot UPDATE/REMOVE endpoints and the owner warmer do so too (when they actually produce a delta), or the RDF would move while the fingerprint stayed frozen and this check would silently miss it.
4. Build a **field-scoped** edit carrying only the changed predicates and apply it. Field-scoped (not a full snapshot) is required because the display read model does not expose the `isPublic` boolean, and the edit path strips-then-conditionally-re-adds the public/private classification — a full-snapshot edit with a null `isPublic` would silently drop it. (The one edit helper that isn't null-safe for this is hardened accordingly.)

**Granularity:** per-change, partial-ok. A change spanning two concept-CRUD calls (flip, rel→hierarchy) is all-or-nothing — the second call is gated on the first, and the overlay is cleared only on full success; a failure leaves the whole change staged and reported.

## Versioning

Because the diagram holds no *standalone* concept content, "diagram versioning" stays small. **Layout history** is a pure-PG concern (snapshot layout rows) — deferred; ship a single current layout first. **Staged edits** are transient by design and need no version history. **Concept/ontology versioning** already lives in the existing model (RDF, published-vs-draft, deviations) and the diagram inherits it for free by reading live content.

## Export

PNG/SVG export is a **frontend** concern (`html-to-image` `toPng`/`toSvg` against the ReactFlow viewport, client-side). The backend has no pixel-accurate view of the canvas. A server-side export endpoint only makes sense for headless use (scheduled reports) — a separate, later feature.

---

*ISMD Tool · diagram layer · every node is a materialized concept · staged structural edits as a keyed transient overlay · Save (PG) vs. Převzít (RDF) · per-change all-or-nothing · no third store*
