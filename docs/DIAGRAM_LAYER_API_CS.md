# Diagramová vrstva: FE / REST kontrakt

> Stav: **hotovo** — `DiagramController` implementuje všechny níže uvedené endpointy a cesty jsou
> v allowlistu SecurityConfig. Kontrakt je stabilní; integrace FE může začít. Anglická verze:
> [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md). Architektura a zdůvodnění:
> [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md).

Kontrakt na drátě pro diagramovou funkci: **tence při zápisu, tučně při čtení.** Backend spojí řádky rozvržení s živým obsahem pojmů a aplikuje overlay každého uzlu, takže FE dostane payload, který lze předat téměř přímo do ReactFlow. Tento dokument je integrační referencí pro FE; proč je model takto tvarován, viz [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md).

## REST rozhraní

Controller `DiagramController`, základ `/api/diagram`. Všechny odpovědi jsou zabaleny v `ApiResponseDto<T>`. Všechny cesty jsou autentizované (každá musí být v allowlistu SecurityConfig). Tento controller se dotýká *rozvržení + overlaye rozpracovaných úprav*; **Převzít** se v procesu rozvětvuje do stávajících pojmových služeb.

| Sloveso · Cesta | Účel | Tělo → Odpověď |
|---|---|---|
| `GET /all` | Odlehčený seznam všech diagramů (identita + počet uzlů), např. pro výběr diagramu. Libovolný přihlášený uživatel. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Načíst kanonický diagram, rozvržení spojené s živým obsahem pojmů s aplikovanými overlayi. Slovník, který zatím diagram nemá, se načte jako prázdné plátno — **čtení nic nevytváří**; řádek vznikne až prvním zápisem. | → `DiagramDto` (tučný, připravený k vykreslení) |
| `PUT /{ontologySlug}/layout` | **Uložit diagram.** Uložit rozvržení (pozice, viewport, hrany-jako-projekce) *a* overlaye uzlů. Idempotentní úplná náhrada — toto volání **je** členstvím na plátně: přítomný uzel je přidán (dosud neznámé IRI se v odpovědi hydratuje), vynechaný uzel je z plátna odebrán. **Žádné RDF.** | `DiagramLayoutDto` → `DiagramDto` (tučný, hydratovaný) |
| `PATCH /{ontologySlug}/nodes/overlay` | Nasadit/aktualizovat strukturální úpravu jednoho uzlu (cílová pole) pro uzel určený polem `nodeId` **v těle požadavku**, nebo ji **zahodit** odesláním samotného `nodeId` (všechna overlay pole null → návrat k živému obsahu). Nematerializuje se. | `NodeOverlayDto` → uzel |
| `POST /{ontologySlug}/materialize` | **Převzít.** Aplikovat každou nasazenou změnu přes stávající CRUD pojmů → outbox → RDF; vícevolání vše-nebo-nic; per-změna částečně-OK. | → `MaterializeResultDto` + obnovený `DiagramDto` |

**Členství na plátně jede na uložení rozvržení.** Není žádný vyhrazený endpoint pro přidání/odebrání uzlu. Protože `PUT …/layout` je idempotentní úplná náhrada, **přidat** = uzel zahrnout (holé `{id, position}` pro pojem dosud ne na plátně; odpověď `DiagramDto` hydratuje jeho label/typ/slug z živého RDF) a **odebrat z plátna** = vynechat. Pojem není v žádném případě dotčen — jediné smazání v RDF, které diagram způsobí, je implicitní, uvnitř op 6, řešené `/materialize`.

**Žádný endpoint pro smazání pojmu, žádný coverage endpoint.** Vytvoření pojmu z plátna volá stávající `POST /api/concept/{slug}/create` (vlastnost/vztah lze vytvořit bez domény), poté FE uzel umístí zahrnutím do dalšího `PUT …/layout`. Coverage („které pojmy nejsou na plátně") je **množinový rozdíl na straně klienta** — FE už má úplný seznam pojmů ontologie i IRI uzlů na plátně; žádné volání serveru.

**Konvence id uzlu.** `iri:<úplné-iri>` pro každý uzel (všechny uzly odkazují na pojem). ReactFlow vyžaduje jen to, aby `node.id` byl unikátní řetězec; toto schéma je stabilní napříč načteními a umožňuje `PUT …/layout` přidat uzel podle IRI bez předchozího volání serveru.

## Hledání diagramů — `GET /api/diagram/all` a hledání `type=DIAGRAM`

Dvě cesty, jak diagramy nabídnout uživateli:

- **Seznam:** `GET /api/diagram/all` → `List<DiagramSummaryDto>` (`ontologySlug`, `ontologyName`, `graphName`, `nodeCount`, `updatedAt`). Libovolný přihlášený uživatel; odlehčené (bez spojení s živým obsahem).
- **Hledání:** `GET /api/search?type=DIAGRAM` vrátí jeden `SearchResultDto` na každý slovník, který má diagram (shoda podle slugu slovníku). Při výchozím hledání (`type` vynechán) se řádky diagramů objeví vedle řádků `ONTOLOGY`/`CONCEPT`; NKD se pro `type=DIAGRAM` přeskočí. `SearchResponseDto.totalDiagrams` nese celkový počet.

**Směrování výsledku hledání DIAGRAM → detail diagramu (obejití detailu slovníku).** `SearchResultDto` typu DIAGRAM je:

| Pole | Hodnota | Použití na FE |
|---|---|---|
| `type` | `DIAGRAM` | větvit podle něj |
| `slug` | **slug slovníku** | **směrovací klíč** → `GET /api/diagram/{slug}/detail` |
| `iri` | syntetické `{graphName}#diagram` | **jen pro deduplikaci — nelinkovat podle něj**; existuje, aby hledání `type=null` nesloučilo řádek DIAGRAM do řádku `ONTOLOGY` daného slovníku |
| `id` | id řádku diagramu | není id pojmu; ke směrování není potřeba |
| `ontologyIri` | IRI grafu slovníku | pokud potřebujete identitu slovníku |
| `isPublished` | stav publikace **slovníku** | diagram vlastní stav nemá — je viditelný přesně tak jako jeho slovník |
| `lastModified` | `updatedAt` diagramu | |

**Rozsah publikace.** Diagram zrcadlí viditelnost svého slovníku. `?source=UNPUBLISHED` vrací pouze
diagramy nepublikovaných slovníků (a `totalDiagrams` počítá jen je); bez filtru publikace
(`source=ISMD`/`ALL`) se diagramy vracejí bez ohledu na stav publikace. Zdroj „pouze publikované"
neexistuje a diagram nelze publikovat nezávisle na jeho slovníku.

Takže: při `result.type === 'DIAGRAM'` přejít rovnou na diagram pomocí `result.slug`. Pro řádky DIAGRAM nikdy neodvozovat odkaz z `result.iri`.

## Autorizace čtení (záměrná)

`GET …/all` i `GET …/detail` jsou chráněny přes `canViewResource()` — **libovolný přihlášený uživatel** může číst diagram jakéhokoli slovníku, v souladu s celokódovým modelem čtení, kde každý přihlášený volající vidí všechny grafy. Pouze zápisové cesty (`/layout`, `/overlay`, `/materialize`) jsou omezené na vlastníka přes `belongsToUserBySlug`.

**Autorizace zápisu omezuje slug *i* IRI.** `belongsToUserBySlug` autorizuje slovník v cestě, ale IRI pojmů cestují v těle požadavku, takže zápisové cesty navíc vyžadují, aby každý odkazovaný pojem patřil do vlastního grafu slovníku daného diagramu. IRI uzlu ukazující na pojem jiného slovníku způsobí u `PUT …/layout` chybu HTTP 400 a nic se neuloží; táž kontrola proběhne znovu při materializaci (`FOREIGN_CONCEPT`), takže ani řádek zapsaný před zavedením této pojistky nelze aplikovat, a vztahuje se i na `addBroaderOn` / `broader` u op 6, které pojmenovávají pojmy, jež nikdy nemusely být na plátně. Uzel, jehož řádek pojmu prostě *chybí*, odmítnut není — jde o smazaný pojem, hlášený jako `skippedStale`.

**Čtení nikdy nezapisuje.** `GET …/detail` je jen pro čtení: slovník bez diagramu je obsloužen z neuloženého objektu v paměti, takže otevřením cizího plátna nemůže nevlastník vytvořit řádek v `diagrams`. Řádek vznikne až prvním úspěšným zápisem a `GET /all` diagram uvede teprve tehdy, když byl skutečně uložen — pouhé otevření plátna jej tam nezobrazí. Diagram patří vlastníkovi svého slovníku; samostatné pole vlastníka diagramu neexistuje.

## Čtení — `GET /api/diagram/{ontologySlug}/detail` → 200 · `DiagramDto`

Backend již spojil řádky rozvržení s živým obsahem pojmů a aplikoval overlay každého uzlu.

```jsonc
{
  "ontologySlug": "pracovni-pomer",
  "version": 7,                   // vraťte v dalším PUT …/layout (optimistický zámek); null = řádek diagramu zatím neexistuje
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },

  "nodes": [
    {
      // živý pojem, žádné rozpracované úpravy — obsah z RDF, pozice z PG
      "id": "iri:https://…/pojem/zamestnanec",
      "type": "classNode",
      "position": { "x": 240, "y": 80 },
      "parentId": null,
      "data": {
        "conceptType": "TRIDA",
        "iri": "https://…/pojem/zamestnanec",
        "slug": "pracovni-pomer-zamestnanec",       // FE odkazuje na /detail
        "label": { "cs": "Zaměstnanec", "en": "Employee" },
        "stale": false,                              // true ⇒ odkazovaný pojem byl smazán
        "hasPendingEdits": false
      }
    },
    {
      // živý pojem VZTAH s nasazenou strukturální úpravou — obsah je živý ⊕ overlay
      "id": "iri:https://…/pojem/je-zamestnan-u",
      "type": "relationNode",
      "position": { "x": 520, "y": 210 },
      "data": {
        "conceptType": "VZTAH",
        "iri": "https://…/pojem/je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" },         // label je pouze živý; NELZE editovat přes overlay
        "stale": false,
        "hasPendingEdits": true,
        "pendingEdit": {                             // strukturální diff, aby FE mohl zobrazit odznak/diff
          "range": "iri:https://…/pojem/organizace"  // přesměrovaný obor hodnot, dosud ne v RDF
        }
      }
    }
  ],

  "edges": [
    {
      // projekce z (živý ⊕ overlay) oboru hodnot uzlu VZTAHu — odráží nasazené přesměrování
      "id": "e-201",
      "source": "iri:https://…/pojem/je-zamestnan-u",
      "target": "iri:https://…/pojem/organizace",
      "type": "relationEdge",
      "sourceHandle": null, "targetHandle": null,
      "markerEnd": { "type": "arrowclosed" },
      "data": { "edgeKind": "RANGE", "pending": true }   // pending ⇒ koncový bod pochází z overlaye
    }
  ],

  "pendingChangeCount": 1        // řídí akci „Převzít N změn"
}
```

## Zápis — Uložit rozvržení: `PUT /api/diagram/{ontologySlug}/layout` · `DiagramLayoutDto`

Odstraňte přechodná pole ReactFlow (`selected`, `dragging`, `measured`) a pošlete jen to, co se ukládá. Backend zde ignoruje obsah `data` uzlů — toto volání je pouze rozvržení; strukturální úpravy jdou přes overlay endpoint. Hrany jsou projekce; poslání aktuální sady uloží jejich úchyty/pozice, ale směrodatnou hodnotou koncového bodu pro nasazené přesměrování je vždy overlay uzlu (backend při čtení znovu projektuje).

**Toto volání je směrodatné pro členství na plátně.** Pole `nodes[]` je úplná sada — přítomný uzel je zachován (nebo **přidán**, je-li jeho IRI na plátně nové; odpověď `DiagramDto` hydratuje jeho živý obsah), vynechaný uzel je **odebrán z plátna** (pojem zůstává nedotčen). Přidání uzlu vyžaduje jen `{id, position}`; zbytek backend spojí z živého RDF.

**`version` je povinná — vraťte tu, ze které jste vykreslovali.** Protože členství je úplná náhrada, uložení postavené na zastaralém pohledu by tiše smazalo uzly přidané jiným editorem i s jejich nasazenými overlayi. Vraťte `version` z `DiagramDto`, ze kterého tato úprava vycházela (z načtení, nebo z odpovědi vašeho posledního uložení). Pokud mezitím uložil jiný editor, volání vrátí **409** a nic se nezapíše; načtěte diagram znovu a změny aplikujte znovu. Verzi posouvá každý úspěšný `PUT …/layout` **i** `PATCH …/nodes/overlay`, používejte tedy vždy nejnovější obdrženou hodnotu.

`version` smí být `null` **pouze** při úplně prvním uložení plátna, které dosud nemá řádek diagramu. Jakmile diagram existuje, je `null` verze chybou 409 — klient, který nikdy nenačetl aktuální stav, nemůže bezpečně provést úplnou náhradu členství.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false },
    { "id": "iri:https://…/pojem/je-zamestnan-u",
      "position": { "x": 520, "y": 210 } }
  ],
  "edges": [
    { "id": "e-201", "source": "iri:https://…/pojem/je-zamestnan-u",
      "target": "iri:https://…/pojem/organizace", "edgeKind": "RANGE" },
    // vlastnost třídy je hrana DOMAIN z uzlu vlastnosti k její třídě
    { "id": "e-202", "source": "iri:https://…/pojem/datum-narozeni",
      "target": "iri:https://…/pojem/zamestnanec", "edgeKind": "DOMAIN" }
  ]
}
```

`edgeKind` ∈ `DOMAIN` · `RANGE` · `SUBCLASS_OF` · `SUB_PROPERTY` · `SUB_RELATION` · `EXACT_MATCH`.

## Zápis — nasadit strukturální úpravu: `PATCH /api/diagram/{ontologySlug}/nodes/overlay` · `NodeOverlayDto`

**Cílový uzel se určuje polem `nodeId` v těle požadavku, nikoli v cestě.** Id uzlu má tvar `iri:<plné-iri>` a IRI pojmu obsahuje lomítka, která v segmentu cesty neprojdou — procentuálně zakódovaná je Tomcat odmítne (`400 Invalid URI: [The encoded slash character is not allowed]`), nezakódovaná vytvoří segmenty navíc, které neodpovídají žádnému mapování. `nodeId` je povinné (`@NotBlank`).

Jen změněná strukturální pole. Uloženo do `pending_edit_json`; do RDF neposláno až do Převzít. Overlay je **pouze strukturální** — žádný `label`/`name`; editace labelu se dělá běžným editorem pojmů, ne diagramem (změna labelu přejmenuje IRI pojmu).

**Zahození = tělo obsahující pouze `nodeId`.** `PATCH` s `{"nodeId": "iri:…"}` (všechna overlay pole null) vymaže overlay uzlu a vrátí jej k živému obsahu — není žádný samostatný `DELETE …/overlay`. `nodeId` je adresace, nikoli obsah, takže se nikdy nezapočítává do prázdnosti. Jakýkoli payload nesoucí overlay pole nahradí nasazený diff. **Explicitně prázdný seznam *není* zahození — znamená „vymaž tento predikát"**: např. `{ "nodeId": "iri:…", "broaderConcept": [] }` nasadí „odeber všechny nadtřídy" (A-strana otočení op 2 zahazující svou poslední nadtřídu) a materializuje se jako vymazání `subClassOf`.

Hierarchie je závislá na typu — pošlete pole odpovídající typu pojmu uzlu:

Každé tělo níže nese také `"nodeId": "iri:…"` určující nasazovaný uzel (pro stručnost vynecháno):

```jsonc
// op 1 (přehození směru, VZTAH):          { "domain": "iri:…/A", "range": "iri:…/B" }
// op 4/5 (rodič/doména vlastnosti):        { "domain": "iri:…/VlastniciTrida" }
// op 3 (podtřída → ekvivalent, TRIDA):     { "broaderConcept": [], "exactMatch": ["iri:…/B"] }
// op 2 (otočení): nasazeno na OBA uzly —   A: { "broaderConcept": [ …bez B ] }
//                                          B: { "broaderConcept": [ …, "iri:…/A" ] }
// op 6 (vztah → hierarchie, uzel VZTAHu):  { "convertToHierarchy": { "addBroaderOn": "iri:…/A", "broader": "iri:…/B" } }
```

Referenční pole `DiagramPendingEdit`:

| Pole | Platí pro | Význam |
|---|---|---|
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | seznam `subClassOf` (IRI) |
| `superProperty` | VLASTNOST | seznam `subPropertyOf` (IRI) |
| `superRelation` | VZTAH | seznam `subPropertyOf` (IRI) |
| `exactMatch` | libovolné | seznam `skos:exactMatch` (IRI) — „ekvivalent" v op 3 |
| `convertToHierarchy` | VZTAH | značka op 6: `{ addBroaderOn, broader }` — přidat broader na třídu, poté smazat tento VZTAH |

## Materializace — `POST /api/diagram/{ontologySlug}/materialize` → `MaterializeResultDto`

Aplikuje každou nasazenou změnu. Jedna položka na nasazenou **změnu** (změna může zahrnovat dva pojmy). Per-změna částečně-OK; dvoupojmová změna (otočení, vztah→hierarchie) je vše-nebo-nic.

```jsonc
{
  "materialized": [
    { "nodeId": 1042, "conceptIri": "https://…/je-zamestnan-u", "op": "SWAP_DIRECTION" }
  ],
  "failed": [
    { "nodeId": 1055, "conceptIri": "https://…/organizace", "op": "SWAP_DIRECTION",
      "error": "VALIDATION", "message": "range must be a class", "status": 400 }
      // změna ponechána nasazená; uživatel opraví a spustí Převzít znovu
  ],
  "skippedStale": [
    { "nodeId": 1060, "conceptIri": "https://…/deleted-x" }   // pojem pryč; změnu nelze aplikovat
  ]
}
```

`op` ∈ `SWAP_DIRECTION` · `CHANGE_HIERARCHY_TYPE` · `CHANGE_PROPERTY_PARENT` · `CONVERT_TO_HIERARCHY`. (Nastavení domény vlastnosti bez domény i přesměrování existující se hlásí jako `CHANGE_PROPERTY_PARENT` — z overlaye nerozlišitelné.)

**Otočení hierarchie (op 2) se materializuje jako dvě nezávislé úpravy.** Obrácení hierarchie (B⊐A → A⊐B) se nasadí jako overlay `broaderConcept` na *oba* uzly; každý se materializuje nezávisle jako `CHANGE_HIERARCHY_TYPE`. Není žádná atomická dvouuzlová jednotka otočení — žádná polovina sama o sobě nepoškodí RDF a částečně aplikované otočení je hlášeno po uzlech v `failed` k opětovnému spuštění. Jediná skutečně gatovaná dvouvolání je `CONVERT_TO_HIERARCHY` (op 6).

**Chybové případy, které FE řeší:**

- `error: "VALIDATION"` (HTTP 400) — úprava pojmu neprošla validací; overlay ponechán, opravit a zkusit znovu.
- `error: "STALE_BASE"` (HTTP 409) — podkladový pojem byl od nasazení overlaye editován (běžným `/api/concept`); FE by měl diagram znovu načíst a znovu nasadit.
- `error: "CASCADE_CONFLICT"` — op 6 (vztah→hierarchie) zablokována, protože doména/obor hodnot jiného pojmu míří na daný VZTAH (jeho smazání by kaskádovalo); zobrazit a nechat uživatele vyřešit.
- `error: "FOREIGN_CONCEPT"` (HTTP 400) — IRI pojmu v dané změně patří jinému slovníku než diagramu (buď samotný uzel, nebo `addBroaderOn` / `broader` u op 6). Diagram smí zapisovat jen pojmy vlastního slovníku; legitimní klient tuto chybu nikdy nevyvolá.
- `error: "ERROR"` (HTTP 500) — neočekávaná chyba na straně serveru; překryv zůstává zachován. `message` je vždy obecné `"Nastala neočekávaná chyba."` — konkrétní příčina se pouze loguje na serveru a nikdy se nevrací, takže FE ji má zobrazit tak, jak je, a nepokoušet se ji parsovat.
- `skippedStale` — odkazovaný pojem již neexistuje; nabídnout odebrat-nebo-znovu-vytvořit.

---

*ISMD Tool · diagramová vrstva · FE / REST kontrakt · tenký zápis / tučné čtení · pouze strukturální overlay · per-změna částečně-OK materializace*
