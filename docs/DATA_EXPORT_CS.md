# Publikace otevřených dat: slovníky a diagramy

> Stav: **implementováno**. Produkční použití čeká na GitHub Pages pro kontext; viz
> [Zapnutí](#zapnutí). Anglická verze: [`DATA_EXPORT.md`](./DATA_EXPORT.md).

## Jaký problém řešíme

Rozpracované i publikované slovníky včetně jejich metadat a data diagramů se mají zveřejnit jako
otevřená data. ISMD na vyžádání administrátora sestaví dva soubory otevřených dat, každý běh uloží do
PostgreSQL a administrátorovi soubory zpřístupní ke stažení. Pipeline DIA LKOD je nahraje do LKOD DIA,
odkud se katalogizují v NKOD. ISMD žádnou veřejnou kopii neservíruje a do LKOD nijak nezasahuje.

| Připomínka klienta | Jak ji návrh plní |
|---|---|
| Nesmí to být ZIP ani X souborů, ale pevný malý počet souborů | Právě **dva soubory**: `slovniky.jsonld` a `diagramy.jsonld` |
| `manifest.json` nebude; jeho obecné údaje (`complete`, `exportedAt`, …) i údaje o slovnících a diagramech (`createdAt`, `isPublished`, …) patří do souborů | Každý soubor nese uzel **záznamu o publikaci** (obecné údaje); metadata slovníků a diagramů jsou přímo na jejich uzlech |
| Místo souboru na každý slovník a diagram jeden soubor pro všechny slovníky a jeden pro všechny diagramy | `@graph` s jedním uzlem na slovník, resp. diagram |
| `concepts.json` zaniká, jeho obsah se propíše do souboru slovníků | Metadata pojmů z PostgreSQL se doplní přímo k pojmům v `pojmy` |
| Atributy odkazující na soubory zanikají | Žádné `file`, `concepts`, `rdf.file`; vazby jsou jen přes IRI |
| Vše ve formátu `.jsonld` s `@context` slovníku popisujícího tato OD | Dva kontexty: OFN Slovníky a kontext ISMD OD (viz [Kontexty](#kontexty)) |
| Metadata datové sady nemusí generovat ISMD, stačí je jednou vytvořit ručně | Katalogizační záznam je v LKOD DIA a spravuje ho pipeline DIA LKOD (viz [Katalogizace](#katalogizace)) |

## Proč soubory sestavuje ISMD

Data jsou rozdělena mezi dvě úložiště a žádné ze stávajících rozhraní je nevydá jako dva soubory.

| Možnost | Proč nestačí |
|---|---|
| **Fuseki / SPARQL napřímo** | Diagramy a metadata slovníků (slug, stav publikace, časové značky) existují jen v PostgreSQL. Služba Fuseki navíc vedle `query` vystavuje i `update`, `upload` a `gsp-rw`, takže ji nelze v současné podobě otevřít pipeline. |
| **Stávající REST endpointy** | Sestavení souborů by potřebovalo jedno volání pro seznam slovníků, jedno stažení na každý slovník, jedno volání pro seznam diagramů a jedno volání detailu na každý diagram. Stažení slovníku je při zapnutém omezení odmítnuto pro slovníky s validačními chybami, model seznamu nese pole o uživateli a komentářích a detail diagramu je „tlusté“ čtení, které obsahuje i rozpracované změny. |

Publikace znovu používá stávající stavební kameny (`OntologyDownloadService`, repozitáře diagramů)
a pravidla exportu uplatňuje na jednom místě.

## Rozsah

| Zahrnuto | Vyloučeno |
|---|---|
| Publikované slovníky; rozpracované podle `ismd.publication.include-drafts` | Komentáře |
| Slovníky bez ohledu na stav validace | Jakékoli informace o uživatelích, včetně id vlastníka |
| Metadata slovníků z PostgreSQL | Rozpracované změny diagramů (`diagram_pending_edits`) |
| Obsah slovníku jako vyčištěný OFN JSON-LD (stejný výstup jako stažení slovníku v JSON-LD) | Validační reporty |
| Metadata pojmů z PostgreSQL, doplněná přímo k pojmům | Outbox, rekonciliátor a další provozní data |
| Diagramy zahrnutých slovníků s uloženým rozložením | Data z NKD, NKOD, RPP a e-Sbírky |


## Konfigurace

| Vlastnost | Výchozí | Význam |
|---|---|---|
| `ismd.publication.include-drafts` | `true` | Rozpracované slovníky se publikují. `false` vynechá rozpracované slovníky, rozpracované pojmy publikovaných slovníků a diagramy vynechaných slovníků (viz [Rozpracované slovníky](#rozpracované-slovníky)) |
| `ismd.publication.retain-files` | `12` | Kolik nejnovějších uložených publikací si v PostgreSQL ponechá obsah souborů (nejméně 1); metadata se uchovávají navždy (viz [Archiv](#archiv)) |
| `ismd.publication.iri-base` | `https://data.dia.gov.cz/zdroj/ismd/` | Základ IRI, která ISMD vytváří: `<základ>diagramy/<iri_id>` a `<základ>publikace/<soubor>` (viz [IRI diagramu](#iri-diagramu)) |
| `ismd.publication.context-url` | `https://datagov-cz.github.io/ismd-tool-backend/ismd-otevřená-data/draft/kontext.jsonld` | Druhý kontext v `@context` obou souborů, veřejná URL `https://` (viz [Hostování kontextu](#hostování-kontextu)). Prázdná hodnota způsobí selhání běhu. |

## Administrátorské endpointy

```
POST /api/admin/publikace/run
GET  /api/admin/publikace/history?page&size
GET  /api/admin/publikace/{id}/{file}
```

Všechny tři jsou jen pro administrátory (`@PreAuthorize("hasRole('ADMIN')")`
v `PublicationAdminController`); `SecurityConfig` pouští `/api/admin/publikace/**` přihlášeným
uživatelům, stejně jako `/api/admin/reconciler/**`. Veřejný endpoint neexistuje.

### Spuštění publikace

`POST /run` sestaví a uloží publikaci; při startu ani podle plánu se nic nespouští. Požadavek čeká na
dokončení sestavení a odpovídá `ApiResponseDto`:

| Stav | Význam |
|---|---|
| `200` | Uloženo; data `PublicationRun` `{id, publishedAt, vocabularies, diagrams, complete, includeDrafts}`, `id` = uložená publikace |
| `409` `PUBLICATION_IN_PROGRESS` | Zámek drží jiný běh |
| `500` `PUBLICATION_FAILED` | Běh selhal (včetně prázdné `context-url`); nic se neuloží |

Kontroler jen deleguje na `PublicationService.publish(triggeredBy)`, která vrací `PublicationRun`.
Služba získá zámek a selhání hlásí vyhozením `PublicationInProgressException` nebo
`PublicationFailedException` (balíček `com.dia.ismdtoolbackend.exception`); na stavy výše je mapuje
`GlobalExceptionHandler`. Běží vždy nejvýše jedno sestavení (`ReentrantLock` stačí, backend běží
v jedné instanci).

### Historie

`GET /history` vypíše uložené publikace od nejnovější (`page` výchozí `0`, `size` výchozí `50`, nejvýše
`200`), jen metadata: `PublicationSummary` s poli běhu, `triggeredBy`, `contextUrl` a u každého souboru
`name`, `size`, `etag` a `stored` (`false`, jakmile je obsah mimo `retain-files`).

### Stažení souborů

`GET /{id}/{file}` (`file` = `slovniky.jsonld` | `diagramy.jsonld`) vrátí uložený soubor z PostgreSQL.
Tak administrátor získá soubory pro LKOD DIA; `id` nejnovější publikace je v odpovědi běhu nebo
v prvním záznamu `history`.

| Stav | Význam |
|---|---|
| `200` | Uložený obsah, `application/ld+json;charset=UTF-8`, `ETag` (SHA-256 obsahu v uvozovkách), `Content-Disposition: attachment; filename="publikace-<id>-<file>"` |
| `404` `RESOURCE_NOT_FOUND` | Neznámá publikace nebo soubor |
| `410` `PUBLICATION_FILE_PRUNED` | Publikace existuje, ale její obsah je mimo `retain-files` |

## Formát souborů

### Kontexty

Oba soubory mají `@context` se dvěma kontexty:

```json
"@context": [
  "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld",
  "<ismd.publication.context-url>"
]
```

- **OFN Slovníky** (publikovaná verze 2026-02-26) definuje obsah slovníků a pojmů: `iri`, `typ`,
  `název`, `popis`, `vytvořeno`, `aktualizováno`, `Časový okamžik`, `pojmy` a všechna pole pojmů.
  Stávající stažení v JSON-LD tento kontext už používá.
- **Kontext ISMD OD** definuje vše, co OFN nepokrývá: metadata z PostgreSQL, záznam o publikaci
  a diagramy. Je uveden jako druhý, takže OFN termíny nepřepisuje. Hostuje se v tomto repozitáři
  přes GitHub Pages (viz [Hostování kontextu](#hostování-kontextu)).

### Konvence pojmenování

Převzaty z norem v [`datagov-cz/otevrene-formalni-normy`](https://github.com/datagov-cz/otevrene-formalni-normy)
(DCAT-AP-CZ, OFN Slovníky) a z [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia):

| Prvek | Konvence | Příklad v normách | ISMD |
|---|---|---|---|
| Vlastnost (klíč JSON) | česky, malá písmena, slova spojená `_` | `periodicita_aktualizace`, `klíčové_slovo`, `je_součástí`, `datum_a_čas` | `je_publikován`, `stav_poslední_validace`, `počet_pojmů` |
| Typ (hodnota `typ`) | česky, velké počáteční písmeno, mezery | `Datová sada`, `Časový okamžik`, `Typ objektu práva` | `Diagram`, `Uzel diagramu`, `Publikace ISMD` |
| `iri` / `typ` | aliasy `@id` / `@type` | všechny kontexty | beze změny |
| Vícejazyčný text | `@container: @language` | `název`, `popis` | jen kde jde o text pro lidi |
| Odkaz na zdroj | `@type: @id` | `poskytovatel`, `téma` | `slovník`, `pojem`, `seskupen_pod` |
| Umístění kontextu | `<norma>/<RRRR-MM-DD>/<profil>/kontext.jsonld`, pracovní verze v `draft/` | `dcat-ap-cz-hvd/2026-09-23/datová-sada/kontext.jsonld` | `ismd-otevřená-data/<datum>/kontext.jsonld` (jeden kontext pro oba soubory), pracovní verze v `ismd-otevřená-data/draft/` |
| IRI zdrojů DIA | `https://data.dia.gov.cz/zdroj/<oblast>/…` | `https://data.dia.gov.cz/zdroj/datové-sady/nkod/nkod-kompletní` | `https://data.dia.gov.cz/zdroj/ismd/diagramy/<uuid>` |
| IRI termínů slovníku | slovník.gov.cz `…/<slovník>/pojem/<termín>` (OFN), data.gov.cz `slovník/<název>/<termín>` (DCAT-AP-CZ) | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/okamžik-vytvoření` | `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/<termín>` |

Termíny IRI slovníku používají spojovník (`okamžik-vytvoření`), klíče v kontextu podtržítko
(`datum_a_čas`). OFN Slovníky používá u polí pojmů spojovník (`alternativní-název`,
`nadřazený-pojem`), DCAT-AP-CZ podtržítko. Klíče ISMD se drží DCAT-AP-CZ, takže pojem
v `slovniky.jsonld` nese obě podoby vedle sebe (`alternativní-název` z OFN, `je_publikován` z ISMD).
Všechny klíče jsou v jedné třídě (`PublicationJson`).

Slovník popisující tato OD je sám OFN slovník (třídy `Diagram`, `Uzel diagramu`, vlastnosti
`je_publikován`, …), takže jej lze vytvořit přímo v ISMD a kontext z něj vygenerovat.

Časové značky mají tvar OFN `{"typ": "Časový okamžik", "datum_a_čas": "…"}` s explicitním posunem,
na celé sekundy.
PostgreSQL ukládá `createdAt` / `updatedAt` bez časové zóny; publikace doplní `Europe/Prague`.

**Neexportují se žádné databázové identifikátory.** Slovníky a pojmy jsou identifikovány přes IRI,
diagram vlastním IRI (viz [IRI diagramu](#iri-diagramu)) a uzel diagramu přes IRI pojmu.

### Záznam o publikaci

Je v obou souborech jako jeden uzel `@graph`; nahrazuje obecná pole dřívějšího manifestu.

```json
{
  "iri": "https://data.dia.gov.cz/zdroj/ismd/publikace/slovniky.jsonld",
  "typ": "Publikace ISMD",
  "publikováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-10-07T03:00:00+02:00" },
  "úplná": true,
  "obsahuje_rozpracované": true
}
```

| Pole | Dříve (manifest) | Poznámka |
|---|---|---|
| `publikováno` | `exportedAt` | Okamžik snímku PostgreSQL (viz [Konzistence](#konzistence)); stejný v obou souborech jednoho běhu |
| `úplná` | `complete` | `false`, pokud má alespoň jeden slovník `stav_obsahu = FAILED`. V souboru diagramů je vždy `true`. |
| `obsahuje_rozpracované` | — | Hodnota `ismd.publication.include-drafts`, aby konzument věděl, zda chybějící slovník neexistuje, nebo je jen rozpracovaný |
| — | `schemaVersion` | Zaniká; verzi formátu nese datovaná URL kontextu ISMD OD |
| — | `rdfFormat` | Zaniká; formát je vždy JSON-LD |
| — | `ontologies[]`, `file`, `concepts`, `diagrams[].file` | Zanikají; uzly slovníků a diagramů jsou přímo v `@graph` |

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

| Pole | Dříve | Poznámka |
|---|---|---|
| `vytvořeno_v_ismd`, `aktualizováno_v_ismd` | `createdAt`, `updatedAt` | Časy záznamu v PostgreSQL. OFN `vytvořeno` / `aktualizováno` slovníku zůstávají z obsahu (RDF) beze změny, proto vlastní termíny. |
| `slug` | `slug` | |
| `je_publikován` | `isPublished` | U slovníku i pojmu |
| `stav_poslední_validace` | `lastValidationStatus` | `VALIDATED`, `SKIPPED_UNAVAILABLE` nebo `FAILED`. Říká, zda poslední běh validace doběhl, nikoli zda našel chyby; nálezy jsou ve validačním reportu, který se neexportuje. |
| `okamžik_poslední_validace` | `lastValidationAt` | |
| `stav_obsahu` | `rdf.status` | `OK`, `EMPTY` (slovník zatím nemá RDF obsah) nebo `FAILED` (načtení nebo transformace selhaly). Při `EMPTY` a `FAILED` nese uzel jen metadata z PostgreSQL. |
| `počet_pojmů` | `conceptCount` | Počet pojmů v PostgreSQL po uplatnění `include-drafts` |
| — | `type` (concepts.json) | Neopakuje se; nese ho OFN `typ` |
| — | `inTezaurus` (concepts.json) | Nepublikuje se |
| — | `rdf.file`, `concepts` | Zanikají |

- Uzel slovníku je stávající výstup stažení v OFN JSON-LD (obsahová pole, `pojmy` a vše v nich)
  doplněný o metadata z PostgreSQL. Každý pojem v `pojmy` dostane podle IRI svá pole z PostgreSQL.
- `iri` je název grafu slovníku.
- JSON Schema OFN popisuje jeden slovník, takže validuje jednotlivé uzly slovníků, ne celý soubor.

### `diagramy.jsonld`

**Uložené rozložení**, nikoli projektovaný čtecí model, který dostává frontend.

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

| Pole | Dříve | Poznámka |
|---|---|---|
| `název_diagramu` | `name` | Prostý řetězec. OFN `název` je jazyková mapa na `skos:prefLabel`, proto vlastní termín. |
| `vytvořeno_v_ismd`, `aktualizováno_v_ismd` | `createdAt`, `updatedAt` (manifest) | Přesunuto z manifestu na uzel diagramu |
| `slovník` | `ontologyIri` | Odkaz přes IRI (`@type: @id`) na uzel slovníku v `slovniky.jsonld` |
| `pojem` | `conceptIri` | Odkaz přes IRI |
| `seskupen_pod` | `parentConceptIri` | Odkaz přes IRI na pojem uzlu, pod kterým je tento uzel seskupen; u neseskupeného uzlu chybí |
| `cizí` | `foreign` | Uzel odkazuje na pojem z jiného slovníku |
| `klíč_hrany` | `edgeKey` | Řetězec, ne odkaz přes IRI: u hran hierarchie jde o klíč sestavený frontendem |
| `výřez` | `viewport` | Chybí, dokud diagram nebyl poprvé uložen |
| — | `collapsed`, `visibleProperties`, `segments` | Nepublikují se (`sbalený`, `zobrazené_vlastnosti`, `body_zlomu` vypuštěny 2026-11-16) |
| — | `file` | Zaniká |

Publikuje se jen to, co uživatel uspořádal: které pojmy jsou na plátně a kde, jak jsou seskupené
a které hrany jsou na plátně. Trasy hran, sbalení uzlů a zobrazené vlastnosti se nepublikují. Které
hrany existují a všechny popisky se při čtení odvozují z RDF. Publikace toto rozdělení zachovává: soubor diagramů odkazuje na pojmy přes IRI a jejich obsah dodává `slovniky.jsonld`.
Pojem je v diagramu jako uzel nejvýše jednou. Uzly a hrany nemají vlastní IRI (v RDF jsou to
prázdné uzly).

Id řádků, verze pro optimistický zámek (`version`) a interní evidenční sloupce hran se neexportují.

### IRI diagramu

Diagram je dnes identifikován slovníkem a názvem. Ani jedno není stálé: diagram lze přejmenovat
a přejmenování slovníku mění jeho IRI. Publikace proto dává každému diagramu vlastní IRI:

- Sloupec `diagrams.iri_id` (`UUID`, `NOT NULL`, unikátní `uq_diagrams_iri_id`, výchozí
  `gen_random_uuid()`), Liquibase `024-diagram-iri-id.yaml`. Výchozí hodnota dala každému
  existujícímu řádku vlastní UUID; rollback omezení i sloupec odebere.
- `DiagramEntity.iriId` se nastaví při vytvoření objektu, nemá setter a po vložení se nemění.
- IRI = `ismd.publication.iri-base` + `diagramy/` + `iri_id`. Výchozí základ
  `https://data.dia.gov.cz/zdroj/ismd/` sleduje vzor IRI zdrojů DIA v LKOD DIA.
- IRI přežije přejmenování diagramu i přejmenování nebo přesun jeho slovníku. Po smazání diagramu se
  znovu nepoužije. Základ IRI je po první publikaci třeba považovat za neměnný.

## Rozpracované slovníky

Řídí je `ismd.publication.include-drafts`. Při `false`:

| Co | Pravidlo |
|---|---|
| Slovník | Vynechá se celý slovník s `isPublished = false` |
| Pojem publikovaného slovníku | Pojem s `isPublished = false` se odebere z `pojmy` podle IRI. RDF rozpracovaných pojmů je ve stejném grafu jako publikovaných, takže filtr musí běžet nad výstupem stažení, ne nad dotazem do Fuseki. |
| Diagram | Vynechá se diagram vynechaného slovníku |
| Uzel diagramu | Uzel, jehož pojem je rozpracovaný pojem **kteréhokoli** slovníku ISMD, se vynechá, aby cizí uzel neprozradil rozpracovaný pojem jiného slovníku; `seskupen_pod` odkazující na vynechaný uzel se vynechá. Uzly na pojmy, které ISMD nezná (NKD), se ponechají. |
| Odkazy v pojmech | Odkazy ponechaných pojmů na vynechané pojmy (`nadřazený-pojem`, `nadřazená-třída`, `obor-hodnot`, …) se odeberou; seznam, který zůstane prázdný, se odebere celý. |
| Hrana | Hrana, jejíž `klíč_hrany` je rozpracovaný pojem (vztah), nebo hrana hierarchie `edge\|DRUH\|zdroj\|cíl` s rozpracovaným koncem, se vynechá |

Při `true` (výchozí) se publikuje vše a rozlišení nese `je_publikován`.

## Jak se publikace sestavuje

Spouští ji administrátor přes `POST /api/admin/publikace/run` (viz
[Spuštění publikace](#spuštění-publikace)); běh sestaví oba soubory.

1. **Zámek a kontext.** Běh získá zámek (jiný běh probíhá → `409`) a s prázdnou `context-url` selže.
2. **Jedno čtení z PostgreSQL.** Zaznamená se `publikováno` a `PublicationSnapshotReader` načte
   v jediné transakci pouze pro čtení (jeden dotaz na tabulku) slovníky s pojmy a diagramy s uzly
   a hranami do `PublicationSnapshot`. Ten nemá pole pro uživatele, komentáře ani rozpracované změny
   diagramů, takže vyloučená data nemohou uniknout přes sdílený model. Při `include-drafts=false`
   na snímek použije pravidla výše `DraftFilter`.
3. **`slovniky.jsonld` slovník po slovníku.** Pro každý slovník se zavolá
   `OntologyDownloadService.downloadOntology(id, "json-ld")`, doplní se metadata z PostgreSQL a uzel
   se zapíše streamovacím zapisovačem JSON do dočasného adresáře (`ismd-publication-*`). V paměti je
   vždy jen jeden slovník.
4. **`diagramy.jsonld`** se sestaví jen z PostgreSQL.
5. **Záznam o publikaci** se zapíše do obou souborů jako poslední uzel (pořadí uzlů v `@graph` nemá
   význam); spočítá se velikost a ETag (SHA-256) každého souboru (`BuiltFile`).
6. **Archiv.** `PublicationArchive.save(...)` uloží běh i oba soubory (gzip) do PostgreSQL v jedné
   transakci, která zároveň odebere obsah mimo `retain-files`.
7. **Úklid.** Dočasný adresář se v každém případě smaže; běh vrátí `PublicationRun`.

Jakékoli selhání znamená selhání běhu (`PUBLICATION_FAILED`) a nic se neuloží.

Návrhové body plynoucí ze stávajícího kódu:

- **Validace publikaci neblokuje.** Omezení stahování slovníků s validačními chybami je
  v `OntologyController`, nikoli ve službě, takže přímé volání služby zahrne každý slovník.
- **Vyčištěný obsah.** Služba používá stejné filtrování a transformaci OFN jako stávající stažení slovníku (`/api/ontology/{slug}/download`),
  takže publikace a stažení jednotlivého slovníku se shodují (až na filtr rozpracovaných pojmů).
- **Selhání se hlásí po slovnících** (`stav_obsahu = FAILED`, `úplná = false`).
- **Prázdný slovník není selhání.** Slovník bez RDF obsahu se publikuje se svými metadaty
  a `stav_obsahu = EMPTY`.
- **Zátěž Fuseki.** Grafy se načítají jeden po druhém přes stávající semafor Fuseki, takže publikace
  nezahltí interaktivní požadavky. Běh spouští jen administrátor.

## Archiv

Každá publikace se ukládá do PostgreSQL jako auditní stopa a jako zdroj stažení pro administrátora
(Liquibase `025-create-publications.yaml`, schéma `ismd_schema`):

| Tabulka | Sloupce | Uchování |
|---|---|---|
| `publication` | `id`, `published_at` (`timestamptz`), `triggered_by` (např. `ADMIN:<username>`), `include_drafts`, `complete`, `vocabularies`, `diagrams`, `context_url` | Navždy |
| `publication_file` | `publication_id` (FK, `ON DELETE CASCADE`), `name`, `size`, `etag`, `content_gzip` (`bytea`, gzip souboru); unikátní (`publication_id`, `name`) | `name`, `size`, `etag` navždy; `content_gzip` se nastaví na `NULL`, jakmile publikace není mezi `retain-files` nejnovějšími |

`triggered_by` zůstává v databázi; do publikovaných souborů se nedostane.

## Konzistence

PostgreSQL a TDB2 nesdílejí transakci (viz
[`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Strana PostgreSQL je jeden
konzistentní snímek; RDF každého slovníku se čte krátce poté. Úprava provedená během běhu se proto
může objevit v RDF, ale ne v metadatech. `publikováno` označuje snímek PostgreSQL. Kdy publikovat,
volí administrátor, takže je to přijatelné. Mezi publikacemi soubory úmyslně nesledují změny; aktuální
stav dávají stávající REST endpointy.

## Katalogizace

Metadata datové sady ISMD negeneruje a distribuce nehostuje. Katalogizační záznam a jeho distribuce
jsou v [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia) (např.
`ismd/slovníky-a-diagramy.jsonld`) a patří pipeline DIA LKOD, která nahrává soubory vytvořené v ISMD;
záznam do NKOD harvestuje LinkedPipes ETL:

- IRI datové sady `https://data.dia.gov.cz/zdroj/datové-sady/ismd/slovníky-a-diagramy`, poskytovatel
  `https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921` (DIA).
- Dvě distribuce (slovníky, diagramy), formát `JSON_LD`, typ média `application/ld+json` a `schéma` =
  URL kontextu ISMD OD (resp. JSON Schema, pokud vznikne). URL distribucí jsou tam, kde LKOD DIA
  nahrané soubory hostuje.
- `periodicita_aktualizace` = `http://publications.europa.eu/resource/authority/frequency/IRREG`
  (nepravidelně): publikace běží na vyžádání.
- Rozpracované slovníky se publikují (`include-drafts=true`), proto to musí popis datové sady výslovně uvést.

## Hostování kontextu

Kontext ISMD OD je v tomto repozitáři a servíruje ho GitHub Pages:

- **Soubor a URL.** `pages/ismd-otevřená-data/draft/kontext.jsonld`, servírovaný na
  `https://datagov-cz.github.io/ismd-tool-backend/ismd-otevřená-data/draft/kontext.jsonld`, což je
  výchozí hodnota `ismd.publication.context-url` (`PublicationConfig.DRAFT_CONTEXT_URL`).
  `PublicationFilesTest.defaultContextUrl_pointsAtThePublishedDraftContext` ověřuje, že výchozí URL
  odpovídá souboru v `pages/`; testy přes něj a přes kontext OFN expandují oba soubory.
- **Nasazení.** `.github/workflows/pages.yml` běží při pushi do `dev`, který mění `pages/**` (a ručním
  spuštěním), ověří každý `.jsonld` nástrojem `jq` a nasadí `pages/` na GitHub Pages. V repozitáři je
  třeba zapnout GitHub Pages (Source: GitHub Actions), k čemuž jsou nutná administrátorská práva
  k repozitáři.
- **Hlavičky.** Procesory JSON-LD načítají vzdálený kontext za běhu; URL musí vracet
  `application/ld+json` (nebo `application/json`) a `Access-Control-Allow-Origin: *`. GitHub Pages
  vrací obojí bez konfigurace. `raw.githubusercontent.com` vrací `text/plain`, a proto nevyhovuje.
- **Verzování.** Podle vzoru OFN se datovaná složka `pages/ismd-otevřená-data/<RRRR-MM-DD>/kontext.jsonld`
  po zveřejnění nemění; `draft/` je pracovní, měnitelná verze. Před produkcí se zmrazí datovaná verze
  a `context-url` se přepne na ni. Změna termínů = nová datovaná složka a nová `context-url`.
- **Jeden kontext** slouží oběma souborům; jejich termíny se nepřekrývají. IRI termínů jsou pod
  `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/`.
- **`@version` 1.1** je nutná: časové termíny používají vnořené kontexty, funkci JSON-LD 1.1, stejně
  jako OFN.

## Implementace

Balíček `com.dia.ismdtoolbackend.publication`:

| Třída | Úloha |
|---|---|
| `PublicationConfig` | `@ConfigurationProperties("ismd.publication")` s výchozími hodnotami výše |
| `snapshot.PublicationSnapshotReader` | Jedno čtení PostgreSQL do `PublicationSnapshot` |
| `snapshot.DraftFilter` | Pravidla `include-drafts=false` |
| `file.VocabularyFileWriter` | `slovniky.jsonld`: stažení OFN JSON-LD + metadata z PostgreSQL, odebrání odkazů na vynechané pojmy |
| `file.DiagramFileWriter` | `diagramy.jsonld` jen z PostgreSQL |
| `file.PublicationJson` | Všechny klíče na jednom místě, rámec dokumentu, záznam o publikaci, časové okamžiky |
| `BuiltFile` | Soubor zapsaný během běhu: `name`, `path`, `size`, `etag` |
| `PublicationService` | Jeden běh: zámek, snímek, filtr, zápis obou souborů do dočasného adresáře, archivace, úklid; vrací `PublicationRun`, nebo vyhodí `PublicationInProgressException` / `PublicationFailedException` |
| `PublicationRun` | Výsledek uloženého běhu s jeho `id`, který vrací `POST /run` |
| `PublicationAdminController` | `POST /run`, `GET /history`, `GET /{id}/{file}`; deleguje na `PublicationService` a `PublicationArchive` |
| `archive.PublicationEntity`, `archive.PublicationFileEntity` | Řádky `publication` a `publication_file` |
| `archive.PublicationRepository`, `archive.PublicationFileRepository` | Jejich repozitáře |
| `archive.PublicationArchive` | `save` (s uchováním), `history`, `file` |
| `archive.PublicationSummary`, `archive.PublicationFileSummaryRow` | Záznam historie a projekce souboru bez obsahu |

Mimo balíček: Liquibase `024-diagram-iri-id.yaml` + `DiagramEntity.iriId`; Liquibase
`025-create-publications.yaml`; `/api/admin/publikace/**` v `SecurityConfig`;
`PublicationInProgressException`, `PublicationFailedException`, `PublicationFilePrunedException`
v `exception`, které `GlobalExceptionHandler` mapuje na `PUBLICATION_*` v `ErrorCode`; kontext
v `pages/ismd-otevřená-data/draft/kontext.jsonld` a jeho nasazení v `.github/workflows/pages.yml`.
`OntologyDownloadService` a repozitáře se znovu používají beze změny; `VocabularyConstants.CONTEXT` už
ukazoval na OFN `2026-02-26`.

`PublicationFileEntity.content_gzip` je mapováno s `columnDefinition = "bytea"`: bez toho selže
validace schématu proti skutečnému PostgreSQL (`bytea` hlášeno jako `BINARY` vs. `VARBINARY`); H2 to
nezachytí.

**Testy:** `DiagramRepositoryTest` (IRI diagramu), `PublicationSnapshotReaderTest`, `DraftFilterTest`,
`PublicationFilesTest` (skutečný výstup `JsonExporter`, expanze přes oba skutečné kontexty, každý klíč je
definovaný termín, výchozí `context-url` odpovídá souboru v `pages/`), `PublicationServiceTest` (uložené
soubory a běh, odstranění dočasného sestavení, vypnuté rozpracované slovníky, selhání snímku,
zapisovače nebo archivu nic neuloží, chybějící `context-url`, probíhající běh),
`archive.PublicationArchivePostgresTest` (skutečný PostgreSQL přes Testcontainers: `bytea` tam a zpět,
uchování ponechá metadata a odebere obsah, nenalezeno), `PublicationAdminControllerTest`. Klíčová
pravidla ověřena záměrným rozbitím (mutace). Validace proti JSON Schema OFN se nedělá: v classpath není
validátor schémat.

## Zapnutí

Přepínač `enabled` neexistuje a nic se nepředává infrastruktuře. Před produkčním použitím:

1. Zapnout GitHub Pages v repozitáři (Source: GitHub Actions; nutná administrátorská práva
   k repozitáři) a ověřit, že URL pracovní verze kontextu vrací `application/ld+json`.
2. Zmrazit datovanou verzi kontextu (`pages/ismd-otevřená-data/<RRRR-MM-DD>/`) a nastavit na ni
   `context-url`.
3. Administrátor spustí `POST /api/admin/publikace/run` a stáhne oba soubory přes
   `GET /api/admin/publikace/{id}/{file}`.
4. Pipeline DIA LKOD je nahraje a spravuje katalogizační záznam.
