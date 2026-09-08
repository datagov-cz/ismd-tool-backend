# Diagramová vrstva: FE / REST kontrakt

> Stav: **hotovo a ověřeno smoke testem** — `DiagramController` implementuje každý níže uvedený endpoint,
> cesty jsou v allowlistu SecurityConfig a zápisová cesta byla 2026-08-25 ověřena end-to-end proti
> lokálnímu Postgresu + Fuseki. Anglická verze:
> [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md). Architektura a zdůvodnění:
> [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md). Doslovné přepisy požadavků/odpovědí:
> `.planning/diagram-write-consolidation-FE-EXAMPLES.md`.

Drátový kontrakt diagramové funkce: **tenký na zápis, tučný na čtení.** Backend spojí řádky rozvržení s živým obsahem pojmů a aplikuje overlay každého pojmu, takže FE dostane payload, který může předat téměř přímo do ReactFlow. Tento dokument je referencí pro integraci FE; proč je model takto tvarovaný, viz [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md).

**Co je uzel, hrana a řádek.** Plátno kreslí každý typ pojmu ve tvaru, který odpovídá tomu, čím *je*:

| Typ pojmu | Vykreslen jako | Identita na drátě |
|---|---|---|
| `TRIDA` | **uzel** (`classNode`) | `nodes[].id` = `iri:<plné-iri>` |
| `VZTAH` | **hrana** mezi svými dvěma třídami | `edges[].id` = vlastní **IRI pojmu** daného vztahu |
| `VLASTNOST` | **řádek uvnitř** uzlu své doménové třídy | `nodes[].data.properties[].iri` |

Vztah je jedna hrana, ne uzel s odkazem na každý konec — spojuje třídu `rdfs:domain` s třídou `rdfs:range`, což je přesně to, co pojem znamená. Vlastnost má jen doménu (její range je literálový datový typ), takže není ke komu druhému kreslit; je řádkem ve třídě, která ji vlastní.

**Nekompletní pojmy nejsou na plátně.** VZTAH bez domény nebo range a VLASTNOST bez domény se prostě nekreslí — není k čemu je připojit. Umisťují se přetažením z detailu slovníku, což je právě ten úkon, který chybějící konec doplní. Proto model čtení nikdy nepotřebuje stav „visící hrana" ani „plovoucí vlastnost".

## REST rozhraní

Controller `DiagramController`, základ `/api/diagram`. Všechny odpovědi jsou zabaleny v `ApiResponseDto<T>`. Všechny cesty jsou autentizované (každá musí být v allowlistu SecurityConfig). Tento controller se dotýká *rozvržení + overlaye rozpracovaných úprav*; **Převzít** se v procesu rozvětvuje do stávajících pojmových služeb.

| Sloveso · Cesta | Účel | Tělo → Odpověď |
|---|---|---|
| `GET /all` | Odlehčený seznam všech diagramů napříč slovníky. Libovolný přihlášený uživatel. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/list` | **Diagramy jednoho slovníku**, od nejstaršího — jen identita a počet uzlů. Navigační seznam: dvojice `diagramId` + `name` je název a odkaz. **Jen pro čtení; nic nevytváří.** | → `List<DiagramSummaryDto>` |
| `POST /{ontologySlug}/create` | **Vytvořit nové prázdné plátno.** Prázdný/chybějící `name` dostane číslovaný výchozí, takže vytvoření bez názvu nikdy neselže. | `DiagramCreateDto` → `DiagramDto` |
| `GET /{ontologySlug}/{diagramId}/detail` | Načíst jeden diagram, rozvržení spojené s živým obsahem pojmů s aplikovanými overlayi. Neznámé id je **404** — čtení nic nevytváří. | → `DiagramDto` (tučný, připravený k vykreslení) |
| `GET /usage/concept/{conceptSlug}` | **Kde je tento pojem nakreslen?** Všechny diagramy, na jejichž plátně se pojem nachází, s odkazem a se strukturou, kterou dané plátno zobrazuje. Pro detail pojmu. Libovolný přihlášený uživatel. | → `DiagramConceptUsageDto` |
| `PUT /{ontologySlug}/{diagramId}/layout` | **Uložit diagram — jediný zápis rozvržení.** Uloží rozvržení (pozice, viewport, body lomu hran) *a* nasazené strukturální overlays. **Žádné RDF.** | `DiagramLayoutDto` → `DiagramDto` (tučný, hydratovaný) |
| `POST /{ontologySlug}/{diagramId}/materialize` | **Převzít.** Aplikovat každou nasazenou změnu přes stávající CRUD pojmů → outbox → RDF. Odmítne s přehledem kolizí, pokud tentýž pojem nasazuje i sourozenecký diagram — viz níže. | → `MaterializeResultDto` |
| `DELETE /{ontologySlug}/{diagramId}` | Smazat jeden diagram, jeho rozvržení a nasazené úpravy. **Pojmů slovníku se to nedotkne.** | → `null` |

### Cesty vnořují diagram pod jeho slovník

`/{ontologySlug}/{diagramId}/…` místo `/{diagramId}/…`, takže každý zápis dál autorizuje slug přes stávající `belongsToUserBySlug` — žádný nový bezpečnostní výraz a žádná nová cesta, která by mohla propadnout allowlistem SecurityConfig až k `denyAll`.

Slug **neomezuje** id, které cestuje vedle něj, takže služba navíc ověřuje, že diagram patří pojmenovanému slovníku. Adresovat diagram jiného slovníku přes svůj slug je **404** (ne 403 — odpověď nesmí potvrdit, že to id existuje jinde).

### ⚠ Zásadní změna: diagram se zakládá explicitně

Dříve `GET …/detail` vrátil prázdné náhradní plátno pro slovník bez diagramu a první `PUT …/layout` s `version: 0` řádek vytvořil. Ani jedno už neplatí — není žádné id, které by šlo adresovat.

**Postup při prvním otevření je:** `GET /{slug}/list` → pokud je prázdný, `POST /{slug}/create` → `GET /{slug}/{diagramId}/detail`.

Čtení zůstává striktně bez zápisu, což je záměr: uživatel, který není vlastníkem, nesmí otevřením cizího slovníku způsobit vznik řádku v `diagrams`.

**Názvy diagramů jsou unikátní v rámci svého slovníku** (jsou tím, čím uživatel dvě plátna rozliší — ve výběru i ve vyhledávání). Kolize při vytvoření vrací **409** s `errorCode: "DIAGRAM_NAME_CONFLICT"`, ne obecnou chybu omezení.

**Jediný zápisový endpoint.** Rozvržení i strukturální nasazování cestují ve stejném volání. `PATCH …/nodes/overlay` neexistuje — byl odstraněn. Plátno drží celý svůj stav na klientu a při každém Uložit už stejně posílá kompletní rozvržení, takže samostatné kolečko na každou úpravu nic nepřinášelo a vytvářelo druhý zdroj čítače verze.

**Členství na plátně jede na uložení rozvržení.** Neexistuje samostatný endpoint pro přidání/odebrání uzlu. Protože `nodes[]` je idempotentní úplná náhrada, **přidat** = uzel zahrnout (holé `{id, position}` u pojmu, který zatím na plátně není; odpověď `DiagramDto` doplní jeho label/typ/slug z živého RDF) a **odebrat z plátna** = vynechat ho. Pojem se ani jedním nedotkne — jediné RDF smazání, které diagram způsobí, je implicitní, uvnitř op 6, řešené přes `/materialize`.

**Žádný endpoint pro smazání pojmu, žádný endpoint pro pokrytí.** Vytvoření pojmu z plátna volá stávající `POST /api/concept/{slug}/create` (vlastnost/vztah lze vytvořit bez domény), FE ho pak umístí zahrnutím do dalšího `PUT …/layout`. Pokrytí („které pojmy nejsou na plátně") je **množinový rozdíl na klientu** — FE už drží úplný seznam pojmů slovníku i IRI uzlů na plátně; žádné kolečko na server.

**Konvence id uzlu.** `iri:<plné-iri>` pro každý uzel (všechny uzly odkazují na pojem). ReactFlow vyžaduje jen to, aby `node.id` byl unikátní řetězec; toto schéma je stabilní napříč načteními a umožňuje `PUT …/layout` přidat uzel podle IRI bez předchozího kolečka na server.

## Hledání diagramů — `GET /api/diagram/all` a hledání `type=DIAGRAM`

Dvě cesty, jak diagramy uživateli nabídnout:

- **Seznam:** `GET /api/diagram/{ontologySlug}/list` → `List<DiagramSummaryDto>` (`diagramId`, `name`, `ontologySlug`, `ontologyName`, `graphName`, `nodeCount`, `updatedAt`) pro jeden slovník; `GET /api/diagram/all` pro všechny. Libovolný přihlášený uživatel; odlehčené (bez spojení s živým obsahem).
- **Hledání:** `GET /api/search?type=DIAGRAM` vrací **jeden `SearchResultDto` na každý diagram** — slovník se třemi plátny přispěje třemi řádky, se shodou na slugu slovníku **nebo na názvu diagramu**. Při výchozím hledání (`type` vynecháno) se řádky diagramů objeví vedle řádků `ONTOLOGY`/`CONCEPT`; pro `type=DIAGRAM` se NKD přeskakuje. Celkový počet nese `SearchResponseDto.totalDiagrams`.

**Směrování výsledku hledání DIAGRAM → detail diagramu (s obejitím detailu slovníku).** `SearchResultDto` typu DIAGRAM je:

| Pole | Hodnota | Použití ve FE |
|---|---|---|
| `type` | `DIAGRAM` | větvit podle toho |
| `slug` | **slug slovníku** | polovina klíče pro směrování → `GET /api/diagram/{slug}/{diagramId}/detail` |
| `diagramId` | id diagramu | **druhá polovina klíče pro směrování** |
| `label` | **vlastní název diagramu** | to, čím uživatel odliší dvě plátna téhož slovníku ve výsledcích |
| `iri` | syntetické `{graphName}#diagram-{id}` | **jen pro deduplikaci — neodkazovat přes něj**; brání sloučení řádku DIAGRAM do řádku `ONTOLOGY` při `type=null` i sloučení diagramů téhož slovníku mezi sebou |
| `id` | id řádku diagramu | tatáž hodnota jako `diagramId`; není to id pojmu |
| `ontologyIri` | IRI grafu slovníku | pokud potřebujete identitu slovníku |
| `isPublished` | stav publikace **slovníku** | diagram žádný vlastní nemá — je přesně tak viditelný jako jeho slovník |
| `lastModified` | `updatedAt` diagramu | |

**Rozsah publikace.** Diagram zrcadlí viditelnost svého slovníku. `?source=UNPUBLISHED` vrací jen
diagramy nepublikovaných slovníků (a `totalDiagrams` počítá jen ty); bez filtru publikace
(`source=ISMD`/`ALL`) se diagramy vracejí bez ohledu na stav publikace. Neexistuje zdroj „jen
publikované" ani způsob, jak publikovat diagram nezávisle na jeho slovníku.

Tedy: při `result.type === 'DIAGRAM'` navigujte rovnou na diagram pomocí `result.slug` **a `result.diagramId`**. U řádků DIAGRAM nikdy neodvozujte odkaz z `result.iri`.

## Autorizace

| Volání | Vlastník | Jiný přihlášený uživatel | Nepřihlášený |
|---|---|---|---|
| `GET …/all`, `GET …/list`, `GET …/detail`, `GET /usage/concept/…` | 200 | **200** | 401 |
| `PUT …/layout` | 200 | **403** | 401 |
| `POST …/materialize` | 200 | **403** | 401 |
| `POST …/create`, `DELETE …/{id}` | 200 | **403** | 401 |

**Čtení je záměrně otevřené.** `canViewResource()` dovoluje **libovolnému přihlášenému uživateli** číst diagram kteréhokoli slovníku, v souladu s celokódovým přístupem ke čtení, kde každý přihlášený volající vidí všechny grafy. Pouze zápisové cesty jsou omezené na vlastnictví přes `belongsToUserBySlug`.

**Autorizace zápisu omezuje slug *i* IRI.** `belongsToUserBySlug` autorizuje slovník v cestě, ale každé IRI pojmu cestuje uvnitř těla požadavku, takže zápisová cesta navíc vyžaduje, aby každý pojem, který úprava **zapisuje**, patřil do vlastního grafu slovníku daného diagramu — `conceptIri` overlaye, jeho `domain` a `addBroaderOn` v op 6. Cizí IRI na těchto místech selže s **400** a nic neuloží; táž kontrola běží znovu při materializaci (`FOREIGN_CONCEPT`). Konce, které jsou pouze **odkazované** (`range`, `broaderConcept`, `exactMatch`, `broader` v op 6), i samotné umístění uzlu cizí být smějí — viz *Uzly — členství*. Pojem, jehož řádek prostě *chybí*, odmítnut není — to je smazaný pojem, hlášený jako `skippedStale`.

**Čtení nikdy nezapisuje.** `GET …/list` i `GET …/{diagramId}/detail` jsou jen pro čtení: slovník bez diagramu vrátí prázdný seznam a neznámé id vrátí 404, takže nevlastník, který otevře cizí plátno, nemůže řádek v `diagrams` přivést na svět. Řádek vznikne výhradně explicitním `POST …/create`. Diagram patří tomu, kdo vlastní jeho slovník; samostatné pole vlastníka diagramu neexistuje.

## Čtení — `GET /api/diagram/{ontologySlug}/{diagramId}/detail` → 200 · `DiagramDto`

Backend už spojil řádky rozvržení s živým obsahem pojmů a aplikoval overlay každého uzlu.

```jsonc
{
  "diagramId": 4,
  "name": "Pohled HR",            // unikátní v rámci slovníku
  "ontologySlug": "pracovni-pomer",
  "version": 7,                   // pošlete zpět v dalším PUT …/layout (optimistický zámek)
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },

  // uzly jsou POUZE TŘÍDY — vztah je hrana, vlastnost je řádek níže
  "nodes": [
    {
      "id": "iri:https://…/pojem/zamestnanec",
      "type": "classNode",
      "position": { "x": 240, "y": 80 },
      "parentId": null,
      "collapsed": false,           // jde tam a zpět: co pošlete v PUT …/layout, dostanete sem zpátky
      "data": {
        "conceptType": "TRIDA",
        "iri": "https://…/pojem/zamestnanec",
        "slug": "pracovni-pomer-zamestnanec",       // FE odkazuje hluboko na /detail
        "label": { "cs": "Zaměstnanec", "en": "Employee" },
        "stale": false,                              // true ⇒ odkazovaný pojem byl smazán
        "hasPendingEdits": false,
        "readOnly": false,                           // true ⇒ CIZÍ pojem: vykreslit needitovatelně
        // VLASTNOSTi dané třídy, vykreslené jako řádky uvnitř uzlu. Vždy přítomné (prázdné, nikdy null)
        // a seřazené podle labelu, aby se řádky mezi čteními nepřeskupovaly.
        "properties": [
          {
            "iri": "https://…/pojem/datum-narozeni",
            "slug": "pracovni-pomer-datum-narozeni",
            "label": { "cs": "datum narození" },
            "rangeResolved": { /* datový typ — pravý sloupec řádku */ },
            "stale": false,
            "hasPendingEdits": true,
            "pendingEdit": { "domain": "https://…/pojem/osoba" }   // nasazený přesun do jiné třídy
          }
        ]
      }
    },
    {
      "id": "iri:https://…/pojem/organizace",
      "type": "classNode",
      "position": { "x": 720, "y": 80 },
      "data": {
        "conceptType": "TRIDA", "iri": "https://…/pojem/organizace",
        "label": { "cs": "Organizace" }, "stale": false, "hasPendingEdits": false,
        "properties": []
      }
    }
  ],

  "edges": [
    {
      // VZTAH: JEDNA hrana mezi svými dvěma třídami, nesoucí vlastní identitu pojmu.
      // id JE IRI pojmu daného vztahu — je unikátní na hranu a stabilní napříč načteními.
      "id": "https://…/pojem/je-zamestnan-u",
      "source": "iri:https://…/pojem/zamestnanec",   // jeho rdfs:domain  (živý ⊕ overlay)
      "target": "iri:https://…/pojem/organizace",    // jeho rdfs:range   (živý ⊕ overlay)
      "type": "relationEdge",
      "segments": [{ "x": 120, "y": 40 }],   // body lomu jen pro FE; při výchozím vedení se vynechávají
      "data": {
        "edgeKind": "VZTAH",
        "pending": true,                     // konec pochází z nematerializovaného overlaye
        "conceptType": "VZTAH",
        "iri": "https://…/pojem/je-zamestnan-u",
        "slug": "pracovni-pomer-je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" }, // label je jen živý; NELZE ho editovat přes overlay
        "stale": false,
        "hasPendingEdits": true,
        "pendingEdit": { "range": "https://…/pojem/organizace" }   // přesměrováno, zatím ne v RDF
      }
    },
    {
      // Holá RDF trojice — není za ní pojem, takže `data` nenese iri/label.
      // Hrana s nenulovým data.iri je podložená pojmem (vybíratelná, nasaditelná, odkazovatelná);
      // hrana bez něj je prostý hierarchický/ekvivalenční odkaz. To je rozlišovací znak pro FE.
      "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "source": "iri:https://…/pojem/zamestnanec",
      "target": "iri:https://…/pojem/osoba",
      "type": "hierarchyEdge",
      "data": { "edgeKind": "SUBCLASS_OF", "pending": false }
    }
  ],

  // KAŽDÁ nasazená úprava, ať už její pojem plátno vykresluje, nebo ne. Vždy přítomno (prázdné, nikdy null).
  // `pendingEdits.length` pohání ovládací prvek „Převzít N změn".
  "pendingEdits": [
    { "iri": "https://…/pojem/je-zamestnan-u",
      "conceptType": "VZTAH",
      "slug": "pracovni-pomer-je-zamestnan-u",
      "label": { "cs": "je zaměstnán u" },
      "stale": false,                          // true ⇒ pojem byl pod diagramem smazán
      "pendingEdit": { "range": "https://…/pojem/organizace" } }
  ]
}
```

**`nodes[]` je plátno: pouze třídy.** Vztah je v `edges[]`, vlastnost v `data.properties[]` své třídy. Ani jeden není nikdy uzlem — na klientovi není potřeba filtrovat podle `conceptType`.

**`pendingEdits[]` je jediný stabilní domov nasazené práce.** Je *úplné*, ne zbytkové: pojem, který plátno vykresluje, nese svůj overlay na daném prvku **a zároveň** je zde. Ta redundance je záměrná — členství na plátně se během relace neustále mění (třídu odtáhnete pryč, pak zpět) a seznam obsahující jen neviditelné úpravy by při každé takové změně položku vkládal a zase vyjímal, takže by klient musel po každém uložení znovu odvozovat, které ze čtyř míst danou úpravu vlastní. Identitou je IRI pojmu; úpravu čtěte odsud a kopii na uzlu/hraně/řádku berte jako pomůcku pro vykreslení.

**Uzel nenese žádnou `version`.** Verze patří diagramu; klíč u žádného uzlu není — ani jako `null`.

`edgeKind` (jen na straně čtení) ∈ `VZTAH` · `SUBCLASS_OF` · `EXACT_MATCH`.

`DOMAIN` a `RANGE` neexistují — vztah je jedna hrana mezi svými dvěma třídami, ne uzel s odkazem na každou. `SUB_PROPERTY` a `SUB_RELATION` (`rdfs:subPropertyOf` mezi dvěma vlastnostmi nebo dvěma vztahy) se **na plátně nevykreslují**: ani jeden konec není uzel, takže odkaz nemá k čemu přiléhat, a byznysová sémantika je do vzniku požadavku nedefinovaná. Samotný vztah to neovlivňuje — v běžném editoru pojmů zůstává plně podporován.

## Pojem → diagramy — `GET /api/diagram/usage/concept/{conceptSlug}` → 200 · `DiagramConceptUsageDto`

Obrácení čtení výše, pro **detail pojmu**: je dán pojem — na kterých plátnech je nakreslen? Vrací jednu položku `placements[]` na každý diagram, každou s dvojicí název-a-odkaz (`ontologySlug` + `diagramId`) a se strukturou, kterou dané plátno zobrazuje.

```jsonc
{
  "conceptIri": "https://…/pojem/je-zamestnan-u",
  "conceptName": { "cs": "je zaměstnán u" },
  "conceptSlug": "je-zamestnan-u",
  "placements": [
    { "diagramId": 5, "diagramName": "Hlavní diagram", "ontologySlug": "pracovni-pomer",
      "kind": "EDGE",
      "domain": { "iri": "https://…/pojem/zamestnanec", "conceptSlug": "zamestnanec",
                  "conceptName": { "cs": "Zaměstnanec" } },
      "range":  { "iri": "https://…/pojem/organizace",  "conceptSlug": "organizace",
                  "conceptName": { "cs": "Organizace" } },
      "broader": [], "exactMatch": [], "pending": false }
  ]
}
```

**Prázdné `placements[]` je normální odpověď**, ne 404 — pojem existuje, ale žádné plátno ho nekreslí. 404 znamená neznámý *slug*.

### `kind` — tři způsoby, jak být „na plátně"

Otázka zní jako jedno vyhledání, ale jsou to tři, protože členství se eviduje na třech různých místech. `kind` říká FE, co má hledat, a **není** synonymem typu pojmu:

| `kind` | Vykreslen jako | Členství žije v |
|---|---|---|
| `NODE` | buňka třídy | řádku `diagram_nodes` |
| `EDGE` | čára vztahu | řádku `diagram_edges` klíčovaném vlastním IRI vztahu |
| `PROPERTY_ROW` | řádek **uvnitř** buňky své třídy | `visible_properties_json` daného uzlu |

U `PROPERTY_ROW` pojmenovává `hostClass` třídu, v jejíž buňce se řádek vykresluje — bez toho se uživatel dozví „je na tomto diagramu", ale nemá jak ho najít. U ostatních tvarů chybí.

Vlastnost je hlášena jen tehdy, když ji její hostitelská třída skutečně **uvádí**. Nekurátorovaná třída nezobrazuje žádné řádky, takže pouhá existence uzlu nestačí.

### Struktura je per diagram, `pending` říká proč

`domain` / `range` / `broader` / `exactMatch` jsou resolvované (`{iri, conceptName, conceptSlug, …}`), ne holá IRI, a jsou hlášené **tak, jak je daný diagram zobrazuje**: živé RDF s navrstveným vlastním overlayem daného diagramu, pole po poli. Dvě umístění téhož pojmu se tedy mohou legitimně lišit — ten rozdíl je smyslem věci a `pending: true` označuje plátno, jehož nasazená změna ho způsobuje.

`domain`/`range` jsou u třídy null — třída ani jedno nemá. `broader`/`exactMatch` jsou `[]`, ne null, když žádné nejsou.

### Výkon

Jeden PG dotaz na všechny tři tvary členství, jeden na overlaye, jeden `SELECT` na hierarchii a jedno dávkové (per-IRI cachované) resolvování všech IRI, která kterékoli umístění zmiňuje — **bez ohledu na počet vrácených diagramů**. Záměrně nevyužívá `GET …/detail`, který načítá celý graf slovníku pro každý diagram; odpovědět takto by stálo načtení celého grafu na každý vypsaný diagram. Pojem, který není na žádném plátně, se zkratuje v Postgresu a Fuseki se vůbec nedotkne.

## Zápis — Uložit: `PUT /api/diagram/{ontologySlug}/{diagramId}/layout` · `DiagramLayoutDto`

Jedno volání nese vše: rozvržení **i** strukturální overlays. Odstraňte přechodná pole ReactFlow (`selected`, `dragging`, `measured`) a posílejte jen to, co se ukládá. Backend ignoruje obsah `data` u uzlu — strukturální záměr cestuje v `overlays`, nikdy v `data` uzlu.

### Jediné pravidlo, které je třeba si osvojit

**`nodes` a `edges` jsou úplná náhrada. `overlays` jsou přírůstkové napříč pojmy — ale každá položka je úplnou náhradou overlaye jednoho pojmu.**

| Pole | Vynecháno / `null` | `[]` |
|---|---|---|
| `version` | **400** — vždy povinné | — |
| `nodes` | **400** — vždy povinné | plátno vyprázdněno (nasazené úpravy nedotčeny — jsou v jiné tabulce) |
| `nodes[].properties` | daná třída nevykreslí **žádné** řádky vlastností | totéž |
| `edges` | **členství hran nedotčeno** | všechny hrany odebrány z plátna |
| `edges[].segments` | uložené vedení dané hrany **zachováno** | vedení vyčištěno na výchozí |
| **`overlays`** | **nasazené úpravy nedotčeny** | **nasazené úpravy nedotčeny** |

Pojem chybějící v `overlays` si ponechá, co je na něm nasazeno. **Jediný** způsob, jak overlay zahodit, je položka nesoucí `conceptIri` a nic jiného.

**Přírůstkové napříč pojmy, náhrada uvnitř jednoho.** Položka je *celý* overlay daného pojmu, nikoli patch jednotlivých polí: nahradí nasazenou úpravu celou, takže jakékoli pole overlaye, které položka vynechá, zanikne. Chcete-li změnit jeden predikát a ostatní zachovat, pošlete celý nasazený záměr pojmu — čtení vám jej vrací v `pendingEdits[]`, takže stačí ten objekt poslat zpět se zapracovanou změnou.

```jsonc
// nasazeno: { "broaderConcept": ["…/trida-b"] }
{ "conceptIri": "…/trida-a", "exactMatch": ["…/trida-c"] }
// výsledek: { "exactMatch": ["…/trida-c"] }   ← broaderConcept ZANIKL, nesloučí se
{ "conceptIri": "…/trida-a", "broaderConcept": ["…/trida-b"], "exactMatch": ["…/trida-c"] }
// výsledek: obojí zachováno                    ← posílejte celý overlay
```

**Rozvržení a nasazené úpravy jsou nezávislé.** Žijí v oddělených tabulkách, takže odebrání uzlu z plátna nikdy nezahodí jeho nasazenou úpravu a nasazení úpravy nikdy nedostane pojem na plátno. Odebrání je čistá prezentace a nenese žádný RDF záměr; zahození je vlastní, explicitní pokyn.

> **Proč se `overlays` liší od `edges`.** Nasazený overlay se v čtení objevit vůbec nemusí — jeho koncová třída může být mimo plátno, nebo mohl být pojem smazán pod diagramem — takže po klientovi nelze chtít, aby poslal zpět něco, co mu nikdy nebylo ukázáno. Kdyby vynechání znamenalo zahození, zničilo by to nasazenou práci při každém uložení sestaveném ze stavu plátna, což je přesně způsob, jak se automatické ukládání nad ReactFlow staví. Asymetrie je záměrná; „neopravujte" ji preventivním posíláním `overlays: []`.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  // pouze třídy; vztah ani vlastnost nikdy nejsou uzel
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false,
      // řádky VLASTNOSTí, které tato třída vykreslí — ploché pole IRI, úplná náhrada jako `position`
      "properties": ["https://…/pojem/datum-narozeni"] },
    // parentId/collapsed jsou volitelné — vynechané nebo null znamená bez rodiče / nesbaleno
    { "id": "iri:https://…/pojem/organizace",
      "position": { "x": 720, "y": 80 }, "properties": [] },
    // pojem z JINÉHO slovníku — umísťuje se jako každý jiný uzel; server jej označí jen ke čtení
    { "id": "iri:https://…/jiny-slovnik/pojem/osoba",
      "position": { "x": 1100, "y": 80 }, "properties": [] }
  ],
  // jen body lomu — pošlete zpět id, které jste dostali při čtení; konce se odvozují, neposílají
  "edges": [
    { "id": "https://…/pojem/je-zamestnan-u",              // VZTAH: jeho IRI pojmu
      "segments": [{ "x": 120, "y": 40 }] },
    { "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "segments": [] }                                     // [] nebo vynecháno = výchozí vedení
  ],
  // nasazené strukturální úpravy — PŘÍRŮSTKOVÉ; vynechte celý klíč, ať nasazená práce zůstane
  "overlays": [
    { "conceptIri": "iri:https://…/pojem/je-zamestnan-u",
      "range": "https://…/pojem/organizace" }
  ]
}
```

### Uzly — členství

**`nodes[]` je autoritativní pro členství na plátně.** Přítomný uzel zůstává (nebo je **přidán**, je-li jeho IRI na plátně nové; odpověď doplní jeho živý obsah), vynechaný uzel je **odebrán z plátna** (pojem zůstává nedotčen, a stejně tak jakákoli úprava na něm nasazená). K přidání uzlu stačí `{id, position}`; zbytek backend doplní z živého RDF.

**Pouze třídy.** VZTAH cestuje v `edges[]` a VLASTNOST uvnitř `properties[]` své třídy — nikdy jako uzel, v žádném směru.

**Umístění pojmu z jiného slovníku nevyžaduje nic zvláštního.** Pošlete uzel jako každý jiný; pojem patřící *jinému* slovníku (nebo NKD) je přijat a uložen jen ke čtení, aby uživatel mohl nakreslit vztah od pojmu, který vlastní, k pojmu, který nevlastní. Vykreslí se s `data.readOnly: true` a názvem načteným z grafu, který jej vlastní.

**Cizost se odvozuje, nedeklaruje.** Server ji při každém uložení určí z grafu pojmu — v požadavku pro ni není žádné pole, takže uzel nelze ani nepravdivě zamknout, ani nepravdivě zpřístupnit k editaci, a klient, který pouze vrací to, co přečetl, uloží vždy správně. (`data.readOnly` je jen v odpovědi; neposílejte jej zpět.)

⚠️ **Jen ke čtení znamená nikdy nebýt PODMĚTEM úpravy.** Položka `overlays[]`, jejíž `conceptIri` je cizí pojem, je 400 při uložení a `FOREIGN_CONCEPT` při materializaci — její materializace by zapsala RDF jiného slovníku.

**Cizí pojem naopak smí být PŘEDMĚTEM trojice ve vašem vlastním grafu**, což je celý smysl jeho umístění. Pravidlo tento rozdíl sleduje konec po konci:

| Pole overlay | Cizí povolen? | Proč |
|---|---|---|
| `conceptIri` (podmět) | ❌ | Editovaný pojem |
| `domain` | ❌ | Třída, na které VZTAH/VLASTNOST visí — *počátek* vazby, a ten musí být váš |
| `range` | ✅ | Stává se předmětem trojice ve vašem grafu |
| `broaderConcept`, `exactMatch` | ✅ | Totéž — váš pojem míří na jejich |
| `convertToHierarchy.addBroaderOn` | ❌ | Třída, která se edituje |
| `convertToHierarchy.broader` | ✅ | Pouze odkazovaný |

VZTAH vlastněný vaším slovníkem tedy smí mířit **na** cizí třídu (`range`), ale nikdy nesmí viset **na** ní (`domain`). Obojí se kontroluje při uložení i znovu při materializaci.

### `nodes[].properties` — řádky, které třída vykresluje

**Plané pole IRI vlastností, autoritativní úplná náhrada** — chová se jako `position`, ne jako `overlays`. Vlastnost se vykreslí jako řádek uvnitř třídy jen tehdy, dokud ji ta třída uvádí. Členství je **kurátorované, ne odvozené**: třída s `"properties": []` nezobrazí žádné řádky, i když její VLASTNOSTi v RDF existují, a backend se nikdy nevrací k „zobraz všechny".

**Vynechání klíče je totéž jako poslat `[]`** — uzel v payloadu udává svou úplnou množinu řádků. Tvar pro čtení a zápis se liší: čtení vrací bohaté objekty `PropertyRow`, zápis bere holá IRI, takže uložení mapuje `node.data.properties.map(p => p.iri)`.

**Přidání** řádku = zahrnout jeho IRI; **odebrání** = vynechat ho a zbytek poslat znovu. **Přesun vlastnosti k jiné třídě vyžaduje obojí**: uvést ji u nové hostitelské třídy *a* nasadit `{"domain": "<nová třída>"}` na její overlay. Samotný overlay nevykreslí nic — umístění a struktura jsou oddělené pokyny.

### Hrany — explicitní členství na plátně

**Hrana ukládá právě dvě věci: `id` a `segments`.** Její existence, konce i druh se při každém čtení znovu odvozují z `živý ⊕ overlay`, takže `source`, `target` ani `edgeKind` **se na zápisu nepřijímají** — poslat je znamená, že se ignorují. Je to záměrné: uložený konec by mohl tiše odporovat projekci, kterou duplikuje, a přesně proti tomuto rozcházení je diagramová vrstva postavena. Chcete-li změnit, kam vztah míří, nasaďte `{domain, range}` na jeho overlay; hrana se přizpůsobí.

**`edges` je členství na plátně, stejně jako `nodes` — pošlete zpět každou hranu, kterou chcete mít nakreslenou.** Uvedená hrana je na plátně; hrana vynechaná z přítomného pole `edges` se z něj odebere. Takové odebrání je **čistá prezentace**: trojice v RDF zůstává nedotčená, takže hrana je dál projektovatelná a lze ji později přidat zpět.

**Projektovatelná hrana, kterou uživatel nikdy neumístil, se nekreslí.** Stejně jako třída, která ve slovníku existuje, ale nebyla přetažena na plátno, čeká v postranním panelu, dokud ji uživatel nepřidá. Právě díky tomu mohou na plátně být dvě třídy *bez* vztahu mezi nimi — což při kreslení hran čistě z projekce nešlo vyjádřit.

**Co se kreslit MŮŽE, dál řídí projekce, takže hrana nikdy nezůstane viset na jednom konci.** Pokud koncová třída opustí plátno nebo overlay vztah přesměruje, hrana se přestane projektovat a nekreslí se, ať už členství říká cokoli. Členství může projektovatelnou hranu skrýt; neprojektovatelnou nikdy neoživí.

`segments` jsou trojhodnotové a na členství nezávislé:

- **vynecháno / `null`** — uložené vedení se zachová. Položka je výrok o členství a o geometrii neříká nic, takže klient, který vedení nespravuje, je nemůže omylem zahodit.
- **`[]`** — vedení se explicitně vyčistí na výchozí.
- **seznam** — nastaví body lomu.

Přesměrování konce body lomu záměrně zahodí: id hrany v sobě nese její konce, takže geometrie nakreslená pro starý cíl nemůže přejít na nový. Body lomu jsou čistá prezentace — nic je neodvozuje z RDF a nic je proti němu nevaliduje.

### Overlays — nasazené strukturální úpravy

Každá položka nasadí nebo aktualizuje strukturální diff **jednoho pojmu**. Ukládá se do `pending_edit_json`; do RDF se neposílá až do Převzít.

**`conceptIri` adresuje *pojem*, ne uzel plátna.** VZTAH se vykresluje jako hrana a VLASTNOST jako řádek uvnitř své třídy a oba se nasazují přes totéž pole svým vlastním IRI. Hodnotou je plné IRI, volitelně s prefixem `iri:`. Je povinné (`@NotBlank`); položka bez něj je **400**.

Overlay je **čistě strukturální** — žádný `label`/`name` zde není. Editace labelu se dělá v běžném editoru pojmů, ne v diagramu (změna labelu přejmenuje IRI pojmu).

| Pole | Platí pro | Význam |
|---|---|---|
| `conceptIri` | — | **povinné**; nasazovaný pojem |
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | seznam `subClassOf` (IRI) |
| `exactMatch` | libovolný | seznam `skos:exactMatch` (IRI) — „ekvivalent" z op 3 |
| `convertToHierarchy` | VZTAH | marker op 6: `{ addBroaderOn, broader }` — **obojí povinné** |

`baseUpdatedAt` se objevuje ve **čtení** (uvnitř `pendingEdit`), ale **na zápisu se nikdy neposílá** — otisk zastaralého základu razítkuje server sám. Poslání se ignoruje.

**Zahození = položka nesoucí pouze `conceptIri`.** `{"conceptIri": "iri:…"}` vyprázdní overlay daného pojmu a vrátí ho k živému obsahu. `conceptIri` je adresování, ne obsah, takže se do „prázdnosti" nikdy nepočítá.

**Explicitně prázdný seznam *není* zahození — znamená „vyprázdni tento predikát".** `{ "conceptIri": "iri:…", "broaderConcept": [] }` nasadí „odeber všechny nadtřídy" (A-strana otočení op 2 zbavující se poslední nadtřídy) a materializuje se jako vyprázdnění `subClassOf`.

**Tažení konce hrany je editace pojmu** — přesměrování šipky vztahu nasadí `range` na VZTAHu; přetažení řádku vlastnosti do jiné třídy nasadí `domain` na VLASTNOSTi:

```jsonc
// op 1 (přehození směru, VZTAH):          { "conceptIri": "iri:…/rel", "domain": "…/A", "range": "…/B" }
// op 4/5 (rodič/doména vlastnosti):       { "conceptIri": "iri:…/prop", "domain": "…/VlastnicíTrida" }
// op 3 (podtřída → ekvivalent, TRIDA):    { "conceptIri": "iri:…/A", "broaderConcept": [], "exactMatch": ["…/B"] }
// op 2 (otočení): dvě položky v TÉMŽE poli overlays[] —
//     { "conceptIri": "iri:…/A", "broaderConcept": [ …bez B ] },
//     { "conceptIri": "iri:…/B", "broaderConcept": [ …, "…/A" ] }
// op 6 (vztah → hierarchie, na VZTAHu):
//     { "conceptIri": "iri:…/rel", "convertToHierarchy": { "addBroaderOn": "…/A", "broader": "…/B" } }
```

`superProperty` a `superRelation` neexistují, stejně jako hrany `SUB_PROPERTY`/`SUB_RELATION`: diagram nemůže nasadit změnu sub-property/sub-relation, protože ji neumí vykreslit, aby ji uživatel viděl nebo vrátil zpět. Použijte běžný editor pojmů.

### Verze — optimistický zámek

**`version` je povinná — pošlete zpět tu, ze které jste vykreslovali.** Protože členství je úplná náhrada, uložení postavené nad zastaralým pohledem by tiše smazalo uzly, které mezitím přidal jiný editor. Pošlete `version` z `DiagramDto`, od kterého tato editace začala (z čtení, nebo z odpovědi vašeho vlastního posledního uložení).

Každé úspěšné uložení posune verzi a vrátí hodnotu **po inkrementu**, takže lze řetězit uložení bez opětovného čtení. Čerstvě vytvořené plátno nese `version: 0` — pošlete zpět to, co vrátil `POST …/create`. Vynechání je **400** se jménem pole; ve schématu je deklarována jako povinná, takže generovaný klient ji typuje jako nevolitelnou.

Pokud mezitím uložil jiný editor, volání vrátí **409** a **nic se nezapíše** — nasazené overlays zůstanou přesně tak, jak byly:

```jsonc
{ "success": false, "errorCode": null, "data": null,
  "message": "Diagram byl mezitím uložen jiným editorem; načtěte jej znovu a uložte změny znovu." }
```

Načtěte diagram znovu a aplikujte změny znovu.

### Validační chyby (400)

`message` je pevná předpona `Neplatná data v požadavku: ` následovaná cestou k poli — porovnávejte cestu, nikdy celý řetězec. Více chybných polí se spojí pomocí `; `. Každá 400 je **atomická** — nic se nezapíše a nasazená množina zůstává beze změny.

| Tělo | `message` |
|---|---|
| položka overlaye bez `conceptIri` | `Neplatná data v požadavku: overlays[0].conceptIri: must not be blank` |
| `convertToHierarchy` bez `broader` | `Neplatná data v požadavku: overlays[0].convertToHierarchy.broader: must not be blank` |
| `convertToHierarchy` bez `addBroaderOn` | `Neplatná data v požadavku: overlays[0].convertToHierarchy.addBroaderOn: must not be blank` |

IRI pojmu z jiného slovníku — v id uzlu, v `conceptIri` overlaye nebo v kterémkoli konci `convertToHierarchy` — je rovněž 400:

```jsonc
{ "success": false, "data": null,
  "message": "Pojem https://…/a3791---registr-vysokých-škol/pojem/elektronická-adresa nepatří do slovníku tohoto diagramu." }
```

### `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) — zápis se povedl, data pro vykreslení ne

Zápis diagramu je čistý Postgres; obsah pojmů v odpovědi se pak čte z Fuseki. Ty dvě věci záměrně **nejsou** v jedné transakci — načtení je HTTP volání, které může trvat desítky sekund, a držet přes ně DB spojení by dovolilo pomalé Fuseki vyčerpat pool a zastavit nesouvisející endpointy. Zároveň by to zahodilo naprosto v pořádku provedený zápis rozvržení kvůli selhání *čtení*.

Důsledkem je režim selhání, který dříve neměl obdobu: zápis je **commitnutý a trvalý**, ale tělo odpovědi nelze sestavit.

```jsonc
{
  "success": false,
  "errorCode": "DIAGRAM_SAVED_READBACK_FAILED",
  "message": "Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. Načtěte diagram znovu; změny zůstávají uložené.",
  "data": { "version": 16 }        // verze PO commitnutém zápisu
}
```

**Zápis neopakujte.** Uložení už proběhlo a verze se posunula; poslat ho znovu s verzí, kterou jste drželi, by bylo zastaralé a vrátilo **409**. Buď znovu vyvolejte `GET …/detail` a vykreslete aktuální stav, nebo pokračujte z `version` v `data`, chcete-li uložit znovu bez tohoto čtení. Berte to jako „uloženo, ale zatím vám to nemohu ukázat" — nikdy jako „uložení selhalo".

Toto je jediný stav, kdy odpověď se `success: false` přesto znamená, že zápis proběhl, a proto má vlastní kód místo obecné 500.

## Materializace — `POST /api/diagram/{ontologySlug}/{diagramId}/materialize` → `MaterializeResultDto`

Aplikuje každou nasazenou změnu. Jeden záznam na nasazenou **změnu** (změna může zasahovat dva pojmy). Per-změna částečně-OK; dvoupojmová změna (otočení, vztah→hierarchie) je vše-nebo-nic. Nasazené úpravy se při úspěchu mažou, takže `pendingEdits[]` se vyprázdní.

**Členství na plátně zde nehraje roli.** Materializuje se každá nasazená úprava, včetně té, jejíž pojem na plátně není — uživatel ji nasadil a skrytí boxu není rozhodnutí ji zahodit.

```jsonc
{
  "materialized": [
    { "conceptIri": "https://…/je-zamestnan-u", "op": "SWAP_DIRECTION" }
  ],
  "failed": [
    { "conceptIri": "https://…/organizace", "op": "SWAP_DIRECTION",
      "error": "VALIDATION", "message": "range must be a class", "status": 400 }
      // změna zůstává nasazená; uživatel opraví a spustí Převzít znovu
  ],
  "skippedStale": [
    { "conceptIri": "https://…/deleted-x" }   // pojem je pryč; změnu nelze aplikovat
  ]
}
```

**Každý záznam je klíčovaný přes `conceptIri`** — stejnou identitou, jakou používá `pendingEdits[]`, takže řádek výsledku přímo odpovídá nasazené úpravě, ze které vznikl. Žádné `nodeId` není: nasazená úprava nemusí mít uzel na plátně vůbec.

`op` ∈ `SWAP_DIRECTION` · `CHANGE_HIERARCHY_TYPE` · `CHANGE_PROPERTY_PARENT` · `CONVERT_TO_HIERARCHY`. (Nastavení domény vlastnosti bez domény i přesměrování existující se obojí hlásí jako `CHANGE_PROPERTY_PARENT` — z overlaye je nelze rozlišit.)

**Otočení (op 2) se materializuje jako dvě nezávislé editace.** Obrácení hierarchie (B⊐A → A⊐B) se nasazuje jako overlay `broaderConcept` na *obou* pojmech; každý se materializuje samostatně jako `CHANGE_HIERARCHY_TYPE`. Neexistuje atomická dvouuzlová jednotka otočení — ani jedna polovina sama o sobě RDF nepoškodí a napůl aplikované otočení se hlásí po pojmech v `failed`, aby ho uživatel spustil znovu. Jedinou skutečně hlídanou dvouvolánovou jednotkou je `CONVERT_TO_HIERARCHY` (op 6).

**Chybové stavy, které FE řeší:**

- `error: "VALIDATION"` (HTTP 400) — editace pojmu neprošla validací; overlay zůstává, opravit a zkusit znovu.
- `error: "STALE_BASE"` (HTTP 409) — podkladový pojem byl od nasazení overlaye editován (běžným `/api/concept`). Overlay zůstává. **Viz pravidlo nápravy níže.**
- `error: "CASCADE_CONFLICT"` — op 6 (vztah→hierarchie) zablokována, protože domain/range jiného pojmu ukazuje na daný VZTAH (jeho smazání by kaskádovalo); zobrazit a nechat uživatele vyřešit.
- `error: "FOREIGN_CONCEPT"` (HTTP 400) — IRI pojmu ve změně patří do jiného slovníku než vlastního (buď samotný pojem, nebo `addBroaderOn` / `broader` v op 6). Diagram smí zapisovat jen pojmy vlastního slovníku; legitimní klient tohle nikdy nevyprodukuje.
- `error: "ERROR"` (HTTP 500) — neočekávané selhání na straně serveru; overlay zůstává. `message` je vždy obecné `"Nastala neočekávaná chyba."` — skutečná příčina se loguje na serveru a nikdy nevrací, takže FE ji má zobrazit tak, jak je, a nepokoušet se ji parsovat.
- `skippedStale` — odkazovaný pojem už neexistuje; nabídnout odebrání nebo znovuvytvoření.

**Změna, jejíž overlay už zanikl, se nevykazuje nikde.** Zmizí-li nasazená úprava mezi načtením seznamu práce a její vlastní transakcí — opakované Převzít, souběžné Uložit, které ji zahodilo, nebo operace 6 mazající pojem — nic se nezapsalo, a pojem se proto neobjeví v *žádném* ze tří polí. Součet polí tak může mít méně položek, než měl `pendingEdits[]`; to je očekávaný tvar, nikoli ztracený výsledek. Nikdy se nevykazuje jako `materialized`, což by tvrdilo změnu, která se nestala.

### `DIAGRAM_EDIT_CONFLICT` (HTTP 409) — tentýž pojem nasazuje jiný diagram

Nasazené úpravy jsou **po diagramech**, takže dvě plátna jednoho slovníku mohou držet protichůdný záměr pro jeden pojem. Materializace kterékoli strany by posunula `updatedAt` toho pojmu — otisk, na který je připnutá úprava té druhé — takže sourozenec by následně selhal na `STALE_BASE`, po jednom pojmu. Materializace proto kontroluje nejdřív a odmítne, **než cokoli zapíše**:

```jsonc
{ "success": false, "errorCode": "DIAGRAM_EDIT_CONFLICT",
  "message": "Některé změny kolidují se změnami rozpracovanými v jiném diagramu.",
  "data": { "conflicts": [
      { "conceptIri": "https://…/pojem/je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" },
        "mine":   { "range": "https://…/pojem/osoba" },
        "theirs": [ { "diagramId": 4, "diagramName": "Pohled HR",
                      "pendingEdit": { "range": "https://…/pojem/organizace" } } ] } ] } }
```

**Kolize je „nasazeno na obou", ne „nasazeno s jinými hodnotami."** Shodné hodnoty kolidují také, ze stejného důvodu — nefiltrujte přehled na klientu jejich porovnáním.

**Nic se nezapsalo.** Nasazená práce obou stran zůstala přesně taková, jaká byla, takže volání lze bezpečně zopakovat, jakmile se uživatel rozhodne.

**Řešení** — zavolejte znovu a pojmenujte **vítěze**:

| `POST …/materialize?onConflict=` | Účinek |
|---|---|
| *(vynecháno)* | Detekovat a odmítnout s přehledem výše. Jediné bezpečné výchozí chování. |
| `ACCEPT_MINE` | Vítězí **tento** diagram: zahodit kolidující úpravy všech ostatních diagramů a materializovat tento. |
| `ACCEPT_THEIRS` + `winnerDiagramId=<id>` | Vítězí **uvedený** diagram: zahodit kolidující úpravy zde i na všech ostatních diagramech a materializovat vítěze. |

Rozhodnutí pojmenuje jednoho vítěze, nikdy stranu k zahození — kolize může zasáhnout víc než dvě plátna a „zahodit jejich" nemá jediný význam, jakmile tentýž pojem nasadí tři diagramy. Všichni poražení se vyčistí v jednom průchodu, včetně pláten, která volající nepojmenoval, takže jedno rozhodnutí vyřeší celou kolizi místo jednoho kola na každého sourozence.

**`ACCEPT_THEIRS` materializuje vítěze, ne diagram v cestě.** Ponechat zvolenou úpravu jen nasazenou by kolizi přesunulo na plátno, ke kterému se uživatel už nemusí vrátit.

`winnerDiagramId` je pro `ACCEPT_THEIRS` povinné a s `ACCEPT_MINE` odmítnuté. Musí pojmenovat diagram, který je v přehledu kolizí; cokoli jiného je **400**, protože opětovné poslání téhož nemůže uspět. Zásah do jiného plátna je povolený, protože každý diagram v kolizní množině patří slovníku, který volající už vlastní — a množina se načítá znovu na serveru, nikdy se nevěří požadavku.

**Zahodí se pouze sporné pojmy** — nesouvisející nasazená práce každého plátna přežije.

### Náprava `STALE_BASE` — zahodit, pak nasadit znovu

> ⚠️ **Opětovné poslání týchž hodnot overlaye `STALE_BASE` nevyčistí.** Otisk zastaralého základu se razítkuje, když overlay na řádku **vznikne**, a dokud zůstává nasazený, už se neobnovuje — právě to brání tomu, aby se souběžná editace pojmu tiše pohltila. Poslání týchž hodnot ponechá starý otisk na místě a znovu vrátí 409.
>
> **Náprava jsou dvě uložení:** nejprve položka se samotným `conceptIri` (zahození), poté položka s hodnotami znovu. Zahození řádek resetuje, takže nové nasazení vezme čerstvý otisk a materializace projde.

Zastaralý uzel se ve čtení dál objevuje s `"stale": true` a nedotčeným overlayem, aby uživatel viděl, co je nasazené na pojmu, který už neexistuje:

```jsonc
{ "id": "iri:…/pojem/budova-má-definiční-bod", "type": "conceptNode",
  "position": { "x": 0.0, "y": 0.0 }, "collapsed": false,
  "data": { "iri": "…/pojem/budova-má-definiční-bod", "stale": true, "hasPendingEdits": true,
            "pendingEdit": { "range": "…/pojem/parcela", "baseUpdatedAt": "2026-08-15T15:09:01.331737",
                             "domain": null, "broaderConcept": null, "exactMatch": null,
                             "convertToHierarchy": null },
            "properties": [] } }
```

## Generované typy

`DiagramLayoutDto.required` je `["nodes", "version"]` — **`overlays` jsou volitelné**, takže generovaný klient je typuje jako nullable a stávající místa volání se dál překládají. `DiagramLayoutOverlay.required` je `["conceptIri"]`; `DiagramLayoutOverlayConvertToHierarchy.required` je `["addBroaderOn", "broader"]`.

**Vše přidané kvůli více diagramům je ve schématu záměrně volitelné.** `DiagramCreateDto.name` nenese žádné omezení bean validace, takže jej generovaný klient typuje jako nullable a stávající místa volání se dál překládají. (`required` se odvozuje výhradně z bean validace — anotování nového pole by ho v generovaném klientu udělalo nevolitelným a rozbilo každého současného volajícího.) `DiagramDto.diagramId`/`name` a `SearchResultDto.diagramId` jsou na straně odpovědi, takže typ požadavku neovlivňují.

---

*ISMD Tool · diagramová vrstva · FE / REST kontrakt · více diagramů na ontologii · jediný zápisový endpoint · úplná náhrada rozvržení, přírůstkové overlays · čistě strukturální overlay, po diagramech · cizí pojmy pouze odkazované, nikdy zapisované · materializace per-změna částečně-OK*