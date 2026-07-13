# Diagramová vrstva: FE / REST kontrakt

> Stav: **kontrakt uzamčen; controller zatím není hotový.** Anglická verze:
> [`DIAGRAM_LAYER_API.md`](./docs/DIAGRAM_LAYER_API.md). Architektura a zdůvodnění:
> [`DIAGRAM_LAYER_CS.md`](./docs/DIAGRAM_LAYER_CS.md).

Kontrakt na drátě pro diagramovou funkci: **tence při zápisu, tučně při čtení.** Backend spojí řádky rozvržení s živým obsahem pojmů a aplikuje overlay každého uzlu, takže FE dostane payload, který lze předat téměř přímo do ReactFlow. Tento dokument je integrační referencí pro FE; proč je model takto tvarován, viz [`DIAGRAM_LAYER_CS.md`](./docs/DIAGRAM_LAYER_CS.md).

## REST rozhraní

Controller `DiagramController`, základ `/api/diagram`. Všechny odpovědi jsou zabaleny v `ApiResponseDto<T>`. Všechny cesty jsou autentizované (každá musí být v allowlistu SecurityConfig). Tento controller se dotýká *rozvržení + overlaye rozpracovaných úprav*; **Převzít** se v procesu rozvětvuje do stávajících pojmových služeb.

| Sloveso · Cesta | Účel | Tělo → Odpověď |
|---|---|---|
| `GET /all` | Odlehčený seznam všech diagramů (identita + počet uzlů), např. pro výběr diagramu. Libovolný přihlášený uživatel. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Načíst kanonický diagram, rozvržení spojené s živým obsahem pojmů s aplikovanými overlayi. Při prvním otevření líně vytvoří prázdný diagram. | → `DiagramDto` (tučný, připravený k vykreslení) |
| `PUT /{ontologySlug}/layout` | **Uložit diagram.** Uložit rozvržení (pozice, viewport, hrany-jako-projekce) *a* overlaye uzlů. Idempotentní úplná náhrada — toto volání **je** členstvím na plátně: přítomný uzel je přidán (dosud neznámé IRI se v odpovědi hydratuje), vynechaný uzel je z plátna odebrán. **Žádné RDF.** | `DiagramLayoutDto` → `DiagramDto` (tučný, hydratovaný) |
| `PATCH /{ontologySlug}/nodes/{nodeId}/overlay` | Nasadit/aktualizovat strukturální úpravu jednoho uzlu (cílová pole), nebo ji **zahodit** prázdným tělem (`{}` / null → návrat k živému obsahu). Nematerializuje se. | `NodeOverlayDto` → uzel |
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
| `lastModified` | `updatedAt` diagramu | |

Takže: při `result.type === 'DIAGRAM'` přejít rovnou na diagram pomocí `result.slug`. Pro řádky DIAGRAM nikdy neodvozovat odkaz z `result.iri`.

## Čtení — `GET /api/diagram/{ontologySlug}/detail` → 200 · `DiagramDto`

Backend již spojil řádky rozvržení s živým obsahem pojmů a aplikoval overlay každého uzlu.

```jsonc
{
  "ontologySlug": "pracovni-pomer",
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

```jsonc
{
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

## Zápis — nasadit strukturální úpravu: `PATCH /api/diagram/{ontologySlug}/nodes/{nodeId}/overlay` · `NodeOverlayDto`

Jen změněná strukturální pole. Uloženo do `pending_edit_json`; do RDF neposláno až do Převzít. Overlay je **pouze strukturální** — žádný `label`/`name`; editace labelu se dělá běžným editorem pojmů, ne diagramem (změna labelu přejmenuje IRI pojmu).

**Zahození = tělo se samými null.** `PATCH` s `{}` (nebo tělem, kde je každé pole null) vymaže overlay uzlu a vrátí jej k živému obsahu — není žádný samostatný `DELETE …/overlay`. Jakýkoli payload nesoucí pole nahradí nasazený diff. **Explicitně prázdný seznam *není* zahození — znamená „vymaž tento predikát"**: např. `{ "broaderConcept": [] }` nasadí „odeber všechny nadtřídy" (A-strana otočení op 2 zahazující svou poslední nadtřídu) a materializuje se jako vymazání `subClassOf`.

Hierarchie je závislá na typu — pošlete pole odpovídající typu pojmu uzlu:

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
- `skippedStale` — odkazovaný pojem již neexistuje; nabídnout odebrat-nebo-znovu-vytvořit.

---

*ISMD Tool · diagramová vrstva · FE / REST kontrakt · tenký zápis / tučné čtení · pouze strukturální overlay · per-změna částečně-OK materializace*
