# Diagramová vrstva: FE / REST kontrakt

> Stav: **hotovo** — `DiagramController` implementuje všechny níže uvedené endpointy a cesty jsou
> v allowlistu SecurityConfig. Kontrakt je stabilní; integrace FE může začít. Anglická verze:
> [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md). Architektura a zdůvodnění:
> [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md).

Kontrakt na drátě pro diagramovou funkci: **tence při zápisu, tučně při čtení.** Backend spojí řádky rozvržení s živým obsahem pojmů a aplikuje overlay každého pojmu, takže FE dostane payload, který lze předat téměř přímo do ReactFlow. Tento dokument je integrační referencí pro FE; proč je model takto tvarován, viz [`DIAGRAM_LAYER_CS.md`](./DIAGRAM_LAYER_CS.md).

**Co je uzel, co hrana a co řádek.** Plátno vykresluje každý typ pojmu ve tvaru, který odpovídá tomu, čím *je*:

| Typ pojmu | Vykreslen jako | Identita na drátě |
|---|---|---|
| `TRIDA` | **uzel** (`classNode`) | `nodes[].id` = `iri:<úplné-iri>` |
| `VZTAH` | **hrana** mezi jeho dvěma třídami | `edges[].id` = vlastní **IRI pojmu** daného vztahu |
| `VLASTNOST` | **řádek uvnitř** uzlu své doménové třídy | `nodes[].data.properties[].iri` |

Vztah je jedna hrana, nikoli uzel se spojnicí ke každému koncovému bodu — spojuje třídu v `rdfs:domain` s třídou v `rdfs:range`, což je přesně to, co daný pojem znamená. Vlastnost má jen doménu (jejím oborem hodnot je literálový datový typ), takže není druhý pojem, ke kterému by se kreslila; je řádkem ve třídě, která ji vlastní.

**Nedokončené pojmy na plátně nejsou.** VZTAH bez domény nebo oboru hodnot a VLASTNOST bez domény se prostě nevykreslí — není k čemu je připojit. Umísťují se přetažením z detailu slovníku, což je právě ta akce, která chybějící koncový bod doplní. Proto čtecí model nikdy nepotřebuje stav „visící hrana" ani „volně plovoucí vlastnost".

## REST rozhraní

Controller `DiagramController`, základ `/api/diagram`. Všechny odpovědi jsou zabaleny v `ApiResponseDto<T>`. Všechny cesty jsou autentizované (každá musí být v allowlistu SecurityConfig). Tento controller se dotýká *rozvržení + overlaye rozpracovaných úprav*; **Převzít** se v procesu rozvětvuje do stávajících pojmových služeb.

| Sloveso · Cesta | Účel | Tělo → Odpověď |
|---|---|---|
| `GET /all` | Odlehčený seznam všech diagramů (identita + počet uzlů), např. pro výběr diagramu. Libovolný přihlášený uživatel. | → `List<DiagramSummaryDto>` |
| `GET /{ontologySlug}/detail` | Načíst kanonický diagram, rozvržení spojené s živým obsahem pojmů s aplikovanými overlayi. Slovník, který zatím diagram nemá, se načte jako prázdné plátno — **čtení nic nevytváří**; řádek vznikne až prvním zápisem. | → `DiagramDto` (tučný, připravený k vykreslení) |
| `PUT /{ontologySlug}/layout` | **Uložit diagram.** Uložit rozvržení (pozice, viewport, body lomu hran). Idempotentní úplná náhrada — toto volání **je** členstvím na plátně: přítomný uzel je přidán (dosud neznámé IRI se v odpovědi hydratuje), vynechaný uzel je z plátna odebrán. **Žádné RDF.** | `DiagramLayoutDto` → `DiagramDto` (tučný, hydratovaný) |
| `PATCH /{ontologySlug}/nodes/overlay` | Nasadit/aktualizovat strukturální úpravu jednoho pojmu (cílová pole) pro pojem určený polem `conceptIri` **v těle požadavku**, nebo ji **zahodit** odesláním samotného `conceptIri` (všechna overlay pole null → návrat k živému obsahu). Funguje stejně pro třídu, vztah i vlastnost — všechny tři se adresují přes IRI. Nematerializuje se. | `NodeOverlayDto` → uzel |
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

  // uzly jsou POUZE třídy — vztah je hrana, vlastnost je řádek níže
  "nodes": [
    {
      "id": "iri:https://…/pojem/zamestnanec",
      "type": "classNode",
      "position": { "x": 240, "y": 80 },
      "parentId": null,
      "collapsed": false,           // vrací se zpět: co pošlete v PUT …/layout, dostanete zde
      "data": {
        "conceptType": "TRIDA",
        "iri": "https://…/pojem/zamestnanec",
        "slug": "pracovni-pomer-zamestnanec",       // FE odkazuje na /detail
        "label": { "cs": "Zaměstnanec", "en": "Employee" },
        "stale": false,                              // true ⇒ odkazovaný pojem byl smazán
        "hasPendingEdits": false,
        // VLASTNOSTi dané třídy, vykreslené jako řádky uvnitř uzlu. Vždy přítomné (prázdné pole,
        // nikdy null) a seřazené podle labelu, aby se řádky mezi čteními nepřeskupovaly.
        "properties": [
          {
            "iri": "https://…/pojem/datum-narozeni",
            "slug": "pracovni-pomer-datum-narozeni",
            "label": { "cs": "datum narození" },
            "rangeResolved": { /* datový typ — pravý sloupec řádku */ },
            "stale": false,
            "hasPendingEdits": true,
            "pendingEdit": { "domain": "https://…/pojem/osoba" }   // nasazený přesun k jiné třídě
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
      // VZTAH: JEDNA hrana mezi jeho dvěma třídami, nesoucí vlastní identitu pojmu.
      // id JE IRI pojmu daného vztahu — je unikátní na hranu a stabilní napříč načteními.
      "id": "https://…/pojem/je-zamestnan-u",
      "source": "iri:https://…/pojem/zamestnanec",   // jeho rdfs:domain  (živý ⊕ overlay)
      "target": "iri:https://…/pojem/organizace",    // jeho rdfs:range   (živý ⊕ overlay)
      "type": "relationEdge",
      "segments": [{ "x": 120, "y": 40 }],   // body lomu čistě pro FE; vynechané při výchozím vedení
      "data": {
        "edgeKind": "VZTAH",
        "pending": true,                     // koncový bod pochází z nematerializovaného overlaye
        "conceptType": "VZTAH",
        "iri": "https://…/pojem/je-zamestnan-u",
        "slug": "pracovni-pomer-je-zamestnan-u",
        "label": { "cs": "je zaměstnán u" }, // label je pouze živý; NELZE editovat přes overlay
        "stale": false,
        "hasPendingEdits": true,
        "pendingEdit": { "range": "https://…/pojem/organizace" }   // přesměrováno, dosud ne v RDF
      }
    },
    {
      // Holá RDF trojice — žádný pojem za ní, takže `data` nenese iri ani label.
      // Hrana s nenulovým data.iri je podložená pojmem (vybratelná, nasaditelná, prolinkovatelná);
      // hrana bez něj je prostý hierarchický/ekvivalenční odkaz. To je rozlišovač pro FE.
      "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "source": "iri:https://…/pojem/zamestnanec",
      "target": "iri:https://…/pojem/osoba",
      "type": "hierarchyEdge",
      "data": { "edgeKind": "SUBCLASS_OF", "pending": false }
    }
  ],

  "pendingChangeCount": 1        // řídí akci „Převzít N změn"
}
```

## Zápis — Uložit rozvržení: `PUT /api/diagram/{ontologySlug}/layout` · `DiagramLayoutDto`

Odstraňte přechodná pole ReactFlow (`selected`, `dragging`, `measured`) a pošlete jen to, co se ukládá. Backend zde ignoruje obsah `data` uzlů — toto volání je pouze rozvržení; strukturální úpravy jdou přes overlay endpoint.

**Hrana ukládá přesně dvě věci: `id` a `segments`.** Její existence, koncové body i druh se při každém čtení znovu odvozují z `živý ⊕ overlay`, takže `source`, `target` ani `edgeKind` se **při zápisu nepřijímají** — jejich odeslání je ignorováno. Je to záměr: uložený koncový bod by mohl tiše odporovat projekci, kterou duplikuje, a přesně tomuto rozcházení má diagramová vrstva bránit. Chcete-li změnit, kam vztah míří, nasaďte `{domain, range}` v jeho overlayi; hrana se přizpůsobí.

**Body lomu se ukládají úplnou náhradou — pošlete zpět každou hranu, u které chcete zachovat vedení.** Uložení nahradí celou uloženou sadu bodů lomu, takže hrana vynechaná z `edges` se vrátí k výchozímu vedení. Samotná hrana se stále vykreslí (znovu se projektuje z RDF). Přesměrování koncového bodu body lomu rovněž záměrně zahodí: geometrie nakreslená pro původní cíl by novému neodpovídala.

**Samotné `edges` je volitelné** — `null` i `[]` znamenají „nic ručně vedeného", což je přesně stav čerstvě automaticky rozvrženého plátna: ReactFlow rozmístil všechny uzly a uživatel zatím žádný bod lomu netáhl. Povinné jsou pouze `version` a `nodes`.

`segments` je volitelné také — pro hranu s výchozím vedením je vynechte nebo pošlete `[]`; obojí se uloží jako „bez bodů lomu" a ani v jednom případě se řádek vůbec nezapíše. Body lomu jsou čistě prezentační: určují, jak se spojnice vykreslí, a nenesou žádný význam pro propojené pojmy, takže se z RDF neodvozují ani se proti němu nevalidují.

**Toto volání je směrodatné pro členství na plátně.** Pole `nodes[]` je úplná sada — přítomný uzel je zachován (nebo **přidán**, je-li jeho IRI na plátně nové; odpověď `DiagramDto` hydratuje jeho živý obsah), vynechaný uzel je **odebrán z plátna** (pojem zůstává nedotčen). Přidání uzlu vyžaduje jen `{id, position}`; zbytek backend spojí z živého RDF.

**`version` je povinná — vraťte tu, ze které jste vykreslovali.** Protože členství je úplná náhrada, uložení postavené na zastaralém pohledu by tiše smazalo uzly přidané jiným editorem i s jejich nasazenými overlayi. Vraťte `version` z `DiagramDto`, ze kterého tato úprava vycházela (z načtení, nebo z odpovědi vašeho posledního uložení). Pokud mezitím uložil jiný editor, volání vrátí **409** a nic se nezapíše; načtěte diagram znovu a změny aplikujte znovu. Verzi posouvá každý úspěšný `PUT …/layout` **i** `PATCH …/nodes/overlay`, používejte tedy vždy nejnovější obdrženou hodnotu. Obě volání ji vracejí: `PUT …/layout` v `DiagramDto`, `PATCH …/nodes/overlay` v poli `version` vráceného uzlu — nasazení overlaye tedy nikdy nevynutí opětovné načtení jen kvůli udržení aktuální verze.

`version` je **vždy povinná** — její vynechání vrací **400** s uvedením pole, a to při každém uložení včetně prvního. Plátno, které dosud nemá řádek diagramu, posílá `version: 0`. Pole je ve schématu deklarováno jako povinné, takže je generovaný klient netypuje jako volitelné a nevynechá je.

### `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) — zápis se povedl, data pro vykreslení ne

Platí pro **oba** zapisovací endpointy. Zápis diagramu je čistě Postgres; obsah pojmů v odpovědi se pak čte z Fuseki. Obojí záměrně **není** v jedné transakci — načtení je HTTP volání, které může trvat desítky sekund, a držení databázového spojení po celou tu dobu by při pomalé Fuseki vyčerpalo pool a zablokovalo i nesouvisející endpointy. Zároveň by kvůli selhání *čtení* zahodilo zcela v pořádku provedený zápis rozvržení.

Důsledkem je chybový stav, který dříve neexistoval: zápis je **potvrzený a trvalý**, ale tělo odpovědi nelze sestavit.

```jsonc
{
  "success": false,
  "errorCode": "DIAGRAM_SAVED_READBACK_FAILED",
  "message": "Změny diagramu byly uloženy, ale nepodařilo se načíst obsah pojmů pro zobrazení. …",
  "data": { "version": 8 }        // verze PO potvrzeném zápisu
}
```

**Zápis neopakujte.** Uložení již proběhlo a verze se posunula; opětovné odeslání s verzí, kterou jste drželi, by bylo zastaralé a vrátilo by **409**. Buď znovu zavolejte `GET …/detail` a vykreslete aktuální stav, nebo pokračujte s verzí z `data`, pokud chcete uložit znovu bez tohoto načtení. Chápejte to jako „uloženo, ale zatím nemohu zobrazit výsledek" — nikdy jako „uložení selhalo".

Je to jediný stav, kdy odpověď s `success: false` přesto znamená, že zápis proběhl — proto má vlastní kód místo obecné chyby 500.

```jsonc
{
  "version": 7,
  "viewport": { "x": -120, "y": 40, "zoom": 0.85 },
  // pouze třídy; vztah ani vlastnost nikdy nejsou uzlem
  "nodes": [
    { "id": "iri:https://…/pojem/zamestnanec",
      "position": { "x": 240, "y": 80 }, "parentId": null, "collapsed": false },
    // parentId/collapsed jsou volitelné — vynechané nebo null znamená bez rodiče / nesbalené
    { "id": "iri:https://…/pojem/organizace",
      "position": { "x": 720, "y": 80 } }
  ],
  // pouze body lomu — vraťte id, které jste dostali při čtení; koncové body se odvozují, neposílají
  "edges": [
    { "id": "https://…/pojem/je-zamestnan-u",              // VZTAH: jeho IRI pojmu
      "segments": [{ "x": 120, "y": 40 }] },
    { "id": "edge|SUBCLASS_OF|https://…/pojem/zamestnanec|https://…/pojem/osoba",
      "segments": [] }                                     // [] nebo vynechané = výchozí vedení
  ]
}
```

`edgeKind` (pouze na straně čtení) ∈ `VZTAH` · `SUBCLASS_OF` · `EXACT_MATCH`.

`DOMAIN` a `RANGE` jsou pryč — vztah je jedna hrana mezi svými dvěma třídami, ne uzel se spojnicí ke každé z nich. `SUB_PROPERTY` a `SUB_RELATION` (`rdfs:subPropertyOf` mezi dvěma vlastnostmi nebo dvěma vztahy) se **na plátně nevykreslují**: ani jeden koncový bod není uzel, takže odkaz nemá k čemu se připojit, a byznys sémantika je do zadání požadavku nedefinovaná. Samotného vztahu se to netýká — v běžném editoru pojmů zůstává plně podporován.

## Zápis — nasadit strukturální úpravu: `PATCH /api/diagram/{ontologySlug}/nodes/overlay` · `NodeOverlayDto`

**Cíl se určuje polem `conceptIri` v těle požadavku, nikoli v cestě.** Adresuje *pojem*, nikoli uzel plátna — VZTAH se vykresluje jako hrana a VLASTNOST jako řádek uvnitř své třídy, a oba se nasazují tímto stejným polem podle vlastního IRI. Hodnotou je plné IRI, volitelně s prefixem `iri:`. Cestuje v těle, protože IRI pojmu obsahuje lomítka, která v segmentu cesty neprojdou — procentuálně zakódovaná je Tomcat odmítne (`400 Invalid URI: [The encoded slash character is not allowed]`), nezakódovaná vytvoří segmenty navíc, které neodpovídají žádnému mapování. `conceptIri` je povinné (`@NotBlank`).

Jen změněná strukturální pole. Uloženo do `pending_edit_json`; do RDF neposláno až do Převzít. Overlay je **pouze strukturální** — žádný `label`/`name`; editace labelu se dělá běžným editorem pojmů, ne diagramem (změna labelu přejmenuje IRI pojmu).

**Zahození = tělo obsahující pouze `conceptIri`.** `PATCH` s `{"conceptIri": "iri:…"}` (všechna overlay pole null) vymaže overlay uzlu a vrátí jej k živému obsahu — není žádný samostatný `DELETE …/overlay`. `conceptIri` je adresace, nikoli obsah, takže se nikdy nezapočítává do prázdnosti. Jakýkoli payload nesoucí overlay pole nahradí nasazený diff. **Explicitně prázdný seznam *není* zahození — znamená „vymaž tento predikát"**: např. `{ "conceptIri": "iri:…", "broaderConcept": [] }` nasadí „odeber všechny nadtřídy" (A-strana otočení op 2 zahazující svou poslední nadtřídu) a materializuje se jako vymazání `subClassOf`.

Každé tělo níže nese také `"conceptIri": "iri:…"` určující nasazovaný pojem (pro stručnost vynecháno). **Tažení konce hrany je úpravou pojmu** — přesměrování šipky vztahu nasadí `range` na VZTAHu a přetažení řádku vlastnosti do jiné třídy nasadí `domain` na VLASTNOSTI:

```jsonc
// op 1 (přehození směru, VZTAH):          { "domain": "iri:…/A", "range": "iri:…/B" }
// op 4/5 (rodič/doména vlastnosti):        { "domain": "iri:…/VlastniciTrida" }
// op 3 (podtřída → ekvivalent, TRIDA):     { "broaderConcept": [], "exactMatch": ["iri:…/B"] }
// op 2 (otočení): nasazeno na OBA uzly —   A: { "broaderConcept": [ …bez B ] }
//                                          B: { "broaderConcept": [ …, "iri:…/A" ] }
// op 6 (vztah → hierarchie, na VZTAHu):    { "convertToHierarchy": { "addBroaderOn": "iri:…/A", "broader": "iri:…/B" } }
```

Referenční pole `DiagramPendingEdit`:

| Pole | Platí pro | Význam |
|---|---|---|
| `domain` | VZTAH, VLASTNOST | `rdfs:domain` (IRI) |
| `range` | VZTAH | `rdfs:range` (IRI) |
| `broaderConcept` | TRIDA | seznam `subClassOf` (IRI) |
| `exactMatch` | libovolné | seznam `skos:exactMatch` (IRI) — „ekvivalent" v op 3 |
| `convertToHierarchy` | VZTAH | značka op 6: `{ addBroaderOn, broader }` — přidat broader na třídu, poté smazat tento VZTAH |

`superProperty` a `superRelation` byly **odstraněny** spolu s hranami `SUB_PROPERTY`/`SUB_RELATION`: diagram už nemůže nasadit změnu nadřazené vlastnosti/vztahu, protože ji neumí uživateli vykreslit, aby ji viděl nebo vrátil zpět. Použijte běžný editor pojmů.

**Odpověď — jediný nasazený pojem, nesoucí novou `version`.** PATCH vrací pouze řádek dotčeného pojmu (nikoli celý diagram), orazítkovaný verzí diagramu *po* tomto zápisu. Je to potvrzovací payload v obecném tvaru uzlu — VZTAH se zde vrátí, přestože se vykresluje jako hrana:

```jsonc
{
  "id": "iri:https://…/pojem/je-zamestnan-u",
  "type": "relationNode",         // tvar syrového řádku; plátno tento pojem stále kreslí jako HRANU
  "position": { "x": 520, "y": 210 },
  "parentId": null,
  "collapsed": false,
  "data": { "conceptType": "VZTAH", "iri": "https://…/pojem/je-zamestnan-u",
            "hasPendingEdits": true, "pendingEdit": { … } },
  "version": 8                    // posunutá verze — vraťte ji v dalším PUT …/layout
}
```

**`data.properties` je zde vždy prázdné**, i pro třídu. Jeho sestavení vyžaduje celý graf slovníku, což by zrušilo zúžené čtení jediného pojmu, kvůli jehož levnosti tato úsporná odpověď existuje — a nasazení mění overlay jednoho pojmu, nikoli seznam vlastností nějaké třídy. Ponechte si řádky z posledního `GET …/detail`; znovu načítejte jen tehdy, když jste nasadili vlastní `domain` nějaké vlastnosti, což je jediný případ, kdy se řádek přesouvá mezi třídami.

`version` se objevuje **pouze** v této úsporné odpovědi, kde není nadřazené `DiagramDto`, které by ji neslo. V poli `nodes[]` z `GET …/detail` je vynechána: verze patří diagramu, nikoli jednotlivému uzlu, a její opakování u každého uzlu by naznačovalo zámek na úrovni uzlu, který neexistuje. Zahození overlaye (tělo pouze s `conceptIri`) je také úspěšný PATCH, takže rovněž posouvá verzi a vrací ji stejným způsobem.

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
