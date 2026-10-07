# Export dat: slovníky a diagramy

> Stav: **návrh, neimplementováno.** Pouze návrh na vysoké úrovni.

## Jaký problém řešíme

Původní požadavek zněl: systém pravidelně publikuje výtah z databáze obsahující diagramy (kromě
komentářů a informací o uživatelích) do veřejně dostupného souboru ke stažení, který je
katalogizovaný v NKOD. Požadavek se od té doby posunul: aplikace má **zpřístupnit** data diagramů
a rozpracované i publikované slovníky včetně jejich metadat. Kdo si data vyzvedává, jak často a
zda se výsledek publikuje v NKOD, je otevřené obchodní rozhodnutí (viz
[Otevřená rozhodnutí](#otevřená-rozhodnutí)).

Tento návrh dodává část, která na tom rozhodnutí nezávisí: jeden administrátorský endpoint, který
vrátí kompletní export jako jediný soubor.

## Proč nový endpoint

Data jsou rozdělena mezi dvě úložiště a žádné ze stávajících rozhraní je nepokrývá.

| Možnost | Proč nestačí |
|---|---|
| **Fuseki / SPARQL napřímo** | Diagramy a metadata slovníků (slug, stav publikace, časové značky) existují jen v PostgreSQL. Služba Fuseki navíc vedle `query` vystavuje i `update`, `upload` a `gsp-rw`, takže ji nelze v současné podobě otevřít konzumentovi. |
| **Stávající REST endpointy** | Konzument by potřeboval jedno volání pro seznam slovníků, jedno stažení na každý slovník, jedno volání pro seznam diagramů a jedno volání detailu na každý diagram. Stažení slovníku je při zapnutém omezení odmítnuto pro slovníky s validačními chybami, model seznamu nese pole o uživateli a komentářích a detail diagramu je „tlusté“ čtení, které obsahuje i rozpracované změny. |

Export znovu používá stávající stavební kameny (`OntologyDownloadService`, repozitáře diagramů)
za jedním endpointem, který pravidla exportu uplatňuje na jednom místě.

## Rozsah

| Zahrnuto | Vyloučeno |
|---|---|
| Všechny slovníky, rozpracované i publikované, bez ohledu na stav validace | Komentáře |
| Metadata slovníků z PostgreSQL | Jakékoli informace o uživatelích, včetně id vlastníka |
| Obsah slovníku jako vyčištěné RDF (stejný výstup jako stažení slovníku) | Rozpracované změny diagramů (`diagram_pending_edits`) |
| Metadata pojmů z PostgreSQL | Validační reporty |
| Všechny diagramy s uloženým rozložením | Outbox, rekonciliátor a další provozní data |
| | Data z NKD, NKOD, RPP a e-Sbírky |

## Endpoint

```
GET /api/admin/export
```

| Aspekt | Hodnota |
|---|---|
| Autorizace | `@PreAuthorize("hasRole('ADMIN')")` a k tomu matcher `authenticated()` pro `/api/admin/export` v `SecurityConfig` (stejný vzor jako `/api/admin/reconciler` a `/api/admin/outbox`) |
| Parametr | `rdfFormat` = `ttl` (výchozí) nebo `json-ld` |
| Odpověď | `application/zip`, streamovaná, `Content-Disposition: attachment; filename="ismd-export-<časová značka>.zip"` |
| Souběh | Vždy jen jeden export; druhý požadavek během běžícího exportu dostane `409` |

Endpoint je synchronní. Každý slovník stojí jedno načtení grafu z Fuseki, takže doba odpovědi
roste lineárně s počtem slovníků. Pokud by to pro jeden HTTP požadavek bylo příliš pomalé, lze
stejné sestavení přesunout do úlohy, která soubor zapíše do úložiště, aniž by se změnil formát
archivu.

## Struktura archivu

```
manifest.json
ontologies/
  <slug-slovníku>/
    ontology.ttl            (nebo ontology.jsonld)
    concepts.json
    diagrams/
      <id-diagramu>.json
```

### `manifest.json`

Rejstřík archivu a jediné místo, které konzument potřebuje přečíst, aby věděl, co obdržel.

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

- `iri` je název grafu slovníku.
- `rdf.status` je `OK`, `EMPTY` (slovník zatím nemá žádný RDF obsah) nebo `FAILED` (načtení nebo
  transformace selhaly). Pole `file` má jen stav `OK`.
- `complete` je `false`, pokud je alespoň jeden slovník ve stavu `FAILED`.
- `lastValidationStatus` je `VALIDATED`, `SKIPPED_UNAVAILABLE` nebo `FAILED`. Říká, zda poslední
  běh validace doběhl, nikoli zda našel chyby; nálezy jsou ve validačním reportu, který se
  neexportuje.
- Čitelné názvy a popisy se v manifestu neopakují; jsou v RDF.

### `concepts.json`

Metadata jednotlivých pojmů, která existují jen v PostgreSQL.

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

### `diagrams/<id-diagramu>.json`

**Uložené rozložení**, nikoli projektovaný čtecí model, který dostává frontend.

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

Diagram ukládá jen to, co uživatel uspořádal: které pojmy jsou na plátně, kde a jak jsou vedeny
hrany. Které hrany existují a všechny popisky se při čtení odvozují z RDF. Export toto rozdělení
zachovává, takže soubor diagramu odkazuje na pojmy přes IRI a jejich obsah dodává RDF ve stejném
archivu. Uzel označený `foreign` odkazuje na pojem z jiného slovníku.

Verze pro optimistický zámek (`version`) a interní evidenční sloupce hran se neexportují.

## Jak se export sestavuje

1. **Jedno čtení z PostgreSQL.** V jediné transakci pouze pro čtení se načtou všechny slovníky
   s pojmy a diagramy (uzly a hrany) do exportních DTO. Tato DTO nemají pole pro uživatele ani
   komentáře, takže vyloučená data nemohou uniknout přes sdílený model.
2. **Archiv se streamuje slovník po slovníku.** Pro každý slovník se zavolá
   `OntologyDownloadService.downloadOntology(id, format)` a výsledek se zapíše, poté se zapíše
   `concepts.json` a soubory diagramů. V paměti je vždy RDF jen jednoho slovníku.
3. **`manifest.json` se zapisuje jako poslední.** Zaznamená tak skutečný výsledek každého slovníku.

Návrhové body plynoucí ze stávajícího kódu:

- **Validace export neblokuje.** Omezení stahování slovníků s validačními chybami je
  v `OntologyController`, nikoli ve službě, takže přímé volání služby zahrne každý rozpracovaný
  slovník. Export neříká, které slovníky mají validační chyby; pokud to konzument potřebuje,
  lze do manifestu přidat počet chyb na slovník.
- **Vyčištěné RDF.** Služba používá stejné filtrování jako veřejné stažení,
  takže export a stažení jednotlivého slovníku se vždy shodují.
- **Selhání se hlásí po slovnících.** Odpověď je streamovaná, takže HTTP stav je už `200`, když
  některý pozdější slovník selže. Selhání se proto zaznamená do manifestu
  (`rdf.status = FAILED`, `complete = false`). Odpověď přerušená uprostřed streamu nemá manifest
  a není platný ZIP, takže ji konzument nemůže zaměnit za kompletní export.
- **Prázdný slovník není selhání.** Rozpracovaný slovník bez RDF obsahu se exportuje se svými
  metadaty a stavem `rdf.status = EMPTY`.
- **Zátěž Fuseki.** Grafy se načítají jeden po druhém a procházejí stávajícím semaforem Fuseki,
  takže export nemůže zaplnit interaktivní požadavky.

## Konzistence

PostgreSQL a TDB2 nesdílejí transakci (viz
[`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Strana PostgreSQL je v exportu jeden
konzistentní snímek; RDF každého slovníku se čte krátce poté. Úprava provedená během běžícího
exportu se proto může objevit v RDF, ale ne v metadatech. `exportedAt` označuje snímek PostgreSQL.
Pro pravidelný výtah je to přijatelné; export, který musí být přesný, by měl běžet v době, kdy
neprobíhají zápisy.

## Otevřená rozhodnutí

| # | Rozhodnutí | Dopad na návrh |
|---|---|---|
| 1 | **Kdo export volá** (rozhodnutí klienta): administrátor na vyžádání, nebo automatická úloha, která soubor publikuje | Automatický volající potřebuje servisní účet v Keycloaku s rolí admin. Formát archivu je v obou případech stejný. |
| 2 | **Publikace v NKOD**: zda se soubor publikuje a katalogizuje a jak často | Přidává plánovanou úlohu, veřejné úložiště souboru a katalogizační záznam v NKOD. Není součástí tohoto návrhu. |

## Nastínění implementace

| Část | Poznámka |
|---|---|
| `ExportController` (`/api/admin/export`) | Nový, vedle controllerů rekonciliátoru a outboxu |
| `ExportService` | Čte PostgreSQL, řídí stream archivu, sestavuje manifest |
| Exportní DTO | Manifest, položka slovníku, položka pojmu, rozložení diagramu |
| `SecurityConfig` | Jeden matcher `authenticated()`; bez něj požadavek odmítne `denyAll()` dříve, než se spustí `@PreAuthorize` |
| Beze změny znovu použito | `OntologyDownloadService`, repozitáře diagramů a slovníků |
| Testy | Vyloučená data nejsou v žádném souboru; rozpracovaný slovník s validačními chybami se exportuje; prázdný slovník dá `EMPTY`; selhávající slovník dá `FAILED` a `complete = false`; uživatel bez role admin dostane `403` |