# Open-data publication: vocabularies and diagrams

> Status: **implemented, off by default** (`ismd.publication.enabled=false`); see
> [Enabling](#enabling). Czech version: [`DATA_EXPORT_CS.md`](./DATA_EXPORT_CS.md).

## What problem this solves

The application publishes draft and published vocabularies, with their metadata, and the diagram data
as open data. The files are published at a regular interval, can be downloaded without signing in and
are catalogued in NKOD through the DIA local catalogue.

| Client requirement | How the design meets it |
|---|---|
| No ZIP and no X files, a small fixed set of files | Exactly **two files**: `slovniky.jsonld` and `diagramy.jsonld` |
| No `manifest.json`; its general fields (`complete`, `exportedAt`, …) and the vocabulary and diagram fields (`createdAt`, `isPublished`, …) belong in the files | Each file carries a **publication record** node (general fields); vocabulary and diagram metadata sit on their own nodes |
| One file for all vocabularies and one for all diagrams instead of a file each | `@graph` with one node per vocabulary or diagram |
| `concepts.json` goes away, its content moves into the vocabulary file | PostgreSQL concept metadata is added to each concept in `pojmy` |
| Attributes that point to files go away | No `file`, `concepts`, `rdf.file`; links are by IRI only |
| Everything is `.jsonld` with an `@context` of a vocabulary describing this open data | Two contexts: OFN Slovníky and the ISMD OD context (see [Contexts](#contexts)) |
| ISMD need not generate the dataset metadata; it is made once by hand | The catalogue record is stored once, by hand, in the LKOD DIA repository (see [Cataloguing](#cataloguing)) |

## Why a new endpoint

The data is split across two stores and no existing interface covers it.

| Option | Why it falls short |
|---|---|
| **Fuseki / SPARQL directly** | Diagrams and vocabulary metadata (slug, publication state, timestamps) exist only in PostgreSQL. Fuseki also exposes `update`, `upload` and `gsp-rw` next to `query`, so it cannot be opened to consumers as it is. |
| **Existing REST endpoints** | A consumer would need one call for the vocabulary list, one download per vocabulary, one call for the diagram list and one detail call per diagram. With the restriction on, a vocabulary download is refused for vocabularies with validation errors, the list model carries user and comment fields, and the diagram detail is a "fat" read that includes pending edits. |

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
| `ismd.publication.include-drafts` | `true` | `false` leaves out draft vocabularies, draft concepts of published vocabularies and the diagrams of left-out vocabularies (see [Drafts](#drafts)) |
| `ismd.publication.interval` | `P30D` | How old the current publication may be before a new one is built (ISO-8601 `Duration`) |
| `ismd.publication.check-cron` | `0 0 4 * * *` | When the scheduler checks whether the publication is older than `interval` |
| `ismd.publication.directory` | `${java.io.tmpdir}/ismd-publication` | Directory holding the published files (see [Storage](#storage)) |
| `ismd.publication.iri-base` | `https://data.dia.gov.cz/zdroj/ismd/` | Base of the IRIs ISMD mints: `<base>diagramy/<iri_id>` and `<base>publikace/<file>` (see [Diagram IRI](#diagram-iri)) |
| `ismd.publication.context-url` | — (set once LKOD DIA answers) | Second context in both files' `@context`; must be `https://`. Without it a run publishes nothing and logs an error. |
| `ismd.publication.enabled` | `false` | Scheduler and public endpoints; switch on once the context URL resolves |

### Why `interval` + `check-cron` rather than a cron alone

Cron cannot express "every 30 days" (the closest is "the 1st of each month") and a `fixedRate`
restarts with the application. So the scheduler runs daily on `check-cron` (the same pattern as
`reconciler.cron` and `outbox.prune-cron`) and builds a new publication only once `interval` has
passed since the current publication's `publikováno`. The anchor is the `publikováno` stored on disk
(see [Storage](#storage)), so a restart does not shift the period.

- **Application start:** when `directory` holds no publication (new container, empty disk), one is
  built in the background straight away; until then the endpoints answer `503` with `Retry-After`.
- **Own thread:** both checks (at startup and on `check-cron`) run on `publicationExecutor` (one
  thread, no queue, extra triggers dropped), so a run never holds the shared scheduler thread.
- **Stuck run:** at most one build runs at a time (a `ReentrantLock` suffices, the backend runs as a
  single instance). A failed build keeps the previous publication and is retried at the next check.

## Endpoints

```
GET|HEAD /api/publikace/slovniky.jsonld
GET|HEAD /api/publikace/diagramy.jsonld
```

Each file is a separate NKOD distribution. The endpoints only serve the latest published files from
disk; they never build anything.

| Aspect | Value |
|---|---|
| Authorization | Public. `SecurityConfig` opens **exactly these two paths** (no `/api/publikace/*`), so a new endpoint under `/api/publikace` is not public by accident; other paths are refused by `denyAll()`. |
| `200` | `application/ld+json;charset=UTF-8`, `Content-Disposition: attachment; filename="slovniky.jsonld"` (or `diagramy.jsonld`), `Content-Length`, `ETag`, `Last-Modified`, `Cache-Control: no-cache, public` |
| `304` | `If-None-Match` / `If-Modified-Since` matches the current publication |
| `503` + `Retry-After: 600` | Publication is on but none has run yet |
| `404` | Publication is off (`enabled=false`), or another file name (e.g. `current.json`) |
| `HEAD` | Mapped explicitly; answers headers from the in-memory record and never opens the file. NKOD requires an answer within 3 s, met with ample margin. |
| CORS | `Access-Control-Allow-Origin: *`, methods `GET`/`HEAD`/`OPTIONS`, no credentials, for these two paths only; other paths keep the profile's allowlist |

- **ETag** = quoted SHA-256 of the file content. **Last-Modified** = `publikováno`.
- **Why CORS `*`:** NKOD harvests from LKOD DIA, not from ISMD, and server-side clients ignore CORS.
  `*` serves NKOD's CORS quality check on distribution URLs and browser-based consumers. It adds no
  new risk: the data is public, identical for everyone, credential-less and read-only; interception
  is handled by HTTPS at the ingress.

### Storage

Each run writes into its own directory `<directory>/<epoch-millis>/`. The current one is the one named
by `<directory>/current.json` (size and ETag of each file, `publikováno`), which is replaced atomically
only after both files are written. A client therefore never gets a half-built file or a pair of files
from different runs. Older directories are deleted after a successful run; a request still streaming an
old file keeps its open handle. On startup `current.json` is read back and the file sizes are checked;
an incomplete publication is discarded and rebuilt.

Without a persistent volume, the publication is rebuilt after every deployment and the 30-day period
starts again.

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
  record and diagrams. It comes second, so it does not override OFN terms. It is hosted in a dedicated
  LKOD DIA repository (see [Context hosting](#context-hosting)).

### Naming conventions

Taken from the standards in [`datagov-cz/otevrene-formalni-normy`](https://github.com/datagov-cz/otevrene-formalni-normy)
(DCAT-AP-CZ, OFN Slovníky) and from [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia):

| Element | Convention | Example in the standards | ISMD proposal |
|---|---|---|---|
| Property (JSON key) | Czech, lower case, words joined by `_` | `periodicita_aktualizace`, `klíčové_slovo`, `je_součástí`, `datum_a_čas` | `je_publikován`, `stav_poslední_validace`, `počet_pojmů` |
| Type (`typ` value) | Czech, capitalised, spaces | `Datová sada`, `Časový okamžik`, `Typ objektu práva` | `Diagram`, `Uzel diagramu`, `Publikace ISMD` |
| `iri` / `typ` | aliases of `@id` / `@type` | every context | unchanged |
| Multilingual text | `@container: @language` | `název`, `popis` | only for human-readable text |
| Resource reference | `@type: @id` | `poskytovatel`, `téma` | `slovník`, `pojem`, `seskupen_pod` |
| Context location | `<standard>/<YYYY-MM-DD>/<profile>/kontext.jsonld`, working version in `draft/` | `dcat-ap-cz-hvd/2026-09-23/datová-sada/kontext.jsonld` | `ismd-otevřená-data/<date>/slovníky/kontext.jsonld`, `…/diagramy/kontext.jsonld`, or one `…/kontext.jsonld` |
| DIA resource IRIs | `https://data.dia.gov.cz/zdroj/<area>/…` | `https://data.dia.gov.cz/zdroj/datové-sady/nkod/nkod-kompletní` | `https://data.dia.gov.cz/zdroj/ismd/diagramy/<uuid>` |
| Vocabulary term IRIs | slovník.gov.cz `…/<vocabulary>/pojem/<term>` (OFN), data.gov.cz `slovník/<name>/<term>` (DCAT-AP-CZ) | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/okamžik-vytvoření` | `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/<term>` |

Term IRIs use hyphens (`okamžik-vytvoření`), context keys underscores (`datum_a_čas`). **Note the
mismatch:** OFN Slovníky uses hyphens in concept fields (`alternativní-název`, `nadřazený-pojem`),
DCAT-AP-CZ underscores. A concept in `slovniky.jsonld` therefore carries both forms side by side
(`alternativní-název` from OFN, `je_publikován` from ISMD). The design follows DCAT-AP-CZ as
requested; if consistency within a concept should win, only the keys in the ISMD OD context change,
not the term IRIs. All keys live in one class (`PublicationJson`).

The vocabulary describing this open data is itself an OFN vocabulary (classes `Diagram`,
`Uzel diagramu`, properties `je_publikován`, …), so it can be authored in ISMD and the context
generated from it. Term names below are provisional; that vocabulary settles them.

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

The scheduler starts it (see [Configuration](#configuration)); one run builds both files.

1. **One PostgreSQL read.** `publikováno` is recorded and `PublicationSnapshotReader` loads, in one
   read-only transaction (one query per table), the vocabularies with their concepts and the diagrams
   with their nodes and edges into a `PublicationSnapshot`. It has no fields for users, comments or
   pending diagram edits, so excluded data cannot leak through a shared model. With
   `include-drafts=false`, `DraftFilter` applies the rules above to the snapshot.
2. **`slovniky.jsonld`, one vocabulary at a time.** For each vocabulary
   `OntologyDownloadService.downloadOntology(id, "json-ld")` is called, the PostgreSQL metadata is
   added and the node is written by a streaming JSON writer into the run's directory. Only one
   vocabulary is in memory at a time.
3. **`diagramy.jsonld`** is built from PostgreSQL alone.
4. **The publication record** is written as the last node of both files (node order in `@graph` has
   no meaning), the ETag is computed and the run is committed by replacing `current.json`.

Design points that follow from the existing code:

- **Validation does not block publication.** The restriction on downloading vocabularies with
  validation errors lives in `OntologyController`, not in the service, so a direct service call
  includes every vocabulary.
- **Cleaned content.** The service applies the same filtering and OFN transformation as the public
  download, so the publication and a single vocabulary download match (apart from the draft filter).
- **Failures are reported per vocabulary** (`stav_obsahu = FAILED`, `úplná = false`). If the run as a
  whole fails, the previous files stay published.
- **An empty vocabulary is not a failure.** A vocabulary without RDF content is published with its
  metadata and `stav_obsahu = EMPTY`.
- **Fuseki load.** Graphs are fetched one at a time through the existing Fuseki semaphore and the run
  is at `check-cron` (default 04:00, after the reconciler at 03:00 and the outbox prune at 03:30), so
  publication does not crowd out interactive requests. The public URLs build nothing, so they cannot
  be abused to create load.

## Consistency

PostgreSQL and TDB2 share no transaction (see
[`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md)). The PostgreSQL side is one consistent snapshot;
each vocabulary's RDF is read shortly after. An edit made during a run can therefore appear in the RDF
but not in the metadata. `publikováno` marks the PostgreSQL snapshot. With a 30-day period and a night
run this is acceptable. Between publications the files deliberately do not follow changes; the
existing REST endpoints give the current state.

## Cataloguing

ISMD does not generate the dataset metadata. It is created once, by hand, with the NKOD form and stored
in [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia) (e.g. `ismd/slovníky-a-diagramy.jsonld`),
from where LinkedPipes ETL harvests it into NKOD:

- Dataset IRI `https://data.dia.gov.cz/zdroj/datové-sady/ismd/slovníky-a-diagramy`, publisher
  `https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921` (DIA).
- Two distributions (`…/distribuce/0` vocabularies, `…/distribuce/1` diagrams) with
  `přístupové_url` and `soubor_ke_stažení` on the public endpoint URLs, format `JSON_LD`, media type
  `application/ld+json` and `schéma` = the ISMD OD context URL (or a JSON Schema, if one is made). The
  public URL includes the application's context path (`server.servlet.context-path`, default
  `/popisujeme`): `https://<host>/popisujeme/api/publikace/slovniky.jsonld`.
- `periodicita_aktualizace` = `http://publications.europa.eu/resource/authority/frequency/MONTHLY`.
  **Changing `ismd.publication.interval` requires editing the record by hand**, or the catalogue is
  wrong.
- If `include-drafts = true`, the dataset description should say so explicitly.

## Context hosting

The ISMD OD context (and possibly the vocabulary it was generated from) is hosted in a dedicated LKOD
DIA repository. A draft of the context is in this repository as a test resource,
`src/test/resources/com/dia/publication/ismd-od-kontext.jsonld`; term IRIs are under the provisional
`https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/`. The tests expand both files through it
and the OFN context. Before enabling (questions sent to the `lkod-dia` maintainers):

- **Repository and URL.** `lkod-dia` holds only catalogue records today and its ETL processes every
  `.jsonld` in it as a record. The context could end up as a broken record; either the ETL skips it (a
  separate folder) or a separate repository is used. `ismd.publication.context-url` names its stable
  public URL.
- **Headers.** JSON-LD processors fetch a remote context at run time; the URL must return
  `application/ld+json` (or `application/json`) and `Access-Control-Allow-Origin: *`.
  `raw.githubusercontent.com` returns `text/plain`; GitHub Pages works.
- **Versioning.** Following OFN, published versions never change (`<YYYY-MM-DD>/…`); changing terms
  means a new dated version and a new `context-url`, with the working version in `draft/`.
- **`@version` 1.1** is required: the timestamp terms use nested contexts, a JSON-LD 1.1 feature, as
  OFN does.

## Open decisions

| # | Decision | Proposal |
|---|---|---|
| 1 | Default of `include-drafts` | `true` per the requirement ("draft and published vocabularies"); `false` is safer for public data |
| 2 | Exact location and URL of the context in LKOD DIA (see [Context hosting](#context-hosting)) | A folder outside ETL processing, served by GitHub Pages or data.dia.gov.cz |
| 3 | One ISMD OD context for both files, or two profiles (`…/slovníky/`, `…/diagramy/`) | One; the terms do not overlap |
| 4 | Underscores (DCAT-AP-CZ) vs hyphens (OFN Slovníky concept fields) in ISMD keys | Underscores, as requested |
| 5 | IRI base (`iri-base`) | `https://data.dia.gov.cz/zdroj/ismd/` |
| 6 | Persistent storage for `ismd.publication.directory` | Without a persistent volume the publication is rebuilt at startup after every deployment and the period restarts; that is enough. A volume would be an infrastructure hand-off. |

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
| `PublicationService` | One run: snapshot, filter, write both files, commit; guarded against concurrent runs |
| `PublicationStore` | Run directories, `current.json`, reload after restart |
| `PublicationScheduler` | Checks at startup and on `check-cron`, both on `publicationExecutor` |
| `PublicationController` | `GET`/`HEAD` of both files |

Outside the package: Liquibase `024-diagram-iri-id.yaml` + `DiagramEntity.iriId`;
`AsyncConfig.publicationExecutor`; the exact paths in `SecurityConfig`; the CORS rule in `CorsConfig`
and `CorsConfigUtil`. `OntologyDownloadService` and the repositories are reused unchanged;
`VocabularyConstants.CONTEXT` already pointed at OFN `2026-02-26`.

**Tests:** `DiagramRepositoryTest` (diagram IRI), `PublicationSnapshotReaderTest`, `DraftFilterTest`,
`PublicationFilesTest` (real `JsonExporter` output, expansion through both real contexts, every key a
defined term), `PublicationServiceTest`, `PublicationSchedulerTest`, `PublicationSchedulerAsyncTest`,
`PublicationControllerTest`, `SecurityFilterChainIntegrationTest` (public paths, CORS, other paths under
`/api/publikace` answer `401`). The key rules were checked by deliberately breaking them. There is no
validation against the OFN JSON Schema: no schema validator is on the classpath.

## Enabling

Publication stays off until these steps are done:

1. Answers from the `lkod-dia` maintainers → final context, `context-url`, term IRIs and key spelling
   (constants in `PublicationJson`).
2. Publish the context and create the catalogue record (distribution URLs
   `https://<host>/popisujeme/api/publikace/*.jsonld`).
3. Set `ismd.publication.enabled=true` and an `https://` `context-url` per environment
   (infrastructure hand-off); optionally a persistent volume for `directory`.
