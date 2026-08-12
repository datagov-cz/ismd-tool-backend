# Diagramová vrstva: architektura a návrh

> Stav: **hotovo** — entitní vrstva/migrace, služby, controller i zabezpečení jsou implementovány a pokryty
> testy. Před vydáním zbývá: end-to-end ověření proti dev Postgres + Fuseki. Anglická verze:
> [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md). FE/REST kontrakt: [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md).

Plátno založené na ReactFlow, které vizuálně zobrazuje a edituje ISMD ontologii — jeden kanonický diagram na ontologii — s modelem perzistence navrženým tak, aby se diagram *nikdy* nemohl tiše stát rozcházející se kopií dat pojmů.

## Jaký problém řešíme

Diagram je **zároveň** živým obrazem reálného ISMD slovníku **i** pracovní plochou s vlastním CRUD. Právě tato kombinace vyvolává obavy z „rozcházení" (drift). Tato codebase už podobný problém s „dvě úložiště držící kopie téhož obsahu" řešila: duální zápis PG↔TDB2 bez sdílené transakce, outbox, rekonciliátor, dokumentovaný základ známého šumu (viz [`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Diagram, který by ukládal vlastní kopii obsahu pojmu, by tento problém — třetí úložiště — otevřel znovu.

## Řídicí princip

> **Obsah, který diagram vlastní, je vždy *rozpracovaná strukturální úprava reálného pojmu*, nikdy volně stojící třetí kopie.** Diagram vlastní tři věci: *rozvržení* (pozice, seskupení, viewport), *odkazy* na reálné pojmy (přes IRI) a **overlay rozpracovaných úprav** — nasazené, nezapsané strukturální změny pojmů, které již existují a již mají IRI. Neexistují žádné uzly bez IRI; každý uzel odpovídá materializovanému pojmu. Overlay je *diff čekající na aplikaci* a explicitní **Převzít (materializace)** ho protlačí přes stávající CRUD `/api/concept` → outbox → RDF, načež se overlay vyprázdní a živý pojem je opět jediným vlastníkem. Samostatné **Uložit** overlay zapíše do Postgresu, aniž by se dotklo RDF.

**Proč to zůstává bezpečné vůči rozcházení.** Overlay obsah pojmu skutečně drží — záměrná, ohraničená výjimka z pravidla „nikdy nevlastní obsah" — ale je bezpečný, protože je:

1. **Diff, ne kopie** — jen *změněná* strukturální pole, navázaná na reálné IRI, nikdy samostatný pojem.
2. **Explicitně dočasný** — jeho celý smysl je být materializován a vyprázdněn; naplněný overlay je „úkol k provedení", ne zdroj pravdy.
3. **Jediný vlastník při čtení** — vykreslený obsah je `živý pojem ⊕ overlay`; po materializaci je overlay prázdný a živý pojem je jediným vlastníkem. Žádná synchronizace na pozadí, žádný diagramový rekonciliátor — overlay se rekonciliuje *tím, že je materializován*.

## Tři druhy rozcházení — nebezpečný byl vždy jen jeden

- **🟢 Rozcházení rozvržení — bezpečné, záměrně.** Kde uzel leží, co je sbalené, viewport. RDF k tomu nemá co říct. Pouze diagramové, nezávislé, nikdy se nesynchronizuje.
- **🟡 Rozpracované úpravy — záměrné, ohraničené, samoopravné.** Nasazená změna domény/oboru hodnot/hierarchie, dosud nematerializovaná. Obsah vlastněný diagramem, ale navázaný diff, který existuje proto, aby byl materializován, a při Převzít se vyprázdní. Nemůže tiše přetrvat jako stínová pravda: FE ho vykresluje jako „N nezapsaných změn" a Převzít je vědomá akce uživatele.
- **🔴 Tichá třetí kopie — konstrukčně zakázaná.** Uzel držící *samostatnou* kopii obsahu pojmu, která se rozchází bez vlastníka. Overlay není nikdy samostatný (vždy navázaný na živé IRI) a nikdy trvalý (Převzít ho vyprázdní).

Vše ostatní je **zastaralost**, řešená při čtení: **visící odkaz** (uzel míří na pojem smazaný běžným CRUD → uzel označen `stale`) a **mezera v pokrytí** (nové pojmy dosud nejsou na plátně → diagram je záměrně podmnožinovým pohledem). Pokrytí se počítá **na frontendu** — ten už má úplný seznam pojmů slovníku i IRI uzlů na plátně, takže „které pojmy nejsou na plátně" je množinový rozdíl na straně klienta, ne serverový endpoint.

## Dvě akce

Plátno vystavuje pro obsah přesně dvě akce dotýkající se backendu:

- **Uložit diagram** — uloží rozvržení *a* nasazené strukturální úpravy dosud neprojektované do slovníku. Pouze Postgres; **nikdy se nedotýká RDF.**
- **Převzít (materializovat do slovníku)** — aplikuje nasazené úpravy na pojmy slovníku přes stávající CRUD `/api/concept` → outbox → RDF a poté overlay vyprázdní.

## Kam který zápis míří

Vytvoření pojmu a odebrání uzlu jsou okamžité/lokální; **strukturální úpravy se nasazují až do Převzít.** Existuje jen jeden druh uzlu — každý uzel odkazuje na materializovaný pojem.

**Okamžité — nenasazované:**

- **Vytvoření pojmu z plátna** → stávající `POST /api/concept` create → outbox → RDF. Vlastnost nebo vztah lze vytvořit *bez domény* (přesto plně materializované, s reálným IRI); doména se doplní později jako nasazená úprava. (Pozn.: vlastnost vždy dostane `rdfs:range` — výchozí `Literal` — takže skutečně chybět může jen *doména*.) FE jej pak umístí na plátno zahrnutím do dalšího uložení rozvržení.
- **Přidání / odebrání uzlu z plátna** → jede na **uložení rozvržení** (`PUT …/layout`, idempotentní úplná náhrada): přítomný uzel je na plátně, vynechaný uzel z něj zmizí. **Pojem zůstává v obou případech nedotčen.** Není žádný vyhrazený endpoint pro přidání/odebrání uzlu ani akce „smazat pojem".

**Nasazované — Uložit je drží v PG, Převzít je aplikuje do RDF.** Overlay nasazuje přesně tyto strukturální úpravy, vyjádřené jako *cílové hodnoty polí* na dotčených uzlech — nikoli jako log operací:

| # | Akce uživatele | Cílová úprava | Concept-CRUD při Převzít |
|---|---|---|---|
| 1 | Přehození směru vztahu | prohodit `domain` ⇄ `range` na VZTAHu | 1 úprava |
| 2 | Otočení směru hierarchie (B⊐A → A⊐B) | zrušit hierarchický odkaz na A, přidat na B | 2 úpravy — **jedna jednotka, vše nebo nic** |
| 3 | Změna typu hierarchie (podtřída ⇄ ekvivalent) | vyprázdnit seznam podtříd, naplnit `exactMatch` (nebo obráceně) | 1 úprava |
| 4 | Změna nadřazené třídy vlastnosti | změnit `domain` (`rdfs:domain`) VLASTNOSTI | 1 úprava |
| 5 | Doplnění domény u vlastnosti bez domény | vyplnit `domain` VLASTNOSTI | 1 úprava |
| 6 | Převod vztahu na hierarchii | přidat hierarchický odkaz na cílovou třídu, poté smazat VZTAH | 2 volání — **jedna jednotka, vše nebo nic** |
| 7 | Odebrání vlastnosti/vztahu *z plátna* | pouze smazání diagramového řádku | žádné (není to RDF změna) |

**Hierarchie je závislá na typu.** „Nadřazený" jsou tři různé predikáty: třída používá `subClassOf` (`broaderConcept`), vlastnost `subPropertyOf` (`superProperty`), vztah `subPropertyOf` (`superRelation`). Overlay nese pole odpovídající typu pojmu uzlu. „Ekvivalent" (op 3) znamená `skos:exactMatch`, nezávislý symetrický predikát — *ne* směrovanou hierarchii a *ne* jediný přepínač „typu hierarchie".

**Jediné smazání v RDF, které diagram může způsobit, je implicitní** — smazání VZTAHu v op 6, a to až poté, co je úspěšně přidána nahrazující hierarchická hrana. Neexistuje samostatná akce „smazat pojem". Op 6 se nabízí jen tehdy, když na `domain`/`range` daného VZTAHu nic nemíří (jinak by jeho smazání tranzitivně kaskádovalo další pojmy); jinak převod vyvolá konflikt.

**Op 6 nadtřídu přidává, hierarchii cílové třídy nikdy nenahrazuje.** Pole `broaderConcept` v editačním modelu pojmu je *úplná náhrada*, takže applier načte aktuální množinu `rdfs:subClassOf` dané třídy a předá sjednocení. Bez tohoto sloučení by převod tiše zahodil všechny dosavadní nadtřídy — bez hlášení a v rámci požadavku nevratně, protože VZTAH je mazán ve stejné transakci. Zajištěno testem `op6_preservesTargetClassExistingBroaderConcepts`; zrcadlený predikát `nadřazená-třída` se přepisuje ze stejné sloučené množiny, takže se oba nikdy nerozejdou.

## Hrany jsou projekce, ne obsah

Vztah (VZTAH) je sám pojmem — uzlem. Jeho `rdfs:domain`/`rdfs:range` jsou pole na tomto uzlu, nasazená v overlayi uzlu. Hrany `DOMAIN`/`RANGE` vedené z uzlu VZTAHu k cílovým třídám jsou *vizuálním vykreslením* těchto polí. Vlastnost třídy (VLASTNOST) je rovněž uzel, spojený se svou vlastnící třídou hranou `DOMAIN` z uzlu vlastnosti k uzlu třídy.

Proto **tažení hrany je úpravou uzlu** (přesměrování konce `RANGE` aktualizuje pole `range` v overlayi uzlu VZTAHu) a **nakreslení nové hrany vztahu je vytvořením pojmu VZTAH** (operace nad uzlem). Hrany nikdy nehromadí vlastní rozpracovaný stav; při čtení se znovu projektují z `živý pojem ⊕ overlay`. Overlay uzlu je jediným zdrojem pravdy pro doménu/obor hodnot/hierarchii.

## PG entitní model

Tři entity ve dvou + jedné tabulkách, podle vzoru `CommentEntity` (FK na `ontologies.id`, čisté PG, žádný outbox). Rozvržení i overlay rozpracovaných úprav žijí zcela v Postgresu.

**`diagrams`** — jeden kanonický diagram na slovník (`@OneToOne` unikátní FK → `OntologyMetadataEntity`, ON DELETE CASCADE), viewport pan/zoom, sloupec `@Version` pro optimistický zámek a kolekce `@OneToMany` uzlů/hran (cascade ALL, orphanRemoval). Agregátní metody `addNode`/`addEdge`/`removeNode` drží volající na spravovaných instancích; `touch()` vynutí posun `@Version` i při změně jen uzlů/hran.

**`diagram_nodes`** — každý řádek odkazuje na materializovaný pojem: `concept_iri` **NOT NULL**, `backing` (jednohodnotové `ISMD_CONCEPT`, ponecháno pro možnou budoucí rozšiřitelnost na NKD), pozice, `collapsed`, `parent_node_id` a `pending_edit_json` — **nullable**; je-li neprázdné, drží strukturální diff overlaye. `pending_edit_json` **koexistuje** s `concept_iri` (je to diff, ne náhrada). Ochrana `@PrePersist`/`@PreUpdate` a Postgres CHECK vynucují, že `concept_iri` je vždy přítomné.

**`diagram_edges`** — koncové body (`source_node_id`/`target_node_id`, oba s indexem na FK a kaskádovým mazáním), `edge_kind` a nullable kotvy úchytů. Pouze koncové body + druh; **žádný obsah**.

Model obsahu overlaye (`DiagramPendingEdit`) je **pouze strukturální**: `domain`, `range`, hierarchické pole podle typu (`broaderConcept` / `superProperty` / `superRelation`), `exactMatch` a značka `convertToHierarchy` pro op 6.

## Sémantika materializace

Materializace se rozvětvuje **v procesu** do stávajících pojmových služeb (ne přes HTTP volání sebe sama), takže znovu využívá stávající validaci a outbox. Pro každý uzel s neprázdným overlayem:

1. Rozliší `concept_iri` uzlu na číselné id pojmu (edit/delete služby klíčují dle id). Chybějící řádek znamená, že pojem byl smazán → hlášeno jako `skippedStale`.
2. Ověří, že rozlišený pojem patří do **vlastního grafu slovníku daného diagramu**. Endpointy autorizují slug slovníku, ale IRI pojmů cestují v těle požadavku — bez tohoto omezení by libovolný přihlášený uživatel mohl editovat (a přes op 6 smazat) pojmy jiného uživatele. Cizí IRI je odmítnutý požadavek (`FOREIGN_CONCEPT`, 400), ne zastaralý odkaz. Totéž platí pro `addBroaderOn` a `broader` u op 6, které pojmenovávají pojmy, jež nikdy nemusely být na plátně. `PUT …/layout` kontrolu vynucuje i při vstupu, takže cizí IRI se jako uzel vůbec neuloží.
3. Ověří, že pojem nebyl pod overlayem od jeho nasazení editován (otisk „stale-base" na `updatedAt` pojmu). Pokud se posunul, změna je hlášena jako konflikt, místo aby tiše přepsala mezitimní úpravu. `updatedAt` musí razítkovat každá cesta měnící RDF pojmu, nejen editor pojmů — činí tak i endpointy UPDATE/REMOVE lokálních kopií NKD a warmer vlastníka (pokud skutečně vytvoří deltu), jinak by se RDF posunulo, otisk zůstal zmrazený a tato kontrola by změnu tiše minula.
4. Sestaví **polem omezenou** úpravu nesoucí jen změněné predikáty a aplikuje ji. Polem omezená je nutná proto, že zobrazovací read model nevystavuje boolean `isPublic` a editační cesta odstraní-a-podmíněně-znovu-přidá veřejnou/neveřejnou klasifikaci — úprava plným snímkem s null `isPublic` by ji tiše zahodila. (Jediný editační pomocník, který zde není null-safe, je odpovídajícím způsobem zpevněn - bude pravděpodoně ošetřeno, aby null-safe byl)

**Granularita:** per-změna, částečně-OK. Změna zahrnující dvě concept-CRUD volání (otočení, vztah→hierarchie) je vše-nebo-nic — druhé volání je podmíněno prvním a overlay se vyprázdní jen při úplném úspěchu; selhání ponechá celou změnu nasazenou a nahlášenou.

## Verzování

Protože diagram nedrží žádný *samostatný* obsah pojmu, „verzování diagramu" zůstává malé. **Historie rozvržení** je čistě PG záležitost (snímky řádků rozvržení) — odloženo; nejprve jedno aktuální rozvržení. **Nasazené úpravy** jsou záměrně dočasné a historii verzí nepotřebují. **Forma verzování pojmů/slovníků** už žije ve stávajícím modelu (RDF, publikováno-vs-koncept, odchylky) a diagram ho zdědí zdarma čtením živého obsahu.

## Zápis do PG a čtení z Fuseki nikdy nejsou v jedné transakci

Každý endpoint diagramu vrací **tučnou** odpověď: řádky rozvržení z PG spojené s živým obsahem pojmů načteným z Fuseki. Zjevná implementace — jedna metoda s `@Transactional`, která dělá obojí — je špatně hned ze dvou důvodů.

**Drží databázové spojení po celou dobu externího HTTP volání.** Načtení z Fuseki prochází semaforem, jehož samotné získání má povoleno 30 s, ještě než se přenese jediný bajt. Hikari pool má 20 spojení. Pomalá nebo zahlcená Fuseki tedy nezpomalí jen požadavky na diagram — drží spojení, dokud není pool prázdný, a začnou selhávat i nesouvisející endpointy.

**Kvůli selhání čtení zahodí v pořádku provedený zápis.** Vrstva diagramu *nikdy nezapisuje RDF* — jediná volání Fuseki v této službě jsou čtení `fetchGraph` a nastávají striktně až po dokončení všech zápisů do PG. Není zde tedy žádný duální zápis, který by bylo třeba držet atomicky; transakce chránila zápis před selháním, které ho nemůže poškodit.

Služba proto každou veřejnou metodu rozděluje: krok s `@Transactional` (`commitLayout` / `commitOverlay` / `loadForRead`) provede práci v PG a vrátí **odpojený snímek** všeho, co odpověď potřebuje — verzi, viewport, řádky uzlů, typy a slugy pojmů. Načtení z Fuseki i sestavení odpovědi pak běží bez otevřené transakce. Krok se volá přes `@Lazy` self-proxy: přímé `this.commitLayout(...)` by obešlo Spring proxy a tiše běželo zcela bez transakce.

**Pořadí je nejprve zápis, pak čtení.** Opačné pořadí by sice odstranilo níže popsaný chybový stav, ale platilo by úplným načtením grafu při každém zastaralém uložení, jen aby ho zahodilo — a 409 je na sdíleném plátně *běžný* výsledek, nikoli výjimečný. Zároveň by rozšířilo okno mezi kontrolou verze a potvrzením zápisu. Zápis první ponechává levné odmítnutí na straně PG jako první krok a činí obě selhání rozlišitelnými: 409 znamená, že zápis byl odmítnut, selhání zpětného načtení znamená, že proběhl.

Cenou je skutečně nový stav: **potvrzený zápis, nevykreslitelná odpověď.** Hlásí se jako `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) s verzí po zápisu, aby FE načetl znovu místo opakování zápisu do falešného 409. Vrátit zde obecnou chybu 500 by byla lež — změna uživatele je uložená a po znovunačtení se zobrazí. Viz [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md).

## Export

Export PNG/SVG je záležitostí **frontendu** (`html-to-image` `toPng`/`toSvg` nad viewportem ReactFlow, na straně klienta). Backend nemá pixelově přesný pohled na plátno.

---

*ISMD Tool · diagramová vrstva · každý uzel je materializovaný pojem · nasazené strukturální úpravy jako navázaný dočasný overlay · Uložit (PG) vs. Převzít (RDF) · per-změna vše-nebo-nic · žádné třetí úložiště*
