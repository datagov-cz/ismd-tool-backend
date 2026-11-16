# Publikace otevřených dat: slovníky a diagramy

> Stav: **implementováno, ve výchozím stavu vypnuto** (`ismd.publication.enabled=false`); zapnutí
> viz [Zapnutí](#zapnutí). Anglická verze: [`DATA_EXPORT.md`](./DATA_EXPORT.md).

## Jaký problém řešíme

Aplikace má jako otevřená data zpřístupnit rozpracované i publikované slovníky včetně jejich
metadat a data diagramů. Soubory se publikují v pravidelném intervalu, jsou ke stažení bez
přihlášení a jsou katalogizovány v NKOD přes lokální katalog DIA.

| Připomínka klienta | Jak ji návrh plní |
|---|---|
| Nesmí to být ZIP ani X souborů, ale pevný malý počet souborů | Právě **dva soubory**: `slovniky.jsonld` a `diagramy.jsonld` |
| `manifest.json` nebude; jeho obecné údaje (`complete`, `exportedAt`, …) i údaje o slovnících a diagramech (`createdAt`, `isPublished`, …) patří do souborů | Každý soubor nese uzel **záznamu o publikaci** (obecné údaje); metadata slovníků a diagramů jsou přímo na jejich uzlech |
| Místo souboru na každý slovník a diagram jeden soubor pro všechny slovníky a jeden pro všechny diagramy | `@graph` s jedním uzlem na slovník, resp. diagram |
| `concepts.json` zaniká, jeho obsah se propíše do souboru slovníků | Metadata pojmů z PostgreSQL se doplní přímo k pojmům v `pojmy` |
| Atributy odkazující na soubory zanikají | Žádné `file`, `concepts`, `rdf.file`; vazby jsou jen přes IRI |
| Vše ve formátu `.jsonld` s `@context` slovníku popisujícího tato OD | Dva kontexty: OFN Slovníky a kontext ISMD OD (viz [Kontexty](#kontexty)) |
| Metadata datové sady nemusí generovat ISMD, stačí je jednou vytvořit ručně | Katalogizační záznam se jednou ručně uloží do repozitáře LKOD DIA (viz [Katalogizace](#katalogizace)) |

## Proč nový endpoint

Data jsou rozdělena mezi dvě úložiště a žádné ze stávajících rozhraní je nepokrývá.

| Možnost | Proč nestačí |
|---|---|
| **Fuseki / SPARQL napřímo** | Diagramy a metadata slovníků (slug, stav publikace, časové značky) existují jen v PostgreSQL. Služba Fuseki navíc vedle `query` vystavuje i `update`, `upload` a `gsp-rw`, takže ji nelze v současné podobě otevřít konzumentovi. |
| **Stávající REST endpointy** | Konzument by potřeboval jedno volání pro seznam slovníků, jedno stažení na každý slovník, jedno volání pro seznam diagramů a jedno volání detailu na každý diagram. Stažení slovníku je při zapnutém omezení odmítnuto pro slovníky s validačními chybami, model seznamu nese pole o uživateli a komentářích a detail diagramu je „tlusté“ čtení, které obsahuje i rozpracované změny. |

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
| `ismd.publication.include-drafts` | `true` | `false` vynechá rozpracované slovníky, rozpracované pojmy publikovaných slovníků a diagramy vynechaných slovníků (viz [Rozpracované slovníky](#rozpracované-slovníky)) |
| `ismd.publication.interval` | `P30D` | Jak stará smí být poslední publikace, než se sestaví nová (ISO-8601 `Duration`) |
| `ismd.publication.check-cron` | `0 0 4 * * *` | Kdy plánovač kontroluje, zda je publikace starší než `interval` |
| `ismd.publication.directory` | `${java.io.tmpdir}/ismd-publication` | Adresář s publikovanými soubory (viz [Úložiště](#úložiště)) |
| `ismd.publication.iri-base` | `https://data.dia.gov.cz/zdroj/ismd/` | Základ IRI, která ISMD vytváří: `<základ>diagramy/<iri_id>` a `<základ>publikace/<soubor>` (viz [IRI diagramu](#iri-diagramu)) |
| `ismd.publication.context-url` | — (doplní se podle odpovědi LKOD DIA) | Druhý kontext v `@context` obou souborů; musí být `https://`. Bez něj běh nic nepublikuje a zaloguje chybu. |
| `ismd.publication.enabled` | `false` | Plánovač a veřejné endpointy; zapnout až URL kontextu funguje |

### Proč `interval` + `check-cron`, a ne samotný cron

Cron neumí vyjádřit „každých 30 dní“ (nejbližší je „1. den v měsíci“) a `fixedRate` se restartem
aplikace vynuluje. Plánovač proto běží denně podle `check-cron` (stejný vzor jako
`reconciler.cron` a `outbox.prune-cron`) a novou publikaci sestaví jen tehdy, když od
`publikováno` poslední publikace uplynul `interval`. Kotvou je `publikováno` uložené na disku
(viz [Úložiště](#úložiště)), takže restart periodu neposune.

- **Start aplikace:** pokud v `directory` publikace chybí (nový kontejner, prázdný disk), sestaví se
  na pozadí hned; do té doby odpovídají endpointy `503` s `Retry-After`.
- **Vlastní vlákno:** obě kontroly (při startu i podle `check-cron`) běží na `publicationExecutor`
  (jedno vlákno, bez fronty, další spuštění se zahodí), takže běh nedrží sdílené vlákno plánovače.
- **Zaseknutý běh:** běží vždy nejvýše jedno sestavení (`ReentrantLock` stačí, backend běží
  v jedné instanci). Nezdařené sestavení ponechá předchozí publikaci a zopakuje se při příští kontrole.

## Endpointy

```
GET|HEAD /api/publikace/slovniky.jsonld
GET|HEAD /api/publikace/diagramy.jsonld
```

Každý soubor je samostatná distribuce v NKOD. Endpointy jen servírují poslední publikované soubory
z disku; nikdy nic nesestavují.

| Aspekt | Hodnota |
|---|---|
| Autorizace | Veřejné. `SecurityConfig` otevírá **přesně tyto dvě cesty** (žádné `/api/publikace/*`), aby nový endpoint pod `/api/publikace` nebyl veřejný omylem; ostatní cesty odmítne `denyAll()`. |
| `200` | `application/ld+json;charset=UTF-8`, `Content-Disposition: attachment; filename="slovniky.jsonld"` (nebo `diagramy.jsonld`), `Content-Length`, `ETag`, `Last-Modified`, `Cache-Control: no-cache, public` |
| `304` | `If-None-Match` / `If-Modified-Since` odpovídá aktuální publikaci |
| `503` + `Retry-After: 600` | Publikace je zapnutá, ale ještě žádná neproběhla |
| `404` | Publikace je vypnutá (`enabled=false`), nebo jde o jiný název souboru (např. `current.json`) |
| `HEAD` | Mapováno explicitně; vrací jen hlavičky ze záznamu v paměti a soubor vůbec neotevře. NKOD vyžaduje odpověď do 3 s, ta je tím splněna s rezervou. |
| CORS | `Access-Control-Allow-Origin: *`, metody `GET`/`HEAD`/`OPTIONS`, bez credentials, jen pro tyto dvě cesty; ostatní cesty mají allowlist profilu |

- **ETag** = SHA-256 obsahu souboru v uvozovkách. **Last-Modified** = `publikováno`.
- **Proč CORS `*`:** sklizeň do NKOD čte LKOD DIA, ne ISMD, a serverovým klientům je CORS lhostejný.
  `*` slouží kontrole kvality CORS, kterou NKOD dělá na URL distribucí, a konzumentům v prohlížeči.
  Nová rizika nepřináší: data jsou veřejná, stejná pro všechny, bez credentials a jen ke čtení;
  odposlech řeší HTTPS na ingressu.

### Úložiště

Každý běh zapisuje do vlastního adresáře `<directory>/<epoch-millis>/`. Aktuální je ten, který jmenuje
`<directory>/current.json` (velikost a ETag každého souboru, `publikováno`); soubor se nahrazuje
atomicky až po úspěšném zápisu obou souborů. Klient tak nikdy nedostane napůl sestavený soubor ani
dvojici souborů z různých běhů. Starší adresáře se po úspěšném běhu smažou; požadavek, který starý
soubor právě streamuje, si drží otevřený popisovač. Při startu se `current.json` načte a velikosti
souborů ověří; neúplná publikace se zahodí a sestaví znovu.

Bez trvalého svazku se publikace po každém nasazení sestaví znovu a 30denní perioda začne znovu.

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
  a diagramy. Je uveden jako druhý, takže OFN termíny nepřepisuje. Hostuje se ve vyhrazeném
  repozitáři LKOD DIA (viz [Hostování kontextu](#hostování-kontextu)).

### Konvence pojmenování

Převzaty z norem v [`datagov-cz/otevrene-formalni-normy`](https://github.com/datagov-cz/otevrene-formalni-normy)
(DCAT-AP-CZ, OFN Slovníky) a z [`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia):

| Prvek | Konvence | Příklad v normách | Návrh pro ISMD |
|---|---|---|---|
| Vlastnost (klíč JSON) | česky, malá písmena, slova spojená `_` | `periodicita_aktualizace`, `klíčové_slovo`, `je_součástí`, `datum_a_čas` | `je_publikován`, `stav_poslední_validace`, `počet_pojmů` |
| Typ (hodnota `typ`) | česky, velké počáteční písmeno, mezery | `Datová sada`, `Časový okamžik`, `Typ objektu práva` | `Diagram`, `Uzel diagramu`, `Publikace ISMD` |
| `iri` / `typ` | aliasy `@id` / `@type` | všechny kontexty | beze změny |
| Vícejazyčný text | `@container: @language` | `název`, `popis` | jen kde jde o text pro lidi |
| Odkaz na zdroj | `@type: @id` | `poskytovatel`, `téma` | `slovník`, `pojem`, `seskupen_pod` |
| Umístění kontextu | `<norma>/<RRRR-MM-DD>/<profil>/kontext.jsonld`, pracovní verze v `draft/` | `dcat-ap-cz-hvd/2026-09-23/datová-sada/kontext.jsonld` | `ismd-otevřená-data/<datum>/slovníky/kontext.jsonld`, `…/diagramy/kontext.jsonld`, nebo jeden `…/kontext.jsonld` |
| IRI zdrojů DIA | `https://data.dia.gov.cz/zdroj/<oblast>/…` | `https://data.dia.gov.cz/zdroj/datové-sady/nkod/nkod-kompletní` | `https://data.dia.gov.cz/zdroj/ismd/diagramy/<uuid>` |
| IRI termínů slovníku | slovník.gov.cz `…/<slovník>/pojem/<termín>` (OFN), data.gov.cz `slovník/<název>/<termín>` (DCAT-AP-CZ) | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/okamžik-vytvoření` | `https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/<termín>` |

Termíny IRI slovníku používají spojovník (`okamžik-vytvoření`), klíče v kontextu podtržítko
(`datum_a_čas`). **Pozor na nesoulad:** OFN Slovníky používá u polí pojmů spojovník
(`alternativní-název`, `nadřazený-pojem`), DCAT-AP-CZ podtržítko. Pojem v `slovniky.jsonld` tak
ponese obě podoby vedle sebe (`alternativní-název` z OFN, `je_publikován` z ISMD). Návrh drží
DCAT-AP-CZ podle zadání; pokud má mít přednost jednotnost uvnitř pojmu, stačí v kontextu ISMD OD
přejmenovat klíče, IRI termínů se nemění. Všechny klíče jsou v jedné třídě (`PublicationJson`).

Slovník popisující tato OD je sám OFN slovník (třídy `Diagram`, `Uzel diagramu`, vlastnosti
`je_publikován`, …), takže jej lze vytvořit přímo v ISMD a kontext z něj vygenerovat. Názvy termínů
níže jsou předběžné; finální názvy určí ten slovník.

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

Spouští ji plánovač (viz [Konfigurace](#konfigurace)); běh sestaví oba soubory.

1. **Jedno čtení z PostgreSQL.** Zaznamená se `publikováno` a `PublicationSnapshotReader` načte
   v jediné transakci pouze pro čtení (jeden dotaz na tabulku) slovníky s pojmy a diagramy s uzly
   a hranami do `PublicationSnapshot`. Ten nemá pole pro uživatele, komentáře ani rozpracované změny
   diagramů, takže vyloučená data nemohou uniknout přes sdílený model. Při `include-drafts=false`
   na snímek použije pravidla výše `DraftFilter`.
2. **`slovniky.jsonld` slovník po slovníku.** Pro každý slovník se zavolá
   `OntologyDownloadService.downloadOntology(id, "json-ld")`, doplní se metadata z PostgreSQL a uzel
   se zapíše streamovacím zapisovačem JSON do adresáře běhu. V paměti je vždy jen jeden slovník.
3. **`diagramy.jsonld`** se sestaví jen z PostgreSQL.
4. **Záznam o publikaci** se zapíše do obou souborů jako poslední uzel (pořadí uzlů v `@graph` nemá
   význam), spočítá se ETag a běh se potvrdí přepsáním `current.json`.

Návrhové body plynoucí ze stávajícího kódu:

- **Validace publikaci neblokuje.** Omezení stahování slovníků s validačními chybami je
  v `OntologyController`, nikoli ve službě, takže přímé volání služby zahrne každý slovník.
- **Vyčištěný obsah.** Služba používá stejné filtrování a transformaci OFN jako veřejné stažení,
  takže publikace a stažení jednotlivého slovníku se shodují (až na filtr rozpracovaných pojmů).
- **Selhání se hlásí po slovnících** (`stav_obsahu = FAILED`, `úplná = false`). Pokud selže běh
  jako celek, zůstanou publikované předchozí soubory.
- **Prázdný slovník není selhání.** Slovník bez RDF obsahu se publikuje se svými metadaty
  a `stav_obsahu = EMPTY`.
- **Zátěž Fuseki.** Grafy se načítají jeden po druhém přes stávající semafor Fuseki a běh je ve
  `check-cron` (výchozí 04:00, po rekonciliátoru ve 03:00 a pročištění outboxu ve 03:30), takže
  publikace nezahltí interaktivní požadavky. Veřejné URL nic nesestavují, takže je nelze zneužít
  k zátěži.

## Konzistence

PostgreSQL a TDB2 nesdílejí transakci (viz
[`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Strana PostgreSQL je jeden
konzistentní snímek; RDF každého slovníku se čte krátce poté. Úprava provedená během běhu se proto
může objevit v RDF, ale ne v metadatech. `publikováno` označuje snímek PostgreSQL. Při periodicitě
30 dní a běhu v noci je to přijatelné. Mezi publikacemi soubory úmyslně nesledují změny; aktuální
stav dávají stávající REST endpointy.

## Katalogizace

Metadata datové sady ISMD negeneruje. Vytvoří se jednou ručně formulářem NKOD a uloží do
[`datagov-cz/lkod-dia`](https://github.com/datagov-cz/lkod-dia) (např. `ismd/slovníky-a-diagramy.jsonld`),
odkud je LinkedPipes ETL harvestuje do NKOD:

- IRI datové sady `https://data.dia.gov.cz/zdroj/datové-sady/ismd/slovníky-a-diagramy`, poskytovatel
  `https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921` (DIA).
- Dvě distribuce (`…/distribuce/0` slovníky, `…/distribuce/1` diagramy) s `přístupové_url`
  a `soubor_ke_stažení` na veřejné URL endpointů, formát `JSON_LD`, typ média `application/ld+json`
  a `schéma` = URL kontextu ISMD OD (resp. JSON Schema, pokud vznikne). Veřejná URL obsahuje
  kontextovou cestu aplikace (`server.servlet.context-path`, výchozí `/popisujeme`):
  `https://<host>/popisujeme/api/publikace/slovniky.jsonld`.
- `periodicita_aktualizace` = `http://publications.europa.eu/resource/authority/frequency/MONTHLY`.
  **Změna `ismd.publication.interval` vyžaduje ruční úpravu záznamu**, jinak katalog lže.
- Pokud `include-drafts = true`, má to popis datové sady výslovně uvést.

## Hostování kontextu

Kontext ISMD OD (a případně slovník, ze kterého vznikl) se hostuje ve vyhrazeném repozitáři LKOD DIA.
Návrh kontextu je v tomto repozitáři jako testovací prostředek
`src/test/resources/com/dia/publication/ismd-od-kontext.jsonld`; IRI termínů jsou pod předběžným
`https://slovník.gov.cz/generický/ismd-otevřená-data/pojem/`. Testy přes něj a přes kontext OFN
expandují oba soubory. Před zapnutím je třeba ověřit (otázky odeslány správcům `lkod-dia`):

- **Repozitář a URL.** `lkod-dia` dnes obsahuje jen katalogizační záznamy a každý `.jsonld` v něm
  zpracovává ETL jako záznam. Kontext tam může skončit jako chybný záznam; buď ho ETL musí vynechat
  (samostatná složka), nebo se použije samostatný repozitář. Stálé veřejné URL kontextu určuje
  `ismd.publication.context-url`.
- **Hlavičky.** Procesory JSON-LD načítají vzdálený kontext za běhu; URL musí vracet
  `application/ld+json` (nebo `application/json`) a `Access-Control-Allow-Origin: *`.
  `raw.githubusercontent.com` vrací `text/plain`, GitHub Pages vyhovuje.
- **Verzování.** Podle vzoru OFN se publikované verze nemění (`<RRRR-MM-DD>/…`); změna termínů =
  nová datovaná verze a změna `context-url`, pracovní verze v `draft/`.
- **`@version` 1.1** je nutná: časové termíny používají vnořené kontexty, funkci JSON-LD 1.1, stejně
  jako OFN.

## Otevřená rozhodnutí

| # | Rozhodnutí | Návrh |
|---|---|---|
| 1 | Výchozí hodnota `include-drafts` | `true` podle zadání („rozpracované i publikované slovníky“); pro veřejná data je bezpečnější `false` |
| 2 | Přesné umístění a URL kontextu v LKOD DIA (viz [Hostování kontextu](#hostování-kontextu)) | Složka mimo zpracování ETL, servírovaná přes GitHub Pages nebo data.dia.gov.cz |
| 3 | Jeden kontext ISMD OD pro oba soubory, nebo dva profily (`…/slovníky/`, `…/diagramy/`) | Jeden; termíny se nepřekrývají |
| 4 | Podtržítka (DCAT-AP-CZ) vs. spojovníky (pole pojmů OFN Slovníky) v klíčích ISMD | Podtržítka podle zadání |
| 5 | Základ IRI (`iri-base`) | `https://data.dia.gov.cz/zdroj/ismd/` |
| 6 | Trvalé úložiště `ismd.publication.directory` | Bez trvalého svazku se publikace po každém nasazení sestaví znovu při startu a perioda začne znovu; to stačí. Svazek je případně předávka pro infrastrukturu. |

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
| `PublicationService` | Jeden běh: snímek, filtr, zápis obou souborů, potvrzení; zámek proti souběhu |
| `PublicationStore` | Adresáře běhů, `current.json`, načtení po restartu |
| `PublicationScheduler` | Kontrola při startu a podle `check-cron`, obě na `publicationExecutor` |
| `PublicationController` | `GET`/`HEAD` obou souborů |

Mimo balíček: Liquibase `024-diagram-iri-id.yaml` + `DiagramEntity.iriId`; `AsyncConfig.publicationExecutor`;
přesné cesty v `SecurityConfig`; pravidlo CORS v `CorsConfig` a `CorsConfigUtil`. `OntologyDownloadService`
a repozitáře se znovu používají beze změny; `VocabularyConstants.CONTEXT` už ukazoval na OFN `2026-02-26`.

**Testy:** `DiagramRepositoryTest` (IRI diagramu), `PublicationSnapshotReaderTest`, `DraftFilterTest`,
`PublicationFilesTest` (skutečný výstup `JsonExporter`, expanze přes oba skutečné kontexty, každý klíč je
definovaný termín), `PublicationServiceTest`, `PublicationSchedulerTest`, `PublicationSchedulerAsyncTest`,
`PublicationControllerTest`, `SecurityFilterChainIntegrationTest` (veřejné cesty, CORS, jiné cesty pod
`/api/publikace` odpoví `401`). Klíčová pravidla ověřena záměrným rozbitím (mutace). Validace proti
JSON Schema OFN se nedělá: v classpath není validátor schémat.

## Zapnutí

Publikace je vypnutá, dokud nejsou splněny tyto kroky:

1. Odpovědi správců `lkod-dia` → finální kontext, `context-url`, IRI termínů a podoba klíčů
   (konstanty v `PublicationJson`).
2. Zveřejnit kontext a vytvořit katalogizační záznam (URL distribucí
   `https://<host>/popisujeme/api/publikace/*.jsonld`).
3. Nastavit `ismd.publication.enabled=true` a `https://` `context-url` pro každé prostředí (předávka
   infrastruktuře); volitelně trvalý svazek pro `directory`.
