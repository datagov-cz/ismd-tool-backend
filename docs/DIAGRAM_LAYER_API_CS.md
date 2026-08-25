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
| `GET /all` | Odlehčený seznam všech diagramů (identita + počet uzlů), např. pro výběr diagramu. Libovolný přihlášený uživatel. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Načíst kanonický diagram, rozvržení spojené s živým obsahem pojmů s aplikovanými overlayi. Slovník, který zatím diagram nemá, se načte jako prázdné plátno — **čtení nic nevytváří**; řádek vznikne až prvním zápisem. | → `DiagramDto` (tučný, připravený k vykreslení) |
| `PUT /{ontologySlug}/layout` | **Uložit diagram — jediný zápisový endpoint.** Uloží rozvržení (pozice, viewport, body lomu hran) *a* nasazené strukturální overlays. **Žádné RDF.** | `DiagramLayoutDto` → `DiagramDto` (tučný, hydratovaný) |
| `POST /{ontologySlug}/materialize` | **Převzít.** Aplikovat každou nasazenou změnu přes stávající CRUD pojmů → outbox → RDF; vícevolání vše-nebo-nic; per-změna částečně-OK. | → `MaterializeResultDto` |

**Jediný zápisový endpoint.** Rozvržení i strukturální nasazování cestují ve stejném volání. `PATCH …/nodes/overlay` neexistuje — byl odstraněn. Plátno drží celý svůj stav na klientu a při každém Uložit už stejně posílá kompletní rozvržení, takže samostatné kolečko na každou úpravu nic nepřinášelo a vytvářelo druhý zdroj čítače verze.

**Členství na plátně jede na uložení rozvržení.** Neexistuje samostatný endpoint pro přidání/odebrání uzlu. Protože `nodes[]` je idempotentní úplná náhrada, **přidat** = uzel zahrnout (holé `{id, position}` u pojmu, který zatím na plátně není; odpověď `DiagramDto` doplní jeho label/typ/slug z živého RDF) a **odebrat z plátna** = vynechat ho. Pojem se ani jedním nedotkne — jediné RDF smazání, které diagram způsobí, je implicitní, uvnitř op 6, řešené přes `/materialize`.

**Žádný endpoint pro smazání pojmu, žádný endpoint pro pokrytí.** Vytvoření pojmu z plátna volá stávající `POST /api/concept/{slug}/create` (vlastnost/vztah lze vytvořit bez domény), FE ho pak umístí zahrnutím do dalšího `PUT …/layout`. Pokrytí („které pojmy nejsou na plátně") je **množinový rozdíl na klientu** — FE už drží úplný seznam pojmů slovníku i IRI uzlů na plátně; žádné kolečko na server.

**Konvence id uzlu.** `iri:<plné-iri>` pro každý uzel (všechny uzly odkazují na pojem). ReactFlow vyžaduje jen to, aby `node.id` byl unikátní řetězec; toto schéma je stabilní napříč načteními a umožňuje `PUT …/layout` přidat uzel podle IRI bez předchozího kolečka na server.

## Hledání diagramů — `GET /api/diagram/all` a hledání `type=DIAGRAM`

Dvě cesty, jak diagramy uživateli nabídnout:

- **Seznam:** `GET /api/diagram/all` → `List<DiagramSummaryDto>` (`ontologySlug`, `ontologyName`, `graphName`, `nodeCount`, `updatedAt`). Libovolný přihlášený uživatel; odlehčené (bez spojení s živým obsahem).
- **Hledání:** `GET /api/search?type=DIAGRAM` vrací jeden `SearchResultDto` na každý slovník, který má diagram (shoda na slugu slovníku). Při výchozím hledání (`type` vynecháno) se řádky diagramů objeví vedle řádků `ONTOLOGY`/`CONCEPT`; pro `type=DIAGRAM` se NKD přeskakuje. Celkový počet nese `SearchResponseDto.totalDiagrams`.

**Směrování výsledku hledání DIAGRAM → detail diagramu (s obejitím detailu slovníku).** `SearchResultDto` typu DIAGRAM je:

| Pole | Hodnota | Použití ve FE |
|---|---|---|
| `type` | `DIAGRAM` | větvit podle toho |
| `slug` | **slug slovníku** | **klíč pro směrování** → `GET /api/diagram/{slug}/detail` |
| `iri` | syntetické `{graphName}#diagram` | **jen pro deduplikaci — neodkazovat přes něj**; existuje proto, aby hledání s `type=null` nesloučilo řádek DIAGRAM do řádku `ONTOLOGY` daného slovníku |
| `id` | id řádku diagramu | není to id pojmu; pro směrování není potřeba |
| `ontologyIri` | IRI grafu slovníku | pokud potřebujete identitu slovníku |
| `isPublished` | stav publikace **slovníku** | diagram žádný vlastní nemá — je přesně tak viditelný jako jeho slovník |
| `lastModified` | `updatedAt` diagramu | |

**Rozsah publikace.** Diagram zrcadlí viditelnost svého slovníku. `?source=UNPUBLISHED` vrací jen
diagramy nepublikovaných slovníků (a `totalDiagrams` počítá jen ty); bez filtru publikace
(`source=ISMD`/`ALL`) se diagramy vracejí bez ohledu na stav publikace. Neexistuje zdroj „jen
publikované" ani způsob, jak publikovat diagram nezávisle na jeho slovníku.

Tedy: při `result.type === 'DIAGRAM'` navigujte rovnou na diagram pomocí `result.slug`. U řádků DIAGRAM nikdy neodvozujte odkaz z `result.iri`.

## Autorizace

| Volání | Vlastník | Jiný přihlášený uživatel | Nepřihlášený |
|---|---|---|---|
| `GET …/all`, `GET …/detail` | 200 | **200** | 401 |
| `PUT …/layout` | 200 | **403** | 401 |
| `POST …/materialize` | 200 | **403** | 401 |

**Čtení je záměrně otevřené.** `canViewResource()` dovoluje **libovolnému přihlášenému uživateli** číst diagram kteréhokoli slovníku, v souladu s celokódovým přístupem ke čtení, kde každý přihlášený volající vidí všechny grafy. Pouze zápisové cesty jsou omezené na vlastnictví přes `belongsToUserBySlug`.

**Autorizace zápisu omezuje slug *i* IRI.** `belongsToUserBySlug` autorizuje slovník v cestě, ale každé IRI pojmu cestuje uvnitř těla požadavku, takže zápisová cesta navíc vyžaduje, aby každý odkazovaný pojem patřil do vlastního grafu slovníku daného diagramu — IRI uzlů, `conceptIri` overlayů i `addBroaderOn` / `broader` v op 6. Cizí IRI selže s **400** a nic neuloží; táž kontrola běží znovu při materializaci (`FOREIGN_CONCEPT`), takže řádek zapsaný ještě před vznikem této pojistky nelze aplikovat. Pojem, jehož řádek prostě *chybí*, odmítnut není — to je smazaný pojem, hlášený jako `skippedStale`.

**Čtení nikdy nezapisuje.** `GET …/detail` je jen pro čtení: slovník bez diagramu se obslouží z neuloženého zástupce v paměti, takže nevlastník, který otevře cizí plátno, nemůže řádek v `diagrams` přivést na svět. Řádek vznikne až prvním úspěšným zápisem a diagram se v `GET /all` objeví teprve poté, co byl skutečně uložen. Diagram patří tomu, kdo vlastní jeho slovník; samostatné pole vlastníka diagramu neexistuje.

## Čtení — `GET /api/diagram/{ontologySlug}/detail` → 200 · `DiagramDto`

Backend už spojil řádky rozvržení s živým obsahem pojmů a aplikoval overlay každého uzlu.

```jsonc
{
  "ontologySlug": "pracovni-pomer",
  "version": 7,                   // pošlete zpět v dalším PUT …/layout (optimistický zámek); null = řádek diagramu zatím neexistuje
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

  "pendingChangeCount": 1        // pohání ovládací prvek „Převzít N změn"
}
```

**Uzel nenese žádnou `version`.** Verze patří diagramu; klíč u žádného uzlu není — ani jako `null`.

`edgeKind` (jen na straně čtení) ∈ `VZTAH` · `SUBCLASS_OF` · `EXACT_MATCH`.

`DOMAIN` a `RANGE` neexistují — vztah je jedna hrana mezi svými dvěma třídami, ne uzel s odkazem na každou. `SUB_PROPERTY` a `SUB_RELATION` (`rdfs:subPropertyOf` mezi dvěma vlastnostmi nebo dvěma vztahy) se **na plátně nevykreslují**: ani jeden konec není uzel, takže odkaz nemá k čemu přiléhat, a byznysová sémantika je do vzniku požadavku nedefinovaná. Samotný vztah to neovlivňuje — v běžném editoru pojmů zůstává plně podporován.

## Zápis — Uložit: `PUT /api/diagram/{ontologySlug}/layout` · `DiagramLayoutDto`

Jedno volání nese vše: rozvržení **i** strukturální overlays. Odstraňte přechodná pole ReactFlow (`selected`, `dragging`, `measured`) a posílejte jen to, co se ukládá. Backend ignoruje obsah `data` u uzlu — strukturální záměr cestuje v `overlays`, nikdy v `data` uzlu.

### Jediné pravidlo, které je třeba si osvojit

**`nodes` a `edges` jsou úplná náhrada. `overlays` jsou přírůstkové.**

| Pole | Vynecháno / `null` | `[]` |
|---|---|---|
| `version` | **400** — vždy povinné | — |
| `nodes` | **400** — vždy povinné | plátno vyprázdněno (řádky nesoucí overlay přežijí — viz Uzly) |
| `edges` | všechny body lomu se vrátí k výchozímu vedení | totéž |
| **`overlays`** | **nasazené úpravy nedotčeny** | **nasazené úpravy nedotčeny** |

Pojem chybějící v `overlays` si ponechá, co je na něm nasazeno. **Jediný** způsob, jak overlay zahodit, je položka nesoucí `conceptIri` a nic jiného.

> **Proč se `overlays` liší od `edges`.** Nasazený overlay se v čtení objevit vůbec nemusí — jeho koncová třída může být mimo plátno, nebo mohl být pojem smazán pod diagramem — takže po klientovi nelze chtít, aby poslal zpět něco, co mu nikdy nebylo ukázáno. Kdyby vynechání znamenalo zahození, zničilo by to nasazenou práci při každém uložení sestaveném ze stavu plátna, což je přesně způsob, jak se automatické ukládání nad ReactFlow staví. Asymetrie je záměrná; „neopravujte" ji preventivním posíláním `overlays: []`.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  // pouze třídy; vztah ani vlastnost nikdy nejsou uzel
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false },
    // parentId/collapsed jsou volitelné — vynechané nebo null znamená bez rodiče / nesbaleno
    { "id": "iri:https://…/pojem/organizace",
      "position": { "x": 720, "y": 80 } }
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

**`nodes[]` je autoritativní pro členství na plátně.** Přítomný uzel zůstává (nebo je **přidán**, je-li jeho IRI na plátně nové; odpověď doplní jeho živý obsah), vynechaný uzel je **odebrán z plátna** (pojem zůstává nedotčen). K přidání uzlu stačí `{id, position}`; zbytek backend doplní z živého RDF.

Řádek, který nese nasazený overlay, **není** sklizen tím, že chybí v `nodes[]` — právě tak si VZTAH nebo VLASTNOST udrží svou nasazenou úpravu, protože ani jeden nikdy necestuje jako uzel.

### Hrany — jen body lomu

**Hrana ukládá právě dvě věci: `id` a `segments`.** Její existence, konce i druh se při každém čtení znovu odvozují z `živý ⊕ overlay`, takže `source`, `target` ani `edgeKind` **se na zápisu nepřijímají** — poslat je znamená, že se ignorují. Je to záměrné: uložený konec by mohl tiše odporovat projekci, kterou duplikuje, a přesně proti tomuto rozcházení je diagramová vrstva postavena. Chcete-li změnit, kam vztah míří, nasaďte `{domain, range}` na jeho overlay; hrana se přizpůsobí.

**Body lomu jsou úplná náhrada — pošlete zpět každou hranu, jejíž vedení chcete zachovat.** Uložení nahradí celou uloženou sadu bodů lomu, takže hrana vynechaná z `edges` se vrátí k výchozímu vedení. Sama hrana se dál vykresluje (znovu se projektuje z RDF). Přesměrování konce body lomu rovněž záměrně zahodí: geometrie nakreslená pro starý cíl by na nový neseděla.

`segments` jsou volitelné — vynechte je nebo pošlete `[]` u hrany s výchozím vedením; obojí se uloží jako „bez bodů lomu" a ani jedno nezapíše řádek. Body lomu jsou čistá prezentace: určují, jak se odkaz kreslí, a nenesou žádný význam pro spojované pojmy, takže je nic neodvozuje z RDF a nic je proti němu nevaliduje.

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

Každé úspěšné uložení posune verzi a vrátí hodnotu **po inkrementu**, takže lze řetězit uložení bez opětovného čtení. Plátno bez řádku diagramu posílá `version: 0`. Vynechání je **400** se jménem pole; ve schématu je deklarována jako povinná, takže generovaný klient ji typuje jako nevolitelnou.

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

## Materializace — `POST /api/diagram/{ontologySlug}/materialize` → `MaterializeResultDto`

Aplikuje každou nasazenou změnu. Jeden záznam na nasazenou **změnu** (změna může zasahovat dva pojmy). Per-změna částečně-OK; dvoupojmová změna (otočení, vztah→hierarchie) je vše-nebo-nic. Overlays se při úspěchu mažou, takže `pendingChangeCount` klesne na 0.

```jsonc
{
  "materialized": [
    { "nodeId": 1042, "conceptIri": "https://…/je-zamestnan-u", "op": "SWAP_DIRECTION" }
  ],
  "failed": [
    { "nodeId": 1055, "conceptIri": "https://…/organizace", "op": "SWAP_DIRECTION",
      "error": "VALIDATION", "message": "range must be a class", "status": 400 }
      // změna zůstává nasazená; uživatel opraví a spustí Převzít znovu
  ],
  "skippedStale": [
    { "nodeId": 1060, "conceptIri": "https://…/deleted-x" }   // pojem je pryč; změnu nelze aplikovat
  ]
}
```

`op` ∈ `SWAP_DIRECTION` · `CHANGE_HIERARCHY_TYPE` · `CHANGE_PROPERTY_PARENT` · `CONVERT_TO_HIERARCHY`. (Nastavení domény vlastnosti bez domény i přesměrování existující se obojí hlásí jako `CHANGE_PROPERTY_PARENT` — z overlaye je nelze rozlišit.)

**Otočení (op 2) se materializuje jako dvě nezávislé editace.** Obrácení hierarchie (B⊐A → A⊐B) se nasazuje jako overlay `broaderConcept` na *obou* pojmech; každý se materializuje samostatně jako `CHANGE_HIERARCHY_TYPE`. Neexistuje atomická dvouuzlová jednotka otočení — ani jedna polovina sama o sobě RDF nepoškodí a napůl aplikované otočení se hlásí po pojmech v `failed`, aby ho uživatel spustil znovu. Jedinou skutečně hlídanou dvouvolánovou jednotkou je `CONVERT_TO_HIERARCHY` (op 6).

**Chybové stavy, které FE řeší:**

- `error: "VALIDATION"` (HTTP 400) — editace pojmu neprošla validací; overlay zůstává, opravit a zkusit znovu.
- `error: "STALE_BASE"` (HTTP 409) — podkladový pojem byl od nasazení overlaye editován (běžným `/api/concept`). Overlay zůstává. **Viz pravidlo nápravy níže.**
- `error: "CASCADE_CONFLICT"` — op 6 (vztah→hierarchie) zablokována, protože domain/range jiného pojmu ukazuje na daný VZTAH (jeho smazání by kaskádovalo); zobrazit a nechat uživatele vyřešit.
- `error: "FOREIGN_CONCEPT"` (HTTP 400) — IRI pojmu ve změně patří do jiného slovníku než vlastního (buď samotný pojem, nebo `addBroaderOn` / `broader` v op 6). Diagram smí zapisovat jen pojmy vlastního slovníku; legitimní klient tohle nikdy nevyprodukuje.
- `error: "ERROR"` (HTTP 500) — neočekávané selhání na straně serveru; overlay zůstává. `message` je vždy obecné `"Nastala neočekávaná chyba."` — skutečná příčina se loguje na serveru a nikdy nevrací, takže FE ji má zobrazit tak, jak je, a nepokoušet se ji parsovat.
- `skippedStale` — odkazovaný pojem už neexistuje; nabídnout odebrání nebo znovuvytvoření.

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

---

*ISMD Tool · diagramová vrstva · FE / REST kontrakt · jediný zápisový endpoint · úplná náhrada rozvržení, přírůstkové overlays · čistě strukturální overlay · materializace per-změna částečně-OK*