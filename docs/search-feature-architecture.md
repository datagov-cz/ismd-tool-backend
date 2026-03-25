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
                              │          SecurityFilterChain            │
                              │            @Order(0)                    │
                              │                                        │
                              │  • Matches /api/search only            │
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
       │   NKD Virtuoso        │       │  PostgreSQL   │  │ Apache Fuseki│
       │   SPARQL Endpoint     │       │  (metadata)   │  │ TDB2 (RDF)  │
       │                       │       │               │  │              │
       │ oha02.dia.gov.cz/     │       │ ontologies    │  │ Named graphs │
       │   vsparql | sparql    │       │ concepts      │  │ Labels, desc │
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
│  │  │                     │          lang, ontologyIri, relationTypes       │ │
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
│  │    ┌────┘     └────┐                                                    │ │
│  │    ▼               ▼                                                    │ │
│  │  ┌───────────────────┐  ┌──────────────────────┐                        │ │
│  │  │ NkdSearchProvider │  │ IsmdSearchProvider    │                        │ │
│  │  │                   │  │                       │                        │ │
│  │  │ • Builds SPARQL   │  │ • Queries PG repos    │                        │ │
│  │  │   with bif:cont.  │  │ • Enriches via Fuseki │                        │ │
│  │  │ • Executes SELECT │  │ • Applies visibility  │                        │ │
│  │  │ • Maps to DTOs    │  │ • Maps to DTOs        │                        │ │
│  │  └────────┬──────────┘  └───┬──────────┬────────┘                        │ │
│  └───────────┼─────────────────┼──────────┼────────────────────────────────┘ │
│              │                 │          │                                   │
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
│  └───────────┼─────────┼──────────────────┼────────────────────────────────┘ │
│              │         │                  │                                   │
└──────────────┼─────────┼──────────────────┼───────────────────────────────────┘
               │         │                  │
               ▼         ▼                  ▼
      ┌──────────────┐ ┌──────────┐  ┌───────────────┐
      │NKD Virtuoso  │ │PostgreSQL│  │ Apache Fuseki  │
      │SPARQL Endpt. │ │          │  │ TDB2           │
      │              │ │ontologies│  │                │
      │Full-text idx │ │concepts  │  │ Named graphs   │
      │bif:contains  │ │(metadata)│  │ (RDF triples)  │
      └──────────────┘ └──────────┘  └───────────────┘
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
  │  securityMatcher: /api/search            │
  │                                          │
  │  • permitAll()                           │
  │  • oauth2ResourceServer (optional JWT)   │
  │  • Custom entryPoint (no 401 on anon)    │
  │                                          │
  │  Result: SecurityUser filled OR null     │
  ├──────────────────────────────────────────┤
  │  Does request match /api/search?         │
  │  YES → process here, STOP               │
  │  NO  → pass to next chain ↓             │
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
  │  Match? YES → process, STOP             │
  │         NO  → pass to next chain ↓      │
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

  ontologyIri=<iri1>,<iri2>     →     FILTER(?ontology IN (<iri1>, <iri2>))

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

### Two-phase search: PG-first, Fuseki-fallback

Phase 1 (PostgreSQL) handles the common case — name matches. Phase 2 (Fuseki SPARQL)
only runs when PG finds nothing, catching matches on alt names, descriptions, and definitions.
This avoids double-query overhead when name search is sufficient.

```
  IsmdSearchProvider.search(query, userId, filters)
         │
         │  PHASE 1: PostgreSQL text search (name match)
         │  ────────────────────────────────────────────
         ▼
  ┌──────────────────────────────┐
  │  OntologyMetadataRepository  │   WHERE slug LIKE '%query%'
  │  .searchByText(q, userId)    │   AND (isPublished=true OR userId=:uid)
  └──────────────┬───────────────┘
                 │
  ┌──────────────────────────────┐
  │  ConceptMetadataRepository   │   WHERE conceptName LIKE '%query%'
  │  .searchByText(q, userId,   │   AND (isPublished=true OR userId=:uid)
  │                ontologyIris) │   AND ontology.graphName IN (:iris) [optional]
  └──────────────┬───────────────┘
                 │
                 ▼
         ┌──────────────┐
         │ PG results?  │
         └──────┬───────┘
                │
         ┌──────┴──────┐
         │             │
     HAS RESULTS    NO RESULTS
         │             │
         │             │  PHASE 2: Fuseki SPARQL fallback
         │             │  ──────────────────────────────
         │             ▼
         │    ┌──────────────────────────────────────────┐
         │    │  JenaTDB2Repository — SPARQL text search │
         │    │                                          │
         │    │  Search across all named graphs:         │
         │    │  • skos:prefLabel (redundant but needed  │
         │    │    to also return name in results)       │
         │    │  • skos:altLabel                         │
         │    │  • dcterms:description                   │
         │    │  • skos:definition                       │
         │    │                                          │
         │    │  FILTER(CONTAINS(LCASE(?field), query))  │
         │    │  + visibility via known graph names      │
         │    └──────────────┬───────────────────────────┘
         │                   │
         │                   ▼
         │    ┌──────────────────────────────────────────┐
         │    │  Cross-reference with PG metadata        │
         │    │  • Look up slug, isPublished, userId     │
         │    │    by conceptIri / graphName              │
         │    │  • Apply visibility filter               │
         │    │    (published OR owned by userId)         │
         │    └──────────────┬───────────────────────────┘
         │                   │
         ▼                   ▼
  ┌──────────────────────────────────────────────────────┐
  │  Fuseki RDF enrichment (for ALL results)             │
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
  ┌──────────────────────────────────────────────────────┐
  │  JenaTDB2Repository — ASK queries per concept        │
  │                                                      │
  │  For each concept IRI, check in its named graph:     │
  │                                                      │
  │  SUBCLASS:        ASK { GRAPH ?g {                   │
  │                     <iri> rdfs:subClassOf ?x } }     │
  │  SUPERCLASS:      ASK { GRAPH ?g {                   │
  │                     ?x rdfs:subClassOf <iri> } }     │
  │  EXACT_MATCH:     ASK { GRAPH ?g {                   │
  │                     <iri> skos:exactMatch ?x } }     │
  │  PROPERTY_OF:     ASK { GRAPH ?g {                   │
  │                     <iri> rdfs:domain ?x } }         │
  │  RELATIONSHIP_OF: ASK { GRAPH ?g {                   │
  │                     <iri> rdfs:range ?x } }          │
  │                                                      │
  │  Multiple types → OR logic (keep if ANY matches)     │
  │  Remove concepts that don't match any filter         │
  │                                                      │
  │  Optimization: batch into single SELECT query:       │
  │  SELECT ?concept WHERE {                             │
  │    VALUES ?concept { <iri1> <iri2> ... }             │
  │    GRAPH ?g {                                        │
  │      { ?concept rdfs:subClassOf ?x }                 │
  │      UNION { ?concept skos:exactMatch ?x } ...      │
  │    }                                                 │
  │  }                                                   │
  └──────────────────────────────────────────────────────┘
         │
         │  MERGE + DEDUP
         │  ─────────────
         ▼
  ┌──────────────────────────────────────────────────────┐
  │  Deduplicate by IRI (Map<String, SearchResultDto>)   │
  │  Merge PG metadata + Fuseki labels                   │
  │  → List<SearchResultDto>                             │
  │    source = ISMD                                     │
  │    slug, isPublished from PG                         │
  │    label, altName, description, definition from RDF  │
  └──────────────────────────────────────────────────────┘
```

**Graceful degradation:** If Fuseki is unreachable, return PG results (Phase 1 only) with
`conceptName` as label and no description. Phase 2 is skipped. The response `sourceStatuses`
will include a warning.

### Deduplication strategy (applies to both NKD and ISMD)

Both NKD SPARQL UNION queries and ISMD Fuseki fallback can produce multiple rows
for the same resource (e.g., matched on both prefLabel and altLabel). Dedup happens
in Java after result mapping:

```
  Raw results from SPARQL / PG+Fuseki
         │
         ▼
  ┌──────────────────────────────────────────────────────┐
  │  Map<String, SearchResultDto> seen  (keyed by IRI)   │
  │                                                      │
  │  For each raw row:                                   │
  │    if IRI already in map:                            │
  │      merge non-null fields into existing entry       │
  │      (e.g., fill altName, definition if null)        │
  │    else:                                             │
  │      add new entry                                   │
  │                                                      │
  │  Result: one SearchResultDto per unique IRI          │
  │  Preserves insertion order (LinkedHashMap)            │
  └──────────────────────────────────────────────────────┘
```

---

## 9. Error Handling & Resilience

```
  SearchServiceImpl
         │
         ├── CompletableFuture: NkdSearchProvider.search()
         │     .orTimeout(5s)
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
               └── ISMD: OK | TIMEOUT | ERROR | SKIPPED

  Degradation scenarios:
  ┌─────────────────────┬──────────────┬──────────┬───────────────────────┐
  │ Scenario            │ HTTP Status  │ Results  │ sourceStatuses        │
  ├─────────────────────┼──────────────┼──────────┼───────────────────────┤
  │ Both OK             │ 200          │ NKD+ISMD │ NKD=OK, ISMD=OK      │
  │ NKD timeout (anon)  │ 200          │ empty    │ NKD=TIMEOUT           │
  │ NKD timeout (auth)  │ 200          │ ISMD     │ NKD=TIMEOUT, ISMD=OK │
  │ Fuseki down (auth)  │ 200          │ NKD+PG   │ NKD=OK, ISMD=OK*     │
  │ Both fail (auth)    │ 200          │ empty    │ NKD=ERROR, ISMD=ERROR │
  │ Anon, NKD not conf. │ 200          │ empty    │ NKD=UNAVAILABLE      │
  └─────────────────────┴──────────────┴──────────┴───────────────────────┘
  * ISMD returns PG-only results with warning in status message
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
    "totalResults": 2,
    "limit": 20,
    "offset": 0,
    "sourceStatuses": {
      "NKD": { "status": "OK", "resultCount": 1, "message": null },
      "ISMD": { "status": "OK", "resultCount": 1, "message": null }
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
  │ Parameter       │ Required │ Default │ Description                      │
  ├────────────────┼──────────┼─────────┼──────────────────────────────────┤
  │ q              │ yes      │ —       │ Search term (min 2 chars)        │
  │ type           │ no       │ both    │ ONTOLOGY | CONCEPT               │
  │ source         │ no       │ *       │ NKD | ISMD | ALL                 │
  │ limit          │ no       │ 20      │ Max 100                          │
  │ offset         │ no       │ 0       │ Pagination offset                │
  │ lang           │ no       │ cs      │ Preferred label language         │
  │ ontologyIri    │ no       │ —       │ Comma-separated ontology IRIs    │
  │ relationTypes  │ no       │ —       │ SUBCLASS,SUPERCLASS,EXACT_MATCH, │
  │                │          │         │ PROPERTY_OF,RELATIONSHIP_OF      │
  └────────────────┴──────────┴─────────┴──────────────────────────────────┘
  * source default: NKD for anonymous, ALL for authenticated

  Responses:
  ┌──────┬────────────────────────────────────────┐
  │ 200  │ Search completed (may have partial)    │
  │ 400  │ Invalid input (q too short, bad enum)  │
  │ 500  │ Unexpected server error                │
  └──────┴────────────────────────────────────────┘
```

---

## 12. Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Endpoint count | Single `/api/search` | Frontend doesn't need to know about NKD vs ISMD split |
| NKD text search | Virtuoso `bif:contains` | Full-text indexed, prefix matching with `*` wildcard |
| ISMD search strategy | PG-first, Fuseki-enrich | PG is fast for text matching; Fuseki adds RDF labels |
| ISMD visibility | Published + own unpublished | Consistent with existing list endpoints |
| Security approach | Dedicated @Order(0) chain | Cleanly handles optional JWT without complicating existing chains |
| Parallel execution | CompletableFuture per source | NKD and ISMD don't depend on each other |
| Error isolation | Per-source status reporting | One source failing doesn't block the other |
| Relation type filter | `FILTER EXISTS` in SPARQL | Checks ANY relationship of that type exists |
| Diagram filter | Deferred | Feature doesn't exist yet |
| Cross-source pagination | Per-source LIMIT/OFFSET | True cross-source pagination too complex for v1 |

---

## 13. Performance Design (targeting ~100k NKD records)

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                     Performance-Critical Decisions                      │
  ├────────────────────────┬────────────────────────────────────────────────┤
  │ Concern                │ Approach                                      │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD text search        │ Virtuoso bif:contains uses full-text index.   │
  │ at 100k records        │ Sub-second even at 100k — this is what the    │
  │                        │ index is built for. FILTER(CONTAINS()) would  │
  │                        │ be O(n) full scan — NOT acceptable at scale.  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD relation type      │ FILTER EXISTS with rdfs:subClassOf etc.       │
  │ filters at scale       │ Virtuoso handles EXISTS efficiently via       │
  │                        │ indexed triple patterns. Combine multiple     │
  │                        │ types with UNION (not nested FILTERs) for     │
  │                        │ optimizer-friendly execution.                 │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ NKD query timeout      │ 5s hard timeout. At ~100k with bif:contains   │
  │                        │ this should be well under 2s. Timeout is a    │
  │                        │ safety net, not expected to trigger.          │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ SPARQL result size     │ Server-side LIMIT/OFFSET. Never fetch more    │
  │                        │ than 100 results per source per request.      │
  │                        │ Prevents memory issues on large result sets.  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD PG text search    │ LIKE '%term%' is fine for low thousands.      │
  │                        │ If ISMD grows toward 100k, add pg_trgm       │
  │                        │ GIN index for indexed trigram matching.       │
  │                        │ No action needed now — note for future.       │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD Fuseki enrichment │ Batch CONSTRUCT with VALUES clause for IRIs.  │
  │                        │ One round-trip regardless of result count.    │
  │                        │ Capped by LIMIT so max ~100 IRIs per batch.  │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD Fuseki fallback   │ FILTER(CONTAINS()) on Fuseki is fine —        │
  │ (Phase 2)              │ ISMD is low thousands, not 100k. Only runs    │
  │                        │ when PG Phase 1 returns no results.           │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ ISMD relation filter   │ Batched SELECT with VALUES clause for         │
  │                        │ concept IRIs. One round-trip. Capped at       │
  │                        │ LIMIT (max 100 concepts to check).           │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ Parallel execution     │ NKD and ISMD run concurrently via             │
  │                        │ CompletableFuture. Total latency =            │
  │                        │ max(NKD, ISMD), not sum.                     │
  ├────────────────────────┼────────────────────────────────────────────────┤
  │ Min query length       │ 2 chars minimum. Prevents overly broad        │
  │                        │ full-text queries that would scan too many    │
  │                        │ index entries at 100k scale.                  │
  └────────────────────────┴────────────────────────────────────────────────┘
```

### Key principle

The full-text index (`bif:contains`) is the critical scaling lever for NKD. All NKD text
matching MUST go through the index — never fall back to `FILTER(CONTAINS())` on the NKD
side. The `*` wildcard suffix in `bif:contains '"term*"'` enables prefix matching while
staying indexed.

For ISMD, the dataset stays small enough that PG LIKE and Fuseki FILTER(CONTAINS()) are
acceptable. If ISMD ever approaches NKD-scale, the upgrade path is:
- PG: Add `pg_trgm` GIN index on `conceptName`
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
| 1 | ISMD alt name / definition search | PG-first for name match; if no results, fall back to Fuseki SPARQL text search across altLabel, description, definition |
| 2 | Duplicate results (same IRI matched on multiple fields) | Java-side dedup via `Map<IRI, SearchResultDto>` with field merging. Applies to both NKD and ISMD providers. |
| 3 | ISMD visibility | Published + own unpublished (consistent with existing list endpoints) |
| 4 | NKD production endpoint | Will be configured before deploy |
| 5 | NKD full-text search | Virtuoso `bif:contains` with `*` wildcard prefix matching |
| 6 | Diagram filter | Deferred — feature doesn't exist yet |
| 7 | Relation type filter semantics | Checks concept has ANY relationship of the specified type(s), not a specific target |
| 8 | ISMD relation type filter | Apply via Fuseki SPARQL (not NKD-only). Post-PG filter step using ASK queries against concept graphs. |
| 9 | Pagination | Infinite scroll. Independent LIMIT/OFFSET per source. Frontend sorts NKD vs ISMD results. Not an issue — dataset is bounded, not millions of records. |