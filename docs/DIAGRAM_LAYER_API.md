# The Diagram Layer: FE / REST Contract

> Status: **built** — `DiagramController` implements every endpoint below and the paths are in the
> SecurityConfig allowlist. The contract is stable; FE integration can proceed. Czech version:
> [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md). Architecture & rationale:
> [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md).

The wire contract for the diagram feature: **thin on write, fat on read.** The backend joins layout rows to live concept content and applies each node's overlay, so the FE receives a payload it can pass almost directly to ReactFlow. This document is the FE integration reference; see [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md) for why the model is shaped this way.

## REST surface

Controller `DiagramController`, base `/api/diagram`. All responses wrap in `ApiResponseDto<T>`. All paths are authenticated (each must be in the SecurityConfig allowlist). This controller touches *layout + the pending-edit overlay*; **Převzít** fans out in-process to the existing concept services.

| Verb · Path | Purpose | Body → Response |
|---|---|---|
| `GET /all` | Lightweight list of every diagram (identity + node count), e.g. for a diagram picker. Any authenticated user. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Load the canonical diagram, layout joined to live concept content with overlays applied. An ontology with no diagram yet reads as an empty canvas — **the read creates nothing**; the row is provisioned by the first write. | → `DiagramDto` (fat, render-ready) |
| `PUT /{ontologySlug}/layout` | **Save the diagram.** Persist layout (positions, viewport, edges-as-projections) *and* node overlays. Idempotent full-replace — this call **is** canvas membership: a node present is added (a previously-unseen IRI is hydrated in the response), a node omitted is removed from the canvas. **No RDF.** | `DiagramLayoutDto` → `DiagramDto` (fat, hydrated) |
| `PATCH /{ontologySlug}/nodes/overlay` | Stage/update one node's structural edit (end-state fields) for the node named by `nodeId` **in the body**, or **discard** it by sending only `nodeId` (all overlay fields null → revert to live content). Not materialized. | `NodeOverlayDto` → node |
| `POST /{ontologySlug}/materialize` | **Převzít.** Apply each staged change via the existing concept CRUD → outbox → RDF; multi-call changes all-or-nothing; per-change partial-ok. | → `MaterializeResultDto` + refreshed `DiagramDto` |

**Canvas membership rides the layout save.** There is no dedicated add/remove-node endpoint. Because `PUT …/layout` is an idempotent full-replace, **add** = include the node (a bare `{id, position}` for a concept not yet on the canvas; the `DiagramDto` response hydrates its label/type/slug from live RDF) and **remove-from-canvas** = omit it. The concept is never touched by either — the single RDF delete the diagram causes is implicit, inside op 6, handled by `/materialize`.

**No concept-delete endpoint, no coverage endpoint.** Creating a concept from the canvas calls the existing `POST /api/concept/{slug}/create` (a property/relationship may be created without a domain), then the FE places it by including it in the next `PUT …/layout`. Coverage ("which concepts are not on the canvas") is a **client-side set-diff** — the FE already holds the full ontology concept list and the on-canvas node IRIs; no server round-trip.

**Node id convention.** `iri:<full-iri>` for every node (all nodes reference a concept). ReactFlow only requires `node.id` be a unique string; this scheme is stable across reloads and lets `PUT …/layout` add a node by IRI without a prior server round-trip.

## Finding diagrams — `GET /api/diagram/all` and `type=DIAGRAM` search

Two ways to surface diagrams to the user:

- **List:** `GET /api/diagram/all` → `List<DiagramSummaryDto>` (`ontologySlug`, `ontologyName`, `graphName`, `nodeCount`, `updatedAt`). Any authenticated user; lightweight (no live-content join).
- **Search:** `GET /api/search?type=DIAGRAM` returns one `SearchResultDto` per ontology that has a diagram (matched on the ontology slug). On a default search (`type` omitted) diagram rows appear alongside `ONTOLOGY`/`CONCEPT` rows; NKD is skipped for `type=DIAGRAM`. `SearchResponseDto.totalDiagrams` carries the total.

**Routing a DIAGRAM search result → diagram detail (bypassing ontology detail).** A DIAGRAM `SearchResultDto` is:

| Field | Value | FE use |
|---|---|---|
| `type` | `DIAGRAM` | branch on this |
| `slug` | the **ontology slug** | **the routing key** → `GET /api/diagram/{slug}/detail` |
| `iri` | synthetic `{graphName}#diagram` | **dedup-only — do not link on it**; it exists so a `type=null` search doesn't collapse the DIAGRAM row into the ontology's `ONTOLOGY` row |
| `id` | the diagram row id | not a concept id; not needed for routing |
| `ontologyIri` | the ontology graph IRI | if you need the ontology identity |
| `isPublished` | the **ontology's** publish state | a diagram has none of its own — it is exactly as visible as its slovník |
| `lastModified` | diagram `updatedAt` | |

**Publish scoping.** A diagram mirrors its ontology's visibility. `?source=UNPUBLISHED` returns only
diagrams of unpublished ontologies (and `totalDiagrams` counts only those); with no publish filter
(`source=ISMD`/`ALL`) diagrams come back regardless of publish state. There is no published-only
source, and no way to publish a diagram independently of its ontology.

So: on `result.type === 'DIAGRAM'`, navigate straight to the diagram using `result.slug`. Never derive a link from `result.iri` for DIAGRAM rows.

## Read — `GET /api/diagram/{ontologySlug}/detail` → 200 · `DiagramDto`

The backend has already joined layout rows to live concept content and applied each node's overlay.

**Read authorization (deliberate).** `GET …/all` and `GET …/detail` are gated by `canViewResource()` — **any authenticated user** may read any ontology's diagram, matching the codebase-wide read posture where every authenticated caller sees all graphs. Only the write paths (`/layout`, `/overlay`, `/materialize`) are ownership-scoped via `belongsToUserBySlug`.

**Write authorization scopes the slug *and* the IRIs.** `belongsToUserBySlug` authorizes the ontology in the path, but every concept IRI travels inside the request body, so the write paths additionally require each referenced concept to belong to the diagram's own ontology graph. A node IRI resolving to another ontology's concept fails `PUT …/layout` with HTTP 400 and persists nothing; the same check re-runs at materialize (`FOREIGN_CONCEPT`) so a row written before this guard existed still cannot be applied, and it covers op 6's `addBroaderOn` / `broader`, which name concepts that need never be on the canvas. A node whose concept row is simply *missing* is not rejected — that is a deleted concept, reported as `skippedStale`.

**Reads never write.** `GET …/detail` is read-only: an ontology with no diagram is served from an unsaved in-memory stand-in, so a non-owner opening someone else's canvas cannot bring a `diagrams` row into existence. The row appears on the first successful write, and a diagram is listed by `GET /all` only once it has actually been saved — opening a canvas does not make it show up there. A diagram belongs to whoever owns its ontology; there is no separate diagram-owner field.

```jsonc
{
  "ontologySlug": "pracovni-pomer",
  "version": 7,                   // echo this in the next PUT …/layout (optimistic lock); null = no diagram row yet
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },

  "nodes": [
    {
      // a live concept, no pending edits — content from RDF, position from PG
      "id": "iri:https://…/pojem/zamestnanec",
      "type": "classNode",
      "position": { "x": 240, "y": 80 },
      "parentId": null,
      "data": {
        "conceptType": "TRIDA",
        "iri": "https://…/pojem/zamestnanec",
        "slug": "pracovni-pomer-zamestnanec",       // FE deep-links to /detail
        "label": { "cs": "Zaměstnanec", "en": "Employee" },
        "stale": false,                              // true ⇒ referenced concept was deleted
        "hasPendingEdits": false
      }
    },
    {
      // a live VZTAH concept WITH a staged structural edit — content is live ⊕ overlay
      "id": "iri:https://…/pojem/je-zamestnan-u",
      "type": "relationNode",
      "position": { "x": 520, "y": 210 },
      "data": {
        "conceptType": "VZTAH",
        "iri": "https://…/pojem/je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" },         // label is live-only; NOT editable via the overlay
        "stale": false,
        "hasPendingEdits": true,
        "pendingEdit": {                             // the structural diff, so FE can badge/diff-view it
          "range": "iri:https://…/pojem/organizace"  // repointed range, not yet in RDF
        }
      }
    }
  ],

  "edges": [
    {
      // projected from the VZTAH node's (live ⊕ overlay) range — reflects the staged repoint
      "id": "e-201",
      "source": "iri:https://…/pojem/je-zamestnan-u",
      "target": "iri:https://…/pojem/organizace",
      "type": "relationEdge",
      "sourceHandle": null, "targetHandle": null,
      "markerEnd": { "type": "arrowclosed" },
      "data": { "edgeKind": "RANGE", "pending": true }   // pending ⇒ endpoint comes from the overlay
    }
  ],

  "pendingChangeCount": 1        // drives the "Převzít N changes" affordance
}
```

## Write — Save layout: `PUT /api/diagram/{ontologySlug}/layout` · `DiagramLayoutDto`

Strip ReactFlow's transient fields (`selected`, `dragging`, `measured`) and send only what persists. The backend ignores node `data` content here — this call is layout only; structural edits go through the overlay endpoint. Edges are projections; sending the current set persists their handles/positions, but the authoritative endpoint value for a staged repoint is always the node overlay (the backend re-projects on read).

**This call is authoritative for canvas membership.** The `nodes[]` array is the complete set — a node present is kept (or **added** if its IRI is new to the canvas; the response `DiagramDto` hydrates its live content), a node omitted is **removed from the canvas** (the concept is untouched). Adding a node needs only `{id, position}`; the backend joins the rest from live RDF.

**`version` is required — send back the one you rendered from.** Because membership is a full replace, a save built on a stale view would silently delete nodes another editor added, taking their staged overlays with them. Echo the `version` from the `DiagramDto` this edit started from (the read, or the response of your own last save). If another editor saved in the meantime the call returns **409** and nothing is written; reload the diagram and re-apply. Every successful `PUT …/layout` **and** `PATCH …/nodes/overlay` advances the version, so always use the newest one you have received.

`version` may be `null` **only** for the very first save of a canvas that has no diagram row yet. Once a diagram exists, a null version is a 409 — a client that never read the current state cannot safely full-replace its membership.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false },
    { "id": "iri:https://…/pojem/je-zamestnan-u",
      "position": { "x": 520, "y": 210 } }
  ],
  "edges": [
    { "id": "e-201", "source": "iri:https://…/pojem/je-zamestnan-u",
      "target": "iri:https://…/pojem/organizace", "edgeKind": "RANGE" },
    // a class property is a DOMAIN edge from the property node to its class
    { "id": "e-202", "source": "iri:https://…/pojem/datum-narozeni",
      "target": "iri:https://…/pojem/zamestnanec", "edgeKind": "DOMAIN" }
  ]
}
```

`edgeKind` ∈ `DOMAIN` · `RANGE` · `SUBCLASS_OF` · `SUB_PROPERTY` · `SUB_RELATION` · `EXACT_MATCH`.

## Write — stage a structural edit: `PATCH /api/diagram/{ontologySlug}/nodes/overlay` · `NodeOverlayDto`

**The target node is named by `nodeId` in the body, not in the path.** A node id is `iri:<full-iri>` and a concept IRI contains slashes, which cannot survive a path segment — percent-encoded, Tomcat rejects `%2F` outright (`400 Invalid URI: [The encoded slash character is not allowed]`); raw, the extra segments match no mapping. `nodeId` is mandatory (`@NotBlank`).

Only the changed structural fields. Persisted to `pending_edit_json`; not sent to RDF until Převzít. The overlay is **structural-only** — there is no `label`/`name` here; label editing is done through the normal concept editor, not the diagram (a label change renames the concept IRI).

**Discard = a body carrying only `nodeId`.** A `PATCH` with `{"nodeId": "iri:…"}` (every overlay field null) clears the node's overlay, reverting it to live content — there is no separate `DELETE …/overlay`. `nodeId` is addressing, not content, so it never counts toward emptiness. Any payload carrying an overlay field replaces the staged diff. **An explicitly-empty list is *not* a discard — it means "clear this predicate"**: e.g. `{ "nodeId": "iri:…", "broaderConcept": [] }` stages "remove all superclasses" (the flip op-2 A-side dropping its last broader), and materializes as a `subClassOf` clear.

Hierarchy is type-specific — send the field matching the node's concept type:

Every body below also carries `"nodeId": "iri:…"` naming the node being staged (omitted here for brevity):

```jsonc
// op 1 (swap direction, VZTAH):        { "domain": "iri:…/A", "range": "iri:…/B" }
// op 4/5 (property parent / domain):   { "domain": "iri:…/OwningClass" }
// op 3 (subclass → equivalent, TRIDA): { "broaderConcept": [], "exactMatch": ["iri:…/B"] }
// op 2 (flip): staged on BOTH nodes —  A: { "broaderConcept": [ …without B ] }
//                                      B: { "broaderConcept": [ …, "iri:…/A" ] }
// op 6 (rel → hierarchy, VZTAH node):  { "convertToHierarchy": { "addBroaderOn": "iri:…/A", "broader": "iri:…/B" } }
```

Field reference for `DiagramPendingEdit`:

| Field | Applies to | Meaning |
|---|---|---|
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | `subClassOf` list (IRIs) |
| `superProperty` | VLASTNOST | `subPropertyOf` list (IRIs) |
| `superRelation` | VZTAH | `subPropertyOf` list (IRIs) |
| `exactMatch` | any | `skos:exactMatch` list (IRIs) — op 3's "equivalent" |
| `convertToHierarchy` | VZTAH | op 6 marker: `{ addBroaderOn, broader }` — add broader on a class, then delete this VZTAH |

## Materialize — `POST /api/diagram/{ontologySlug}/materialize` → `MaterializeResultDto`

Applies every staged change. One entry per staged **change** (a change may span two concepts). Per-change partial-ok; a two-concept change (flip, rel→hierarchy) is all-or-nothing.

```jsonc
{
  "materialized": [
    { "nodeId": 1042, "conceptIri": "https://…/je-zamestnan-u", "op": "SWAP_DIRECTION" }
  ],
  "failed": [
    { "nodeId": 1055, "conceptIri": "https://…/organizace", "op": "SWAP_DIRECTION",
      "error": "VALIDATION", "message": "range must be a class", "status": 400 }
      // change kept staged; user fixes and re-runs Převzít
  ],
  "skippedStale": [
    { "nodeId": 1060, "conceptIri": "https://…/deleted-x" }   // concept gone; change un-applyable
  ]
}
```

`op` ∈ `SWAP_DIRECTION` · `CHANGE_HIERARCHY_TYPE` · `CHANGE_PROPERTY_PARENT` · `CONVERT_TO_HIERARCHY`. (Setting a domainless property's domain and repointing an existing one both report as `CHANGE_PROPERTY_PARENT` — indistinguishable from the overlay.)

**Flip (op 2) materializes as two independent edits.** Reversing a hierarchy (B⊐A → A⊐B) is staged as a `broaderConcept` overlay on *both* nodes; each materializes independently as a `CHANGE_HIERARCHY_TYPE`. There is no atomic two-node flip unit — neither half corrupts RDF on its own, and a half-applied flip is reported per-node in `failed` for the user to re-run. Only `CONVERT_TO_HIERARCHY` (op 6) is a genuinely gated two-call unit.

**Error cases the FE handles:**

- `error: "VALIDATION"` (HTTP 400) — the concept edit failed validation; overlay retained, fix and retry.
- `error: "STALE_BASE"` (HTTP 409) — the underlying concept was edited (via normal `/api/concept`) since the overlay was staged; the FE should reload the diagram and re-stage.
- `error: "CASCADE_CONFLICT"` — op 6 (rel→hierarchy) blocked because another concept's domain/range points at the VZTAH (deleting it would cascade); surface and let the user resolve.
- `error: "FOREIGN_CONCEPT"` (HTTP 400) — a concept IRI in the change belongs to a different ontology than the diagram's own (either the node itself, or op 6's `addBroaderOn` / `broader`). The diagram may only write its own ontology's concepts; a legitimate client never produces this.
- `error: "ERROR"` (HTTP 500) — an unexpected server-side failure; overlay retained. `message` is always the generic `"Nastala neočekávaná chyba."` — the underlying cause is server-logged, never returned, so the FE should show it as-is and not try to parse it.
- `skippedStale` — the referenced concept no longer exists; offer remove-or-recreate.

---

*ISMD Tool · diagram layer · FE / REST contract · thin write / fat read · structural-only overlay · per-change partial-ok materialize*
