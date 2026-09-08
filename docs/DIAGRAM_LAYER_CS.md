# Diagramová vrstva: architektura a návrh

> Stav: **hotovo, pokryto testovací sadou** — entitní vrstva/migrace, služby, controller i zabezpečení
> jsou implementovány. Jádro s jedním diagramem bylo 2026-08-25 ověřeno end-to-end proti lokálnímu
> Postgresu + Fuseki; **více diagramů na ontologii, kolize mezi diagramy a cizí pojmy jsou pokryty
> integračními testy proti reálnému Postgresu, ale stejným živým smoke testem zatím neprošly.**
> Anglická verze: [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md). FE/REST kontrakt:
> [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md).
>
> ⚠ **Zásadní změna pro FE.** Každá cesta diagramu nyní nese id diagramu a diagram se zakládá
> explicitně, místo aby vznikl při prvním uložení. Viz poznámku o migraci v API kontraktu.

Plátno založené na ReactFlow, které vizuálně zobrazuje a edituje ISMD ontologii — **více diagramů na ontologii**, každý jinak zaměřený pohled na tytéž pojmy — s modelem perzistence navrženým tak, aby se diagram *nikdy* nemohl tiše stát rozcházející se kopií dat pojmů.

## Jaký problém řešíme

Diagram je **zároveň** živým obrazem reálného ISMD slovníku **i** pracovní plochou s vlastním CRUD. Právě tato kombinace vyvolává obavy z „rozcházení" (drift). Tato codebase už podobný problém s „dvě úložiště držící kopie téhož obsahu" řešila: duální zápis PG↔TDB2 bez sdílené transakce, outbox, rekonciliátor, dokumentovaný základ známého šumu (viz [`PG_TDB2_CONSISTENCY_CS.md`](./PG_TDB2_CONSISTENCY_CS.md)). Diagram, který by ukládal vlastní kopii obsahu pojmu, by tento problém — třetí úložiště — otevřel znovu.

## Řídicí princip

> **Obsah, který diagram vlastní, je vždy *rozpracovaná strukturální úprava reálného pojmu*, nikdy volně stojící třetí kopie.** Diagram vlastní tři věci: *rozvržení* (pozice, seskupení, viewport), *odkazy* na reálné pojmy (přes IRI) a **overlay rozpracovaných úprav** — nasazené, nezapsané strukturální změny pojmů, které již existují a již mají IRI. Neexistují žádné uzly bez IRI; každý uzel odpovídá materializovanému pojmu. Overlay je *diff čekající na aplikaci* a explicitní **Převzít (materializace)** ho protlačí přes stávající CRUD `/api/concept` → outbox → RDF, načež se overlay vyprázdní a živý pojem je opět jediným vlastníkem. Samostatné **Uložit** zapíše rozvržení i overlay do Postgresu, aniž by se dotklo RDF.

**Proč to zůstává bezpečné vůči rozcházení.** Overlay obsah pojmu skutečně drží — záměrná, ohraničená výjimka z pravidla „nikdy nevlastní obsah" — ale je bezpečný, protože je:

1. **Diff, ne kopie** — jen *změněná* strukturální pole, navázaná na reálné IRI, nikdy samostatný pojem.
2. **Explicitně dočasný** — jeho celý smysl je být materializován a vyprázdněn; naplněný overlay je „úkol k provedení", ne zdroj pravdy.
3. **S jediným vlastníkem při čtení** — vykreslovaný obsah je `živý pojem ⊕ overlay`; po materializaci je overlay prázdný a živý pojem je jediným vlastníkem. Žádná synchronizace na pozadí, žádný rekonciliátor diagramů — overlay se rekonciluje *tím, že je materializován*.

## Dva druhy rozcházení — nebezpečný byl vždy jen jeden

- **🟢 Rozcházení rozvržení — bezpečné, záměrně.** Kde box leží, co je sbalené, viewport. RDF na to nemá žádný názor. Pouze diagramové, nezávislé, nikdy se nesynchronizuje.
- **🟡 Rozpracované úpravy — záměrné, ohraničené, samoléčivé.** Nasazená změna domain/range/hierarchie, dosud nematerializovaná. Obsah vlastněný diagramem, ale navázaný diff, který existuje proto, aby byl materializován, a při Převzít se maže. Nemůže tiše přetrvat jako stínová pravda: FE ho vykresluje jako „N nezapsaných změn" a Převzít je vědomá akce uživatele.
- **🔴 Tichá třetí kopie — konstrukčně zakázána.** Uzel držící *samostatnou* kopii obsahu pojmu, která se rozchází bez vlastníka. Overlay není nikdy samostatný (vždy navázaný na živé IRI) a nikdy trvalý (Převzít ho vyprázdní).

Vše ostatní je **zastaralost**, řešená při čtení: **visící odkaz** (uzel ukazuje na pojem smazaný běžným CRUD → uzel označen `stale`) a **mezera v pokrytí** (nové pojmy dosud nejsou na plátně → diagram je záměrně podmnožinový pohled). Pokrytí se počítá **na frontendu** — ten už drží úplný seznam pojmů slovníku i IRI uzlů na plátně, takže „které pojmy nejsou na plátně" je množinový rozdíl na klientu, ne serverový endpoint.

## Dvě akce

Plátno vystavuje pro obsah přesně dvě akce dotýkající se backendu:

- **Uložit diagram** (`PUT …/layout`) — uloží rozvržení *a* nasazené strukturální úpravy dosud neprojektované do slovníku. Pouze Postgres; **nikdy se nedotýká RDF.**
- **Převzít (materializovat do slovníku)** (`POST …/materialize`) — aplikuje nasazené úpravy na pojmy slovníku přes stávající CRUD `/api/concept` → outbox → RDF a poté overlay vyprázdní.

**Uložit je jediný endpoint a nese obojí.** Dřívější návrh nasazování odděloval do vlastního volání `PATCH …/nodes/overlay`, po jednom pojmu. To bylo odstraněno: plátno drží celý svůj stav na klientu a při každém Uložit už stejně posílá kompletní rozvržení, takže rozdělení vynucovalo jedno kolečko na každou nasazenou úpravu a vytvářelo druhý zdroj čítače verze diagramu, který pak FE musel provlékat mezi dvěma různě tvarovanými odpověďmi.

## Úplná náhrada vs. přírůstkovost — asymetrie uvnitř jednoho volání

Uvnitř tohoto jediného Uložit mají obě části payloadu záměrně odlišnou sémantiku:

| Payload | Sémantika | Vynecháno / `[]` |
|---|---|---|
| `nodes` | **úplná náhrada** — pole *je* členství na plátně | plátno vyprázdněno (`nodes` samo je povinné) |
| `edges` | **úplná náhrada** — pole *je* členství hran | vynecháno: nedotčeno; `[]`: všechny hrany mimo plátno |
| `overlays` | **přírůstkové napříč pojmy** — položka nasadí jeden pojem a je **úplnou náhradou overlaye tohoto pojmu** | **nasazené úpravy zůstávají nedotčeny** |

**Proč overlays nemohou být úplná náhrada.** Úplná náhrada vyžaduje, aby vše, co klient musí poslat zpět, bylo vidět v tom, co vrací čtení. U overlayů to neplatí:

- VZTAH, jehož koncová třída není na plátně, se neprojektuje jako hrana, takže jeho overlay se nikdy neobjeví;
- VLASTNOST, jejíž doménová třída není na plátně, se nevykreslí jako řádek — totéž;
- pojem **smazaný pod diagramem** nemá živý záznam vůbec, takže jeho overlay je neviditelný — a přitom jde o záměrný, dokumentovaný stav, který materializace hlásí jako `skippedStale`.

V každém z těch případů by klient měl poslat zpět něco, co mu nikdy nebylo ukázáno, a Uložit s úplnou náhradou by to tiše sklidilo. Hůř: plátno sestavuje tělo Uložit ze stavu ReactFlow, kde `overlays` přirozeně chybí úplně — při úplné náhradě by toto běžné automatické ukládání zničilo **veškerou** nasazenou práci.

Položka tedy nasadí nebo aktualizuje jeden pojem, pojem chybějící v poli zůstává nedotčen a **zahození je explicitní**: položka nesoucí pouze `conceptIri`. Cenou je ztráta množinové idempotence, kterou v příběhu klienta nic nepotřebuje.

## Kam který zápis míří

Vytvoření pojmu a odebrání uzlu jsou okamžité/lokální; **strukturální úpravy se nasazují až do Převzít.** Existuje jen jeden druh uzlu — každý uzel odkazuje na materializovaný pojem.

**Okamžité — nenasazuje se:**

- **Vytvoření pojmu z plátna** → stávající `POST /api/concept` create → outbox → RDF. Vlastnost nebo vztah lze vytvořit *bez domény* (stále plně materializovaný, s reálným IRI); doména se doplní později jako nasazená úprava. (Pozn.: vlastnost vždy dostane `rdfs:range` — ve výchozím stavu `Literal` — takže skutečně chybět může jen *doména*.) FE ji pak umístí na plátno tím, že ji zahrne do dalšího Uložit.
- **Přidání / odebrání uzlu z plátna** → jede na úplné náhradě `nodes[]` v rámci Uložit: uzel přítomný je na plátně, uzel vynechaný na něm není. **Pojem zůstává nedotčen** v obou případech. Neexistuje samostatný endpoint pro přidání/odebrání uzlu ani akce „smazat pojem" na diagramu.

**Nasazované — Uložit je drží v PG, materializace je aplikuje do RDF.** Overlay nasazuje přesně tyto strukturální úpravy, vyjádřené jako *hodnoty cílového stavu* na dotčených pojmech — ne jako log operací:

| # | Akce uživatele | Úprava cílového stavu | CRUD pojmu při Převzít |
|---|---|---|---|
| 1 | Přehodit směr vztahu | prohodit u VZTAHu `domain` ⇄ `range` | 1 editace |
| 2 | Otočit směr hierarchie (B⊐A → A⊐B) | zrušit hierarchický odkaz na A, přidat na B | 2 editace — po jedné na pojem |
| 3 | Změnit typ hierarchie (podtřída ⇄ ekvivalent) | vyprázdnit seznam podtříd, naplnit `exactMatch` (nebo obráceně) | 1 editace |
| 4 | Změnit rodičovskou třídu vlastnosti | změnit u VLASTNOSTi `domain` (`rdfs:domain`) | 1 editace |
| 5 | Nastavit doménu vlastnosti bez domény | doplnit u VLASTNOSTi `domain` | 1 editace |
| 6 | Převést vztah na hierarchii | přidat hierarchický odkaz na cílovou třídu, pak smazat VZTAH | 2 volání — **jedna jednotka, vše nebo nic** |
| 7 | Odebrat vlastnost/vztah *z plátna* | jen odebrání řádku uzlu | žádné (není to změna RDF) |

**Hierarchie na plátně je jen pro třídy.** „Nadřazenost" mezi třídami je `subClassOf` (`broaderConcept`). Ekvivalenty pro vlastnosti a vztahy (`subPropertyOf`) **nejsou součástí diagramu** — proč, viz „Hrany jsou projekce" níže. „Ekvivalent" (op 3) znamená `skos:exactMatch`, nezávislý symetrický predikát — *ne* orientovanou hierarchii a *ne* jediný přepínač „typu hierarchie".

**Jediné RDF smazání, které diagram může způsobit, je implicitní** — smazání VZTAHu v op 6, a to až poté, co je úspěšně přidána náhradní hierarchická hrana. Neexistuje samostatná možnost „smazat pojem". Op 6 se nabízí jen tehdy, když na daný VZTAH nic neukazuje svým `domain`/`range` (jinak by jeho smazání tranzitivně kaskádovalo na další pojmy); jinak převod ohlásí konflikt.

**Op 6 nadtřídu přidává; hierarchii cílové třídy nikdy nenahrazuje.** `broaderConcept` v editačním modelu pojmu je *úplná náhrada*, takže applier načte aktuální množinu `rdfs:subClassOf` dané třídy a předá sjednocení. Bez tohoto sloučení by převod tiše zahodil všechny dosavadní nadtřídy — bez hlášení a v rámci požadavku nevratně, protože VZTAH se maže ve stejné transakci. Zafixováno testem `op6_preservesTargetClassExistingBroaderConcepts`; zrcadlený predikát `nadřazená-třída` se přepisuje ze stejné sloučené množiny, takže se ty dva nikdy nerozejdou.

**Op 6 se v rámci jednoho Převzít aplikuje jako poslední.** `CONVERT_TO_HIERARCHY` posune `updatedAt` své cílové třídy; pokud tato třída nese v témže běhu i vlastní overlay, aplikace op 6 jako první by jí posunula otisk pod rukama a vyvolala falešný `STALE_BASE`.

## Hrany jsou projekce, ne obsah

Každý pojem je vykreslen ve tvaru, který odpovídá tomu, čím *je*. **Třída** (TRIDA) je uzel. **Vztah** (VZTAH) je *hrana* mezi svou třídou `rdfs:domain` a `rdfs:range` — jedna hrana nesoucí vlastní identitu pojmu, protože přesně to vztah znamená. **Vlastnost** (VLASTNOST) má jen doménu (její range je literálový datový typ, takže není ke komu druhému vést), a je proto *řádkem uvnitř* třídy, která ji vlastní.

Zásadní je, že jde o rozhodnutí o **vykreslení**, ne o vlastnictví. Všechny tři zůstávají plnohodnotnými pojmy s vlastním IRI a vlastním overlayem. Identitou je vždy IRI pojmu, a proto položka v `overlays[]` adresuje třídu, vztah i vlastnost naprosto stejně — žádný z nich nemusí být „uzlem", aby šel nasadit.

Z toho plyne, že **tažení konce hrany je editace pojmu** (přesměrování šipky mění overlay `range` u VZTAHu; přetažení řádku vlastnosti do jiné třídy mění `domain` u VLASTNOSTi) a **nakreslení nové čáry vztahu je vytvoření pojmu VZTAH**. Hrany nikdy neakumulují vlastní rozpracovaný stav; při čtení se znovu projektují z `živý ⊕ overlay`. Overlay pojmu je jediným zdrojem pravdy pro domain/range/hierarchii.

**Hrana ukládá své členství a své zlomové body, nic víc.** Existence, konce i druh se odvozují, takže `diagram_edges` ukládá pouze `(edge_key, segments_json)`: existence řádku říká, že hrana je na plátně, a `segments_json` říká, jak je vedená. Ukládat konce by duplikovalo projekci a mohlo by jí tiše odporovat — přesměrujete range a uložený konec dál jmenuje starou třídu. Přesně proti této třídě rozcházení je celá tato vrstva postavena, takže ty sloupce neexistují.

**Členství a projekce jsou dvě různé otázky.** Projekce odpovídá, zda hrana *může* být nakreslena (oba konce na plátně, trojice v RDF); členství odpovídá, zda *má*. Hrana, kterou uživatel nikdy neumístil, se nekreslí, i když se projektuje — je to tentýž stav jako třída, která ve slovníku existuje, ale nebyla přetažena na plátno. Právě díky tomu mohou na plátně být dvě třídy bez vztahu mezi nimi. Členství může projektovatelnou hranu jen skrýt, nikdy neoživí neprojektovatelnou, takže **hrana nikdy nezůstane viset na jednom konci**: ztráta konce ji přestane kreslit, ať už její řádek říká cokoli.

**Nekompletní pojmy žijí mimo plátno.** VZTAH bez jednoho konce nebo VLASTNOST bez domény se prostě nekreslí — není k čemu je připojit. Nic to nestojí, protože umístění *je* dokončení: takový pojem se na plátno dostane přetažením z detailu slovníku a to přetažení chybějící konec doplní. Model hran a řádků tak nikdy nemusí reprezentovat rozestavěný pojem, což je jediná věc, kterou starší model „uzel na pojem" uměl vyjádřit a tento neumí.

**Právě tato neviditelnost je důvod, proč jsou overlays přírůstkové.** Konec mimo plátno znamená nasazený overlay, který žádné čtení nevystaví — viz sekce o asymetrii výše.

**Hierarchie sub-property a sub-relation se nevykresluje.** `rdfs:subPropertyOf` mezi dvěma vlastnostmi nebo dvěma vztahy by se musela kreslit z řádku do řádku nebo z čáry do čáry — ani jeden konec není uzel. Nad rámec mechaniky je otevřenou byznysovou otázkou, co by tam měl uživatel vidět a dělat, takže diagram tento vztah nevykresluje ani nenasazuje; v běžném editoru pojmů zůstává plně podporován. Viz `.planning/diagram-edge-model-REDESIGN.md`.

## Cizí pojmy — pouze odkazované, nikdy zapisované

Plátno může umístit pojem z **jiné ISMD ontologie nebo z NKD**, aby uživatel mohl nakreslit vztah od pojmu, který vlastní, k pojmu, který nevlastní. Takový uzel je označen `is_foreign` a vykresluje se jen ke čtení.

**Pravidlo, které to činí bezpečným: každý odkaz, který plátno umí nakreslit, má původ na pojmu, který vlastníme.** Trojice se zapisuje do našeho grafu a cizí zdroj se nikdy nezapisuje. VZTAH, který vlastníme, nese `rdfs:range` mířící na cizí třídu; `rdfs:subClassOf` se zapisuje na našeho potomka; `skos:exactMatch` se tvrdí z naší strany. (`exactMatch` je v SKOS symetrický, takže opačná trojice *plyne* z odvození — tvrdíme svou polovinu a jejich nikdy nezapisujeme.)

**Cizí uzel je cílem hrany, nikdy jejím zdrojem.** Projektor přeskakuje hierarchii a ekvivalenci, jejichž subjektem je cizí pojem, takže odkaz mezi dvěma cizími uzly se nekreslí, ani když jsou oba na plátně a trojice existuje: patří grafu, který ji vlastní, a toto plátno ji neumí ani rozpracovat, ani přesměrovat. Odkaz z vlastního pojmu na cizí je přesně to, co tato funkce kreslí.

**Příznak povoluje pouze umístění.** Overlay nikdy nesmí mířit na cizí pojem, protože jeho materializace by zapsala RDF jiné ontologie. To je vynuceno na vstupu a znovu ověřeno při materializaci (`FOREIGN_CONCEPT`) a je to právě to, co zachovává záruku proti zápisům napříč vlastníky. Příznak je zároveň tvrzení, které server ověřuje oběma směry: cizí IRI je přijato jen na uzlu, který ho nastaví, a nastavení na vlastním pojmu je 400.

**Čtení načítá i cizí grafy**, seskupeně po jednom načtení na graf, ne na uzel — bez toho se cizí uzel vykreslí bez názvu a jako `stale`, k nerozeznání od pojmu, který někdo smazal. Cizí graf, který se nepodaří načíst, degraduje své uzly na `stale`, místo aby shodil celé čtení.

**Nasměrování `rdfs:range` na *publikovaný NKD* pojem je povoleno pouze pro VZTAH** a cíl se snímkuje jako lokální kopie (`RANGE_TARGET`) stejně jako ostatní NKD odkazy. `rdfs:domain` mířící na publikovaný pojem zůstává neplatný pro každý typ a `range` u VLASTNOSTI zůstává neplatný, protože pojmenovává XSD datový typ, ne pojem. Viz [`NKD_LOCAL_COPY_SNAPSHOT.md`](./NKD_LOCAL_COPY_SNAPSHOT.md).

## Entitní model v PG

Čtyři entity, po vzoru `CommentEntity` (FK na `ontologies.id`, čisté PG, bez outboxu). Rozvržení i overlay rozpracovaných úprav žijí celé v Postgresu, v **oddělených tabulkách s oddělenými životními cykly**.

**`diagrams`** — jeden řádek na plátno, **více na ontologii** (`@ManyToOne` FK → `OntologyMetadataEntity`, ON DELETE CASCADE), `name` unikátní v rámci své ontologie, posun/přiblížení viewportu, sloupec optimistického zámku `@Version` a `@OneToMany` kolekce uzlů/hran (cascade ALL, orphanRemoval). Agregátní pomocníci `addNode`/`addEdge`/`removeNode` drží volající na spravovaných instancích; `touch()` vynutí posun `@Version` i při změnách jen v uzlech/hranách. Sloupec vlastníka diagramu neexistuje — vlastnictví patří ontologii, o jeden join dál.

**`diagram_nodes`** — **jen rozvržení.** Každý řádek odkazuje na materializovaný pojem: `concept_iri` **NOT NULL**, `backing` (jednohodnotové `ISMD_CONCEPT`, ponecháno pro budoucí rozšíření), pozice, `collapsed`, `parent_node_id`, `visible_properties_json`. Entitní strážce `@PrePersist`/`@PreUpdate` a CHECK v Postgresu vynucují, že `concept_iri` je vždy přítomné, a unikátní index pokrývá `(diagram_id, concept_iri)`.

Řádek znamená přesně jednu věc: **tento pojem je box na plátně.** Pouze třídy — VZTAH se vykresluje jako hrana a VLASTNOST jako řádek uvnitř své třídy, takže ani jeden nemá řádek rozvržení.

**`diagram_pending_edits`** — **nasazený RDF záměr, nic vizuálního.** `pending_edit_json` (**NOT NULL** — řádek existuje jen po dobu, kdy je co aplikovat, takže zahození ho maže, ne vyprazdňuje), `base_updated_at`, unikát na `(diagram_id, concept_iri)`. **Žádné sloupce pozice.**

Vázáno na **diagram**, s `ontology_metadata_id` ponechaným vedle. Každé plátno nasazuje nezávisle, takže dva diagramy jedné ontologie mohou držet protichůdné úpravy téhož pojmu — což je přesně stav, který má [detekce kolizí mezi diagramy](#kolize-mezi-diagramy) hlásit. Vázání na ontologii by tuto kolizi učinilo nezobrazitelnou: oba by sdílely jediný řádek a jedno uložení by tiše přepsalo druhé. Denormalizované id ontologie je to, co dovoluje dotazu na kolize levně najít *sourozenecké* diagramy.

> **Toto obrací dřívější rozhodnutí.** Dokud byl na ontologii jeden diagram, nasazování bylo záměrně vázáno na ontologii — „nasazená úprava patří pojmu, ne tomu plátnu, které ji nasadilo" — a bylo zaznamenáno jako dopředu kompatibilní s více diagramy. Není: jakmile mohou nasazovat dvě plátna, sdílený řádek *je* ta kolize, tiše rozhodnutá tím, kdo uloží jako poslední.

> **Proč jsou to dvě tabulky.** Kdysi to byl jeden řádek, a protože členství na plátně je „které řádky uzlů existují", nasazení úpravy tím připnulo její pojem na plátno: odebrání uzlu — čistě vizuální úkon — bylo blokováno úkonem čistě sémantickým. Pojem bez vlastního boxu si musel vymyslet pozici (sloupce rozvržení jsou NOT NULL) a skončil ukotvený v počátku. Rozdělení tuto fikci odstraňuje, dovoluje sklizni být prostou úplnou náhradou a dělá z „diagram je pohled na podmnožinu" pravdu ve schématu, ne jen v záměru.

**`diagram_edges`** — `edge_key` (id projektované hrany, ke které tento řádek patří: IRI pojmu VZTAH, nebo složené `edge|KIND|source|target` u hierarchického odkazu) a `segments_json`, unikátní na `(diagram_id, edge_key)`. **Členství plus zlomové body** — žádné konce, žádný druh, žádný obsah. Řádek, jehož hrana se už neprojektuje, při čtení nenajde protějšek a příští Uložit ho smaže; nic nemusí dohledávat sirotky.

Obsahový model overlaye (`DiagramPendingEdit`) je **čistě strukturální**: `domain`, `range`, `broaderConcept` (`subClassOf`, TRIDA), `exactMatch`, marker `convertToHierarchy` pro op 6 a `baseUpdatedAt` (otisk pro detekci zastaralého základu, razítkuje server, na zápisu se nikdy nepřijímá). Záměrně vynechává **editaci názvu/labelu** — změna názvu přejmenuje IRI pojmu (přesune všechny jeho triples), což by osiřelo IRI odkaz uzlu diagramu. Editace labelu zůstává v běžném editoru pojmů, mimo diagram.

## Rekonciliace při Uložit

`DiagramLayoutReconciler` aplikuje jedno Uložit v pevném pořadí a to pořadí je nosné:

1. **`nodes[]` → `diagram_nodes`** — aktualizovat odpovídající řádky na místě, vložit řádky pro nová IRI, pak sklidit: každý uložený řádek chybějící v příchozí množině se odstraní. Prostá úplná náhrada, bez výjimek.
2. **`edges[]` → `diagram_edges`** — vložit či aktualizovat řádek pro každou položku a sklidit každý řádek chybějící v příchozí množině; stejná úplná náhrada jako u uzlů. `edges` rovné `null` tuto polovinu přeskočí celou, takže klient, který hrany nespravuje, je nemůže vynecháním smazat. U položky vynechané `segments` ponechají uložené vedení a `[]` je vyčistí — položka je výrok o členství a o geometrii neříká nic, pokud ji sama nenese.
3. **`overlays[]` → `diagram_pending_edits`** — u každé položky zkontrolovat graf pojmu, pak nasadit či aktualizovat jeho úpravu, nebo řádek smazat při zahození (položka nesoucí jen `conceptIri`).

**Žádná z polovin neomezuje ostatní.** Nesdílejí řádek, takže na jejich pořadí nezáleží a žádná nemůže zrušit tu druhou: třída může opustit plátno a přitom si ponechat nasazenou úpravu, a nasazení úpravy nikdy nedostane pojem na plátno. Odebrání uzlu nenese žádný RDF záměr — zahození je samostatný, explicitní pokyn.

**Zřizovat z uložené množiny, ne z příchozí.** `diagram_nodes` má prostý unikát na `(diagram_id, concept_iri)` a kolekce je `orphanRemoval`, takže kdyby jedno Uložit kdy vyprodukovalo odstranění řádku a vložení pro totéž IRI, Hibernate by vydal INSERT před DELETE a omezení by spadlo. Je to totéž riziko, proti kterému už byl zpevněn rekonciliátor hran.

**Graf se kontroluje u každého cíle overlaye při každém Uložit**, ne jen při zřizování řádku — jinak by pojem, který už řádek má, mohl dostat overlay z cizího grafu bez kontroly.

### Otisk zastaralého základu

`baseUpdatedAt` zaznamenává `updatedAt` odkazovaného pojmu v okamžiku nasazení a materializace odmítne aplikovat overlay, jehož otisk už neodpovídá (`STALE_BASE`). Pravidlo razítkování je **jen při prvním výskytu**:

```
edit.baseUpdatedAt = (prior == null) ? aktuální-otisk : prior.baseUpdatedAt
```

Když overlays jedou v *každém* Uložit, přerazítkování při každém uložení by otisk neustále obnovovalo a strážce by zcela vyřadilo — souběžná editace pojmu by se pohltila místo ohlášení. Porovnávat místo toho strukturální pole overlaye je horší v opačném směru: po `STALE_BASE` by uživatel, který znovu nasadí *tutéž* hodnotu, vyšel jako shodný, ponechal si zastaralý otisk a dostával 409 navždy, bez akce, která by to vyřešila.

Razítkování při prvním výskytu dělá z nápravy explicitní a dosažitelnou sekvenci: **zahodit a nasadit znovu.** Zahození nastaví `prior` na null, takže nové nasazení vezme čerstvý otisk a materializace projde. Proto model overlaye vůbec potřebuje explicitní signál zahození — a je to ověřeno v obou směrech: samotné znovuposlání dál konfliktuje, zahození s následným nasazením konflikt vyčistí.

## Sémantika materializace

Materializace se rozvětvuje **in-process** na stávající služby pojmů (ne přes HTTP volání sebe sama), takže znovu používá existující validátory i outbox. Pro každý uzel s neprázdným overlayem:

1. Přeložit `concept_iri` uzlu na číselné id pojmu (editační a mazací služby klíčují podle id). Chybějící řádek znamená smazaný pojem → hlášeno jako `skippedStale`.
2. Ověřit, že přeložený pojem patří do **vlastního grafu ontologie daného diagramu**. Endpointy autorizují slug ontologie, ale IRI pojmů cestují uvnitř těla, takže neomezený zápis by dovolil libovolnému přihlášenému uživateli editovat — a přes op 6 mazat — cizí pojmy. Cizí IRI je odmítnutý požadavek (`FOREIGN_CONCEPT`, 400), ne zastaralý odkaz. Táž kontrola platí pro `addBroaderOn` a `broader` v op 6, které jmenují pojmy, jež na plátně být vůbec nemusí. Cesta Uložit ji vynucuje už na vstupu, takže cizí IRI se nikdy vůbec neuloží.
3. Ověřit, že pojem nebyl pod overlayem od nasazení editován (otisk zastaralého základu výše). Pokud se pohnul, je změna ohlášena jako konflikt místo tichého přepsání mezitímní editace. Každá cesta, která mění RDF pojmu, musí razítkovat `updatedAt` — nejen editor pojmů; endpointy UPDATE/REMOVE snímků NKD i warmer vlastníka to dělají také (když skutečně vyprodukují změnu), jinak by se RDF pohnulo, otisk zůstal zmrazený a tato kontrola by to tiše minula.
4. Sestavit **polem omezenou** editaci nesoucí jen změněné predikáty a aplikovat ji. Omezení na pole (ne úplný snímek) je nutné, protože zobrazovací model čtení nevystavuje boolean `isPublic` a editační cesta klasifikaci veřejný/neveřejný odstraní a podmíněně znovu přidá — úplný snímek s nulovým `isPublic` by ji tiše zahodil. (Jediný editační pomocník, který v tomto není null-safe, je odpovídajícím způsobem zpevněn.)

**Granularita:** po jednotlivých změnách, částečný úspěch povolen. Změna zahrnující dvě volání CRUD pojmu (op 6) je vše nebo nic — druhé volání je podmíněno prvním a overlay se maže až při úplném úspěchu; selhání ponechá celou změnu nasazenou a ohlásí ji. Otočení (op 2) jsou dvě nezávislé jednopojmové editace, hlášené zvlášť.

## Kolize mezi diagramy

Protože nasazování je po diagramech, dvě plátna jedné ontologie mohou držet protichůdný záměr pro tentýž pojem. Materializace to proto kontroluje **dřív**, než cokoli aplikuje.

**Proč to nelze nechat na STALE_BASE.** Aplikace jedné strany posune `updatedAt` pojmu — právě ten otisk, proti kterému byla nasazená úprava druhé strany orazítkována — takže sourozenec by následně selhal na `STALE_BASE`, po jednom pojmu, aniž by z odpovědi šlo poznat, co se pod ním pohnulo. Náprava by znamenala zahodit a znovu nasadit každý pojem zvlášť. Detekce kolize předem z toho dělá jedno rozhodnutí.

**Kolize je „tentýž pojem nasazený na dvou diagramech", ne „nasazený s jinými hodnotami."** Shodné hodnoty kolidují také, ze stejného důvodu: první materializace posune otisk, na který je připnutá druhá. Porovnávání hodnot by pustilo zdánlivě neškodnou dvojici dál a vyrobilo matoucí 409 později místo jasného teď.

**Detekce běží před smyčkou po jednotlivých změnách.** Změny se aplikují ve vlastních `REQUIRES_NEW` transakcích, takže kontrola uvnitř té smyčky by už měla zapsané RDF za vše, co kolizi předchází. Běží ve vlastní transakci, jako první; odmítnutí znamená, že se nic nezapsalo a nasazená práce obou stran zůstala nedotčená.

**Rozhodnutí je uživatelovo a explicitní.** `POST …/materialize` bez `onConflict` kolizi ohlásí a odmítne (409). Klient zavolá znovu a pojmenuje **vítěze**: `ACCEPT_MINE` pro tento diagram, nebo `ACCEPT_THEIRS` s `winnerDiagramId` pro uvedený. **Zahodí se pouze sporné pojmy** — nikdy celá nasazená práce plátna, což by byl mnohem větší úkon, než k jakému dal uživatel souhlas.

**Jeden vítěz, ne strana k zahození.** Kolize může zasáhnout víc než dvě plátna a tam je „zahodit jejich" nejednoznačné, zatímco „zahodit moje" nevyřeší nic — zbylé diagramy spolu stále kolidují. Pojmenování vítěze vyčistí všechny poražené v jednom průchodu, včetně pláten, která volající nezmínil, takže trojstranná kolize stojí jedno rozhodnutí místo jednoho kola na každého sourozence. `ACCEPT_THEIRS` pak materializuje **vítěze**, ne diagram v cestě: ponechat zvolenou úpravu jen nasazenou by kolizi přesunulo na plátno, ke kterému se uživatel už nemusí vrátit.

Zásah do jiného plátna je autorizovaný, protože každý diagram v kolizní množině patří té jedné ontologii, proti které byl volající už autorizován — a vítěz v té množině být musí; načítá se znovu skrz onen rozsah ontologie, místo aby se věřilo požadavku. Vítěz mimo ni je 400, ne 409: opětovné poslání téhož nemůže uspět.

## Verzování

Protože diagram nedrží žádný *samostatný* obsah pojmu, „verzování diagramu" zůstává malé. **Historie rozvržení** je čistě PG záležitost (snímkové řádky rozvržení) — odloženo; nejdřív dodáváme jedno aktuální rozvržení. **Nasazené úpravy** jsou ze své podstaty dočasné a žádnou historii verzí nepotřebují. **Verzování pojmů/slovníku** už žije ve stávajícím modelu (RDF, publikováno vs. rozpracováno, odchylky) a diagram ho zdarma dědí tím, že čte živý obsah.

Sloupec `@Version` je strážce souběhu, ne historie. Vynucuje se ve službě, ne v JPA: Uložit načte diagram čerstvě ve vlastní transakci, takže Hibernate by porovnával jen právě načtenou verzi samu se sebou. Jediná hodnota nesoucí signál „uložil někdo mezitím?" je verze **klienta** — ta, ze které vykresloval. Protože patří diagramu a ne žádnému uzlu, nenese pole verze žádný uzel v odpovědi.

## Zápis do PG a čtení z Fuseki nikdy nejsou v jedné transakci

Každý endpoint diagramu vrací **tučnou** odpověď: řádky rozvržení z PG spojené s živým obsahem pojmů načteným z Fuseki. Zřejmá implementace — jedna `@Transactional` metoda dělající obojí — je špatně hned dvakrát.

**Držela by DB spojení přes externí HTTP volání.** Načtení z Fuseki jde přes semafor, jehož samotné získání smí trvat 30 s, ještě než se pohne první bajt. Hikari pool má 20. Pomalá nebo zahlcená Fuseki tedy nezpomalí jen požadavky na diagram — připne spojení, dokud není pool prázdný, a začnou selhávat nesouvisející endpointy.

**Vrátila by dobrý zápis kvůli selhání čtení.** Diagramová vrstva *nikdy nezapisuje RDF* — jediná volání Fuseki ve službě jsou čtení `fetchGraph` a dějí se striktně po dokončení všech zápisů do PG. Není zde tedy žádný duální zápis, který by bylo třeba udržet atomický; transakce chránila zápis před selháním, které ho nemůže poškodit.

Služba proto každou veřejnou metodu dělí: `@Transactional` krok (`commitLayout` / `loadForRead`) odvede práci v PG a vrátí **odpojený snímek** všeho, co odpověď potřebuje — verzi, viewport, řádky uzlů, typy a slugy pojmů. Načtení z Fuseki a sestavení pak běží bez otevřené transakce. Krok se volá přes `@Lazy` self-proxy: přímé `this.commitLayout(...)` by proxy obešlo a tiše běželo úplně bez transakce.

**Pořadí je zapiš, pak čti.** Čtení jako první by se sice vyhnulo níže popsanému selhání, ale platilo by úplné načtení grafu při každém zastaralém uložení jen proto, aby ho zahodilo — a 409 je na sdíleném plátně *běžný* výsledek, ne vzácný. Zároveň by rozšířilo okno mezi kontrolou verze a commitem. Zápis jako první ponechává vpředu levné odmítnutí z PG a činí obě selhání rozlišitelnými: 409 znamená, že zápis byl odmítnut, selhání zpětného načtení znamená, že proběhl.

Cenou je skutečně nový stav: **commitnutý zápis, nevykreslitelná odpověď.** Hlásí se jako `DIAGRAM_SAVED_READBACK_FAILED` (HTTP 502) nesoucí verzi po zápisu, aby FE načetl znovu místo opakování do falešného 409. Vrátit tam obecnou 500 by byla lež — změna uživatele je uložená a znovunačtení by ji ukázalo. Viz [`DIAGRAM_LAYER_API_CS.md`](./DIAGRAM_LAYER_API_CS.md).

409 je v druhém směru čistý: Uložit je odmítnuto dřív, než se cokoli zapíše, takže nasazené overlays zůstávají přesně tak, jak byly.

## Export

Export do PNG/SVG je záležitost **frontendu** (`html-to-image` `toPng`/`toSvg` proti viewportu ReactFlow, na klientu). Backend nemá pixelově přesný pohled na plátno. Serverový exportní endpoint dává smysl jen pro headless použití (plánované reporty) — samostatná, pozdější funkce.

---

*ISMD Tool · diagramová vrstva · každý uzel je materializovaný pojem · nasazené strukturální úpravy jako navázaný dočasný overlay · jediný zápisový endpoint: úplná náhrada rozvržení, přírůstkové overlays · Uložit (PG) vs. Převzít (RDF) · žádné třetí úložiště*