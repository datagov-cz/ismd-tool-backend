# Export dat: slovníky a diagramy

> Stav: **návrh, neimplementováno.** Pouze návrh na vysoké úrovni. Anglická verze:
> [`DATA_EXPORT.md`](./DATA_EXPORT.md).

## Jaký problém řešíme

Aplikace má jako otevřená data zpřístupnit data diagramů a rozpracované i publikované slovníky
včetně jejich metadat. Soubory jsou k dispozici ke stažení a mohou být katalogizovány v NKOD.

Export tvoří **dva soubory JSON-LD**: jeden se všemi slovníky a jejich pojmy, druhý se všemi
diagramy. Každý soubor popisuje sám sebe (čas exportu, úplnost, metadata slovníků), takže manifest
není. Protože jde o JSON-LD s pevným `@context`, metadata datové sady pro NKOD nemusí generovat
ISMD; vytvoří se jednou ručně.

## Proč nový endpoint

Data jsou rozdělena mezi dvě úložiště a žádné ze stávajících rozhraní je nepokrývá.

| Možnost | Proč nestačí |
|---|---|
| **Fuseki / SPARQL napřímo** | Diagramy a metadata slovníků (slug, stav publikace, časové značky) existují jen v PostgreSQL. Služba Fuseki navíc vedle `query` vystavuje i `update`, `upload` a `gsp-rw`, takže ji nelze v současné podobě otevřít konzumentovi. |
| **Stávající REST endpointy** | Konzument by potřeboval jedno volání pro seznam slovníků, jedno stažení na každý slovník, jedno volání pro seznam diagramů a jedno volání detailu na každý diagram. Stažení slovníku je při zapnutém omezení odmítnuto pro slovníky s validačními chybami, model seznamu nese pole o uživateli a komentářích a detail diagramu je „tlusté“ čtení, které obsahuje i rozpracované změny. |

Export znovu používá stávající stavební kameny (`OntologyDownloadService`, repozitáře diagramů)
za jedním controllerem, který pravidla exportu uplatňuje na jednom místě.

## Rozsah

| Zahrnuto | Vyloučeno |
|---|---|
| Všechny slovníky, rozpracované i publikované, bez ohledu na stav validace (viz [Otevřená rozhodnutí](#otevřená-rozhodnutí)) | Komentáře |
| Metadata slovníků z PostgreSQL | Jakékoli informace o uživatelích, včetně id vlastníka |
| Obsah slovníku jako vyčištěný OFN JSON-LD (stejný výstup jako stažení slovníku v JSON-LD) | Rozpracované změny diagramů (`diagram_pending_edits`) |
| Metadata pojmů z PostgreSQL, doplněná přímo k pojmům | Validační reporty |
| Všechny diagramy s uloženým rozložením | Outbox, rekonciliátor a další provozní data |
| | Data z NKD, NKOD, RPP a e-Sbírky |

## Endpointy

```
GET|HEAD|OPTIONS /api/export/slovniky.jsonld
GET|HEAD|OPTIONS /api/export/diagramy.jsonld
```

Každý soubor je samostatná distribuce v NKOD.

| Aspekt | Hodnota |
|---|---|
| Autorizace | Veřejné (matchery `permitAll()` pro obě cesty v `SecurityConfig`), protože distribuce v NKOD musí jít stáhnout bez přihlášení. Závisí na otevřeném rozhodnutí 1. |
| Odpověď | `application/ld+json; charset=utf-8`, `Content-Disposition: attachment; filename="slovniky.jsonld"` (nebo `diagramy.jsonld`), `ETag`, `Last-Modified` |
| `GET` | Vrací soubor z cache. Pokud se data od sestavení souboru změnila, soubor se nejprve sestaví znovu; to může trvat několik sekund. Podporuje `If-None-Match` / `If-Modified-Since` → `304`. |
| `HEAD` | **Musí odpovědět do 3 s a soubor nikdy nesestavuje.** Viz [HEAD a OPTIONS](#head-a-options). |
| `OPTIONS` | Odpovídá Spring (`Allow`, CORS preflight), aniž by se požadavek dostal do controlleru. |
| Souběh | Single-flight pro každý soubor: souběžné `GET` během sestavování čekají na totéž sestavení. Žádné `409`. |

### HEAD a OPTIONS

Spring ve výchozím stavu odpovídá na `HEAD` tak, že spustí handler pro `GET` a tělo zahodí, takže
`HEAD` by byl stejně pomalý jako celé sestavení. Controller proto mapuje `HEAD` explicitně:

1. Spočítá aktuální **otisk** zdrojových dat souboru jedním dotazem do PostgreSQL (viz
   [Cache](#cache)). Trvá to milisekundy a nesahá to na Fuseki.
2. Pokud soubor v cache otisku odpovídá, vrátí jeho `Content-Type`, `Content-Length`, `ETag`
   a `Last-Modified`.
3. Jinak vrátí `Content-Type` a `ETag` (nový otisk) bez `Content-Length` a spustí sestavení na
   pozadí, aby následující `GET` byl rychlý.

`OPTIONS` nepotřebuje kód, jen test, že odpoví bez volání sestavení.

### Cache

Backend běží v jedné instanci, takže cache je soubor na lokálním disku a v paměti záznam pro každý
exportní soubor: otisk, čas sestavení, velikost.

- **Otisk:** hash přes `(id, updated_at)` všech řádků, ze kterých soubor vzniká (slovníky a pojmy
  pro `slovniky.jsonld`; diagramy pro `diagramy.jsonld` a k tomu IRI slovníků, na které diagramy
  odkazují). Hash přes celý seznam id, nejen `max(updated_at)`, zachytí i smazání. Úpravy jen v RDF
  posouvají `updated_at` přes `MetadataTouchService`, takže jsou zachyceny také.
- **ETag** = otisk. **Last-Modified** = čas sestavení souboru v cache.
- **Sestavení:** zapisuje se do dočasného souboru, který se na místo přesune až kompletní, takže
  klient nikdy nedostane napůl sestavený soubor a stav HTTP odpovídá skutečnému výsledku.
- **Start:** po restartu je cache prázdná; při startu se spustí sestavení na pozadí.

## Formát souborů

Oba soubory jsou JSON-LD se dvěma kontexty:

```json
"@context": [
  "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld",
  "<URL kontextu exportu ISMD>"
]
```

- **OFN Slovníky** (publikovaná verze 2026-02-26) definuje obsah slovníků a pojmů: `iri`, `typ`,
  `název`, `popis`, `vytvořeno`, `aktualizováno`, `pojmy` a všechna pole pojmů. Stávající stažení
  v JSON-LD tento kontext už používá přes URL `draft2`; oba soubory jsou totožné a export použije
  URL s datem.
- **Kontext exportu ISMD** definuje vše, co OFN nepokrývá: metadata z PostgreSQL, záznam o exportu
  a diagramy. Jeho termíny pocházejí ze slovníku popisujícího tuto datovou sadu, který se jednou
  vytvoří ručně (viz otevřené rozhodnutí 2). Názvy termínů níže jsou předběžné; finální názvy určí
  ten slovník.

Každý soubor je pole `@graph` na nejvyšší úrovni: jeden uzel na slovník (nebo diagram) a jeden uzel
**záznamu o exportu**. JSON Schema OFN popisuje jeden slovník, takže validuje jednotlivé uzly
slovníků, ne celý soubor.

Časové značky mají tvar OFN `{"typ": "Časový okamžik", "datum_a_čas": "…"}` s explicitním posunem.
PostgreSQL ukládá `createdAt` / `updatedAt` bez časové zóny; export doplní `Europe/Prague`.

**Neexportují se žádné databázové identifikátory.** Slovníky a pojmy jsou identifikovány přes IRI,
diagram vlastním IRI (viz [IRI diagramu](#iri-diagramu)) a uzel diagramu přes IRI pojmu.

### Záznam o exportu

Je v obou souborech; nahrazuje pole manifestu na nejvyšší úrovni.

```json
{
  "typ": "Export ISMD",
  "exportováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-10-07T11:30:00+02:00" },
  "úplný": true
}
```

- `exportováno` označuje snímek PostgreSQL (viz [Konzistence](#konzistence)).
- `úplný` je `false`, pokud má alespoň jeden slovník `stav-obsahu = FAILED`. V souboru diagramů je
  vždy `true`.

### `slovniky.jsonld`

```json
{
  "@context": [ "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld", "<ISMD>" ],
  "@graph": [
    {
      "iri": "https://slovník.gov.cz/agendový/moje-agenda",
      "typ": ["Slovník"],
      "název": { "cs": "Moje agenda" },
      "vytvořeno": { "typ": "Časový okamžik", "datum_a_čas": "2026-03-02T10:15:00+01:00" },
      "aktualizováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:11+02:00" },
      "slug": "moje-agenda",
      "je-publikován": false,
      "stav-poslední-validace": "VALIDATED",
      "okamžik-poslední-validace": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:15+02:00" },
      "stav-obsahu": "OK",
      "počet-pojmů": 42,
      "pojmy": [
        {
          "iri": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
          "typ": ["Pojem", "Třída"],
          "název": { "cs": "Žadatel" },
          "slug": "zadatel",
          "je-publikován": false,
          "je-v-tezauru": false,
          "vytvořeno": { "typ": "Časový okamžik", "datum_a_čas": "2026-03-02T10:20:00+01:00" },
          "aktualizováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-30T14:02:11+02:00" }
        }
      ]
    },
    { "typ": "Export ISMD", "exportováno": { "…": "…" }, "úplný": true }
  ]
}
```

- Uzel slovníku je stávající výstup stažení v OFN JSON-LD (obsahová pole, `pojmy` a vše v nich)
  doplněný o metadata z PostgreSQL. Každý pojem v `pojmy` dostane podle IRI svá pole z PostgreSQL.
  Dřívější `concepts.json` zaniká.
- Typ pojmu z PostgreSQL (`TRIDA`, …) se neopakuje; nese ho už OFN `typ`.
- `iri` je název grafu slovníku.
- `stav-obsahu` je `OK`, `EMPTY` (slovník zatím nemá žádný RDF obsah) nebo `FAILED` (načtení nebo
  transformace selhaly). Při `EMPTY` a `FAILED` nese uzel jen metadata z PostgreSQL; `počet-pojmů`
  i tak říká, kolik pojmů PostgreSQL zná.
- `stav-poslední-validace` je `VALIDATED`, `SKIPPED_UNAVAILABLE` nebo `FAILED`. Říká, zda poslední
  běh validace doběhl, nikoli zda našel chyby; nálezy jsou ve validačním reportu, který se
  neexportuje.

### `diagramy.jsonld`

**Uložené rozložení**, nikoli projektovaný čtecí model, který dostává frontend.

```json
{
  "@context": [ "https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld", "<ISMD>" ],
  "@graph": [
    {
      "iri": "<základ IRI diagramů>/3f2c9a1e-5b7d-4c0a-9e8f-1a2b3c4d5e6f",
      "typ": "Diagram",
      "název": "Přehled",
      "slovník": "https://slovník.gov.cz/agendový/moje-agenda",
      "vytvořeno": { "typ": "Časový okamžik", "datum_a_čas": "2026-04-11T08:00:00+02:00" },
      "aktualizováno": { "typ": "Časový okamžik", "datum_a_čas": "2026-09-12T16:40:00+02:00" },
      "výřez": { "x": 0.0, "y": 0.0, "přiblížení": 1.0 },
      "uzly": [
        {
          "pojem": "https://slovník.gov.cz/agendový/moje-agenda/pojem/žadatel",
          "pozice": { "x": 120.0, "y": 80.0 },
          "sbalený": false,
          "seskupen-pod": null,
          "cizí": false,
          "zobrazené-vlastnosti": [
            "https://slovník.gov.cz/agendový/moje-agenda/pojem/jméno-žadatele"
          ]
        }
      ],
      "hrany": [
        {
          "klíč-hrany": "https://slovník.gov.cz/agendový/moje-agenda/pojem/podává",
          "body-zlomu": [ { "x": 200.0, "y": 140.0 } ]
        }
      ]
    },
    { "typ": "Export ISMD", "exportováno": { "…": "…" }, "úplný": true }
  ]
}
```

| Pole | Dříve | Poznámka |
|---|---|---|
| `slovník` | `ontologyIri` | Odkaz přes IRI (`@type: @id`) na uzel slovníku v `slovniky.jsonld` |
| `pojem` | `conceptIri` | Odkaz přes IRI |
| `seskupen-pod` | `parentConceptIri` | Odkaz přes IRI na pojem uzlu, pod kterým je tento uzel seskupen, nebo `null` |
| `cizí` | `foreign` | Uzel odkazuje na pojem z jiného slovníku |
| `zobrazené-vlastnosti` | `visibleProperties` | Odkazy přes IRI |
| `klíč-hrany` | `edgeKey` | Řetězec, ne odkaz přes IRI: u hran hierarchie jde o klíč sestavený frontendem |
| `body-zlomu` | `segments` | |
| `výřez` | `viewport` | |

Diagram ukládá jen to, co uživatel uspořádal: které pojmy jsou na plátně, kde a jak jsou vedeny
hrany. Které hrany existují a všechny popisky se při čtení odvozují z RDF. Export toto rozdělení
zachovává: soubor diagramů odkazuje na pojmy přes IRI a jejich obsah dodává `slovniky.jsonld`.
Pojem je v diagramu jako uzel nejvýše jednou. Uzly a hrany nemají vlastní IRI (v RDF jsou to
prázdné uzly).

Id řádků, verze pro optimistický zámek (`version`) a interní evidenční sloupce hran se neexportují.

### IRI diagramu

Diagram je dnes identifikován slovníkem a názvem. Ani jedno není stálé: diagram lze přejmenovat
a přejmenování slovníku mění jeho IRI. Export proto dává každému diagramu vlastní IRI:

- Nový sloupec `diagrams.iri_id` (`UUID`, `NOT NULL`, unikátní), nastavený při vytvoření diagramu.
  Liquibase `024` sloupec přidá, existující řádky doplní přes `gen_random_uuid()` a poté přidá
  omezení. Rollback sloupec odebere.
- IRI = `<základ IRI diagramů>/<iri_id>`. Základ je konfigurace (`ismd.export.diagram-iri-base`);
  jeho hodnota závisí na otevřeném rozhodnutí 2.
- IRI přežije přejmenování diagramu i přejmenování nebo přesun jeho slovníku. Po smazání diagramu se
  znovu nepoužije.

## Jak se export sestavuje

`slovniky.jsonld`:

1. **Jedno čtení z PostgreSQL.** V jediné transakci pouze pro čtení se načtou všechny slovníky
   s pojmy do exportních DTO. Tato DTO nemají pole pro uživatele ani komentáře, takže vyloučená
   data nemohou uniknout přes sdílený model. Zaznamená se otisk a `exportováno`.
2. **Zápis slovník po slovníku.** Pro každý slovník se zavolá
   `OntologyDownloadService.downloadOntology(id, "json-ld")`, doplní se metadata z PostgreSQL a uzel
   se zapíše do dočasného souboru streamovacím zapisovačem JSON. V paměti je vždy jen jeden slovník.
3. **Záznam o exportu se zapíše jako poslední** (pořadí uzlů v `@graph` nemá význam) a soubor se
   přesune na místo.

`diagramy.jsonld` čte jen PostgreSQL (diagramy s uzly a hranami, IRI slovníků) a je rychlý.

Návrhové body plynoucí ze stávajícího kódu:

- **Validace export neblokuje.** Omezení stahování slovníků s validačními chybami je
  v `OntologyController`, nikoli ve službě, takže přímé volání služby zahrne každý rozpracovaný
  slovník.
- **Vyčištěný obsah.** Služba používá stejné filtrování a transformaci OFN jako veřejné stažení,
  takže export a stažení jednotlivého slovníku se vždy shodují.
- **Selhání se hlásí po slovnících** (`stav-obsahu = FAILED`, `úplný = false`). Pokud selže
  sestavení jako celek, zůstane v cache předchozí soubor a požadavek dostane `503`.
- **Prázdný slovník není selhání.** Rozpracovaný slovník bez RDF obsahu se exportuje se svými
  metadaty a `stav-obsahu = EMPTY`.
- **Zátěž Fuseki.** Grafy se načítají jeden po druhém a procházejí stávajícím semaforem Fuseki,
  takže export nemůže zaplnit interaktivní požadavky. Díky cache nelze veřejnou URL zneužít
  k opakovanému plnému sestavování: sestavení proběhne jen tehdy, když se data změnila.

## Konzistence

PostgreSQL a TDB2 nesdílejí transakci (viz
[`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Strana PostgreSQL je v exportu jeden
konzistentní snímek; RDF každého slovníku se čte krátce poté. Úprava provedená během běžícího
exportu se proto může objevit v RDF, ale ne v metadatech. `exportováno` označuje snímek PostgreSQL.
Taková úprava změní otisk, takže další požadavek soubor sestaví znovu.

## Otevřená rozhodnutí

Předáno klientovi.

| # | Rozhodnutí | Dopad na návrh |
|---|---|---|
| 1 | **Přístup a rozpracované slovníky**: jsou oba soubory veřejné včetně rozpracovaných slovníků, nebo se rozpracované vynechají? | Návrh počítá s veřejnými soubory včetně rozpracovaných. Jen publikované: jeden filtr při čtení z PostgreSQL; u pojmů publikovaného slovníku rozlišení rozpracovaný/publikovaný dává doplněné `je-publikován`. Pokud soubory nebudou veřejné, nemohou být distribucí v NKOD. |
| 2 | **Umístění slovníku a kontextu exportu ISMD**: kde bude slovník popisující tuto datovou sadu, odkud se bude servírovat jeho `@context` a jaký bude základ IRI diagramů | Kontext musí být na stálé, veřejné, statické URL. Možnosti: statický prostředek v tomto backendu, nebo repozitář publikovaný obdobně jako OFN. Základ IRI diagramů plyne ze stejné volby. |

Katalogizační záznamy obou distribucí v NKOD se vytvoří jednou ručně a nejsou součástí tohoto
návrhu.

## Nastínění implementace

| Část | Poznámka |
|---|---|
| `ExportController` (`/api/export/*.jsonld`) | Explicitní mapování `GET` a `HEAD` pro každý soubor |
| `OntologyExportBuilder`, `DiagramExportBuilder` | Čtou PostgreSQL, zapisují soubor JSON-LD |
| `ExportCache` | Dotaz na otisk, dočasný soubor + atomický přesun, single-flight sestavení, sestavení na pozadí při startu a při zastaralém `HEAD` |
| Exportní DTO | Uzel slovníku, metadata pojmu, diagram, záznam o exportu |
| Liquibase `024` + `DiagramEntity.iriId` | Sloupec IRI diagramu a doplnění hodnot |
| `SecurityConfig` | Matchery `permitAll()` pro obě cesty; bez nich požadavek odmítne `denyAll()` |
| `VocabularyConstants.CONTEXT` | Přepnout z URL OFN `draft2` na `2026-02-26` (totožný soubor) |
| Kontext exportu ISMD | Statický soubor `.jsonld`, umístění podle otevřeného rozhodnutí 2 |
| Beze změny znovu použito | `OntologyDownloadService`, repozitáře diagramů a slovníků |
| Testy | Vyloučená data nejsou v žádném ze souborů; rozpracovaný slovník s validačními chybami se exportuje; prázdný slovník dá `EMPTY`; selhávající slovník dá `FAILED` a `úplný = false`; `HEAD` odpoví do 3 s i se zaseknutým Fuseki a nikdy nevolá sestavení; `OPTIONS` nikdy nevolá sestavení; smazání změní otisk; IRI diagramu přežije přejmenování diagramu i jeho slovníku; oba soubory projdou zpracováním JSON-LD (expand) a každý uzel slovníku projde JSON Schema OFN |