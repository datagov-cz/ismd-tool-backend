# The Diagram Layer: FE / REST Contract

> Status: **built and smoke-tested** — `DiagramController` implements every endpoint below, the paths are
> in the SecurityConfig allowlist, and the write path was verified end to end against local
> Postgres + Fuseki on 2026-08-25. Czech version:
> [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md). Architecture & rationale:
> [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md). Verbatim request/response transcripts:
> `.planning/diagram-write-consolidation-FE-EXAMPLES.md`.

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
| `GET /all` | Lightweight list of every diagram across all ontologies. Any authenticated user. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/list` | **The ontology's diagrams**, oldest first — identity and node count only. The navigation list: `diagramId` + `name` is the name-and-link pair. **Read-only; creates nothing.** | → `List<DiagramSummaryDto>` |
| `POST /{ontologySlug}/create` | **Create a new empty canvas.** A blank/absent `name` takes a numbered default, so a create with no name never fails. | `DiagramCreateDto` → `DiagramDto` |
| `GET /{ontologySlug}/{diagramId}/detail` | Load one diagram, layout joined to live concept content with overlays applied. An unknown id is a **404** — the read creates nothing. | → `DiagramDto` (fat, render-ready) |
| `GET /usage/concept/{conceptSlug}` | **Where is this concept drawn?** Every diagram whose canvas shows it, with a link and the structure that canvas displays. For the concept detail page. Any authenticated user. | → `DiagramConceptUsageDto` |
| `PUT /{ontologySlug}/{diagramId}/layout` | **Save the diagram — the only layout write.** Persists layout (positions, viewport, edge waypoints) *and* the staged structural overlays. **No RDF.** | `DiagramLayoutDto` → `DiagramDto` (fat, hydrated) |
| `POST /{ontologySlug}/{diagramId}/materialize` | **Materialize.** Apply each staged change via the existing concept CRUD → outbox → RDF. Refuses with a conflict report when a sibling diagram stages the same concept — see below. | → `MaterializeResultDto` |
| `DELETE /{ontologySlug}/{diagramId}` | Delete one diagram, its layout and its staged edits. **The ontology's concepts are untouched.** | → `null` |

### Paths nest the diagram under its ontology

`/{ontologySlug}/{diagramId}/…` rather than `/{diagramId}/…`, so every write keeps authorizing the slug through the existing `belongsToUserBySlug` — no new security expression, and no new path that could fall through the SecurityConfig allowlist to `denyAll`.

The slug does **not** constrain the id travelling beside it, so the service additionally asserts that the diagram belongs to the named ontology. Addressing another ontology's diagram through your own slug is a **404** (not 403 — the response must not confirm that the id exists elsewhere).

### ⚠ Breaking change: a diagram is created explicitly

Previously `GET …/detail` returned an empty stand-in canvas for an ontology with no diagram, and the first `PUT …/layout` with `version: 0` brought the row into existence. Neither works now — there is no id to address.

**The first-open flow is:** `GET /{slug}/list` → if empty, `POST /{slug}/create` → `GET /{slug}/{diagramId}/detail`.

Reads stay strictly read-only, which is deliberate: a non-owner opening someone else's ontology must not be able to bring a `diagrams` row into existence.

**Diagram names are unique within their ontology** (they are how a user tells two canvases apart, in the picker and in search). A clash on create returns **409** with `errorCode: "DIAGRAM_NAME_CONFLICT"` rather than a generic constraint error.

**One write endpoint.** Layout and structural staging travel in the same call. There is no `PATCH …/nodes/overlay` — it was removed. The canvas holds its whole state client-side and already sends the full layout on every Save, so a separate per-edit round-trip bought nothing and created a second source of the version counter.

**Canvas membership rides the layout save.** There is no dedicated add/remove-node endpoint. Because `nodes[]` is an idempotent full-replace, **add** = include the node (a bare `{id, position}` for a concept not yet on the canvas; the `DiagramDto` response hydrates its label/type/slug from live RDF) and **remove-from-canvas** = omit it. The concept is never touched by either — the single RDF delete the diagram causes is implicit, inside op 6, handled by `/materialize`.

**No concept-delete endpoint, no coverage endpoint.** Creating a concept from the canvas calls the existing `POST /api/concept/{slug}/create` (a property/relationship may be created without a domain), then the FE places it by including it in the next `PUT …/layout`. Coverage ("which concepts are not on the canvas") is a **client-side set-diff** — the FE already holds the full ontology concept list and the on-canvas node IRIs; no server round-trip.

**Node id convention.** `iri:<full-iri>` for every node (all nodes reference a concept). ReactFlow only requires `node.id` be a unique string; this scheme is stable across reloads and lets `PUT …/layout` add a node by IRI without a prior server round-trip.

## Finding diagrams — `GET /api/diagram/all` and `type=DIAGRAM` search

Two ways to surface diagrams to the user:

- **List:** `GET /api/diagram/{ontologySlug}/list` → `List<DiagramSummaryDto>` (`diagramId`, `name`, `ontologySlug`, `ontologyName`, `graphName`, `nodeCount`, `updatedAt`) for one ontology; `GET /api/diagram/all` for every ontology. Any authenticated user; lightweight (no live-content join).
- **Search:** `GET /api/search?type=DIAGRAM` returns **one `SearchResultDto` per diagram** — an ontology with three canvases contributes three rows, matched on the ontology slug **or the diagram's own name**. On a default search (`type` omitted) diagram rows appear alongside `ONTOLOGY`/`CONCEPT` rows; NKD is skipped for `type=DIAGRAM`. `SearchResponseDto.totalDiagrams` carries the total.

**Routing a DIAGRAM search result → diagram detail (bypassing ontology detail).** A DIAGRAM `SearchResultDto` is:

| Field | Value | FE use |
|---|---|---|
| `type` | `DIAGRAM` | branch on this |
| `slug` | the **ontology slug** | half the routing key → `GET /api/diagram/{slug}/{diagramId}/detail` |
| `diagramId` | the diagram's id | **the other half of the routing key** |
| `label` | the **diagram's own name** | what distinguishes two canvases of one ontology in a result list |
| `iri` | synthetic `{graphName}#diagram-{id}` | **dedup-only — do not link on it**; it keeps a DIAGRAM row from collapsing into the ontology's `ONTOLOGY` row on a `type=null` pass, and keeps an ontology's diagrams from collapsing into each other |
| `id` | the diagram row id | same value as `diagramId`; not a concept id |
| `ontologyIri` | the ontology graph IRI | if you need the ontology identity |
| `isPublished` | the **ontology's** publish state | a diagram has none of its own — it is exactly as visible as its slovník |
| `lastModified` | diagram `updatedAt` | |

**Publish scoping.** A diagram mirrors its ontology's visibility. `?source=UNPUBLISHED` returns only
diagrams of unpublished ontologies (and `totalDiagrams` counts only those); with no publish filter
(`source=ISMD`/`ALL`) diagrams come back regardless of publish state. There is no published-only
source, and no way to publish a diagram independently of its ontology.

So: on `result.type === 'DIAGRAM'`, navigate straight to the diagram using `result.slug` **and `result.diagramId`**. Never derive a link from `result.iri` for DIAGRAM rows.

## Authorization

| Call | Owner | Other authenticated user | Anonymous |
|---|---|---|---|
| `GET …/all`, `GET …/list`, `GET …/detail`, `GET /usage/concept/…` | 200 | **200** | 401 |
| `PUT …/layout` | 200 | **403** | 401 |
| `POST …/materialize` | 200 | **403** | 401 |
| `POST …/create`, `DELETE …/{id}` | 200 | **403** | 401 |

**Reads are deliberately open.** `canViewResource()` lets **any authenticated user** read any ontology's diagram, matching the codebase-wide read posture where every authenticated caller sees all graphs. Only the write paths are ownership-scoped via `belongsToUserBySlug`.

**Write authorization scopes the slug *and* the IRIs.** `belongsToUserBySlug` authorizes the ontology in the path, but every concept IRI travels inside the request body, so the write path additionally requires each concept the edit **writes** to belong to the diagram's own ontology graph — the overlay's `conceptIri`, its `domain`, and op 6's `addBroaderOn`. A foreign IRI there fails with **400** and persists nothing; the same check re-runs at materialize (`FOREIGN_CONCEPT`). Endpoints that are only **referenced** (`range`, `broaderConcept`, `exactMatch`, op 6's `broader`) and node placement itself may be foreign — see *Nodes — membership*. A concept row that is simply *missing* is not rejected — that is a deleted concept, reported as `skippedStale`.

**Reads never write.** `GET …/detail` is read-only: an ontology with no diagram is served from an unsaved in-memory stand-in, so a non-owner opening someone else's canvas cannot bring a `diagrams` row into existence. The row appears on the first successful write, and a diagram is listed by `GET /all` only once it has actually been saved. A diagram belongs to whoever owns its ontology; there is no separate diagram-owner field.

## Read — `GET /api/diagram/{ontologySlug}/{diagramId}/detail` → 200 · `DiagramDto`

The backend has already joined layout rows to live concept content and applied each node's overlay.

```jsonc
{
  "diagramId": 4,
  "name": "Pohled HR",            // unique within the ontology
  "ontologySlug": "pracovni-pomer",
  "version": 7,                   // echo this in the next PUT …/layout (optimistic lock)
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
        "readOnly": false,                           // true ⇒ a FOREIGN concept: render non-editable
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

  // EVERY staged edit, whether or not the canvas renders its concept. Always present (empty, never null).
  // `pendingEdits.length` drives the "Převzít N changes" affordance.
  "pendingEdits": [
    { "iri": "https://…/pojem/je-zamestnan-u",
      "conceptType": "VZTAH",
      "slug": "pracovni-pomer-je-zamestnan-u",
      "label": { "cs": "je zaměstnán u" },
      "stale": false,                          // true ⇒ the concept was deleted underneath the diagram
      "pendingEdit": { "range": "https://…/pojem/organizace" } }
  ]
}
```

**`nodes[]` is the canvas: classes only.** A relationship is in `edges[]`, a property in its class's `data.properties[]`. Neither is ever a node — no filtering by `conceptType` is required on the client.

**`pendingEdits[]` is the one stable home for staged work.** It is *complete*, not a leftover: a concept the canvas draws carries its overlay on that element **and** appears here. That redundancy is deliberate — canvas membership changes constantly within a session (drag a class off, drag it back), and a list holding only the invisible edits would move an entry in and out on every such change, forcing the client to re-derive which of four places owns each edit after every save. Identity is the concept IRI; read the edit from here and treat the copy on a node/edge/row as a rendering convenience.

**A node carries no `version`.** The version belongs to the diagram; the key is absent from every node, not present-and-null.

`edgeKind` (read-side only) ∈ `VZTAH` · `SUBCLASS_OF` · `EXACT_MATCH`.

`DOMAIN` and `RANGE` do not exist — a relationship is one edge between its two classes, not a node with a link to each. `SUB_PROPERTY` and `SUB_RELATION` (`rdfs:subPropertyOf` between two properties or two relationships) are **not rendered on the canvas**: neither endpoint is a node, so the link has nothing to attach to, and the business semantics are undefined pending a requirement. The relation itself is unaffected — it stays fully supported in the normal concept editor.

## Concept → diagrams — `GET /api/diagram/usage/concept/{conceptSlug}` → 200 · `DiagramConceptUsageDto`

The inverse of the read above, for the **concept detail page**: given a concept, which canvases draw it? Returns one `placements[]` entry per diagram, each carrying the name-and-link pair (`ontologySlug` + `diagramId`) plus the structure that canvas shows.

```jsonc
{
  "conceptIri": "https://…/pojem/je-zamestnan-u",
  "conceptName": { "cs": "je zaměstnán u" },
  "conceptSlug": "je-zamestnan-u",
  "placements": [
    { "diagramId": 5, "diagramName": "Hlavní diagram", "ontologySlug": "pracovni-pomer",
      "kind": "EDGE",
      "domain": { "iri": "https://…/pojem/zamestnanec", "conceptSlug": "zamestnanec",
                  "conceptName": { "cs": "Zaměstnanec" } },
      "range":  { "iri": "https://…/pojem/organizace",  "conceptSlug": "organizace",
                  "conceptName": { "cs": "Organizace" } },
      "broader": [], "exactMatch": [], "pending": false }
  ]
}
```

**An empty `placements[]` is a normal answer**, not a 404 — the concept exists but no canvas draws it. A 404 means the *slug* is unknown.

### `kind` — three ways to be "on the canvas"

The question sounds like one lookup but is three, because membership is recorded in three different places. `kind` tells the FE what it is looking for, and is **not** a synonym for the concept's type:

| `kind` | Drawn as | Membership lives in |
|---|---|---|
| `NODE` | a class cell | a `diagram_nodes` row |
| `EDGE` | a relationship line | a `diagram_edges` row keyed by the VZTAH's own IRI |
| `PROPERTY_ROW` | a row **inside** its class's cell | that node's `visible_properties_json` |

For `PROPERTY_ROW`, `hostClass` names the class whose cell renders the row — without it the user is told "it is on this diagram" with no way to find it. It is absent for the other kinds.

A property is reported only when its host class actually **lists** it. An uncurated class shows no rows, so a node that merely exists is not enough.

### Structure is per diagram, `pending` says why

`domain` / `range` / `broader` / `exactMatch` are resolved (`{iri, conceptName, conceptSlug, …}`), not bare IRIs, and they are reported **as that diagram currently shows them**: live RDF with that diagram's own staged overlay layered on top, field by field. So two placements of the same concept can legitimately disagree — that disagreement is the point, and `pending: true` marks the canvas whose staged edit causes it.

`domain`/`range` are null for a class, which has neither. `broader`/`exactMatch` are `[]` rather than null when there are none.

### Performance

One PG query for all three membership shapes, one for the overlays, one hierarchy `SELECT`, and one batched (per-IRI cached) resolve for every IRI any placement mentions — **regardless of how many diagrams come back**. It deliberately does *not* reuse `GET …/detail`, which fetches an entire ontology graph per diagram; answering this that way would cost a whole-graph fetch per listed diagram. A concept on no canvas short-circuits in Postgres and never touches Fuseki at all.

## Write — Save: `PUT /api/diagram/{ontologySlug}/{diagramId}/layout` · `DiagramLayoutDto`

One call carries everything: layout **and** the structural overlays. Strip ReactFlow's transient fields (`selected`, `dragging`, `measured`) and send only what persists. The backend ignores node `data` content — structural intent travels in `overlays`, never in a node's `data`.

### The one rule to internalise

**`nodes` and `edges` are a full replace. `overlays` is additive across concepts — but each entry is a full replace of that one concept's overlay.**

| Field | Omitted / `null` | `[]` |
|---|---|---|
| `version` | **400** — always required | — |
| `nodes` | **400** — always required | canvas emptied (staged edits are untouched — a separate table) |
| `nodes[].properties` | that class renders **no** property rows | same |
| `edges` | **edge membership untouched** | every edge removed from the canvas |
| `edges[].segments` | that edge's stored routing **kept** | routing cleared to default |
| **`overlays`** | **staged edits untouched** | **staged edits untouched** |

A concept absent from `overlays` keeps whatever is staged on it. The **only** way to discard an overlay is an entry carrying `conceptIri` and nothing else.

**Additive across concepts, replace within one.** An entry is that concept's *whole* overlay, not a per-field patch: it replaces the staged edit outright, so any overlay field the entry omits is dropped. To change one predicate and keep the rest, send the concept's full staged intent — the read hands it back to you in `pendingEdits[]`, so re-send that object with your change applied.

```jsonc
// staged: { "broaderConcept": ["…/trida-b"] }
{ "conceptIri": "…/trida-a", "exactMatch": ["…/trida-c"] }
// result: { "exactMatch": ["…/trida-c"] }   ← broaderConcept is GONE, not merged
{ "conceptIri": "…/trida-a", "broaderConcept": ["…/trida-b"], "exactMatch": ["…/trida-c"] }
// result: both kept                          ← send the whole overlay
```

**Layout and staged edits are independent.** They live in separate tables, so removing a node from the canvas never discards its staged edit, and staging an edit never puts a concept on the canvas. Removal is pure presentation and carries no RDF intent; discarding is its own explicit instruction.

> **Why `overlays` differs from `edges`.** A staged overlay need not appear in a read at all — its endpoint class may be off-canvas, or the concept may have been deleted underneath the diagram — so the client cannot be asked to echo back what it was never shown. Treating omission as discard would destroy staged work on every save built from canvas state, which is exactly how a ReactFlow-driven autosave is built. The asymmetry is deliberate; do not "fix" it by sending `overlays: []` defensively.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  // classes only; a relationship or property is never a node
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false,
      // the VLASTNOST rows this class renders — a flat IRI array, full-replace like `position`
      "properties": ["https://…/pojem/datum-narozeni"] },
    // parentId/collapsed are optional — omitted or null means no parent / not collapsed
    { "id": "iri:https://…/pojem/organizace",
      "position": { "x": 720, "y": 80 }, "properties": [] },
    // a concept from ANOTHER ontology — placed like any other node; the server marks it read-only
    { "id": "iri:https://…/jiny-slovnik/pojem/osoba",
      "position": { "x": 1100, "y": 80 }, "properties": [] }
  ],
  // waypoints only — echo the id you were given on read; endpoints are derived, never sent
  "edges": [
    { "id": "https://…/pojem/je-zamestnan-u",              // a VZTAH: its concept IRI
      "segments": [{ "x": 120, "y": 40 }] },
    { "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "segments": [] }                                     // [] or omitted = default routing
  ],
  // staged structural edits — ADDITIVE; omit the whole key to leave staged work alone
  "overlays": [
    { "conceptIri": "iri:https://…/pojem/je-zamestnan-u",
      "range": "https://…/pojem/organizace" }
  ]
}
```

### Nodes — membership

**`nodes[]` is authoritative for canvas membership.** A node present is kept (or **added** if its IRI is new to the canvas; the response hydrates its live content), a node omitted is **removed from the canvas** (the concept is untouched, and so is any edit staged on it). Adding a node needs only `{id, position}`; the backend joins the rest from live RDF.

**Classes only.** A VZTAH travels in `edges[]` and a VLASTNOST inside its class's `properties[]` — never as a node, in either direction.

**Placing a concept from another ontology needs nothing special.** Send the node like any other; a concept belonging to a *different* ontology (or to NKD) is accepted and stored read-only, so the user can draw a relationship from a concept they own to one they do not. It renders with `data.readOnly: true` and its label fetched from the graph that owns it.

**Foreignness is derived, never declared.** The server resolves it from the concept's own graph on every save — there is no request field for it, so a node can be neither falsely locked nor falsely made editable, and a client that simply echoes back what it read always saves correctly. (`data.readOnly` is response-only; do not send it back.)

⚠️ **Read-only means never the SUBJECT of an edit.** An `overlays[]` entry whose `conceptIri` is a foreign concept is a 400 at save and `FOREIGN_CONCEPT` at materialize — materializing it would write another ontology's RDF.

**What a foreign concept may be is the OBJECT of a triple in your own graph**, which is the whole point of placing one. The rule follows that distinction, endpoint by endpoint:

| Overlay field | Foreign allowed? | Why |
|---|---|---|
| `conceptIri` (the subject) | ❌ | The concept being edited |
| `domain` | ❌ | The class the VZTAH/VLASTNOST hangs off — the *origin* of the link, and it must be yours |
| `range` | ✅ | Becomes the object of a triple in your graph |
| `broaderConcept`, `exactMatch` | ✅ | Same — your concept points at theirs |
| `convertToHierarchy.addBroaderOn` | ❌ | The class that gets edited |
| `convertToHierarchy.broader` | ✅ | Only referenced |

So a VZTAH your ontology owns may point **at** a foreign class (`range`), but a VZTAH may never hang **off** one (`domain`). Both are checked at save and re-checked at materialize.

### `nodes[].properties` — the rows a class renders

**A flat array of VLASTNOST IRIs, authoritative full-replace** — it behaves like `position`, not like `overlays`. A property renders as a row inside a class only while that class lists it. Membership is **curated, not derived**: a class with `"properties": []` shows no rows even when its VLASTNOSTi exist in RDF, and the backend never falls back to "show all".

**Omitting the key is the same as sending `[]`** — a node in the payload states its full row set. Note the read and write shapes differ: the read returns rich `PropertyRow` objects, the write takes bare IRIs, so a Save maps `node.data.properties.map(p => p.iri)`.

**Adding** a row = include its IRI; **removing** = omit it and resend the rest. **Moving a property to another class needs both**: list it under the new host *and* stage `{"domain": "<new class>"}` on its overlay. The overlay alone renders nothing — placement and structure are separate instructions.

### Edges — explicit canvas membership

**An edge persists exactly two things: `id` and `segments`.** Its existence, endpoints and kind are re-derived from `live ⊕ overlay` on every read, so `source`, `target` and `edgeKind` are **not accepted on write** — sending them is ignored. This is deliberate: a stored endpoint could silently contradict the projection it duplicates, which is precisely the drift the diagram layer is built to prevent. To change where a relationship points, stage `{domain, range}` on its overlay; the edge follows.

**`edges` is canvas membership, exactly like `nodes` — echo back every edge you want to keep drawn.** An edge present is on the canvas; an edge omitted from a present `edges` array is taken off it. Removing an edge this way is **pure presentation**: the triple is untouched, so the edge stays projectable and can be re-added later.

**A projectable edge that was never placed is not drawn.** Like a class that exists in the ontology but has not been dragged onto the canvas, it waits in the sidebar until the user adds it. This is what lets two classes sit on a canvas *without* the relationship between them — impossible while edges were drawn purely by projection.

**Projection still governs what CAN be drawn, so an edge is never orphaned on one end.** If an endpoint class leaves the canvas, or an overlay repoints the relationship, the edge stops projecting and is not drawn whatever its membership says. Membership can hide a projectable edge; it can never resurrect an unprojectable one.

`segments` is three-way, and independent of membership:

- **omitted / `null`** — keeps the stored routing. The entry is a membership statement and says nothing about geometry, so a client that does not manage routing cannot discard it by accident.
- **`[]`** — explicitly clears the routing to default.
- **a list** — sets the waypoints.

Repointing an endpoint drops the waypoints by design: the edge id embeds its endpoints, so geometry drawn for the old target cannot follow it to the new one. Waypoints are pure presentation — nothing derives them from RDF and nothing validates them against it.

### Overlays — staged structural edits

Each entry stages or updates **one concept's** structural diff. Persisted to `pending_edit_json`; not sent to RDF until Převzít.

**`conceptIri` addresses a *concept*, not a canvas node.** A VZTAH renders as an edge and a VLASTNOST as a row inside its class, and both are staged through this same field by their own IRI. The value is the full IRI, optionally `iri:`-prefixed. It is mandatory (`@NotBlank`); an entry without it is a **400**.

The overlay is **structural-only** — there is no `label`/`name` here. Label editing is done through the normal concept editor, not the diagram (a label change renames the concept IRI).

| Field | Applies to | Meaning |
|---|---|---|
| `conceptIri` | — | **required**; the concept being staged |
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | `subClassOf` list (IRIs) |
| `exactMatch` | any | `skos:exactMatch` list (IRIs) — op 3's "equivalent" |
| `convertToHierarchy` | VZTAH | op 6 marker: `{ addBroaderOn, broader }` — **both mandatory** |

`baseUpdatedAt` appears in **reads** (inside `pendingEdit`) but is **never sent on write** — the server stamps the stale-base fingerprint itself. Sending it is ignored.

**Discard = an entry carrying only `conceptIri`.** `{"conceptIri": "iri:…"}` clears that concept's overlay, reverting it to live content. `conceptIri` is addressing, not content, so it never counts toward emptiness.

**An explicitly-empty list is *not* a discard — it means "clear this predicate".** `{ "conceptIri": "iri:…", "broaderConcept": [] }` stages "remove all superclasses" (the flip op-2 A-side dropping its last broader) and materializes as a `subClassOf` clear.

**Dragging an edge endpoint is a concept edit** — repointing a relationship's arrow stages `range` on the VZTAH; dragging a property row into another class stages `domain` on the VLASTNOST:

```jsonc
// op 1 (swap direction, VZTAH):        { "conceptIri": "iri:…/rel", "domain": "…/A", "range": "…/B" }
// op 4/5 (property parent / domain):   { "conceptIri": "iri:…/prop", "domain": "…/OwningClass" }
// op 3 (subclass → equivalent, TRIDA): { "conceptIri": "iri:…/A", "broaderConcept": [], "exactMatch": ["…/B"] }
// op 2 (flip): two entries in the SAME overlays[] array —
//     { "conceptIri": "iri:…/A", "broaderConcept": [ …without B ] },
//     { "conceptIri": "iri:…/B", "broaderConcept": [ …, "…/A" ] }
// op 6 (rel → hierarchy, on the VZTAH):
//     { "conceptIri": "iri:…/rel", "convertToHierarchy": { "addBroaderOn": "…/A", "broader": "…/B" } }
```

`superProperty` and `superRelation` do not exist, along with the `SUB_PROPERTY`/`SUB_RELATION` edges: the diagram cannot stage a sub-property/sub-relation change, because it cannot render one for the user to see or undo. Use the normal concept editor.

### Version — the optimistic lock

**`version` is required — send back the one you rendered from.** Because membership is a full replace, a save built on a stale view would silently delete nodes another editor added. Echo the `version` from the `DiagramDto` this edit started from (the read, or the response of your own last save).

Every successful save advances the version and returns the **post-increment** value, so you can chain saves without re-reading. A freshly created canvas carries `version: 0` — echo what `POST …/create` returned. Omitting it is a **400** naming the field; it is declared required in the schema, so a generated client types it non-optional.

If another editor saved in the meantime the call returns **409** and **nothing is written** — staged overlays are left exactly as they were:

```jsonc
{ "success": false, "errorCode": null, "data": null,
  "message": "Diagram byl mezitím uložen jiným editorem; načtěte jej znovu a uložte změny znovu." }
```

Reload the diagram and re-apply.

### Validation errors (400)

`message` is the fixed prefix `Neplatná data v požadavku: ` followed by the field path — match on the path, never on the whole string. Several failing fields join with `; `. Every 400 is **atomic** — nothing is written and the staged set is unchanged.

| Body | `message` |
|---|---|
| overlay entry with no `conceptIri` | `Neplatná data v požadavku: overlays[0].conceptIri: must not be blank` |
| `convertToHierarchy` missing `broader` | `Neplatná data v požadavku: overlays[0].convertToHierarchy.broader: must not be blank` |
| `convertToHierarchy` missing `addBroaderOn` | `Neplatná data v požadavku: overlays[0].convertToHierarchy.addBroaderOn: must not be blank` |

A concept IRI from another ontology — in a node id, an overlay `conceptIri`, or either `convertToHierarchy` endpoint — is also a 400:

```jsonc
{ "success": false, "data": null,
  "message": "Pojem https://…/a3791---registr-vysokých-škol/pojem/elektronická-adresa nepatří do slovníku tohoto diagramu." }
```

### `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) — the write succeeded, the render data did not

A diagram write is pure Postgres; the concept content in the response is then read from Fuseki. The two are deliberately **not** in one transaction — the fetch is an HTTP call that can take tens of seconds, and holding a DB connection across it would let a slow Fuseki exhaust the pool and stall unrelated endpoints. It would also discard a perfectly good layout write because a *read* failed.

The consequence is a failure mode with no equivalent before: the write is **committed and durable**, but the response body cannot be assembled.

```jsonc
{
  "success": false,
  "errorCode": "DIAGRAM_SAVED_READBACK_FAILED",
  "message": "Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. Načtěte diagram znovu; změny zůstávají uložené.",
  "data": { "version": 16 }        // the version AFTER the committed write
}
```

**Do not retry the write.** The save already happened and the version has advanced; re-sending it with the version you held would be stale and return **409**. Either re-issue `GET …/detail` to render the current state, or continue from the `version` in `data` if you want to save again without that read first. Treat it as "saved, but I can't show you the result yet" — never as "the save failed".

This is the one status where a `success: false` response still means the write landed, which is why it has its own code instead of a generic 500.

## Materialize — `POST /api/diagram/{ontologySlug}/{diagramId}/materialize` → `MaterializeResultDto`

Applies every staged change. One entry per staged **change** (a change may span two concepts). Per-change partial-ok; a two-concept change (flip, rel→hierarchy) is all-or-nothing. Staged edits are deleted on success, so `pendingEdits[]` empties.

**Canvas membership is irrelevant here.** Every staged edit materializes, including one whose concept is not on the canvas — the user staged it, and hiding a box is not a decision to abandon the edit.

```jsonc
{
  "materialized": [
    { "conceptIri": "https://…/je-zamestnan-u", "op": "SWAP_DIRECTION" }
  ],
  "failed": [
    { "conceptIri": "https://…/organizace", "op": "SWAP_DIRECTION",
      "error": "VALIDATION", "message": "range must be a class", "status": 400 }
      // change kept staged; user fixes and re-runs Převzít
  ],
  "skippedStale": [
    { "conceptIri": "https://…/deleted-x" }   // concept gone; change un-applyable
  ]
}
```

**Every entry is keyed by `conceptIri`** — the same identity `pendingEdits[]` uses, so a result row maps straight onto the staged edit it came from. There is no `nodeId`: a staged edit need not have a canvas node at all.

`op` ∈ `SWAP_DIRECTION` · `CHANGE_HIERARCHY_TYPE` · `CHANGE_PROPERTY_PARENT` · `CONVERT_TO_HIERARCHY`. (Setting a domainless property's domain and repointing an existing one both report as `CHANGE_PROPERTY_PARENT` — indistinguishable from the overlay.)

**Flip (op 2) materializes as two independent edits.** Reversing a hierarchy (B⊐A → A⊐B) is staged as a `broaderConcept` overlay on *both* concepts; each materializes independently as a `CHANGE_HIERARCHY_TYPE`. There is no atomic two-node flip unit — neither half corrupts RDF on its own, and a half-applied flip is reported per-concept in `failed` for the user to re-run. Only `CONVERT_TO_HIERARCHY` (op 6) is a genuinely gated two-call unit.

**Error cases the FE handles:**

- `error: "VALIDATION"` (HTTP 400) — the concept edit failed validation; overlay retained, fix and retry.
- `error: "STALE_BASE"` (HTTP 409) — the underlying concept was edited (via normal `/api/concept`) since the overlay was staged. Overlay retained. **See the recovery rule below.**
- `error: "CASCADE_CONFLICT"` — op 6 (rel→hierarchy) blocked because another concept's domain/range points at the VZTAH (deleting it would cascade); surface and let the user resolve.
- `error: "FOREIGN_CONCEPT"` (HTTP 400) — a concept IRI in the change belongs to a different ontology than the diagram's own (either the concept itself, or op 6's `addBroaderOn` / `broader`). The diagram may only write its own ontology's concepts; a legitimate client never produces this.
- `error: "ERROR"` (HTTP 500) — an unexpected server-side failure; overlay retained. `message` is always the generic `"Nastala neočekávaná chyba."` — the underlying cause is server-logged, never returned, so the FE should show it as-is and not try to parse it.
- `skippedStale` — the referenced concept no longer exists; offer remove-or-recreate.

**A change whose overlay is already gone is reported nowhere.** If the staged edit vanishes between the work-list read and its own transaction — a retried Převzít, a concurrent Save that discarded it, or op 6 deleting the concept — nothing was written, so the concept appears in *none* of the three arrays. Sum the arrays and you may get fewer entries than `pendingEdits[]` held; that is the expected shape, not a lost result. It is never reported as `materialized`, which would claim a change that never happened.

### `DIAGRAM_EDIT_CONFLICT` (HTTP 409) — another diagram stages the same concept

Staged edits are **per diagram**, so two canvases of one ontology can hold competing intent for one concept. Materializing either would move that concept's `updatedAt` — the fingerprint the other's edit is pinned to — so the sibling would afterwards fail `STALE_BASE` one concept at a time. Materialize therefore checks first and refuses, **before writing anything**:

```jsonc
{ "success": false, "errorCode": "DIAGRAM_EDIT_CONFLICT",
  "message": "Některé změny kolidují se změnami rozpracovanými v jiném diagramu.",
  "data": { "conflicts": [
      { "conceptIri": "https://…/pojem/je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" },
        "mine":   { "range": "https://…/pojem/osoba" },
        "theirs": [ { "diagramId": 4, "diagramName": "Pohled HR",
                      "pendingEdit": { "range": "https://…/pojem/organizace" } } ] } ] } }
```

**A conflict is "the same concept staged on both", not "staged with different values."** Identical values still conflict, for the reason above — do not filter the report client-side by comparing them.

**Nothing was written.** Both sides' staged work is exactly as it was, so the call is safe to repeat once the user chooses.

**Resolving** — re-call naming the **winner**:

| `POST …/materialize?onConflict=` | Effect |
|---|---|
| *(omitted)* | Detect and refuse with the report above. The only safe default. |
| `ACCEPT_MINE` | **This** diagram wins: drop every other diagram's conflicting edits, then materialize this one. |
| `ACCEPT_THEIRS` + `winnerDiagramId=<id>` | The **named** diagram wins: drop the conflicting edits here and on every other diagram, then materialize the winner. |

A resolution names one winner, never a side to discard — a conflict can span more than two canvases, and "discard theirs" has no single meaning once three diagrams stage the same concept. Every loser is cleared in the same pass, including canvases the caller never named, so one decision settles the whole collision rather than one round trip per sibling.

**`ACCEPT_THEIRS` materializes the winner, not the diagram in the path.** Leaving the chosen edit merely staged would push the conflict onto a canvas the user may never return to.

`winnerDiagramId` is required by `ACCEPT_THEIRS` and rejected with `ACCEPT_MINE`. It must name a diagram that appears in the conflict report; anything else is **400**, since re-sending it cannot succeed. Reaching another canvas is allowed because every diagram in the conflict set belongs to the ontology the caller already owns — and the set is re-read server-side, never trusted from the request.

**Only the contested concepts are discarded** — every canvas's unrelated staged work survives.

### Recovering from `STALE_BASE` — discard, then re-stage

> ⚠️ **Re-sending the same overlay values does not clear a `STALE_BASE`.** The stale-base fingerprint is stamped when an overlay **comes into existence** on a row and is never refreshed while it stays staged — that is what stops a concurrent concept edit from being silently absorbed. Re-sending identical values leaves the old fingerprint in place and 409s again.
>
> **The recovery is two saves:** first an all-null entry for that `conceptIri` (discard), then the entry with the values again. The discard resets the row, so the re-stage takes a fresh fingerprint and materialize succeeds.

A stale node still appears in reads with `"stale": true` and its overlay intact, so the user can see what is pending on a concept that no longer exists:

```jsonc
{ "id": "iri:…/pojem/budova-má-definiční-bod", "type": "conceptNode",
  "position": { "x": 0.0, "y": 0.0 }, "collapsed": false,
  "data": { "iri": "…/pojem/budova-má-definiční-bod", "stale": true, "hasPendingEdits": true,
            "pendingEdit": { "range": "…/pojem/parcela", "baseUpdatedAt": "2026-08-15T15:09:01.331737",
                             "domain": null, "broaderConcept": null, "exactMatch": null,
                             "convertToHierarchy": null },
            "properties": [] } }
```

## Generated types

`DiagramLayoutDto.required` is `["nodes", "version"]` — **`overlays` is optional**, so a generated client types it nullable and existing call sites keep compiling. `DiagramLayoutOverlay.required` is `["conceptIri"]`; `DiagramLayoutOverlayConvertToHierarchy.required` is `["addBroaderOn", "broader"]`.

**Everything added for many-diagrams is optional in the schema, deliberately.** `DiagramCreateDto.name` carries no bean-validation constraint, so a generated client types it nullable and existing call sites keep compiling. (`required` is derived from bean validation alone — annotating a new field would make it non-optional in the generated client and break every current caller.) `DiagramDto.diagramId`/`name` and `SearchResultDto.diagramId` are response-side, so they never affect a request type.

---

*ISMD Tool · diagram layer · FE / REST contract · many diagrams per ontology · one write endpoint · full-replace layout, additive overlays · structural-only overlay, per diagram · foreign concepts referenced, never written · per-change partial-ok materialize*