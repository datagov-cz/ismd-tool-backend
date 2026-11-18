# Open-data publication: vocabularies and diagrams

> Status: **implemented**. Production use waits on GitHub Pages for the context; see
> [Enabling](#enabling). Czech version: [`DATA_EXPORT_CS.md`](./DATA_EXPORT_CS.md).

## What problem this solves

Draft and published vocabularies, with their metadata, and the diagram data are to be published as
open data. ISMD builds the two open-data files on demand for an administrator, stores each run in
PostgreSQL and lets the administrator download the files. The DIA LKOD pipeline uploads them to LKOD
DIA, from where they are catalogued in NKOD. ISMD serves no public copy and never touches LKOD.

| Client requirement | How the design meets it |
|---|---|
| No ZIP and no X files, a small fixed set of files | Exactly **two files**: `slovniky.jsonld` and `diagramy.jsonld` |
| No `manifest.json`; its general fields (`complete`, `exportedAt`, …) and the vocabulary and diagram fields (`createdAt`, `isPublished`, …) belong in the files | Each file carries a **publication record** node (general fields); vocabulary and diagram metadata sit on their own nodes |
| One file for all vocabularies and one for all diagrams instead of a file each | `@graph` with one node per vocabulary or diagram |
| `concepts.json` goes away, its content moves into the vocabulary file | PostgreSQL concept metadata is added to each concept in `pojmy` |
| Attributes that point to files go away | No `file`, `concepts`, `rdf.file`; links are by IRI only |
| Everything is `.jsonld` with an `@context` of a vocabulary describing this open data | Two contexts: OFN Slovníky and the ISMD OD context (see [Contexts](#contexts)) |
| ISMD need not generate the dataset metadata; it is made once by hand | The catalogue record lives in LKOD DIA and is maintained by the DIA LKOD pipeline (see [Cataloguing](#cataloguing)) |

## Why ISMD builds the files

The data is split across two stores and no existing interface yields it as two files.

| Option | Why it falls short |
|---|---|
| **Fuseki / SPARQL directly** | Diagrams and vocabulary metadata (slug, publication state, timestamps) exist only in PostgreSQL. Fuseki also exposes `update`, `upload` and `gsp-rw` next to `query`, so it cannot be opened to the pipeline as it is. |
| **Existing REST endpoints** | Assembling the files would need one call for the vocabulary list, one download per vocabulary, one call for the diagram list and one detail call per diagram. With the restriction on, a vocabulary download is refused for vocabularies with validation errors, the list model carries user and comment fields, and the diagram detail is a "fat" read that includes pending edits. |

Publication reuses the existing building blocks (`OntologyDownloadService`, the diagram repositories)
and applies the export rules in one place.

## Scope

| Included | Excluded |
|---|---|
| Published vocabularies; drafts per `ismd.publication.include-drafts` | Comments |
| Vocabularies regardless of validation state | Any user information, including the owner id |
| Vocabulary metadata from PostgreSQL | Pending diagram edits (`diagram_pending_edits`) |
| Vocabulary content as cleaned OFN JSON-LD (the same output as the JSON-LD vocabulary download) | Validation reports |
| Concept metadata from PostgreSQL, added to the concepts | Outbox, reconciler and other operational data |
| Diagrams of included vocabularies with their saved layout | Data from NKD, NKOD, RPP and e-Sbírka |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ismd.publication.include-drafts` | `true` | Drafts are published. `false` leaves out draft vocabularies, draft concepts of published vocabularies and the diagrams of left-out vocabularies (see [Drafts](#drafts)) |
| `ismd.publication.retain-files` | `12` | How many of the newest stored publications keep their file content in PostgreSQL (at least 1); metadata is kept forever (see [Archive](#archive)) |
| `ismd.publication.iri-base` | `https://data.dia.gov.cz/zdroj/ismd/` | Base of the IRIs ISMD mints: `<base>diagramy/<iri_id>` and `<base>publikace/<file>` (see [Diagram IRI](#diagram-iri)) |
| `ismd.publication.context-url` | `https://datagov-cz.github.io/ismd-tool-backend/ismd-otevřená-data/draft/kontext.jsonld` | Second context in both files' `@context`, a public `https://` URL (see [Context hosting](#context-hosting)). A blank value fails the run. |

## Admin endpoints

```
POST /api/admin/publikace/run
GET  /api/admin/publikace/history?page&size
GET  /api/admin/publikace/{id}/{file}
```

All three are admin-only (`@PreAuthorize("hasRole('ADMIN')")` in `PublicationAdminController`);
`SecurityConfig` lets `/api/admin/publikace/**` through to authenticated users, the same pattern as
`/api/admin/reconciler/**`. There is no public endpoint.

### Running a publication

`POST /run` builds and stores a publication; nothing runs on startup or on a schedule. The request
waits for the build and answers with `ApiResponseDto`:

| Status | Meaning |
|---|---|
| `200` | Stored; data `PublicationRun` `{id, publishedAt, vocabularies, diagrams, complete, includeDrafts}`, `id` = the stored publication |
| `409` `PUBLICATION_IN_PROGRESS` | Another run holds the lock |
| `500` `PUBLICATION_FAILED` | The run failed (including a blank `context-url`); nothing is stored |

The controller only delegates to `PublicationService.publish(triggeredBy)`, which returns the
`PublicationRun`. The service takes the lock and reports failure by throwing
`PublicationInProgressException` or `PublicationFailedException` (package
`com.dia.ismdtoolbackend.exception`); `GlobalExceptionHandler` maps them to the statuses above. At
most one build runs at a time (a `ReentrantLock` suffices, the backend runs as a single instance).

### History

`GET /history` lists the stored publications newest first (`page` default `0`, `size` default `50`,
at most `200`), metadata only: `PublicationSummary` with the run's fields, `triggeredBy`,
`contextUrl` and per file `name`, `size`, `etag` and `stored` (`false` once the content is past
`retain-files`).

### Downloading the files

`GET /{id}/{file}` (`file` = `slovniky.jsonld` | `diagramy.jsonld`) returns a stored file from
PostgreSQL. This is how an admin gets the files for LKOD DIA; the newest publication's `id` comes from
the run response or the first `history` entry.

| Status | Meaning |
|---|---|
| `200` | Stored content, `application/ld+json;charset=UTF-8`, `ETag` (quoted SHA-256 of the content), `Content-Disposition: attachment; filename="publikace-<id>-<file>"` |
| `404` `RESOURCE_NOT_FOUND` | Unknown publication or file |
| `410` `PUBLICATION_FILE_PRUNED` | The publication exists but its content is past `retain-files` |

## File format

### Contexts

Both files have an `@context` of two contexts:

```json
"@context": [
  "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld",
  "<ismd.publication.context-url>"
]
```

- **OFN Slovníky** (published version 2026-02-26) defines vocabulary and concept content: `iri`,
  `typ`, `název`, `popis`, `vytvořeno`, `aktualizováno`, `Časový okamžik`, `pojmy` and every concept
  field. The existing JSON-LD download already uses this context.
- **The ISMD OD context** defines everything OFN does not cover: PostgreSQL metadata, the publication
  record and diagrams. It comes second, so it does not override OFN terms. It is hosted in this
  repository on GitHub Pages (see [Context hosting](#context-hosting)).

### Naming conventions

Taken from the standards in [`datagov-cz/otevrene-formalni-normy`](https://github.com/datagov-cz/otevrene-formalni-normy)
(DCAT-AP-CZ, OFN Slovníky) and from [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia):

| Element | Convention | Example in the standards | ISMD |
|---|---|---|---|
| Property (JSON key) | Czech, lower case, words joined by `_` | `periodicita_aktualizace`, `klíčové_slovo`, `je_součástí`, `datum_a_čas` | `je_publikován`, `stav_poslední_validace`, `počet_pojmů` |
| Type (`typ` value) | Czech, capitalised, spaces | `Datová sada`, `Časový okamžik`, `Typ objektu práva` | `Diagram`, `Uzel diagramu`, `Publikace ISMD` |
| `iri` / `typ` | aliases of `@id` / `@type` | every context | unchanged |
| Multilingual text | `@container: @language` | `název`, `popis` | only for human-readable text |
| Resource reference | `@type: @id` | `poskytovatel`, `téma` | `slovník`, `pojem`, `seskupen_pod` |
| Context location | `<standard>/<YYYY-MM-DD>/<profile>/kontext.jsonld`, working version in `draft/` | `dcat-ap-cz-hvd/2026-09-23/datová-sada/kontext.jsonld` | `ismd-otevřená-data/<date>/kontext.jsonld` (one context for both files), working version in `ismd-otevřená-data/draft/` |
| DIA resource IRIs | `https://data.dia.gov.cz/zdroj/<area>/…` | `https://data.dia.gov.cz/zdroj/datové-sady/nkod/nkod-kompletní` | `https://data.dia.gov.cz/zdroj/ismd/diagramy/<uuid>` |
| Vocabulary term IRIs | slovník.gov.cz `…/<vocabulary>/pojem/<term>` (OFN), data.gov.cz `slovník/<name>/<term>` (DCAT-AP-CZ) | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/okamžik-vytvoření` | `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/<term>` |

Term IRIs use hyphens (`okamžik-vytvoření`), context keys underscores (`datum_a_čas`). OFN Slovníky
uses hyphens in concept fields (`alternativní-název`, `nadřazený-pojem`), DCAT-AP-CZ underscores. ISMD
keys follow DCAT-AP-CZ, so a concept in `slovniky.jsonld` carries both forms side by side
(`alternativní-název` from OFN, `je_publikován` from ISMD). All keys live in one class
(`PublicationJson`).

The vocabulary describing this open data is itself an OFN vocabulary (classes `Diagram`,
`Uzel diagramu`, properties `je_publikován`, …), so it can be authored in ISMD and the context
generated from it.

Timestamps take the OFN shape `{"typ": "Časový okamžik", "datum_a_čas": "…"}` with an explicit
offset, to whole seconds. PostgreSQL stores `createdAt` / `updatedAt` without a zone; publication
states `Europe/Prague`.

**No database ids are published.** Vocabularies and concepts are identified by IRI, a diagram by its
own IRI (see [Diagram IRI](#diagram-iri)) and a diagram node by its concept's IRI.

### Publication record

One `@graph` node in each file; it replaces the general fields of the former manifest.

```json
{
  "iri": "https://data.dia.gov.cz/zdroj/ismd/publikace/slovniky.jsonld",
  "typ": "Publikace ISMD",
  "publikováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-10-07T03:00:00+02:00" },
  "úplná": true,
  "obsahuje_rozpracované": true
}
```

| Field | Formerly (manifest) | Note |
|---|---|---|
| `publikováno` | `exportedAt` | Instant of the PostgreSQL snapshot (see [Consistency](#consistency)); identical in both files of one run |
| `úplná` | `complete` | `false` when at least one vocabulary has `stav_obsahu = FAILED`. Always `true` in the diagram file. |
| `obsahuje_rozpracované` | — | The value of `ismd.publication.include-drafts`, so a consumer knows whether a missing vocabulary does not exist or is only a draft |
| — | `schemaVersion` | Gone; the dated ISMD OD context URL carries the format version |
| — | `rdfFormat` | Gone; the format is always JSON-LD |
| — | `ontologies[]`, `file`, `concepts`, `diagrams[].file` | Gone; vocabulary and diagram nodes sit directly in `@graph` |

### `slovniky.jsonld`

```json
{
  "@context": [ "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld", "<ISMD OD>" ],
  "@graph": [
    {
      "iri": "https://slovník.gov.cz/agendový/moje-agenda",
      "typ": ["Slovník"],
      "název": { "cs": "Moje agenda" },
      "slug": "moje-agenda",
      "je_publikován": false,
      "stav_poslední_validace": "VALIDATED",
      "okamžik_poslední_validace": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:15+02:00" },
      "vytvořeno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-03-02T10:15:00+01:00" },
      "aktualizováno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:11+02:00" },
      "stav_obsahu": "OK",
      "počet_pojmů": 42,
      "pojmy": [
        {
          "iri": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
          "typ": ["Pojem", "Třída"],
          "název": { "cs": "Žadatel" },
          "slug": "zadatel",
          "je_publikován": false,
          "vytvořeno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-03-02T10:20:00+01:00" },
          "aktualizováno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:11+02:00" }
        }
      ]
    },
    { "iri": "https://data.dia.gov.cz/zdroj/ismd/publikace/slovniky.jsonld", "typ": "Publikace ISMD", "…": "…" }
  ]
}
```

| Field | Formerly | Note |
|---|---|---|
| `vytvořeno_v_ismd`, `aktualizováno_v_ismd` | `createdAt`, `updatedAt` | PostgreSQL record times. The vocabulary's OFN `vytvořeno` / `aktualizováno` stay as its RDF content has them, hence separate terms. |
| `slug` | `slug` | |
| `je_publikován` | `isPublished` | On vocabularies and concepts |
| `stav_poslední_validace` | `lastValidationStatus` | `VALIDATED`, `SKIPPED_UNAVAILABLE` or `FAILED`. Says whether the last validation run completed, not whether it found errors; findings are in the validation report, which is not published. |
| `okamžik_poslední_validace` | `lastValidationAt` | |
| `stav_obsahu` | `rdf.status` | `OK`, `EMPTY` (no RDF content yet) or `FAILED` (download or transformation failed). With `EMPTY` and `FAILED` the node carries PostgreSQL metadata only. |
| `počet_pojmů` | `conceptCount` | Number of PostgreSQL concepts after `include-drafts` |
| — | `type` (concepts.json) | Not repeated; the OFN `typ` carries it |
| — | `inTezaurus` (concepts.json) | Not published |
| — | `rdf.file`, `concepts` | Gone |

- A vocabulary node is the existing OFN JSON-LD download (content fields, `pojmy` and everything in
  it) plus PostgreSQL metadata. Each concept in `pojmy` gets its PostgreSQL fields by IRI.
- `iri` is the vocabulary's graph name.
- The OFN JSON Schema describes one vocabulary, so it validates individual vocabulary nodes, not the
  whole file.

### `diagramy.jsonld`

The **saved layout**, not the projected read model the frontend receives.

```json
{
  "@context": [ "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld", "<ISMD OD>" ],
  "@graph": [
    {
      "iri": "https://data.dia.gov.cz/zdroj/ismd/diagramy/3f2c9a1e-5b7d-4c0a-9e8f-1a2b3c4d5e6f",
      "typ": "Diagram",
      "název_diagramu": "Přehled",
      "slovník": "https://slovník.gov.cz/agendový/moje-agenda",
      "vytvořeno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-04-11T08:00:00+02:00" },
      "aktualizováno_v_ismd": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-12T16:40:00+02:00" },
      "výřez": { "x": 0.0, "y": 0.0, "přiblížení": 1.0 },
      "uzly": [
        {
          "typ": "Uzel diagramu",
          "pojem": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
          "pozice": { "typ": "Bod", "x": 120.0, "y": 80.0 },
          "cizí": false
        }
      ],
      "hrany": [
        {
          "typ": "Hrana diagramu",
          "klíč_hrany": "https://slovník.gov.cz/agendový/moje-agenda/pojem/podává"
        }
      ]
    },
    { "iri": "https://data.dia.gov.cz/zdroj/ismd/publikace/diagramy.jsonld", "typ": "Publikace ISMD", "…": "…" }
  ]
}
```

| Field | Formerly | Note |
|---|---|---|
| `název_diagramu` | `name` | Plain string. OFN `název` is a language map on `skos:prefLabel`, hence a separate term. |
| `vytvořeno_v_ismd`, `aktualizováno_v_ismd` | `createdAt`, `updatedAt` (manifest) | Moved from the manifest onto the diagram node |
| `slovník` | `ontologyIri` | IRI reference (`@type: @id`) to the vocabulary node in `slovniky.jsonld` |
| `pojem` | `conceptIri` | IRI reference |
| `seskupen_pod` | `parentConceptIri` | IRI reference to the concept of the node this one is grouped under; absent for an ungrouped node |
| `cizí` | `foreign` | The node references a concept of another vocabulary |
| `klíč_hrany` | `edgeKey` | A string, not an IRI reference: for hierarchy edges it is a key the frontend assembles |
| `výřez` | `viewport` | Absent until the diagram is first saved |
| — | `collapsed`, `visibleProperties`, `segments` | Not published (`sbalený`, `zobrazené_vlastnosti`, `body_zlomu` dropped 2026-11-16) |
| — | `file` | Gone |

Only what the user arranged is published: which concepts are on the canvas and where, how they are
grouped and which edges are on the canvas. Edge routes, node collapsing and visible properties are not
published. Which edges exist and all labels are derived from RDF on read. Publication keeps that split:
the diagram file references concepts by IRI and `slovniky.jsonld` supplies their content. A concept is
on a diagram as a node at most once. Nodes and edges have no IRI of their own (blank nodes in RDF).

Row ids, the optimistic-lock `version` and internal edge bookkeeping columns are not published.

### Diagram IRI

Today a diagram is identified by its vocabulary and name. Neither is stable: a diagram can be renamed
and renaming a vocabulary changes its IRI. Publication therefore gives every diagram its own IRI:

- Column `diagrams.iri_id` (`UUID`, `NOT NULL`, unique `uq_diagrams_iri_id`, default
  `gen_random_uuid()`), Liquibase `024-diagram-iri-id.yaml`. The default gave every existing row its
  own UUID; rollback drops the constraint and the column.
- `DiagramEntity.iriId` is set when the object is constructed, has no setter and never changes after
  insert.
- IRI = `ismd.publication.iri-base` + `diagramy/` + `iri_id`. The default base
  `https://data.dia.gov.cz/zdroj/ismd/` follows the pattern of DIA resource IRIs in LKOD DIA.
- The IRI survives renaming the diagram and renaming or moving its vocabulary. It is not reused after
  the diagram is deleted. Treat the base as fixed once published.

## Drafts

Governed by `ismd.publication.include-drafts`. With `false`:

| What | Rule |
|---|---|
| Vocabulary | A vocabulary with `isPublished = false` is left out entirely |
| Concept of a published vocabulary | A concept with `isPublished = false` is removed from `pojmy` by IRI. Draft concepts' RDF shares the graph with published ones, so the filter runs on the download output, not on the Fuseki query. |
| Diagram | A diagram of a left-out vocabulary is left out |
| Diagram node | A node whose concept is a draft concept of **any** ISMD vocabulary is left out, so a foreign node cannot reveal another vocabulary's draft; a `seskupen_pod` pointing at a left-out node is dropped. Nodes on concepts ISMD does not know (NKD) stay. |
| References in concepts | References from kept concepts to left-out concepts (`nadřazený-pojem`, `nadřazená-třída`, `obor-hodnot`, …) are removed; a list left empty is removed with its key. |
| Edge | An edge whose `klíč_hrany` is a draft concept (relationship), or a hierarchy edge `edge\|KIND\|source\|target` with a draft end, is left out |

With `true` (the default) everything is published and `je_publikován` tells drafts apart.

## How a publication is built

An admin starts it with `POST /api/admin/publikace/run` (see [Running a publication](#running-a-publication));
one run builds both files.

1. **Lock and context.** The run takes the lock (another run in progress → `409`) and fails on a blank
   `context-url`.
2. **One PostgreSQL read.** `publikováno` is recorded and `PublicationSnapshotReader` loads, in one
   read-only transaction (one query per table), the vocabularies with their concepts and the diagrams
   with their nodes and edges into a `PublicationSnapshot`. It has no fields for users, comments or
   pending diagram edits, so excluded data cannot leak through a shared model. With
   `include-drafts=false`, `DraftFilter` applies the rules above to the snapshot.
3. **`slovniky.jsonld`, one vocabulary at a time.** For each vocabulary
   `OntologyDownloadService.downloadOntology(id, "json-ld")` is called, the PostgreSQL metadata is
   added and the node is written by a streaming JSON writer into a temporary directory
   (`ismd-publication-*`). Only one vocabulary is in memory at a time.
4. **`diagramy.jsonld`** is built from PostgreSQL alone.
5. **The publication record** is written as the last node of both files (node order in `@graph` has
   no meaning); size and ETag (SHA-256) of each file are computed (`BuiltFile`).
6. **Archive.** `PublicationArchive.save(...)` stores the run and both files (gzip) in PostgreSQL in
   one transaction, which also drops content outside `retain-files`.
7. **Clean-up.** The temporary directory is deleted in every case; the run returns `PublicationRun`.

Any failure fails the run (`PUBLICATION_FAILED`) and nothing is stored.

Design points that follow from the existing code:

- **Validation does not block publication.** The restriction on downloading vocabularies with
  validation errors lives in `OntologyController`, not in the service, so a direct service call
  includes every vocabulary.
- **Cleaned content.** The service applies the same filtering and OFN transformation as the existing
  vocabulary download (`/api/ontology/{slug}/download`), so the publication and a single vocabulary download match (apart from the draft filter).
- **Failures are reported per vocabulary** (`stav_obsahu = FAILED`, `úplná = false`).
- **An empty vocabulary is not a failure.** A vocabulary without RDF content is published with its
  metadata and `stav_obsahu = EMPTY`.
- **Fuseki load.** Graphs are fetched one at a time through the existing Fuseki semaphore, so
  publication does not crowd out interactive requests. Only an admin starts a run.

## Archive

Every publication is stored in PostgreSQL as audit trail and as the source of the admin download
(Liquibase `025-create-publications.yaml`, schema `ismd_schema`):

| Table | Columns | Retention |
|---|---|---|
| `publication` | `id`, `published_at` (`timestamptz`), `triggered_by` (e.g. `ADMIN:<username>`), `include_drafts`, `complete`, `vocabularies`, `diagrams`, `context_url` | Forever |
| `publication_file` | `publication_id` (FK, `ON DELETE CASCADE`), `name`, `size`, `etag`, `content_gzip` (`bytea`, gzip of the file); unique (`publication_id`, `name`) | `name`, `size`, `etag` forever; `content_gzip` is set to `NULL` once the publication is older than the newest `retain-files` publications |

`triggered_by` stays in the database; it is not part of the published files.

## Consistency

PostgreSQL and TDB2 share no transaction (see
[`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md)). The PostgreSQL side is one consistent snapshot;
each vocabulary's RDF is read shortly after. An edit made during a run can therefore appear in the RDF
but not in the metadata. `publikováno` marks the PostgreSQL snapshot. An admin chooses when to publish,
so this is acceptable. Between publications the files deliberately do not follow changes; the existing
REST endpoints give the current state.

## Cataloguing

ISMD does not generate the dataset metadata and does not host the distributions. The catalogue record
and its distributions live in [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia) (e.g.
`ismd/slovníky-a-diagramy.jsonld`) and belong to the DIA LKOD pipeline, which uploads the files ISMD
produced; LinkedPipes ETL harvests the record into NKOD:

- Dataset IRI `https://data.dia.gov.cz/zdroj/datové-sady/ismd/slovníky-a-diagramy`, publisher
  `https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921` (DIA).
- Two distributions (vocabularies, diagrams), format `JSON_LD`, media type `application/ld+json` and
  `schéma` = the ISMD OD context URL (or a JSON Schema, if one is made). The distribution URLs are
  wherever LKOD DIA hosts the uploaded files.
- `periodicita_aktualizace` = `http://publications.europa.eu/resource/authority/frequency/IRREG`
  (irregular): publication runs on demand.
- Drafts are published (`include-drafts=true`), so the dataset description must say so explicitly.

## Context hosting

The ISMD OD context is hosted in this repository and served by GitHub Pages:

- **File and URL.** `pages/ismd-otevřená-data/draft/kontext.jsonld`, served at
  `https://datagov-cz.github.io/ismd-tool-backend/ismd-otevřená-data/draft/kontext.jsonld`, the default
  of `ismd.publication.context-url` (`PublicationConfig.DRAFT_CONTEXT_URL`).
  `PublicationFilesTest.defaultContextUrl_pointsAtThePublishedDraftContext` checks that the default URL
  maps to the file in `pages/`; the tests expand both files through it and the OFN context.
- **Deployment.** `.github/workflows/pages.yml` runs on a push to `dev` that touches `pages/**` (and on
  manual dispatch), validates every `.jsonld` with `jq` and deploys `pages/` to GitHub Pages. GitHub
  Pages must be enabled for the repository (Source: GitHub Actions), which needs repository admin
  rights.
- **Headers.** JSON-LD processors fetch a remote context at run time; the URL must return
  `application/ld+json` (or `application/json`) and `Access-Control-Allow-Origin: *`. GitHub Pages
  serves both without configuration. `raw.githubusercontent.com` returns `text/plain` and is unsuitable.
- **Versioning.** Following OFN, a dated folder `pages/ismd-otevřená-data/<YYYY-MM-DD>/kontext.jsonld`
  never changes once published; `draft/` is the working, mutable version. Before production a dated
  version is frozen and `context-url` is switched to it. Changing terms means a new dated folder and a
  new `context-url`.
- **One context** serves both files; their terms do not overlap. Term IRIs are under
  `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/`.
- **`@version` 1.1** is required: the timestamp terms use nested contexts, a JSON-LD 1.1 feature, as
  OFN does.

## Implementation

Package `com.dia.ismdtoolbackend.publication`:

| Class | Role |
|---|---|
| `PublicationConfig` | `@ConfigurationProperties("ismd.publication")` with the defaults above |
| `snapshot.PublicationSnapshotReader` | One PostgreSQL read into `PublicationSnapshot` |
| `snapshot.DraftFilter` | The `include-drafts=false` rules |
| `file.VocabularyFileWriter` | `slovniky.jsonld`: OFN JSON-LD download + PostgreSQL metadata, references to left-out concepts removed |
| `file.DiagramFileWriter` | `diagramy.jsonld` from PostgreSQL alone |
| `file.PublicationJson` | All keys in one place, the document frame, the publication record, timestamps |
| `BuiltFile` | A file written by a run: `name`, `path`, `size`, `etag` |
| `PublicationService` | One run: lock, snapshot, filter, write both files into a temporary directory, archive, clean up; returns `PublicationRun` or throws `PublicationInProgressException` / `PublicationFailedException` |
| `PublicationRun` | Result of a stored run with its `id`, returned by `POST /run` |
| `PublicationAdminController` | `POST /run`, `GET /history`, `GET /{id}/{file}`; delegates to `PublicationService` and `PublicationArchive` |
| `archive.PublicationEntity`, `archive.PublicationFileEntity` | The `publication` and `publication_file` rows |
| `archive.PublicationRepository`, `archive.PublicationFileRepository` | Their repositories |
| `archive.PublicationArchive` | `save` (with retention), `history`, `file` |
| `archive.PublicationSummary`, `archive.PublicationFileSummaryRow` | The history entry and its content-free file projection |

Outside the package: Liquibase `024-diagram-iri-id.yaml` + `DiagramEntity.iriId`; Liquibase
`025-create-publications.yaml`; `/api/admin/publikace/**` in `SecurityConfig`;
`PublicationInProgressException`, `PublicationFailedException`, `PublicationFilePrunedException` in
`exception`, mapped in `GlobalExceptionHandler` to `PUBLICATION_*` in `ErrorCode`; the context in
`pages/ismd-otevřená-data/draft/kontext.jsonld` and its deployment in `.github/workflows/pages.yml`.
`OntologyDownloadService` and the repositories are reused unchanged; `VocabularyConstants.CONTEXT`
already pointed at OFN `2026-02-26`.

`PublicationFileEntity.content_gzip` is mapped with `columnDefinition = "bytea"`: without it, schema
validation against real PostgreSQL fails (`bytea` reported as `BINARY` vs `VARBINARY`); H2 does not
catch it.

**Tests:** `DiagramRepositoryTest` (diagram IRI), `PublicationSnapshotReaderTest`, `DraftFilterTest`,
`PublicationFilesTest` (real `JsonExporter` output, expansion through both real contexts, every key a
defined term, default `context-url` maps to the file in `pages/`), `PublicationServiceTest` (stored
files and run, temporary build removed, drafts off, a snapshot, writer or archive failure stores
nothing, missing `context-url`, run in progress), `archive.PublicationArchivePostgresTest` (real
PostgreSQL via Testcontainers: `bytea` round trip, retention keeps metadata and drops content, not
found), `PublicationAdminControllerTest`. The key rules were checked by deliberately breaking them.
There is no validation against the OFN JSON Schema: no schema validator is on the classpath.

## Enabling

There is no `enabled` switch and nothing to hand off to infrastructure. Before production use:

1. Enable GitHub Pages for the repository (Source: GitHub Actions; needs repository admin rights) and
   verify that the draft context URL resolves with `application/ld+json`.
2. Freeze a dated context version (`pages/ismd-otevřená-data/<YYYY-MM-DD>/`) and set `context-url` to
   it.
3. An admin runs `POST /api/admin/publikace/run` and downloads both files via
   `GET /api/admin/publikace/{id}/{file}`.
4. The DIA LKOD pipeline uploads them and maintains the catalogue record.
