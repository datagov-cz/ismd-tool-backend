# Search Feature — Architecture Document

## 1. Context & Motivation

The ISMD Tool currently has no search functionality. Users can only browse flat lists of ontologies and concepts. We need a unified search endpoint that:

- Queries the **external NKD** (Virtuoso SPARQL endpoint)
- Queries **local ISMD** resources (PostgreSQL metadata + Apache Fuseki TDB2 RDF triple store)
- Enforces access control: anonymous users search NKD only; authenticated users search both

### Scale assumptions

- **NKD**: Low tens of thousands of resources currently. Design target: **~100k records** (one order of magnitude headroom).
- **ISMD local**: Hundreds to low thousands of user-created resources.
- Performance target: Search response under **2 seconds** at design-target scale.

---

## 2. High-Level Architecture

```
                                    ┌─────────────────────────────┐
                                    │         Frontend            │
                                    │   GET /api/search?q=...     │
                                    └─────────────┬───────────────┘
                                                  │
                                                  ▼
                              ┌────────────────────────────────────────┐
                              │          SecurityFilterChain           │
                              │            @Order(0)                   │
                              │                                        │
                              │  • Matches /api/search/**              │
                              │  • permitAll() — no auth required      │
                              │  • JWT processed IF present            │
                              │  • Anonymous requests pass through     │
                              └─────────────┬──────────────────────────┘
                                            │
                                            ▼
                              ┌────────────────────────────────────────┐
                              │         SearchController               │
                              │                                        │
                              │  @GetMapping("/api/search")            │
                              │  SecurityUser = null (anon) or filled  │
                              │                                        │
                              │  Validates input (q >= 2 chars, etc.)  │
                              │  Delegates to SearchService            │
                              └─────────────┬──────────────────────────┘
                                            │
                                            ▼
                              ┌────────────────────────────────────────┐
                              │         SearchServiceImpl              │
                              │         (Orchestrator)                 │
                              │                                        │
                              │  1. Determine sources based on auth    │
                              │  2. Dispatch providers in PARALLEL     │
                              │  3. Apply per-source timeout           │
                              │  4. Merge results + source statuses    │
                              │  5. Return SearchResponseDto           │
                              └──────┬──────────────┬──────────────────┘
                                     │              │
                       ┌─────────────┘              └──────────────┐
                       ▼                                           ▼
       ┌───────────────────────────┐           ┌───────────────────────────┐
       │     NkdSearchProvider     │           │    IsmdSearchProvider     │
       │                           │           │                           │
       │  Virtuoso SPARQL endpoint │           │  PostgreSQL + Fuseki      │
       │  bif:contains full-text   │           │  PG-first, Fuseki-enrich  │
       │  SELECT queries           │           │                           │
       └───────────┬───────────────┘           └─────┬──────────┬──────────┘
                   │                                 │          │
                   ▼                                 ▼          ▼
       ┌───────────────────────┐       ┌──────────────┐  ┌──────────────┐
       │   NKD Virtuoso        │       │  PostgreSQL  │  │ Apache Fuseki│
       │   SPARQL Endpoint     │       │  (metadata)  │  │ TDB2 (RDF)   │
       │                       │       │              │  │              │
       │ oha02.dia.gov.cz/     │       │ ontologies   │  │ Named graphs │
       │   vsparql | sparql    │       │ concepts     │  │ Labels, desc │
       └───────────────────────┘       └──────────────┘  └──────────────┘
```

---

## 3. Request Flow — Anonymous User

```
  Anonymous User                    Backend
  ─────────────                    ───────
       │
       │  GET /api/search?q=osoba
       │  (no Authorization header)
       │─────────────────────────────────►│
       │                                  │
       │                   SecurityFilterChain @Order(0)
       │                   ─ JWT not present → SecurityUser = null
       │                                  │
       │                     SearchController
       │                   ─ isAuthenticated = false
       │                   ─ if source=ISMD or ALL → 401
       │                                  │
       │                     SearchServiceImpl
       │                   ─ Source: NKD only (forced)
       │                   ─ ISMD: SKIPPED
       │                                  │
       │                          ┌───────┴───────┐
       │                          ▼               │
       │                   NkdSearchProvider      │
       │                   ─ SPARQL SELECT        │
       │                   ─ bif:contains         │
       │                   ─ timeout: 5s          │
       │                          │               │
       │                          ▼               │
       │                   ┌─────────────┐        │
       │                   │NKD Virtuoso │        │
       │                   └─────────────┘        │
       │                          │               │
       │                          ▼               │
       │                   SearchResponseDto
       │                   ─ results: [NKD items]
       │                   ─ sourceStatuses:
       │                     NKD=OK, ISMD=SKIPPED
       │◄─────────────────────────────────│
       │
       │  200 OK
       │  ApiResponseDto<SearchResponseDto>
```

---

## 4. Request Flow — Authenticated User

```
  Logged-in User                    Backend
  ──────────────                   ───────
       │
       │  GET /api/search?q=osoba&type=CONCEPT
       │  Authorization: Bearer <JWT>
       │──────────────────────────────────►│
       │                                   │
       │                    SecurityFilterChain @Order(0)
       │                    ─ JWT present → decode → SecurityUser(userId, roles)
       │                                   │
       │                      SearchController
       │                    ─ isAuthenticated = true
       │                    ─ userId extracted from SecurityUser
       │                                   │
       │                      SearchServiceImpl
       │                    ─ Source: ALL (default)
       │                    ─ Dispatch BOTH providers in parallel
       │                                   │
       │                 ┌─────────────────┴─────────────────┐
       │                 ▼                                   ▼
       │          NkdSearchProvider                  IsmdSearchProvider
       │          ─ SPARQL SELECT                    ─ PG text search
       │          ─ bif:contains                     ─ Fuseki label enrichment
       │          ─ concept filters                  ─ userId visibility filter
       │                 │                                   │
       │                 ▼                                   ▼
       │          ┌────────────┐               ┌──────────┐ ┌────────┐
       │          │NKD Virtuoso│               │PostgreSQL│ │ Fuseki │
       │          └────────────┘               └──────────┘ └────────┘
       │                 │                                   │
       │                 └─────────────┬─────────────────────┘
       │                               ▼
       │                    Merge results (NKD + ISMD)
       │                    Build SearchResponseDto
       │                    ─ results: [NKD items + ISMD items]
       │                    ─ sourceStatuses: NKD=OK, ISMD=OK
       │◄──────────────────────────────────│
       │
       │  200 OK
       │  ApiResponseDto<SearchResponseDto>
```

---

## 5. Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              ISMD Tool Backend                               │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                          CONTROLLER LAYER                               │ │
│  │                                                                         │ │
│  │  ┌─────────────────────┐                                                │ │
│  │  │  SearchController   │  GET /api/search                               │ │
│  │  │                     │  Params: q, type, source, limit, offset,       │ │
│  │  │                     │          lang, ontologyIri, relationTypes      │ │
│  │  │                     │  Auth: Optional (SecurityUser nullable)        │ │
│  │  └────────┬────────────┘                                                │ │
│  └───────────┼─────────────────────────────────────────────────────────────┘ │
│              │                                                               │
│  ┌───────────┼─────────────────────────────────────────────────────────────┐ │
│  │           │              SERVICE LAYER                                  │ │
│  │           ▼                                                             │ │
│  │  ┌─────────────────────┐                                                │ │
│  │  │  SearchServiceImpl  │  Orchestrator                                  │ │
│  │  │                     │  • Determines sources from auth state          │ │
│  │  │                     │  • Parallel dispatch via CompletableFuture     │ │
│  │  │                     │  • Per-source timeout + error isolation        │ │
│  │  │                     │  • Merges results into SearchResponseDto       │ │
│  │  └──────┬─────┬────────┘                                                │ │
│  │         │     │                                                         │ │
│  │    ┌────┘     └─────────────────┐                                       │ │
│  │    ▼                            ▼                                       │ │
│  │  ┌───────────────────┐  ┌───────────────────────┐                       │ │
│  │  │ NkdSearchProvider │  │ IsmdSearchProvider    │                       │ │
│  │  │                   │  │                       │                       │ │
│  │  │ • Builds SPARQL   │  │ • Queries PG repos    │                       │ │
│  │  │   with bif:cont.  │  │ • Enriches via Fuseki │                       │ │
│  │  │ • Executes SELECT │  │ • Applies visibility  │                       │ │
│  │  │ • Maps to DTOs    │  │ • Maps to DTOs        │                       │ │
│  │  └────────┬──────────┘  └───┬──────────┬────────┘                       │ │
│  └───────────┼─────────────────┼──────────┼────────────────────────────────┘ │
│              │                 │          │                                  │
│  ┌───────────┼─────────────────┼──────────┼────────────────────────────────┐ │
│  │           │          DATA ACCESS LAYER │                                │ │
│  │           │                 │          │                                │ │
│  │           │    ┌────────────┘          │                                │ │
│  │           │    │                       │                                │ │
│  │           │    ▼                       ▼                                │ │
│  │           │  ┌──────────────┐  ┌──────────────────┐                     │ │
│  │           │  │ OntologyMeta │  │ JenaTDB2         │                     │ │
│  │           │  │  Repository  │  │   Repository     │                     │ │
│  │           │  │ + searchBy() │  │ + fetchConcept   │                     │ │
│  │           │  ├──────────────┤  │   Labels()       │                     │ │
│  │           │  │ ConceptMeta  │  │ + fetchMetadata  │                     │ │
│  │           │  │  Repository  │  │   Properties()   │                     │ │
│  │           │  │ + searchBy() │  │   (existing)     │                     │ │
│  │           │  └──────┬───────┘  └────────┬─────────┘                     │ │
│  └───────────┼─────────┼───────────────────┼───────────────────────────────┘ │
│              │         │                   │                                 │
└──────────────┼─────────┼───────────────────┼─────────────────────────────────┘
               │         │                   │
               ▼         ▼                   ▼
      ┌──────────────┐ ┌──────────┐  ┌────────────────┐
      │NKD Virtuoso  │ │PostgreSQL│  │ Apache Fuseki  │
      │SPARQL Endpt. │ │          │  │ TDB2           │
      │              │ │ontologies│  │                │
      │Full-text idx │ │concepts  │  │ Named graphs   │
      │bif:contains  │ │(metadata)│  │ (RDF triples)  │
      └──────────────┘ └──────────┘  └────────────────┘
       EXTERNAL          INTERNAL      INTERNAL
       (read-only)       (read)        (read)
```

---

## 6. Security Filter Chain Architecture

```
  Incoming HTTP Request
         │
         ▼
  ┌──────────────────────────────────────────┐
  │  @Order(0) — Search Filter Chain  [NEW]  │
  │  securityMatcher: /api/search/**         │
  │                                          │
  │  • permitAll()                           │
  │  • oauth2ResourceServer (optional JWT)   │
  │  • Custom entryPoint (no 401 on anon)    │
  │  • Anonymous + source≠NKD → 401          │
  │                                          │
  │  Result: SecurityUser filled OR null     │
  ├──────────────────────────────────────────┤
  │  Does request match /api/search/**?      │
  │  YES → process here, STOP                │
  │  NO  → pass to next chain ↓              │
  └──────────────┬───────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │  @Order(1) — Public Filter Chain         │
  │  securityMatcher:                        │
  │    /actuator/health, /actuator/info      │
  │    /api/ontology/*/download              │
  │    /api/ontology/*/detail                │
  │    /api/ontology/list                    │
  │    /api/concept/list                     │
  │    /v3/api-docs/**, /swagger-ui/**       │
  │                                          │
  │  • permitAll(), no JWT processing        │
  ├──────────────────────────────────────────┤
  │  Match? YES → process, STOP              │
  │         NO  → pass to next chain ↓       │
  └──────────────┬───────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │  @Order(2) — Authenticated Filter Chain  │
  │  Catches all remaining requests          │
  │                                          │
  │  • Explicit endpoint matchers            │
  │  • oauth2Login + oauth2ResourceServer    │
  │  • anyRequest().denyAll()                │
  │                                          │
  │  Missing JWT → 401 Unauthorized          │
  └──────────────────────────────────────────┘
```

---

## 7. NKD Search — SPARQL Query Strategy

### Searchable Fields

| Resource Type | Searched Fields | Virtuoso Syntax |
|---------------|----------------|-----------------|
| **Ontology** | `skos:prefLabel`, `dcterms:title`, `dcterms:description` | `?field bif:contains '"term*"'` |
| **Concept** | `skos:prefLabel`, `skos:altLabel`, `dcterms:description`, `skos:definition` | `?field bif:contains '"term*"'` |

### Concept Filter Mapping

```
  User request:                        SPARQL clause appended:
  ────────────                         ──────────────────────

  ontologyIri=<iri1>,<iri2>     →     VALUES ?ontology { <iri1> <iri2> }

  relationTypes=SUBCLASS        →     FILTER EXISTS { ?resource rdfs:subClassOf ?x }
  relationTypes=SUPERCLASS      →     FILTER EXISTS { ?x rdfs:subClassOf ?resource }
  relationTypes=EXACT_MATCH     →     FILTER EXISTS { ?resource skos:exactMatch ?x }
  relationTypes=PROPERTY_OF     →     FILTER EXISTS { ?resource rdfs:domain ?x }
  relationTypes=RELATIONSHIP_OF →     FILTER EXISTS { ?resource rdfs:range ?x }

  Multiple relation types combined with OR:
  relationTypes=SUBCLASS,EXACT_MATCH
    →  FILTER(
         EXISTS { ?resource rdfs:subClassOf ?x }
         || EXISTS { ?resource skos:exactMatch ?x }
       )
```

### Query Builder Pattern

```
  NKDSPARQLSearchQuery
  ├── buildOntologySearchQuery(searchTerm, lang, limit, offset)
  │     → SPARQL SELECT with bif:contains on name/title/description
  │
  └── buildConceptSearchQuery(searchTerm, lang, limit, offset,
  │                           ontologyIris?, relationTypes?)
        → SPARQL SELECT with bif:contains on name/altName/desc/def
        → Dynamic FILTER clauses for ontology + relation filters

  All queries use ParameterizedSparqlString for injection safety.
```

---

## 8. ISMD Local Search — Dual-Store Strategy

### Parallel dual-store search: PG + Fuseki, merge in Java

Both stores are always queried in parallel. PostgreSQL searches by `concept_name` / `slug`;
Fuseki searches across `skos:altLabel`, `dcterms:description`, and `skos:definition`.
Results are merged and deduplicated in Java by IRI. This ensures full recall — matches on
alt names or descriptions are never silently dropped.

#### PostgreSQL text search — accent-insensitive

PG queries use the `unaccent()` extension for accent-insensitive matching on Czech text:

```sql
WHERE unaccent(concept_name) ILIKE unaccent('%query%')
  AND (is_published = true OR user_id = :uid)
```

Requires a one-time Liquibase migration to enable the extension:
```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
```

**Why `unaccent()` over `pg_trgm`:**
- `unaccent()` + `ILIKE` is simple, requires no index changes, and handles the primary
  pain point (diacritics: "cestina" matches "čeština", "osoba" matches "Osoba").
- `pg_trgm` adds fuzzy/similarity matching and GIN-indexed performance, but is overkill
  for ISMD's low-thousands dataset. Upgrade path if ISMD grows: add `pg_trgm` GIN index
  on `concept_name` and switch to `similarity()` or `%` operator.

```
  IsmdSearchProvider.search(query, userId, filters)
         │
         ├──────────────────────────────────────────────────┐
         │                                                  │
         ▼                                                  ▼
  PG TEXT SEARCH (parallel)                    FUSEKI SPARQL TEXT SEARCH (parallel)
  ─────────────────────────                    ────────────────────────────────────
  ┌──────────────────────────────┐    ┌──────────────────────────────────────────┐
  │  OntologyMetadataRepository  │    │  JenaTDB2Repository — SPARQL text search │
  │  .searchByText(q, userId)    │    │                                          │
  │                              │    │  Search across all named graphs:         │
  │  WHERE unaccent(slug)        │    │  • skos:prefLabel                        │
  │    ILIKE unaccent('%query%') │    │  • skos:altLabel                         │
  │  AND (isPublished=true       │    │  • dcterms:description                   │
  │       OR userId=:uid)        │    │  • skos:definition                       │
  └──────────────┬───────────────┘    │                                          │
                 │                    │  FILTER(CONTAINS(LCASE(?field), query))  │
  ┌──────────────────────────────┐    │  + visibility via known graph names      │
  │  ConceptMetadataRepository   │    └───────────────┬──────────────────────────┘
  │  .searchByText(q, userId,    │                    │
  │                ontologyIris) │                    ▼
  │                              │    ┌──────────────────────────────────────────┐
  │  WHERE unaccent(conceptName) │    │  Cross-reference with PG metadata        │
  │    ILIKE unaccent('%query%') │    │  • Look up slug, isPublished, userId     │
  │  AND (isPublished=true       │    │    by conceptIri / graphName             │
  │       OR userId=:uid)        │    │  • Apply visibility filter               │
  │  AND ontology.graphName      │    │    (published OR owned by userId)        │
  │    IN (:iris) [optional]     │    └──────────────┬───────────────────────────┘
  └──────────────┬───────────────┘                   │
                 │                                   │
                 └──────────────┬────────────────────┘
                                │
                                ▼
                 MERGE + DEDUP (by IRI)
                 ──────────────────────
  ┌──────────────────────────────────────────────────────┐
  │  LinkedHashMap<String, SearchResultDto> (keyed by    │
  │  IRI, preserves insertion order)                     │
  │                                                      │
  │  For each result from PG and Fuseki:                 │
  │    if IRI already in map:                            │
  │      overwrite non-null fields (last write wins)     │
  │      log duplicate at DEBUG level                    │
  │    else:                                             │
  │      add new entry                                   │
  │                                                      │
  │  PG provides: slug, conceptType, isPublished, userId │
  │  Fuseki provides: label, altName, description, def.  │
  │  Merge priority: last write wins for conflicts       │
  │  (in practice conflicts should not occur — PG name   │
  │   matches RDF prefLabel via create/edit workflow)    │
  └──────────────────────────────────────────────────────┘
                                │
                                ▼
  ┌──────────────────────────────────────────────────────┐
  │  Fuseki RDF enrichment (for ALL merged results)      │
  │                                                      │
  │  Ontology results → fetchMetadataProperties(graphs)  │  (existing)
  │    → skos:prefLabel, dcterms:description             │
  │                                                      │
  │  Concept results  → fetchConceptLabels(iris)         │  (new)
  │    → skos:prefLabel, skos:altLabel,                  │
  │      dcterms:description, skos:definition            │
  └──────────────────────────────────────────────────────┘
         │
         │  RELATION TYPE FILTER (if relationTypes param set)
         │  ─────────────────────────────────────────────
         ▼
  ┌────────────────────────────────────────────────────────────────┐
  │  JenaTDB2Repository — batched SELECT query                     │
  │                                                                │
  │  Single round-trip for all concept IRIs:                       │
  │                                                                │
  │  SELECT ?concept WHERE {                                       │
  │    VALUES ?concept { <iri1> <iri2> ... }                       │
  │    GRAPH ?g {                                                  │
  │      { ?concept rdfs:subClassOf ?x }      # SUBCLASS           │
  │      UNION { ?x rdfs:subClassOf ?concept } # SUPERCLASS        │
  │      UNION { ?concept skos:exactMatch ?x } # EXACT_MATCH       │
  │      UNION { ?concept rdfs:domain ?x }    # PROPERTY_OF        │
  │      UNION { ?concept rdfs:range ?x }     # RELATIONSHIP_OF    │
  │    }                                                           │
  │  }                                                             │
  │                                                                │
  │  Build UNION branches only for requested types.                │
  │  Multiple types → OR logic (keep if ANY matches).              │
  │  Remove concepts not in result set.                            │
  │  Capped at LIMIT (max 100 concepts to check).                  │
  └────────────────────────────────────────────────────────────────┘
```

**Graceful degradation:** If Fuseki is unreachable, return PG-only results with
`conceptName` as label and no description. The response `sourceStatuses`
will report `ISMD` as `DEGRADED` (not `OK`).

### Deduplication strategy (applies to both NKD and ISMD)

Both NKD SPARQL UNION queries and ISMD parallel PG+Fuseki searches can produce
multiple rows for the same resource (e.g., matched on both prefLabel and altLabel,
or found in both PG and Fuseki). Dedup happens in Java after result mapping:

```
  Raw results from SPARQL / PG+Fuseki
         │
         ▼
  ┌──────────────────────────────────────────────────────┐
  │  LinkedHashMap<String, SearchResultDto>              │
  │  (keyed by IRI, preserves insertion order)           │
  │                                                      │
  │  For each raw row:                                   │
  │    if IRI already in map:                            │
  │      overwrite non-null fields (last write wins)     │
  │      log at DEBUG level                              │
  │    else:                                             │
  │      add new entry                                   │
  │                                                      │
  │  Merge precedence: LAST WRITE WINS.                  │
  │  In practice, PG conceptName and RDF prefLabel       │
  │  should always match (enforced by create/edit flow). │
  │  If they diverge, the last-processed value is kept   │
  │  and the mismatch is logged for investigation.       │
  │                                                      │
  │  Result: one SearchResultDto per unique IRI          │
  └──────────────────────────────────────────────────────┘
```

---

## 9. Error Handling & Resilience

```
  SearchServiceImpl
         │
         ├── CompletableFuture: NkdSearchProvider.search()
         │     .orTimeout(10s)  ← matches existing nkd.sparql.timeout config
         │     .exceptionally() → SearchSourceStatus(TIMEOUT)
         │
         ├── CompletableFuture: IsmdSearchProvider.search()
         │     .orTimeout(10s)
         │     .exceptionally() → SearchSourceStatus(ERROR)
         │
         └── Merge whatever succeeded
               │
               ▼
         SearchResponseDto
         ├── results: [items from successful sources]
         └── sourceStatuses:
               ├── NKD:  OK | TIMEOUT | ERROR | UNAVAILABLE | SKIPPED
               └── ISMD: OK | DEGRADED | TIMEOUT | ERROR | SKIPPED

  Degradation scenarios:
  ┌─────────────────────┬──────────────┬──────────┬──────────────────────────┐
  │ Scenario            │ HTTP Status  │ Results  │ sourceStatuses           │
  ├─────────────────────┼──────────────┼──────────┼──────────────────────────┤
  │ Both OK             │ 200          │ NKD+ISMD │ NKD=OK, ISMD=OK          │
  │ NKD timeout (anon)  │ 200          │ empty    │ NKD=TIMEOUT              │
  │ NKD timeout (auth)  │ 200          │ ISMD     │ NKD=TIMEOUT, ISMD=OK     │
  │ Fuseki down (auth)  │ 200          │ NKD+PG   │ NKD=OK, ISMD=DEGRADED    │
  │ Both fail (auth)    │ 200          │ empty    │ NKD=ERROR, ISMD=ERROR    │
  │ Anon, NKD not conf. │ 200          │ empty    │ NKD=UNAVAILABLE          │
  │ Anon, source=ISMD   │ 401          │ —        │ —                        │
  └─────────────────────┴──────────────┴──────────┴──────────────────────────┘

  DEGRADED: ISMD returns PG-only results (Fuseki unreachable).
            Labels use conceptName from PG; no descriptions/definitions.
            Status message explains partial results.
```

---

## 10. Response Schema

```json
{
  "data": {
    "results": [
      {
        "iri": "https://slovnik.gov.cz/datovy/osoby/pojem/osoba",
        "slug": null,
        "label": "Osoba",
        "labelLang": "cs",
        "altName": "Fyzicka osoba",
        "description": "Fyzicka nebo pravnicka osoba...",
        "definition": "Entita, ktera...",
        "type": "CONCEPT",
        "source": "NKD",
        "conceptType": "TRIDA",
        "ontologyIri": "https://slovnik.gov.cz/datovy/osoby",
        "isPublished": null
      },
      {
        "iri": "https://example.org/ontology/1#osoba",
        "slug": "muj-slovnik-osoba",
        "label": "Osoba",
        "labelLang": "cs",
        "altName": null,
        "description": "Lokalni pojem...",
        "definition": null,
        "type": "CONCEPT",
        "source": "ISMD",
        "conceptType": "TRIDA",
        "ontologyIri": "https://example.org/ontology/1",
        "isPublished": false
      }
    ],
    "returnedCount": 2,
    "limit": 20,
    "offset": 0,
    "sourceStatuses": {
      "NKD": { "status": "OK", "returnedCount": 1, "totalCount": 42, "message": null },
      "ISMD": { "status": "OK", "returnedCount": 1, "totalCount": 3, "message": null }
    }
  },
  "message": "Vyhledavani probehlo uspesne.",
  "success": true
}
```

---

## 11. API Contract

```
  GET /api/search

  Query Parameters:
  ┌────────────────┬──────────┬─────────┬──────────────────────────────────┐
  │ Parameter      │ Required │ Default │ Description                      │
  ├────────────────┼──────────┼─────────┼──────────────────────────────────┤
  │ q              │ yes      │ —       │ Search term (min 2 chars)        │
  │ type           │ no       │ both    │ ONTOLOGY | CONCEPT               │
  │ source         │ no       │ *       │ NKD | ISMD | ALL                 │
  │                │          │         │ * Anon default: NKD              │
  │                │          │         │ * Auth default: ALL              │
  │                │          │         │ * Anon + ISMD/ALL → 401          │
  │ limit          │ no       │ 20      │ Max 100 (per source)             │
  │ offset         │ no       │ 0       │ Pagination offset (per source)   │
  │ lang           │ no       │ cs      │ Preferred label language.        │
  │                │          │         │ Fallback: return best available  │
  │                │          │         │ language if preferred not found. │
  │ ontologyIri    │ no       │ —       │ Comma-separated ontology IRIs    │
  │ relationTypes  │ no       │ —       │ SUBCLASS,SUPERCLASS,EXACT_MATCH, │
  │                │          │         │ PROPERTY_OF,RELATIONSHIP_OF      │
  └────────────────┴──────────┴─────────┴──────────────────────────────────┘
  Responses:
  ┌──────┬───────────────────────────────────────────────────┐
  │ 200  │ Search completed (may have partial results)       │
  │ 400  │ Invalid input (q too short, bad enum)             │
  │ 401  │ Anonymous user requested source=ISMD or source=ALL│
  │ 500  │ Unexpected server error                           │
  └──────┴───────────────────────────────────────────────────┘
```

---

## 12. Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Endpoint count | Single `/api/search` | Frontend doesn't need to know about NKD vs ISMD split |
| NKD text search | Virtuoso `bif:contains` | Full-text indexed, prefix matching with `*` wildcard |
| ISMD search strategy | Parallel PG + Fuseki, merge in Java | Always query both stores to ensure full recall; dedup by IRI |
| ISMD text matching | `unaccent()` + `ILIKE` on PG | Handles Czech diacritics (č→c, ř→r) and case. `pg_trgm` deferred — overkill for low thousands |
| ISMD visibility | Published + own unpublished | Consistent with existing list endpoints |
| Security approach | Dedicated @Order(0) chain, `/api/search/**` | Wildcard path matcher prevents future sub-paths falling to auth chain |
| Anonymous source restriction | `source=ISMD` or `ALL` → 401 | ISMD data requires authentication; NKD is public |
| Parallel execution | CompletableFuture per source | NKD and ISMD don't depend on each other |
| Error isolation | Per-source status with DEGRADED | `DEGRADED` when Fuseki down (PG-only results); distinct from `OK` |
| Relation type filter (ISMD) | Batched SELECT with VALUES + UNION | Single round-trip; no N+1 ASK queries |
| Relation type filter (NKD) | `FILTER EXISTS` in SPARQL | Checks ANY relationship of that type exists |
| Dedup merge precedence | Last write wins, log conflicts | PG name = RDF prefLabel by design (create/edit flow); conflicts are bugs |
| conceptType source | PostgreSQL `concept_type` column | Enum stored in PG; RDF has `rdf:type`/`skos` classes but PG is authoritative |
| Language fallback | Return best available if preferred not found | Prevents empty labels when concept only has e.g. `en` label |
| Diagram filter | Deferred | Feature doesn't exist yet |
| Cross-source pagination | Per-source LIMIT/OFFSET | True cross-source pagination too complex for v1. `returnedCount` + per-source `totalCount` for frontend |
| Rate limiting | Required on `/api/search/**` | Public `permitAll()` endpoint; must protect against abuse |

---

## 13. Performance Design (targeting ~100k NKD records)

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                     Performance-Critical Decisions                      │
  ├────────────────────────┬────────────────────────────────────────────────┤
  │ Concern                │ Approach                                       │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD text search        │ Virtuoso bif:contains uses full-text index.    │
  │ at 100k records        │ Sub-second even at 100k — this is what the     │
  │                        │ index is built for. FILTER(CONTAINS()) would   │
  │                        │ be O(n) full scan — NOT acceptable at scale.   │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD relation type      │ FILTER EXISTS with rdfs:subClassOf etc.        │
  │ filters at scale       │ Virtuoso handles EXISTS efficiently via        │
  │                        │ indexed triple patterns. Combine multiple      │
  │                        │ types with UNION (not nested FILTERs) for      │
  │                        │ optimizer-friendly execution.                  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD query timeout      │ 10s hard timeout (matches nkd.sparql.timeout   │
  │                        │ config). At ~100k with bif:contains this       │
  │                        │ should be well under 2s. Timeout is a safety   │
  │                        │ net, not expected to trigger.                  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ SPARQL result size     │ Server-side LIMIT/OFFSET. Never fetch more     │
  │                        │ than 100 results per source per request.       │
  │                        │ Prevents memory issues on large result sets.   │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD PG text search    │ unaccent() + ILIKE for accent-insensitive      │
  │                        │ matching. Fine for low thousands. If ISMD      │
  │                        │ grows toward 100k, add pg_trgm GIN index       │
  │                        │ for indexed trigram matching.                  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD Fuseki enrichment │ Batch CONSTRUCT with VALUES clause for IRIs.   │
  │                        │ One round-trip regardless of result count.     │
  │                        │ Capped by LIMIT so max ~100 IRIs per batch.    │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD Fuseki text search│ FILTER(CONTAINS()) on Fuseki is fine —         │
  │                        │ ISMD is low thousands, not 100k. Always runs   │ 
  │                        │ in parallel with PG to ensure full recall.     │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD relation filter   │ Batched SELECT with VALUES clause for          │
  │                        │ concept IRIs. One round-trip. Capped at        │
  │                        │ LIMIT (max 100 concepts to check).             │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ Parallel execution     │ NKD and ISMD run concurrently via              │
  │                        │ CompletableFuture. Total latency =             │
  │                        │ max(NKD, ISMD), not sum.                       │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ Min query length       │ 2 chars minimum. Prevents overly broad         │
  │                        │ full-text queries that would scan too many     │
  │                        │ index entries at 100k scale.                   │
  └────────────────────────┴────────────────────────────────────────────────┘
```

### Key principle

The full-text index (`bif:contains`) is the critical scaling lever for NKD. All NKD text
matching MUST go through the index — never fall back to `FILTER(CONTAINS())` on the NKD
side. The `*` wildcard suffix in `bif:contains '"term*"'` enables prefix matching while
staying indexed.

For ISMD, the dataset stays small enough that PG `unaccent()` + `ILIKE` and Fuseki
`FILTER(CONTAINS())` are acceptable. If ISMD ever approaches NKD-scale, the upgrade path is:
- PG: Add `pg_trgm` GIN index on `concept_name` for indexed trigram matching + fuzzy search
- Fuseki: Enable Jena text index and use `text:query`

### Future scaling note

If NKD grows beyond 100k toward millions, consider:
- Caching frequent NKD search results (short TTL, e.g., 5 min)
- COUNT queries to provide accurate total counts (currently estimated from LIMIT results)
- Faceted search with Virtuoso's aggregate capabilities

---

## 14. No Open Questions

All design decisions resolved. See section 15 for the full list.

---

## 15. Resolved Decisions

| # | Question | Decision |
|---|----------|----------|
| 1 | ISMD alt name / definition search | Always query both PG and Fuseki in parallel, merge + dedup in Java by IRI. Ensures full recall — matches on altLabel, description, definition are never dropped. |
| 2 | Duplicate results (same IRI matched on multiple fields) | Java-side dedup via `LinkedHashMap<IRI, SearchResultDto>`. Last write wins for field conflicts (should not occur in practice — PG name matches RDF prefLabel via create/edit flow). Conflicts logged at DEBUG. |
| 3 | ISMD visibility | Published + own unpublished (consistent with existing list endpoints) |
| 4 | NKD production endpoint | Will be configured before deploy |
| 5 | NKD full-text search | Virtuoso `bif:contains` with `*` wildcard prefix matching |
| 6 | Diagram filter | Deferred — feature doesn't exist yet |
| 7 | Relation type filter semantics | Checks concept has ANY relationship of the specified type(s), not a specific target |
| 8 | ISMD relation type filter | Batched SELECT with VALUES + UNION in Fuseki SPARQL. Single round-trip, no N+1 ASK queries. |
| 9 | Pagination | Infinite scroll. Independent LIMIT/OFFSET per source. `returnedCount` + per-source `totalCount` for frontend. |
| 10 | Anonymous source restriction | Anonymous users can only use `source=NKD` (default). Requesting `source=ISMD` or `source=ALL` returns 401. |
| 11 | Czech diacritics / case matching | PG uses `unaccent()` + `ILIKE`. Requires `CREATE EXTENSION IF NOT EXISTS unaccent` migration. `pg_trgm` deferred as upgrade path. |
| 12 | conceptType source | PostgreSQL `concept_type` column (enum). RDF has `rdf:type`/`skos` classes and OFN classes but PG is authoritative for search results. |
| 13 | Language fallback | Return best available language if preferred `lang` not found. Prevents empty labels. |
| 14 | Fuseki down status | Report `DEGRADED` (not `OK`) when Fuseki unreachable. PG-only results returned with `conceptName` as label. |
| 15 | NKD timeout alignment | 10s (matches existing `nkd.sparql.timeout` config), not 5s. |
| 16 | Security matcher path | `/api/search/**` wildcard to cover future sub-paths (e.g., `/api/search/suggestions`). |
| 17 | Rate limiting | Required on `/api/search/**`. Public endpoint must be protected against abuse. Implementation TBD (Spring RateLimiter, reverse proxy, or Bucket4j). |