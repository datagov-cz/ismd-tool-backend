# Data Export: Ontologies and Diagrams

> Status: **proposal, not built.** High-level design only. Czech version:
> [`DATA_EXPORT_CS.md`](./DATA_EXPORT_CS.md).

## What problem this solves

The original requirement was a regularly published database extract containing diagrams (without
comments and user information), offered as a publicly downloadable file catalogued in NKOD. The
requirement has since shifted: the application must **make available** its diagram data and its
draft and published ontologies, including their metadata. Who collects the data, how often, and
whether the result is published in NKOD is an open business decision (see
[Pending decisions](#pending-decisions)).

This design delivers the part that is independent of that decision: one admin endpoint that
returns the complete export as a single file.

## Why a new endpoint

The data is split across two stores, and neither existing surface covers it.

| Option | Why it is not enough |
|---|---|
| **Fuseki / SPARQL directly** | Diagrams and the ontology metadata (slug, publication state, timestamps) exist only in PostgreSQL. The Fuseki service also exposes `update`, `upload` and `gsp-rw` next to `query`, so it cannot be opened to a consumer as it is. |
| **Existing REST endpoints** | A consumer would need one call for the ontology list, one download per ontology, one call for the diagram list and one detail call per diagram. The ontology download is refused for ontologies with validation errors when the download restriction is on, the list model carries user and comment fields, and the diagram detail is the fat read that includes staged pending edits. |

The export reuses the existing building blocks (`OntologyDownloadService`, the diagram
repositories) behind one endpoint that applies the export rules in one place.

## Scope

| Included | Excluded |
|---|---|
| Every ontology, draft and published, regardless of validation state | Comments |
| Ontology metadata from PostgreSQL | Any user information, including the owner id |
| Ontology content as cleaned RDF (the same output as the ontology download) | Staged pending diagram edits (`diagram_pending_edits`) |
| Concept metadata from PostgreSQL | Validation reports |
| Every diagram with its stored layout | Outbox, reconciler and other operational data |
| | NKD, NKOD, RPP and e-Sbírka data |

## Endpoint

```
GET /api/admin/export
```

| Aspect | Value |
|---|---|
| Authorization | `@PreAuthorize("hasRole('ADMIN')")`, plus an `authenticated()` matcher for `/api/admin/export` in `SecurityConfig` (same pattern as `/api/admin/reconciler` and `/api/admin/outbox`) |
| Parameter | `rdfFormat` = `ttl` (default) or `json-ld` |
| Response | `application/zip`, streamed, `Content-Disposition: attachment; filename="ismd-export-<timestamp>.zip"` |
| Concurrency | One export at a time; a second request while one is running gets `409` |

The endpoint is synchronous. Every ontology costs one graph fetch from Fuseki, so the response time
grows linearly with the number of ontologies. If that becomes too slow for one HTTP request, the
same builder can be moved behind a job that writes the file to storage without changing the
archive format.

## Archive layout

```
manifest.json
ontologies/
  <ontology-slug>/
    ontology.ttl            (or ontology.jsonld)
    concepts.json
    diagrams/
      <diagram-id>.json
```

### `manifest.json`

The index of the archive and the only place a consumer needs to read to know what it received.

```json
{
  "schemaVersion": 1,
  "exportedAt": "2026-10-07T09:30:00Z",
  "rdfFormat": "ttl",
  "complete": true,
  "ontologies": [
    {
      "slug": "moje-agenda",
      "iri": "https://slovník.gov.cz/agendový/moje-agenda",
      "isPublished": false,
      "createdAt": "2026-03-02T10:15:00",
      "updatedAt": "2026-09-30T14:02:11",
      "lastValidationStatus": "VALIDATED",
      "lastValidationAt": "2026-09-30T14:02:15Z",
      "conceptCount": 42,
      "rdf": { "status": "OK", "file": "ontologies/moje-agenda/ontology.ttl" },
      "concepts": "ontologies/moje-agenda/concepts.json",
      "diagrams": [
        {
          "id": 17,
          "name": "Přehled",
          "createdAt": "2026-04-11T08:00:00",
          "updatedAt": "2026-09-12T16:40:00",
          "file": "ontologies/moje-agenda/diagrams/17.json"
        }
      ]
    }
  ]
}
```

- `iri` is the ontology's graph name.
- `rdf.status` is `OK`, `EMPTY` (the ontology has no RDF content yet) or `FAILED` (the fetch or
  the transformation failed). Only `OK` has a `file`.
- `complete` is `false` when at least one ontology is `FAILED`.
- `lastValidationStatus` is `VALIDATED`, `SKIPPED_UNAVAILABLE` or `FAILED`. It says whether the
  last validation run completed, not whether it found errors; the findings are in the validation
  report, which is not exported.
- Human-readable names and descriptions are not repeated in the manifest; they are in the RDF.

### `concepts.json`

The per-concept metadata that exists only in PostgreSQL.

```json
[
  {
    "iri": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
    "slug": "zadatel",
    "type": "TRIDA",
    "isPublished": false,
    "inTezaurus": false,
    "createdAt": "2026-03-02T10:20:00",
    "updatedAt": "2026-09-30T14:02:11"
  }
]
```

### `diagrams/<diagram-id>.json`

The **stored layout**, not the projected read model the frontend receives.

```json
{
  "id": 17,
  "name": "Přehled",
  "ontologySlug": "moje-agenda",
  "viewport": { "x": 0.0, "y": 0.0, "zoom": 1.0 },
  "nodes": [
    {
      "id": 301,
      "conceptIri": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
      "position": { "x": 120.0, "y": 80.0 },
      "collapsed": false,
      "parentNodeId": null,
      "foreign": false,
      "visibleProperties": [
        "https://slovník.gov.cz/agendový/moje-agenda/pojem/jméno-žadatele"
      ]
    }
  ],
  "edges": [
    {
      "edgeKey": "https://slovník.gov.cz/agendový/moje-agenda/pojem/podává",
      "segments": [ { "x": 200.0, "y": 140.0 } ]
    }
  ]
}
```

A diagram stores only what a user arranged: which concepts are on the canvas, where, and how
edges are routed. Which edges exist, and every label, is derived from RDF on read. The export
keeps that split, so the diagram file references concepts by IRI and the RDF in the same archive
supplies their content. A node marked `foreign` references a concept from another ontology.

The optimistic-lock `version` and the internal edge bookkeeping columns are not exported.

## How the export is built

1. **Read PostgreSQL once.** In a single read-only transaction, load every ontology with its
   concepts and diagrams (nodes and edges) into export DTOs. These DTOs have no user or comment
   fields, so excluded data cannot leak through a shared model.
2. **Stream the archive ontology by ontology.** For each ontology, call
   `OntologyDownloadService.downloadOntology(id, format)` and write the result, then write
   `concepts.json` and the diagram files. Only one ontology's RDF is held in memory at a time.
3. **Write `manifest.json` last.** It then records the real outcome of every ontology.

Design points that follow from the existing code:

- **Validation does not block the export.** The download restriction for ontologies with
  validation errors lives in `OntologyController`, not in the service, so calling the service
  directly includes every draft. The export does not say which ontologies have validation
  errors; if a consumer needs that, an error count per ontology can be added to the manifest.
- **Cleaned RDF.** The service applies the same filtering and OFN transformation as the public
  download, so the export and the per-ontology download always agree.
- **Failures are reported per ontology.** The response is streamed, so the HTTP status is already
  `200` when a later ontology fails. The failure is therefore recorded in the manifest
  (`rdf.status = FAILED`, `complete = false`). A response that breaks off mid-stream has no
  manifest and is not a valid ZIP, so a consumer cannot mistake it for a complete export.
- **An empty ontology is not a failure.** A draft without RDF content is exported with its
  metadata and `rdf.status = EMPTY`.
- **Fuseki load.** Graph fetches run one after another and go through the existing Fuseki
  semaphore, so an export cannot starve interactive requests.

## Consistency

PostgreSQL and TDB2 share no transaction (see [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md)).
The PostgreSQL side of an export is one consistent snapshot; the RDF of each ontology is read
shortly afterwards. An edit made while the export is running can therefore appear in the RDF but
not in the metadata. `exportedAt` marks the PostgreSQL snapshot. This is acceptable for a periodic
extract; an export that must be exact should run while writes are quiet.

## Pending decisions

| # | Decision | Effect on the design |
|---|---|---|
| 1 | **Who calls the export** (client decision): a human admin on demand, or an automated job that publishes the file | An automated caller needs a Keycloak service account with the admin role. The archive format is the same either way. |
| 2 | **Publication in NKOD**: whether the file is published and catalogued, and how often | Adds a scheduled job, public storage for the file and an NKOD catalogue record. Publishing draft ontologies publicly would also need explicit approval. Not part of this design. |

## Implementation outline

| Piece | Note |
|---|---|
| `ExportController` (`/api/admin/export`) | New, alongside the reconciler and outbox controllers |
| `ExportService` | Reads PostgreSQL, drives the archive stream, builds the manifest |
| Export DTOs | Manifest, ontology entry, concept entry, diagram layout |
| `SecurityConfig` | One `authenticated()` matcher; without it the request is rejected by `denyAll()` before `@PreAuthorize` runs |
| Reused unchanged | `OntologyDownloadService`, diagram and ontology repositories |
| Tests | Excluded data is absent from every file; a draft with validation errors is exported; an empty ontology gives `EMPTY`; a failing ontology gives `FAILED` and `complete = false`; a non-admin gets `403` |