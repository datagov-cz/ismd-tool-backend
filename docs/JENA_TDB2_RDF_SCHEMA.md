# Jena TDB2 / Apache Fuseki RDF Schema

## Overview

**RDF store**: Apache Jena Fuseki with a TDB2 dataset (`ismd-tool-dataset`), reached over HTTP
**Jena version**: 5.4.0
**Layout**: one named graph per vocabulary; graph IRI = vocabulary IRI
**Data standard**: OFN Slovníky 2026-02-26, variant *Konceptuální model* (`ofn/slovniky/kontext.jsonld`, `ofn/slovniky/schema.json`)

Every triple ISMD stores for its own vocabularies is expressible in the OFN slovníky JSON-LD context. The ISMD
download is the OFN document read from those triples, with nothing added, renamed or repaired on the way out.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Controllers → Services / Operations → Repositories         │
└────────────┬─────────────────────────────┬──────────────────┘
             ▼                             ▼
┌────────────────────────┐    ┌───────────────────────────────┐
│ PostgreSQL             │    │ Fuseki / TDB2                 │
│ - vocabulary + concept │    │ - one graph per vocabulary    │
│   metadata (ownership, │    │ - owned concepts in OFN shape │
│   publish state, slug) │    │ - link triples to NKD / other │
│ - inTezaurus flag      │    │   vocabularies                │
│ - NKD snapshot copies  │    │                               │
│ - outbox, migration log│    │                               │
└────────────────────────┘    └───────────────────────────────┘
```

- **PostgreSQL** is the source of truth for which concepts exist (one row per owned concept), access control and
  publish state. Writes to TDB2 go through the outbox in the same PostgreSQL transaction; see
  `PG_TDB2_CONSISTENCY.md`.
- **TDB2** holds the RDF. NKD snapshot copies live only in PostgreSQL (`nkd_concept_snapshots`); the owner graph
  holds just the link triple (see `NKD_LOCAL_COPY_SNAPSHOT.md`).

---

## Configuration

**Configuration class**: `com.dia.ismdtoolbackend.config.JenaConfig`

```properties
jena.fuseki.url=${FUSEKI_URL}                       # e.g. http://localhost:3030/ismd-tool-dataset
rdf-migration.enabled=${RDF_MIGRATION_ENABLED:true}  # startup RDF migrations; off only in test contexts
rdf-migration.update-timeout=${RDF_MIGRATION_UPDATE_TIMEOUT:PT30M}
```

---

## Named Graphs

```
TDB2 dataset /ismd-tool-dataset
├── <https://slovník.gov.cz/doprava>            vocabulary graph
│   ├── the vocabulary node  <https://slovník.gov.cz/doprava>
│   ├── owned concepts       <https://slovník.gov.cz/doprava/pojem/…>  (skos:inScheme the graph)
│   ├── their nested nodes   blank nodes: creation instant, digital objects
│   └── link triples         objects only: NKD concepts, other vocabularies, legal provisions
└── <…next vocabulary…>
```

- **Ownership**: a concept belongs to a vocabulary when its IRI starts with `<vocabulary IRI>/` (the `/`
  boundary matters: `…/doprava-2/pojem/x` is not owned by `…/doprava`). Java: `OfnIris.isOwnedBy`; SPARQL:
  `JenaTDB2Repository.OWNED_CONCEPT_PATTERN`. RDF changesets must use the same rule.
- **Membership**: an owned concept is in the vocabulary when it has `skos:inScheme <graph>`. Only owned,
  in-scheme concepts are part of the vocabulary's OFN document (`pojmy`).
- **PostgreSQL link**: `ontologies.graph_name` = graph IRI; `concepts.graph_name` = the parent's graph IRI;
  `concepts.concept_iri` = the concept's IRI.

Two kinds of subjects can sit in a graph without being part of its document. Neither is exported, and neither has
a PostgreSQL row:

| Subject | Why it is there |
|---|---|
| Foreign concept described in place (IRI outside `<graph>/`, e.g. an NKD class with its labels) | Uploaded files describe referenced concepts. They are references only: the upload does not put them in the scheme. Their triples stay. |
| Owned concept without `skos:inScheme` | An upload concept the user excluded or the OFN gate rejected, kept by `ExcludedConceptPruning` because another concept references it. Unreferenced ones are deleted. |

---

## Namespaces

Prefixes as the OFN context defines them; the Turtle download uses the same ones. `do:` is this document's
shorthand only (the context has no prefix for it).

| Prefix | Namespace |
|---|---|
| `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` |
| `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` |
| `owl` | `http://www.w3.org/2002/07/owl#` |
| `skos` | `http://www.w3.org/2004/02/skos/core#` |
| `dcterms` | `http://purl.org/dc/terms/` |
| `xsd` | `http://www.w3.org/2001/XMLSchema#` |
| `schema` | `http://schema.org/` (only `schema:url` of a digital object) |
| `slovníky` | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/` |
| `vsgov` | `https://slovník.gov.cz/veřejný-sektor/pojem/` |
| `l111` (`111-2009` in the context) | `https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/` |
| `a104` | `https://slovník.gov.cz/agendový/104/pojem/` |
| `čas` | `https://slovník.gov.cz/generický/čas/pojem/` |
| `do` | `https://slovník.gov.cz/generický/digitální-objekty/pojem/` |
| `způsoby-získání` | `https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/` |
| `způsoby-sdílení` | `https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/` |
| `typy-obsahu` | `https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/` |

Java constants for every IRI below: `com.dia.ismdtoolbackend.ofn.OfnIris`.

---

## Vocabulary

```turtle
<https://slovník.gov.cz/doprava>
    a slovníky:slovník, skos:ConceptScheme, owl:Ontology ;
    skos:prefLabel "Doprava"@cs ;
    dcterms:description "Pojmy silniční dopravy."@cs ;          # optional
    slovníky:okamžik-vytvoření [
        a čas:časový-okamžik ;
        čas:datum-a-čas "2026-10-07T13:47:18.858014Z"^^xsd:dateTimeStamp
    ] .
```

- All three types, always: ISMD supports only the Konceptuální model, which includes Slovník and Tezaurus.
- `název` (`skos:prefLabel`) needs a `cs` value; a `dcterms:description` needs one too when present.
- `vytvořeno` is a **blank** `čas:časový-okamžik` with an `xsd:dateTimeStamp` (with zone). New vocabularies get
  Europe/Prague time (`OfnDates`).
- `aktualizováno` (`slovníky:okamžik-poslední-změny`) has the same shape. It is optional and not maintained yet.

---

## Concepts

Every owned concept carries `skos:Concept`, `slovníky:pojem`, `skos:inScheme <graph>`, a `skos:prefLabel` with a
`cs` value, and **exactly one OWL kind**.

### Kind and role marker

| Kind | OWL type (the source of truth) | Role marker (derived) |
|---|---|---|
| Třída | `owl:Class` | `slovníky:třída` |
| Vlastnost | `owl:DatatypeProperty` | `slovníky:vlastnost` |
| Vztah | `owl:ObjectProperty` | `slovníky:vztah` |

The markers are not OFN terms. They are kept, also in exports, until the DIA data review, and are always derived
from the OWL kind (`RoleMarkerDerivation`), never the other way round. The OFN gate tolerates exactly these three.

### Třída

```turtle
<https://slovník.gov.cz/doprava/pojem/osoba>
    a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
    a vsgov:typ-subjektu-práva ;                                  # or vsgov:typ-objektu-práva; optional
    skos:inScheme <https://slovník.gov.cz/doprava> ;
    skos:prefLabel "Osoba"@cs ;
    rdfs:subClassOf <https://slovník.gov.cz/doprava/pojem/subjekt> ;    # nadřazená třída
    slovníky:má-instance-definované-číselníkem <https://…/číselníky/typy-osob> .  # Číselník; optional
```

A Číselník is an IRI node: `<codelist> a l111:číselník ; l111:má-v-nkod-zastřešující-datovou-sadu <dataset>`.

### Vlastnost

```turtle
<https://slovník.gov.cz/doprava/pojem/registrační-značka>
    a skos:Concept, slovníky:pojem, owl:DatatypeProperty, slovníky:vlastnost ;
    skos:inScheme <https://slovník.gov.cz/doprava> ;
    skos:prefLabel "Registrační značka"@cs ;
    rdfs:domain <https://slovník.gov.cz/doprava/pojem/vozidlo> ;
    rdfs:range xsd:string ;
    rdfs:subPropertyOf <…> .                                       # nadřazená vlastnost; optional
```

The range comes from `PropertyDataType.rangeIri` on create and edit: the FE sends a code from
`GET /property-datatypes`; an `xsd:`/`rdfs:` CURIE or a full IRI is also accepted; blank or unknown →
`rdfs:Literal`.

### Vztah

```turtle
<https://slovník.gov.cz/doprava/pojem/má-vlastníka>
    a skos:Concept, slovníky:pojem, owl:ObjectProperty, slovníky:vztah ;
    skos:inScheme <https://slovník.gov.cz/doprava> ;
    skos:prefLabel "Má vlastníka"@cs ;
    rdfs:domain <https://slovník.gov.cz/doprava/pojem/vozidlo> ;
    rdfs:range <https://slovník.gov.cz/doprava/pojem/osoba> ;
    rdfs:subPropertyOf <…> .                                       # nadřazený vztah; optional
```

A Vztah may be stored without its domain or range (a draft, e.g. drawn in the diagram). The download is
blocked until both are filled in.

### Fields shared by all kinds

| OFN key | Predicate | Object |
|---|---|---|
| `název` | `skos:prefLabel` | text, one per language, `cs` required |
| `alternativní-název` | `skos:altLabel` | text, several per language |
| `popis` | `dcterms:description` | text, one per language |
| `definice` | `skos:definition` | text, one per language |
| `definující-` / `související-ustanovení-právního-předpisu` | `slovníky:definující-ustanovení` / `slovníky:související-ustanovení` | ELI IRI |
| `definující-` / `související-nelegislativní-zdroj` | `slovníky:definující-nelegislativní-zdroj` / `slovníky:související-nelegislativní-zdroj` | blank `do:digitální-objekt` with `dcterms:title` (text), `dcterms:description` (text), `schema:url` (`xsd:anyURI` literal) |
| `ekvivalentní-pojem` | `skos:exactMatch` | IRI |
| `nadřazený-pojem` | `skos:broader` | IRI |
| `je-sdílen-v-ppdf` | `a104:je-sdílen-v-propojeném-datovém-fondu` | `xsd:boolean` |
| `agenda` | `a104:sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy` | IRI (RPP agenda) |
| `agendový-informační-systém` | `a104:údaje-jsou-v-ais` | IRI (RPP ISVS) |
| `způsob-získání-údaje` | `slovníky:má-způsob-získání-údaje` | `způsoby-získání:` item |
| `typ-obsahu-údaje` | `slovníky:má-typ-obsahu-údaje` | `typy-obsahu:` item |
| `způsoby-sdílení-údaje` | `slovníky:má-způsob-sdílení-údaje` | `způsoby-sdílení:` items |
| type | `l111:veřejný-údaj` or `l111:neveřejný-údaj` | |
| `ustanovení-dokládající-neveřejnost-údaje` | `l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost` | ELI IRI; **required** on a Neveřejný údaj, never written on any other concept |

"Text" means a literal with a language tag. A single-valued key (no list in the schema) holds one value; two
values would make the export an array the schema rejects.

### Pre-OFN shapes

Earlier versions wrote these. Nothing writes them now, the write gate refuses them, and the pre-production
data that held them was wiped (2026-11).

| Was stored | Now |
|---|---|
| `inTezaurus` flag | PostgreSQL `concepts.in_tezaurus` only |
| `<ns>nadřazená-třída` copy of `rdfs:subClassOf` | not written; the detail view still adds it in memory until the readers are OWL-first |
| `slovníky:typ-subjektu-práva` / `-objektu-práva` | `vsgov:` types |
| provision under `l111:ustanovení-dokládající-neveřejnost-údaje` or a `<ns>` copy | `l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost` |
| sources under `<ns>` or `https://slovník.gov.cz/` predicates; `schema:url` as IRI or plain string | `slovníky:` predicates; `xsd:anyURI` literal |
| PPDF as `"true"`/`"false"` string | `xsd:boolean` |
| `<https://slovník.gov.cz/pojem>` (junk class from the common library base model) | not written |
| named `<vocabulary>/casovy-okamzik-vytvoreni` node with a zone-less string | blank instant, `xsd:dateTimeStamp` |
| Třída marked only by `slovníky:třída` | `owl:Class` + marker |
| legal provisions under `dct:conformsTo` / `dct:relation` (older uploads) | `slovníky:definující-ustanovení` / `slovníky:související-ustanovení` |
| misspelled `l111:je-vymezen-ustanovení-stanovujícím-jeho-neveřejnost` (ISMD export 2025-12 – 2026-07) | correct spelling `…ustanovením…` |
| `rdfs:range` pointing to an empty blank node | an IRI, `rdfs:Literal` by default for a Vlastnost |

---

## OFN Gate

`com.dia.ismdtoolbackend.ofn`: `OfnRdfReader` reads RDF into an OFN document by the context (an unmappable
triple is an issue, never repaired); `OfnConformanceGate` checks the document against the schema;
`ConceptKindRule` checks the OWL kind and marker; `OfnWriteGate` combines them.

| Tier | Rule | Examples |
|---|---|---|
| T1 shape | every triple fits the context; schema rules other than `required` | text without a language, `schema:url` as IRI, unknown predicate, two ranges |
| T2 kind | exactly one OWL kind; marker matches | Třída without `owl:Class`, DTP + OP on one concept |
| T3 completeness | schema `required` | Vlastnost/Vztah without domain or range, Neveřejný údaj without a provision |

| Write path | T1 / T2 | T3 |
|---|---|---|
| Concept create, concept edit (incl. diagram, working-copy sync) | refused: `OfnWriteGate.requireWritableConcepts` on the concept as it will be stored, 500 `OFN_WRITE_NONCONFORMANT` | allowed (draft) |
| Vocabulary create (alone or with concepts), vocabulary edit | refused: `requireWritableVocabulary` on the whole graph, 500 `OFN_WRITE_NONCONFORMANT` | allowed |
| Upload | T2 concept → that concept rejected (`RejectedConceptDto`); vocabulary-level T1 → whole upload rejected, 400 `UPLOAD_OFN_NONCONFORMANT` | stored as draft |
| ISMD download | blocked, 400 `ONTOLOGY_DOWNLOAD_BLOCKED_BY_OFN`, every issue listed | blocked |
| NKD download | not gated (ISMD does not own NKD content) | — |

The write checks are a safety net: the writers produce OFN by construction, so a refusal means a writer bug or
stored data written before the gate existed.

---

## RDF Migrations

Ordered, idempotent SPARQL Update changesets in `src/main/resources/db/rdf-changelog/`, applied at startup by
`RdfMigrationRunner` (after Liquibase, before the outbox relay, the reconciler and the web server). Each runs once
per vocabulary graph with `?graph` bound; applied changesets are recorded with a checksum in
`ismd_schema.rdf_migration_log`. Any failure stops startup. Conventions, preconditions and the deploy rule: the
changelog's `README.md`.

The changelog is empty: the pre-production data holding pre-OFN shapes was wiped (2026-11) instead of
migrated, and every write path goes through the OFN write gate.

Read-only check queries for any environment (which triples are not OFN; `NOT COVERED` = no changeset handles it):
`.planning/ofn-conformity/migration/sparql/`.

---

## Repository

`com.dia.ismdtoolbackend.repository.JenaTDB2Repository` is the facade over `FusekiSparqlExecutor` (queries,
timeouts, error mapping), `FusekiGraphWriter` (graph writes) and `FusekiConceptQueries`.

| Method | Use |
|---|---|
| `fetchGraph(graph)` | whole graph as a `Model` |
| `graphHasData`, `listNamedGraphs`, `listOwnedConceptIrisInGraph` | existence and enumeration |
| `saveOntologyModel(graph, model)` | add a model to a graph (outbox `CREATE_GRAPH`, vocabulary edit) |
| `putOntologyModel(graph, model)` | replace a graph (upload) |
| `applyConceptDelta(iri, graph, remove, add)` | outbox `UPSERT_CONCEPT`; deletes every blank child of the concept when the delta touches a blank node, so the editor re-stages surviving blank children (`BlankChildRestaging`) |
| `deleteConceptsFromGraph`, `deleteGraph` | outbox `DELETE_CONCEPTS` / `DELETE_GRAPH` |
| `executeUpdate(label, update)` | SPARQL Update (RDF migrations) |
| `fetchConceptLabels`, `fetchConceptHierarchy`, `fetchConceptResolutions`, `findRelatedConceptUris`, `searchByText`, `filterByRelationTypes` | read queries for detail, resolution and search |

---

## Data Flows

### Concept create / edit

```
ConceptController → ConceptServiceImpl
  → ConceptCreateOperation (ConceptCreator)   | ConceptEditOperation (ConceptEditor + NkdLinkReconciler)
  → OfnWriteGate.requireWritableConcepts       (T1/T2 on the concept as it will be stored)
  → OutboxWriter.enqueueUpsert + metadata row  (one PostgreSQL transaction)
  → after commit: OutboxRelay → JenaTDB2Repository.applyConceptDelta → Fuseki
```

Vocabulary create is the same with `requireWritableVocabulary` and `enqueueCreateGraph`. Vocabulary edit checks the
edited graph with `requireWritableVocabulary` and saves it directly. Upload writes the whole graph with
`putOntologyModel` after its own OFN check.

### Ontology detail (read)

```
OntologyDetailReader → JenaTDB2Repository.fetchGraph
  → OntologyDetailExtractor: DetailModelNormalizer → TurtleFilterUtil / TurtleFormatterUtil
  → ModelAnalyzer + ConceptJsonMapper → OntologyDetailModel
```

`DetailModelNormalizer` is temporary: it accepts pre-OFN shapes for the detail view. It goes when the readers
read the OWL kind first; the GET payloads must stay the same.

### Download

```
OntologyController (stored SHACL report block first)
  → OntologyDownloadServiceImpl → OfnVocabularyExporter
      OfnWriteGate.inspectVocabulary (one read: gated document + all issues)
      any issue → 400 ONTOLOGY_DOWNLOAD_BLOCKED_BY_OFN
      else OfnDocumentOrder → JSON-LD (the document) or Turtle (the document parsed back)
```

Both formats come from one document, so they are the same graph. The JSON-LD `@context` is the OFN URL
`https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld`, served offline from the bundled copy
(`OfnDocumentLoader`; any other remote context is refused). The NKD download still uses `JsonExporter`.

---

## Utility Classes

| Class | Role |
|---|---|
| `utility/creator/ConceptCreator` (+ `ClassConceptWriter`, `PropertyConceptWriter`, `RelationshipConceptWriter`, …) | create-path RDF in OFN shape |
| `utility/creator/OntologyModelFactory` | new vocabulary graph |
| `utility/editor/ConceptEditor`, `OntologyEditor` | edit deltas in OFN shape |
| `utility/upload/*` | upload steps: IRI normaliser, graph name, inScheme stamping, marker derivation, concept gate, pruning |
| `ofn/*` | reader, gate, exporter, dates, IRIs |
| `rdfmigration/*` | changeset loader and startup runner |
| `utility/detail/*`, `utility/exporter/json/ModelAnalyzer`, `ConceptJsonMapper` | detail read path |
| `utility/exporter/json/JsonExporter`, `utility/exporter/turtle/TurtleFilterUtil`, `TurtleFormatterUtil` | NKD download and the detail read path only |

---

## Backup and Recovery

```bash
# Online backup (Fuseki running)
curl -X POST http://localhost:3030/$/backup/ismd-tool-dataset
# Offline backup (Fuseki stopped)
cp -r /data/tdb2/production-dataset /backup/tdb2-$(date +%Y%m%d)
```

Restore: stop Fuseki, replace the dataset, start Fuseki. Back up PostgreSQL and TDB2 in the same window: they
reference each other through `graph_name` and concept IRIs, and `rdf_migration_log` records which changesets
the TDB2 data has had. Take a TDB2 backup before a deploy that adds changesets.

---

## Security

- Only the backend connects to Fuseki; it must not be reachable from outside.
- Authorization is enforced in the application (`SecurityConfig`, `@PreAuthorize`, ownership checks before writes).
- IRIs are validated before RDF is built; user input is sanitized before it reaches SPARQL.
- Validation: the external SHACL report can block a download; the OFN gate always does for non-conformant data.

---

## Monitoring and Maintenance

- Fuseki: `$/ping`, `$/stats/{dataset}`, `$/server`.
- Compaction (Fuseki stopped): `tdb2.tdbcompact --loc=/data/tdb2/production-dataset`.
- Statistics: `tdb2.tdbstats --loc=/data/tdb2/production-dataset --graph=<graph-uri>`.

---

## Key Files

| Component | Path (under `src/main/`) |
|---|---|
| Fuseki config | `java/com/dia/ismdtoolbackend/config/JenaConfig.java` |
| Repository facade | `java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` |
| OFN gate and IRIs | `java/com/dia/ismdtoolbackend/ofn/` |
| OFN context and schema | `resources/ofn/slovniky/` |
| RDF migrations | `resources/db/rdf-changelog/`, `java/com/dia/ismdtoolbackend/rdfmigration/` |
| Outbox | `java/com/dia/ismdtoolbackend/outbox/` |

| Resource | URL |
|---|---|
| OFN Slovníky | https://ofn.gov.cz/slovníky/ |
| Apache Jena | https://jena.apache.org/documentation/ |
| SPARQL 1.1 | https://www.w3.org/TR/sparql11-query/ |

---

**Last updated**: 2026-11-03
