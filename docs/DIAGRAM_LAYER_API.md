# The Diagram Layer: FE / REST Contract

> Status: **built** — `DiagramController` implements every endpoint below and the paths are in the
> SecurityConfig allowlist. The contract is stable; FE integration can proceed. Czech version:
> [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md). Architecture & rationale:
> [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md).

The wire contract for the diagram feature: **thin on write, fat on read.** The backend joins layout rows to live concept content and applies each concept's overlay, so the FE receives a payload it can pass almost directly to ReactFlow. This document is the FE integration reference; see [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md) for why the model is shaped this way.

**What is a node, an edge, and a row.** The canvas draws each concept type in the shape that matches what it *is*:

| Concept type | Rendered as | Identity on the wire |
|---|---|---|
| `TRIDA` | a **node** (`classNode`) | `nodes[].id` = `iri:<full-iri>` |
| `VZTAH` | an **edge** between its two classes | `edges[].id` = the relationship's own **concept IRI** |
| `VLASTNOST` | a **row inside** its domain class's node | `nodes[].data.properties[].iri` |

A relationship is one edge, not a node with a link to each endpoint — it connects a `rdfs:domain` class to a `rdfs:range` class, which is exactly what the concept means. A property has only a domain (its range is a literal datatype), so there is no second concept to draw to; it is a row in the class that owns it.

**Incomplete concepts are not on the canvas.** A VZTAH missing its domain or range, and a domainless VLASTNOST, are simply not drawn — there is nothing to attach them to. They are placed by being dragged in from the ontology detail, which is the act that supplies the missing endpoint. This is why the read model never needs a "dangling edge" or "floating property" state.

## REST surface

Controller `DiagramController`, base `/api/diagram`. All responses wrap in `ApiResponseDto<T>`. All paths are authenticated (each must be in the SecurityConfig allowlist). This controller touches *layout + the pending-edit overlay*; **Převzít** fans out in-process to the existing concept services.

| Verb · Path | Purpose | Body → Response |
|---|---|---|
| `GET /all` | Lightweight list of every diagram (identity + node count), e.g. for a diagram picker. Any authenticated user. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Load the canonical diagram, layout joined to live concept content with overlays applied. An ontology with no diagram yet reads as an empty canvas — **the read creates nothing**; the row is provisioned by the first write. | → `DiagramDto` (fat, render-ready) |
| `PUT /{ontologySlug}/layout` | **Save the diagram.** Persist layout (positions, viewport, edge waypoints). Idempotent full-replace — this call **is** canvas membership: a node present is added (a previously-unseen IRI is hydrated in the response), a node omitted is removed from the canvas. **No RDF.** | `DiagramLayoutDto` → `DiagramDto` (fat, hydrated) |
| `PATCH /{ontologySlug}/nodes/overlay` | Stage/update one concept's structural edit (end-state fields) for the concept named by `conceptIri` **in the body**, or **discard** it by sending only `conceptIri` (all overlay fields null → revert to live content). Works for a class, a relationship, or a property alike — all three are addressed by IRI. Not materialized. | `NodeOverlayDto` → node |
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

  // nodes are CLASSES only — a relationship is an edge, a property is a row below
  "nodes": [
    {
      "id": "iri:https://…/pojem/zamestnanec",
      "type": "classNode",
      "position": { "x": 240, "y": 80 },
      "parentId": null,
      "collapsed": false,           // round-trips: what you sent on PUT …/layout comes back here
      "data": {
        "conceptType": "TRIDA",
        "iri": "https://…/pojem/zamestnanec",
        "slug": "pracovni-pomer-zamestnanec",       // FE deep-links to /detail
        "label": { "cs": "Zaměstnanec", "en": "Employee" },
        "stale": false,                              // true ⇒ referenced concept was deleted
        "hasPendingEdits": false,
        // the class's VLASTNOSTi, rendered as rows inside the node. Always present (empty, never null)
        // and ordered by label, so rows do not reshuffle between reads.
        "properties": [
          {
            "iri": "https://…/pojem/datum-narozeni",
            "slug": "pracovni-pomer-datum-narozeni",
            "label": { "cs": "datum narození" },
            "rangeResolved": { /* the datatype — the row's right-hand column */ },
            "stale": false,
            "hasPendingEdits": true,
            "pendingEdit": { "domain": "https://…/pojem/osoba" }   // staged move to another class
          }
        ]
      }
    },
    {
      "id": "iri:https://…/pojem/organizace",
      "type": "classNode",
      "position": { "x": 720, "y": 80 },
      "data": {
        "conceptType": "TRIDA", "iri": "https://…/pojem/organizace",
        "label": { "cs": "Organizace" }, "stale": false, "hasPendingEdits": false,
        "properties": []
      }
    }
  ],

  "edges": [
    {
      // A VZTAH: ONE edge between its two classes, carrying its own concept identity.
      // id IS the relationship's concept IRI — it is unique per edge and stable across reloads.
      "id": "https://…/pojem/je-zamestnan-u",
      "source": "iri:https://…/pojem/zamestnanec",   // its rdfs:domain  (live ⊕ overlay)
      "target": "iri:https://…/pojem/organizace",    // its rdfs:range   (live ⊕ overlay)
      "type": "relationEdge",
      "segments": [{ "x": 120, "y": 40 }],   // FE-only routing waypoints; omitted when default-routed
      "data": {
        "edgeKind": "VZTAH",
        "pending": true,                     // an endpoint comes from an unmaterialized overlay
        "conceptType": "VZTAH",
        "iri": "https://…/pojem/je-zamestnan-u",
        "slug": "pracovni-pomer-je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" }, // label is live-only; NOT editable via the overlay
        "stale": false,
        "hasPendingEdits": true,
        "pendingEdit": { "range": "https://…/pojem/organizace" }   // repointed, not yet in RDF
      }
    },
    {
      // A bare RDF triple — no concept behind it, so `data` carries no iri/label.
      // An edge with a non-null data.iri is concept-backed (selectable, stageable, deep-linkable);
      // one without is a plain hierarchy/equivalence link. That is the FE's discriminator.
      "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "source": "iri:https://…/pojem/zamestnanec",
      "target": "iri:https://…/pojem/osoba",
      "type": "hierarchyEdge",
      "data": { "edgeKind": "SUBCLASS_OF", "pending": false }
    }
  ],

  "pendingChangeCount": 1        // drives the "Převzít N changes" affordance
}
```

## Write — Save layout: `PUT /api/diagram/{ontologySlug}/layout` · `DiagramLayoutDto`

Strip ReactFlow's transient fields (`selected`, `dragging`, `measured`) and send only what persists. The backend ignores node `data` content here — this call is layout only; structural edits go through the overlay endpoint.

**An edge persists exactly two things: `id` and `segments`.** Its existence, endpoints and kind are re-derived from `live ⊕ overlay` on every read, so `source`, `target` and `edgeKind` are **not accepted on write** — sending them is ignored. This is deliberate: a stored endpoint could silently contradict the projection it duplicates, which is precisely the drift the diagram layer is built to prevent. To change where a relationship points, stage `{domain, range}` on its overlay; the edge follows.

**Waypoints are a full replace — echo back every edge whose routing you want kept.** A Save replaces the whole persisted waypoint set, so an edge omitted from `edges` reverts to default routing. The edge itself still renders (it is re-projected from RDF). Repointing an endpoint likewise drops the waypoints by design: geometry drawn for the old target would not fit the new one.

**`edges` itself is optional** — `null` and `[]` both mean "nothing hand-routed", which is exactly the state of a freshly auto-laid-out canvas: ReactFlow has positioned every node, and the user has not dragged a waypoint yet. Only `version` and `nodes` are mandatory.

`segments` is optional too — omit it, or send `[]`, for an edge using default routing; both store as "no waypoints", and neither writes a row at all. Waypoints are pure presentation: they shape how a link is drawn and carry no meaning for the concepts it connects, so nothing derives them from RDF and nothing validates them against it.

**This call is authoritative for canvas membership.** The `nodes[]` array is the complete set — a node present is kept (or **added** if its IRI is new to the canvas; the response `DiagramDto` hydrates its live content), a node omitted is **removed from the canvas** (the concept is untouched). Adding a node needs only `{id, position}`; the backend joins the rest from live RDF.

**`version` is required — send back the one you rendered from.** Because membership is a full replace, a save built on a stale view would silently delete nodes another editor added, taking their staged overlays with them. Echo the `version` from the `DiagramDto` this edit started from (the read, or the response of your own last save). If another editor saved in the meantime the call returns **409** and nothing is written; reload the diagram and re-apply. Every successful `PUT …/layout` **and** `PATCH …/nodes/overlay` advances the version, so always use the newest one you have received. Both return it: `PUT …/layout` in the `DiagramDto`, `PATCH …/nodes/overlay` in the returned node's own `version` field — so staging an overlay never forces a re-read just to stay current.

`version` is **always mandatory** — omitting it is a **400** naming the field, on every save including the first. A canvas with no diagram row yet sends `version: 0`. It is declared required in the schema, so a generated client types it non-optional rather than letting it be dropped silently.

### `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) — the write succeeded, the render data did not

Applies to **both** write endpoints. A diagram write is pure Postgres; the concept content in the response is then read from Fuseki. The two are deliberately **not** in one transaction — the fetch is an HTTP call that can take tens of seconds, and holding a DB connection across it would let a slow Fuseki exhaust the pool and stall unrelated endpoints. It would also discard a perfectly good layout write because a *read* failed.

The consequence is a failure mode with no equivalent before: the write is **committed and durable**, but the response body cannot be assembled.

```jsonc
{
  "success": false,
  "errorCode": "DIAGRAM_SAVED_READBACK_FAILED",
  "message": "Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. …",
  "data": { "version": 8 }        // the version AFTER the committed write
}
```

**Do not retry the write.** The save already happened and the version has advanced; re-sending it with the version you held would be stale and return **409**. Either re-issue `GET …/detail` to render the current state, or continue from the `version` in `data` if you want to save again without that read first. Treat it as "saved, but I can't show you the result yet" — never as "the save failed".

This is the one status where a `success: false` response still means the write landed, which is why it has its own code instead of a generic 500.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  // classes only; a relationship or property is never a node
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false },
    // parentId/collapsed are optional — omitted or null means no parent / not collapsed
    { "id": "iri:https://…/pojem/organizace",
      "position": { "x": 720, "y": 80 } }
  ],
  // waypoints only — echo the id you were given on read; endpoints are derived, never sent
  "edges": [
    { "id": "https://…/pojem/je-zamestnan-u",              // a VZTAH: its concept IRI
      "segments": [{ "x": 120, "y": 40 }] },
    { "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "segments": [] }                                     // [] or omitted = default routing
  ]
}
```

`edgeKind` (read-side only) ∈ `VZTAH` · `SUBCLASS_OF` · `EXACT_MATCH`.

`DOMAIN` and `RANGE` are gone — a relationship is one edge between its two classes, not a node with a link to each. `SUB_PROPERTY` and `SUB_RELATION` (`rdfs:subPropertyOf` between two properties or two relationships) are **not rendered on the canvas**: neither endpoint is a node, so the link has nothing to attach to, and the business semantics are undefined pending a requirement. The relation itself is unaffected — it stays fully supported in the normal concept editor.

## Write — stage a structural edit: `PATCH /api/diagram/{ontologySlug}/nodes/overlay` · `NodeOverlayDto`

**The target is named by `conceptIri` in the body, not in the path.** It addresses a *concept*, not a canvas node — a VZTAH renders as an edge and a VLASTNOST as a row inside its class, and both are staged through this same field by their own IRI. The value is the full IRI, optionally `iri:`-prefixed. It travels in the body because a concept IRI contains slashes, which cannot survive a path segment — percent-encoded, Tomcat rejects `%2F` outright (`400 Invalid URI: [The encoded slash character is not allowed]`); raw, the extra segments match no mapping. `conceptIri` is mandatory (`@NotBlank`).

Only the changed structural fields. Persisted to `pending_edit_json`; not sent to RDF until Převzít. The overlay is **structural-only** — there is no `label`/`name` here; label editing is done through the normal concept editor, not the diagram (a label change renames the concept IRI).

**Discard = a body carrying only `conceptIri`.** A `PATCH` with `{"conceptIri": "iri:…"}` (every overlay field null) clears the node's overlay, reverting it to live content — there is no separate `DELETE …/overlay`. `conceptIri` is addressing, not content, so it never counts toward emptiness. Any payload carrying an overlay field replaces the staged diff. **An explicitly-empty list is *not* a discard — it means "clear this predicate"**: e.g. `{ "conceptIri": "iri:…", "broaderConcept": [] }` stages "remove all superclasses" (the flip op-2 A-side dropping its last broader), and materializes as a `subClassOf` clear.

Every body below also carries `"conceptIri": "iri:…"` naming the concept being staged (omitted here for brevity). **Dragging an edge endpoint is a concept edit** — repointing a relationship's arrow stages `range` on the VZTAH, and dragging a property row into another class stages `domain` on the VLASTNOST:

```jsonc
// op 1 (swap direction, VZTAH):        { "domain": "iri:…/A", "range": "iri:…/B" }
// op 4/5 (property parent / domain):   { "domain": "iri:…/OwningClass" }
// op 3 (subclass → equivalent, TRIDA): { "broaderConcept": [], "exactMatch": ["iri:…/B"] }
// op 2 (flip): staged on BOTH concepts — A: { "broaderConcept": [ …without B ] }
//                                        B: { "broaderConcept": [ …, "iri:…/A" ] }
// op 6 (rel → hierarchy, on the VZTAH): { "convertToHierarchy": { "addBroaderOn": "iri:…/A", "broader": "iri:…/B" } }
```

Field reference for `DiagramPendingEdit`:

| Field | Applies to | Meaning |
|---|---|---|
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | `subClassOf` list (IRIs) |
| `exactMatch` | any | `skos:exactMatch` list (IRIs) — op 3's "equivalent" |
| `convertToHierarchy` | VZTAH | op 6 marker: `{ addBroaderOn, broader }` — add broader on a class, then delete this VZTAH |

`superProperty` and `superRelation` were **removed** along with the `SUB_PROPERTY`/`SUB_RELATION` edges: the diagram can no longer stage a sub-property/sub-relation change, because it cannot render one for the user to see or undo. Use the normal concept editor.

**Response — the single staged concept, carrying the new `version`.** The PATCH returns just the affected concept's row (not the whole diagram), stamped with the diagram version *after* this write. It is a confirmation payload in the generic node shape — a VZTAH comes back here even though it renders as an edge:

```jsonc
{
  "id": "iri:https://…/pojem/je-zamestnan-u",
  "type": "relationNode",         // the raw row's shape; the canvas still draws this concept as an EDGE
  "position": { "x": 520, "y": 210 },
  "parentId": null,
  "collapsed": false,
  "data": { "conceptType": "VZTAH", "iri": "https://…/pojem/je-zamestnan-u",
            "hasPendingEdits": true, "pendingEdit": { … } },
  "version": 8                    // the advanced version — echo this in your next PUT …/layout
}
```

**`data.properties` is always empty here**, even for a class. Building it needs the whole ontology graph, which would undo the narrowed single-concept read this lean response exists to keep cheap — and staging changes one concept's overlay, not any class's property list. Keep the rows from your last `GET …/detail`; re-read only when you staged a property's own `domain`, which is the one case that moves a row between classes.

`version` appears **only** on this lean stage response, where there is no enclosing `DiagramDto` to carry it. Inside `GET …/detail`'s `nodes[]` it is omitted: the version belongs to the diagram, not to any one node, and repeating it per node would suggest a per-node lock that does not exist. A discard (body with only `conceptIri`) is a successful PATCH too, so it advances the version and returns the new one the same way.

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
