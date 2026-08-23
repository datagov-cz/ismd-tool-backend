# e-Sbírka Law Search & Version Selection — Architecture

## 1. Context & Motivation

The e-Sbírka integration lets an editor attach a Czech legislative source (an ELI-identified
fragment of an act) to an ontology or concept. The user picks a **law**, then a **znění**
(version), then a **fragment**.

Three problems shaped the current design:

1. **Version choice was hard-wired.** `/law/content` always rendered the latest znění. The FE
   received the full version list but had no way to act on a selection.
2. **Search ranked by the wrong thing.** `CONTAINS` on the citation string made `q=49` match
   the *year* of `1/2049` as readily as the *number* of `49/1997`, and a lexical
   `ORDER BY ?citace` then buried the intended law.
3. **A bare number is inherently ambiguous.** Czech acts renumber from 1 every year, so `49`
   identifies ~120 unrelated acts. A flat list ordered newest-first filled its entire window
   with recent years and pushed the wanted law off the page.

### The modelling fact that drives everything

**`49/2026 Sb.` is NOT a version of `49/1997 Sb.`** They are separate `právní-akt` instances
that merely share a number. Verified live: `<…/eli/cz/sb/2026/49> má-znění ?z` returns exactly
one row. There is **no parent link** between same-numbered acts, and none is needed.

This matters because the flat result list *looked* like a broken version hierarchy. It was
not — it was an ambiguity problem wearing a modelling problem's clothes.

---

## 2. Data Model (e-Sbírka SPARQL)

Namespace: `https://slovník.gov.cz/datový/sbírka/pojem/` (`sb:` below).
Endpoint: `https://opendata.eselpoint.gov.cz/sparql` (Virtuoso).

### Three levels, all ELI-IRI addressed

```
právní-akt  (law)          …/esel-esb/eli/cz/{sb|ul0|ul1|sm}/{rok}/{číslo}
   │  sb:má-znění (multi) ─────────────┐
   │  sb:má-poslední-znění (the current one)
   │  sb:má-vyhlášené-znění (original 0000-00-00 shell)
   ▼                                   │
znění-právního-aktu  (version)  …/{rok}/{číslo}/{účinnost-od}
   │  sb:má-fragment-znění (multi, FLAT — every fragment, not just top level)
   ▼
označení-fragmentu-…  (fragment)  …/{version}/dokument/norma/cast_5/…/pism_g
```

### Predicates by level

| Level | Predicate | Notes |
|---|---|---|
| Law | `citace-právního-aktu` | `"187/2006 Sb."` — **the only display string; no title predicate exists** |
| Law | `číslo-předpisu` | `xsd:string`, typed — see §8 |
| Law | `rok-předpisu` | `xsd:gYear` |
| Law | `patří-do-sbírky` | `sb` / `ul0` / `ul1` / `sm` |
| Law | `má-znění`, `má-poslední-znění`, `má-vyhlášené-znění` | version edges |
| Version | `účinnost-znění-od` / `-do` | `xsd:date` — **the canonical sort key**, not the IRI string |
| Version | `má-typ-znění-právního-aktu` | codelist item, e.g. `KONSOL` |
| Version | `má-fragment-znění` | flat list of every fragment |
| Fragment | `má-předka` | canonical parent edge (no IRI parsing needed) |
| Fragment | `pořadí-fragmentu-…` | hex string, lex-sortable → document order |
| Fragment | `hierarchie-fragmentu-…` | `/2/5/5/4/` ordinal path; first segment = container ordinal |
| Fragment | `citace-označení-fragmentu-…` | pre-formatted `"§ 122 odst. 4 písm. g)"` |
| Fragment | `obsahuje-fragment` / `text-fragmentu` | HTML body |

### Scale (measured live 2026-08-23)

| Metric | Value |
|---|---|
| `právní-akt` instances | 92 347 (≈45 935 with a citation) |
| Versions dataset-wide | 117 601 |
| Acts numbered `49` | **120** |
| `q=49` matching on citation `CONTAINS` | **2 217** (only 120 are *numbered* 49) |
| `q=1` prefix-matching on číslo | **12 037** |
| Fragments in a typical version | ~2 200 (187/2006) |

---

## 3. API Surface

| Endpoint | Purpose | Cache |
|---|---|---|
| `GET /api/eli/law/search` | Flat, ranked law list | `esbirkaLawSearch`, 60 min |
| `GET /api/eli/law/search/grouped` | **NEW** — matches grouped by číslo + ambiguity signal | `esbirkaLawSearch`, 60 min |
| `GET /api/eli/law/versions` | All znění of a law, newest first, `latest` flagged | `esbirkaLawVersions`, 60 min |
| `GET /api/eli/law/fragments` | Fragment tree of a znění (no HTML bodies) | — |
| `GET /api/eli/law/content` | Whole znění: header + version list + tree + HTML | `esbirkaLawContent`, 24 h |
| `GET /api/eli/resolve` | ELI URL → display object | `esbirkaFragmentResolution`, 24 h |

**Their accepted input shapes are disjoint — see §4 before wiring a search box.**

All are on the **public** security chain (`SecurityConfig.publicSecurityFilterChain`).
`SecurityConfig` uses an explicit allowlist with `anyRequest().denyAll()`, and the matchers are
**exact paths** — `/api/eli/law/search` does not cover `/api/eli/law/search/grouped`, so each
new endpoint must be listed individually or it 403s before `@PreAuthorize` runs.


---

## 4. Input Contracts — what each endpoint accepts (READ THIS FIRST, FE)

**The three law-facing endpoints accept _disjoint_ input shapes.** This is deliberate but not
enforced anywhere in code, and getting it wrong fails *silently* on one of them. All behaviour
below verified live 2026-08-23.

| Input the user typed | `/law/search` (flat) | `/law/search/grouped` | `/law/content` |
|---|---|---|---|
| `49` (bare number) | ✅ ranked rows | ✅ **the intended endpoint** | ❌ HTTP 400 |
| `49/1997` (číslo/rok) | ✅ exact act first | ⚠️ **0 groups, HTTP 200** | ✅ **the intended endpoint** |
| `49/1997 Sb.` | ✅ exact act first | ⚠️ **0 groups, HTTP 200** | ✅ (suffix stripped) |
| `` (empty) | ✅ newest acts | ✅ lowest čísla | ❌ HTTP 400 |
| `abc` (nonsense) | ✅ 0 rows | ✅ 0 groups | ❌ HTTP 400 |

### ⚠️ The trap: grouped search cannot answer a fully-qualified query

`/law/search/grouped?q=49/1997` returns **HTTP 200 with `groups: []`, `ambiguous: false`,
`totalMatches: 0`** — indistinguishable from a genuine no-match (`q=abc`).

**Why.** Step 1 matches with `STRSTARTS(STR(?cislo), needle)`, and `?cislo` holds `"49"` — it
never contains a slash. So `"49/1997"` prefix-matches no číslo at all. For the same reason
`exactNumberMatch` is **always false** for any needle containing `/`, even when the query pins
exactly one act.

**Consequence for the FE:** if a search box routes every keystroke to `/search/grouped`, the
moment the user types the `/` in `49/1997` the results **vanish**, and the response says
"nothing found" rather than "wrong endpoint".

### Recommended FE routing

```
needle contains "/"  →  /law/search  (flat, exact act ranks first)
                        …and /law/content?law=<needle> once the user commits
needle is digits only → /law/search/grouped   (ambiguity resolution: pick a year)
```

Or simply: **route to `/law/search/grouped` only while the needle is digits-only.**

### If this split is wrong for the FE

It is cheap to change — say which you want:

- **(a) Grouped accepts `číslo/rok` too.** Split the needle on `/`, prefix-match the číslo and
  filter the year in step 1. Grouped then answers every input shape and `exactNumberMatch`
  becomes meaningful for qualified queries. ~1 h. Recommended if the FE wants one search box
  wired to one endpoint.
- **(b) Grouped rejects `/` with HTTP 400** and a Czech message pointing at `/law/content`.
  Makes the current split explicit instead of silent. ~15 min.
- **(c) Leave as-is** and have the FE route as above.

Today the code does **(c)** — no validation, no signal. Nothing else in the system depends on
that choice.

### Other input notes

- **`/law/content?law=` is strict.** It requires exactly `číslo/rok`; a bare `49` is 400
  (`"Referenci zadejte ve tvaru číslo/rok…"`), by design — partial input is the FE's cue to use
  a search endpoint. A trailing `" Sb."` is stripped, and surrounding whitespace trimmed.
- **A missing act is also 400**, not 404 (`"Právní akt č. 999999/1997 nebyl nalezen."`).
- **`limit` is 1..50 on both search endpoints** (default 20); out of range is 400. On
  `/search/grouped` it caps **groups**, not rows.
- **Search never 404s.** No match is HTTP 200 with an empty list/`groups`.
- **`/law/versions` and `/law/fragments` take full IRIs**, not `číslo/rok`, and 400 on anything
  failing `isEsbirkaEliIri` (host + `/eli/` prefix).
- **`/law/content?versionIri=` must belong to the law named by `law=`.** Passing a well-formed
  e-Sbírka version IRI from a *different* act is HTTP 400 (`"Znění … nepatří k právnímu aktu
  č. …"`), not a silent render of the wrong text. Pass a value straight from the same response's
  `versions[]` and this cannot happen. Omitting the parameter (or sending blank) means "latest".
  Note the same message is returned for a *non-existent* version of the right law (e.g.
  `…/1997/49/2020-01-01`, a date with no znění) — membership is the only check, so "wrong act"
  and "no such znění" are not distinguished.
- **Legacy-host IRIs are handled inconsistently across endpoints.** `/resolve` canonicalises
  `opendata.eselpoint.cz` / bare `eselpoint.cz` → `opendata.eselpoint.gov.cz` (via
  `EsbirkaEliParser.canonicalizeHost`), but `/law/versions`, `/law/fragments` and
  `/law/content?versionIri=` validate the **raw** string and reject it with HTTP 400
  (`"Neplatný identifikátor právního aktu."`). Verified live 2026-08-23. Harmless while the FE
  passes IRIs straight through from our own responses; if legacy values are ever read back from
  storage and replayed into these endpoints, canonicalise first — or move the normalisation into
  `requireEsbirkaIri`, which would make all endpoints agree (~15 min).

---

## 5. Flow A — Version Selection (znění)

### Contract

```
GET /api/eli/law/content?law=49/1997                 → latest znění (unchanged behaviour)
GET /api/eli/law/content?law=49/1997&versionIri=…    → that znění
```

`versionIri` is optional and backward-compatible: omitting it reproduces the previous
behaviour exactly.

### Resolution chain

```
law="49/1997"
   │
   ├─ parseNumberYear            → ("49", 1997)          [rejects partial input like "49"]
   ├─ client.findLawByNumberYear → exact law lookup       [NOT a citation substring match]
   ├─ self.getVersions(lawIri)   → all znění — through the proxy, so the
   │                               esbirkaLawVersions cache is shared with /law/versions
   ├─ selectVersion(versions, versionIri, ny)
   │     ├─ versionIri null/blank → pickLatest()  (má-poslední-znění, falls back to newest)
   │     └─ versionIri supplied   → isEsbirkaEliIri() AND membership in `versions`
   ├─ client.fetchVersionContent → tree + HTML bodies, ONE round-trip
   └─ assembleTree + renderBodyHtml
```

### Two guards that matter

**Membership check, not just IRI validity.** `SparqlIriValidator.isEsbirkaEliIri` only
validates host + `/eli/` prefix. A well-formed IRI belonging to a *different* act would pass
it and render that act's text under this law's header. `selectVersion` therefore requires the
IRI to appear in the resolved law's own version list — free, since the version list was already
fetched. It rejects *before* spending the ~2 MB content fetch.

The version list is fetched via `self.getVersions(...)`, not `client.fetchVersions(...)`, so it
shares the `esbirkaLawVersions` cache. This matters for the switcher: because the content cache
key is version-aware, a user stepping through N znění of one law takes N content-cache misses,
and a direct client call would re-issue the identical version-list query on every one of them.

**Version-aware cache key.** `esbirkaLawContent` previously keyed on `number/year` alone.
Adding a version parameter without changing the key would make every znění of a law collide on
one ~2 MB entry — request the 2020 version, receive 2025's text under a 2020 header.

```java
key = "#root.target.normalizeLawRef(#lawRef) + '@' + (#versionIri == null ? '' : #versionIri)"
```

`normalizeLawRef` still collapses `"49/1997"`, `" 49/1997 "` and `"49/1997 Sb."` to one key.

### Response header reflects what was rendered

`versionIri`, `versionEliPath`, `versionDate` and `versionLatest` always describe the znění
actually in `fragments` — never the latest, unless the latest is what was rendered.
`versionLatest` lets the FE mark a historical view without cross-referencing the list.

### `@Cacheable` self-invocation

`getLawContent(lawRef)` delegates to the two-arg overload **through a `@Lazy` self-proxy**:

```java
private final EsbirkaService self;          // injected @Lazy
public LawContentDto getLawContent(String lawRef) {
    return self.getLawContent(lawRef, null);   // NOT this.getLawContent(...)
}
```

A direct `this.` call does not pass through the Spring proxy, so the two-arg method's
`@Cacheable` would be inert and every latest-version request would re-run the whole fetch.
Same pattern as `WorkingCopyDeviationServiceImpl`. Guarded by
`EsbirkaLawContentCacheTest.oneArgOverloadIsAlsoCached`.

---

## 6. Flow B — Flat Search Ranking

`/law/search` filters with `CONTAINS` on the whole citation, so the needle can match anywhere —
number, year, or the `Sb.` suffix. Ranking is bound **inside** the query:

```sparql
FILTER(CONTAINS(LCASE(STR(?citace)), LCASE(?qNeedle)))
BIND(IF(LCASE(STR(?cislo)) = LCASE(?qNeedle), 0,
     IF(STRSTARTS(LCASE(STR(?citace)), LCASE(?qNeedle)), 1,
     IF(STRSTARTS(LCASE(STR(?cislo)), LCASE(?qNeedle)), 2, 3))) AS ?rank)
...
ORDER BY ?rank DESC(?rok) ?citace
```

| Tier | Condition | `q=49` example |
|---|---|---|
| 0 | číslo equals needle | 49/1997, 49/2026 |
| 1 | citation starts with needle | 490/2001; and `q=49/1997` → the exact act |
| 2 | everything else — year-only | 1/2049, 2/1949 |

**Tier 1 is not redundant.** A needle carrying the year (`"49/1997"`) never equals or prefixes
`?cislo`, which holds `"49"` alone. Without tier 1 the exact law sorts *behind* `149/1997`,
`249/1997` and `349/1997`, all of which also contain the substring `"49/1997"`.

**There is deliberately no "číslo starts with needle" tier** — it would be dead code. The
citation is `<číslo>/<rok> Sb.`, so every číslo-prefix match is already a citation-prefix match.

**Ranking must live in the query.** `LIMIT` is applied after `ORDER BY`; ranking client-side
would truncate the best matches before they were ever ranked.

**Ranking only helps sparse čísla.** With ~120 acts numbered 49, a limit of 20 is filled by
tier 0 alone (49/2026 … 49/2007) and 49/1997 is *still* off the page. Ranking fixes the
**fully-qualified** query; for a bare number the real fix is `/law/search/grouped` (§7).

Empty `q` binds no rank and keeps the `rok desc, číslo asc` order.

---

## 7. Flow C — Grouped Search (the ambiguity fix)

### Why a row cap cannot work

Grouping is only correct if it sees *every* act sharing a number. But the match sets are
unbounded for short needles (§2 scale table): `q=1` prefix-matches 12 037 acts. Any row limit
slices a group in half and recreates the original bug — a window full of one number's recent
years while the wanted law falls off the end.

So the cap moved into SPARQL, and applies to **groups**, not rows.

### Two-step query

```
Step 1  buildLawNumberGroupsQuery(q, limit)     → EsbirkaSparqlClient.searchLawNumberGroups
        SELECT ?cislo (COUNT(*) AS ?pocet)
        FILTER(STRSTARTS(LCASE(STR(?cislo)), LCASE(?qNeedle)))
        GROUP BY ?cislo  ORDER BY STRLEN(STR(?cislo)) ?cislo  LIMIT {limit}
              │
              │  distinct čísla + TRUE dataset-wide counts
              ▼
Step 2  buildLawsByNumbersQuery(cisla, 600)     → EsbirkaSparqlClient.fetchLawsByNumbers
        FILTER(STR(?cislo) IN (?c0, ?c1, …))
        ORDER BY DESC(?rok) ?citace   LIMIT 600
              │
              ▼
Service groups rows by číslo, sorts each group newest-first, orders groups, builds the DTO.
```

Cost now tracks the size of the *answer*, not the *noise*: **~0.4 s even for `q=1`**.

**The group cap alone does not bound the response.** Group size is itself unbounded (~120 acts
per low číslo), so 50 groups would stream ~6 000 rows. Step 2 therefore carries a row cap
(`GROUPED_ROW_LIMIT = 600`) and the service caps each group's displayed list
(`MAX_LAWS_PER_GROUP = 60`). Neither loses information: `count` is the dataset-wide total from
step 1's aggregate, independent of how many acts are fetched for display.

**Step 2 does not order by číslo.** The service re-buckets rows by číslo and sorts each bucket,
which would discard any server-side číslo ordering — sorting twice on the expensive side of the
wire is pure waste. `DESC(?rok)` is kept so the row cap keeps the *newest* acts.

**Step 1 uses `COUNT(DISTINCT ?akt)`, not `COUNT(*)`.** `COUNT(*)` counts solutions, so an act
carrying two `patří-do-sbírky` or `citace` values would inflate its group total.

**Prefix, not substring.** Nobody searching `49` means `1490`. `STRSTARTS` is both the correct
semantic and the cheaper one.

### Response shape

```json
{
  "query": "49",
  "ambiguous": true,
  "totalMatches": 204,
  "truncated": true,
  "groups": [
    { "cislo": "49",  "count": 120, "exactNumberMatch": true,  "laws": [ /* 120 acts, 2026→1945 */ ] },
    { "cislo": "490", "count": 21,  "exactNumberMatch": false, "laws": [ … ] }
  ]
}
```

| Field | Meaning |
|---|---|
| `ambiguous` | The user still has a choice: >1 group, or one group with >1 act. **False on zero matches** — that is "not found", not "pick a year". The FE's cue to prompt for a year instead of auto-selecting. |
| `totalMatches` | Acts across the returned groups, summed from the aggregate. |
| `truncated` | The group cap was filled, so more numbers match than were returned. FE should say "refine your search". |
| `count` | Dataset-wide total for that číslo, from `COUNT(*)` — **not** the length of `laws`, which is what was fetched for display. |

### Group ordering

Exact-number match first, then **shortest číslo**, then číslo ascending — mirroring how people
type (`49` → `490` → `4900`).

Group size is deliberately **not** a criterion. Counts run 100+ for every low číslo, so
count-ordering would float whichever number happens to be most legislated rather than the one
the user typed.

---

## 8. Virtuoso Quirks (hard-won)

### `VALUES` on `číslo-předpisu` silently returns ZERO rows

e-Sbírka stores the number as an **explicitly typed** literal:

```json
{"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#string","value":"49"}
```

This Virtuoso does not equate `"49"^^xsd:string` with the plain literal `"49"`, so
`VALUES ?cislo { "49" }` matches nothing — HTTP 200, no error, just an empty result.

**Binding the typed form does not help.** SPARQL 1.1 defines the two as the same term, so Jena
renders `"49"^^xsd:string` back down to `"49"` and the mismatch returns.

**Working shape: `FILTER(STR(?cislo) IN (…))`** — `STR()` sidesteps the datatype (~0.2 s).
`buildLawByNumberYearQuery` already used this (`STR(?cislo) = ?numNeedle`), which is why it
never hit the bug. Guarded by
`EsbirkaSPARQLQueryTest.lawsByNumbers_matchesOnStrNotValues_forVirtuosoDatatypeQuirk`.

Suspect this for **any** equality/`VALUES` against an e-Sbírka string predicate. The failure is
silent, so it reads as "no data" rather than a query bug. `STRSTARTS`/`CONTAINS` wrapped in
`STR()` are unaffected.

### Boolean projections come back as `xsd:integer`

A projected comparison — `((?zneni = ?posledniZneni) AS ?isLatest)` — is returned as
`"1"^^xsd:integer`, **not** `"true"^^xsd:boolean`. `Literal.getBoolean()` throws on that, and
`SparqlSolutions.literalBool` swallowed the exception as `false` — so **`latest` was false for
every version of every law**, silently, since the feature shipped. The original probe recorded
`isLatest=1` correctly; nobody noticed Java could not read it.

`literalBool` now falls back to a numeric read (non-zero = true), then to the lexical form.
Guarded by `SparqlSolutionsBoolTest`. The helper is shared, so RPP/NKD/Fuseki mappers benefit too.

### Never `COUNT(DISTINCT)` over version→fragment property paths

`FILTER NOT EXISTS { ?z má-fragment-znění/obsahuje-fragment/text-fragmentu ?t }` is fine, but
the `COUNT(DISTINCT)` form across versions **times out at 60 s** (join blow-up). Use `EXISTS`.

### The endpoint rate-limits aggressively

Bursts — especially any dataset-wide scan — trigger an Azure Front Door WAF block:
`HTTP 403, "The request is blocked."` It persists for minutes and is *not* a query error.
Queries with an **unbound predicate** (`?s ?p ?o`) are also refused; enumerate predicates
explicitly. Never wire a per-keystroke search directly to this endpoint without debounce.

---

## 9. Fragment Tree Assembly

`má-fragment-znění` returns a **flat** list; `EsbirkaServiceImpl.assembleTree` rebuilds the
hierarchy. Two non-obvious behaviours:

**Three structural roots, not one.** Direct children of any `<versionIri>/dokument/<container>`
are roots: `norma` (body), `poznamkypodcarou` (footnotes), `prilohy` (annexes). Detection is
structural via `DOKUMENT_INFIX`, so new containers work automatically.

**`frag_*` grouper nodes are never returned.** e-Sbírka nests text under intermediate
`…/par_N/frag_NNNN` nodes that `má-fragment-znění` omits. A naive "parent not in result set →
drop" rule lost 275/2499 rows (11 %, all text-bearing) on 262/2006. The fix is **path-walk
re-parenting**: walk the parent IRI up by path segments to the nearest existing node. Safe
because the IRI path provably mirrors `má-předka` (0/2210 mismatches). Unresolvable parents
surface as roots with a warn-log — never dropped.

Depth is capped at `MAX_FRAGMENT_DEPTH = 10`; >5 000 rows logs a warning.

---

## 10. Known Traps

1. **`citace` and `má-předka` MUST be OPTIONAL on fragment queries — FIXED.** Only `pořadí`
   may be required. This was not a 13 % loss but a total outage for some laws: version
   `…/1997/49/2025-11-01` has **2 508 fragments and zero `citace` values**, so the required
   join returned 0 rows and *the entire law rendered blank* (`fragments: []`, empty
   `bodyHtml`). Verified live 2026-08-23; after the fix the same law returns 2 507 fragments
   / 2 497 text-bearing / 1.1 MB in ~1 s. Where `citace` mostly exists it is still missing on
   ~13 % of fragments (296/2198 on 187/2006), 288 of which carry real text. A null `?parent`
   is safe — `assembleTree`'s path-walk re-parenting surfaces it as a root rather than
   dropping it.
2. **`0000-00-00` is not a "no content" marker.** All 117 601 versions carry text-bearing
   fragments, including all 45 958 `0000-00-00` placeholders. Hiding them is a UX choice, not
   a content filter.
3. **`latest: true` is not a has-fragments signal.** Multiple `latest=false` versions return
   full trees.
4. **Never hard-code version dates.** The dataset advances continuously.
5. **No law title exists as metadata.** The title (`ZÁKON / ze dne … / o civilním letectví`)
   lives as `dokument/prefix` *body fragments* on a znění. See
   `.planning/esbirka-law-title-resolution-FINDINGS.md` — parked for a standalone feature.
6. **The query-class comment listing dokument containers is incomplete** — it omits `prilohy`.

---

## 11. Component Map

```
EsbirkaController              /api/eli/**  — limit validation, Czech error messages
        │
EsbirkaService (interface)     ← @Lazy self-proxy target for @Cacheable delegation
        │
EsbirkaServiceImpl            grouping, tree assembly, version selection, HTML rendering
        │
EsbirkaSparqlClient           one method per query; maps ResultSet → models
        │
EsbirkaSPARQLQuery            query builders (ParameterizedSparqlString — never concatenation)
        │
HttpSparqlExecutor            shared pooled HttpClient, timeouts, exception mapping
```

Models: `LawModel`, `LawVersionModel`, `LawNumberGroupModel`, `FragmentModel`,
`FragmentResolutionModel`.
DTOs: `LawDto`, `LawVersionDto`, `LawSearchGroupDto`, `LawSearchResultDto`, `LawContentDto`,
`FragmentDto`, `ResolvedLegalSourceDto`.

Every user-supplied IRI passes `SparqlIriValidator.isEsbirkaEliIri` before interpolation; all
needles are bound via `ParameterizedSparqlString`, never concatenated.

---

## 12. Test Coverage

| Suite | Covers |
|---|---|
| `EsbirkaSPARQLQueryTest` | query text: rank tiers, `STR()` vs `VALUES`, `GROUP BY` cap, injection escaping |
| `EsbirkaLawSearchRankingTest` | executes the real query against an in-memory Jena model and asserts **actual result order** |
| `EsbirkaGroupedSearchTest` | grouping, ambiguity flag, group ordering, dataset-wide counts |
| `EsbirkaLawContentCacheTest` | runs the **real cache manager** — key collisions and self-invocation are only observable through the Spring proxy |
| `EsbirkaServiceImplTest` | version selection, membership guard, tree assembly edge cases |
| `EsbirkaControllerTest` | envelope shape, 400/503 mapping |

The ranking, cache-key, membership and self-proxy guards were each **mutation-tested**:
reverting the fix makes the corresponding test fail with the original symptom.