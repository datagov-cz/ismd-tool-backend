# Konzistence PG ↔ TDB2: Outbox a Rekonciliátor

> Stav: obě funkce se nasazují ve výchozím stavu vypnuté. Zapnutí kterékoli z nich je
> volitelné pro dané prostředí. Anglická verze:
> [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md).

## Jaký problém řešíme

Zápisy pojmů a slovníků jsou **duální zápisy** do dvou úložišť, která **nesdílejí transakci**:

- **PostgreSQL** drží metadata (`ConceptMetadataEntity` / `OntologyMetadataEntity`: id, slug,
  `conceptIri`, `graphName`, `conceptType`, `userId`, `isPublished`) přes JPA `@Transactional`.
- **TDB2 přes Fuseki (HTTP)** drží RDF model. Fuseki **není** zahrnuto v JPA transakci — jediná
  atomická jednotka je jeden SPARQL požadavek.

Protože neexistuje sdílená transakce (a TDB2-přes-Fuseki-HTTP nenabízí XA/dvoufázový commit), pád
nebo chyba mezi oběma zápisy může způsobit jejich nesoulad: RDF je zapsáno, ale PG řádek se
odvolá (osiřelé RDF), nebo PG řádek odkazuje na RDF, které neúspěšné smazání ponechalo.

Tomu čelí dva doplňující se mechanismy:

| Funkce | Role | Kdy zasahuje                                                                                                               |
|---|---|----------------------------------------------------------------------------------------------------------------------------|
| **Outbox** | **Prevence** na cestě zápisu | Při zápisu — učiní RDF zápis zotavitelným, aby se nemohl tiše rozejít s PG commitem                                        |
| **Rekonciliátor** | **Detekce** (a později oprava) | Automatická / na vyžádání — najde již existující odchylku, včetně té jediné cesty zápisu, kterou outbox nepokrývá (upload) |

Outbox je primární obrana; rekonciliátor je záchytná síť za ním (a jediné, co zachytí již existující
rozejití a rozejití z cesty uploadu).

---

# Část 1 — Outbox

## Shrnutí pro provoz

Transakční outbox činí TDB2 stranu zápisu pojmu/slovníku **trvanlivou a opakovatelnou**. Místo
přímého zápisu do Fuseki uvnitř požadavku se zápis zaznamená jako řádek v tabulce
`ismd_schema.outbox_entry` **ve stejné Postgres transakci** jako metadata. Tento řádek pak na Fuseki
aplikuje **relay** (přenašeč):

- **Horká cesta:** after-commit posun okamžitě odešle nový řádek na
  Fuseki, takže čtení po zápisu stále funguje.
- **Záchytná síť:** automatický relay (`outbox.relay-cron`, výchozí každých 10 s) znovu odešle cokoli,
  co po pádu zůstalo ve stavu **PENDING**.

Řádek, který se nepodaří aplikovat, se zkouší znovu až `outbox.max-attempts`-krát, poté se označí
jako **FAILED** a zpřístupní se v admin API k ručnímu opakování. Řádky **DONE** se uchovávají jako
auditní stopa a po `outbox.done-retention` se automaticky promažou.

Při `outbox.enabled=false` (výchozí) se místa zápisu vrací k **původnímu přímému zápisu** — nasazení
kódu nic nemění, dokud prostředí outbox v konfigutaci povolí.

**Pokrytá místa zápisu (4):** vytvoření pojmu, editace pojmu (vč. přejmenování), smazání pojmu,
smazání slovníku. **Nepokryto:** **upload** slovníku (stále přímý zápis — tuto cestu jistí
rekonciliátor a existující **best effort** revert).

## Životní cyklus záznamu

```
PENDING ──(relay aplikuje na Fuseki)──▶ DONE ──(promazání po done-retention)──▶ odstraněno
   │
   └──(aplikace selže, attempts++ až do max-attempts)──▶ FAILED ──(admin retry)──▶ PENDING
```

- Řádky jsou řazeny v rámci **agregátu** (pojem/graf) podle `seq`; relay aplikuje řádek až poté, co
  jsou aplikovány všechny dřívější řádky téhož agregátu — dvě editace jednoho pojmu se tedy nemohou
  aplikovat mimo pořadí.
- Řádek FAILED **blokuje svůj agregát**, dokud se neopakuje — záměrně, aby se vadný zápis nepřeskočil.

## Konfigurace

Prefix `outbox.*` (vázáno v `OutboxConfig`). Všechny hodnoty lze přepsat proměnnou prostředí
v `application.properties`.

| Vlastnost | Proměnná prostředí | Výchozí | Význam |
|---|---|---|---|
| `outbox.enabled` | `OUTBOX_ENABLED` | `false` | Hlavní vypínač. `false` → původní přímý zápis, žádný relay, žádná změna chování. |
| `outbox.relay-cron` | `OUTBOX_RELAY_CRON` | `*/10 * * * * *` | Rozvrh záchytného přenosu (cron Spring 6 polí). Horkou cestu řeší pošťouchnutí po commitu; toto jen zachytí řádky po pádu. |
| `outbox.max-attempts` | `OUTBOX_MAX_ATTEMPTS` | `10` | Počet pokusů o aplikaci, než se řádek označí FAILED (a zablokuje svůj agregát). |
| `outbox.batch-size` | `OUTBOX_BATCH_SIZE` | `100` | Max. počet řádků zpracovaných v jednom průchodu relaye. |
| `outbox.done-retention` | `OUTBOX_DONE_RETENTION` | `P30D` | Jak dlouho se uchovávají řádky DONE (ISO-8601 doba) před promazáním. |
| `outbox.prune-cron` | `OUTBOX_PRUNE_CRON` | `0 30 3 * * *` | Rozvrh promazání řádků DONE (cron Spring 6 polí). |

**Související — fond připojení.** Posunutí po commitu krátce drží **dvě** připojení z fondu na
zapisovatele (obchodní připojení plus `REQUIRES_NEW` drain). Dimenzujte Hikari s rezervou:
`spring.datasource.hikari.maximum-pool-size` (`HIKARI_MAX_POOL_SIZE`, výchozí `20` v dev/production).

## Admin API

Základní cesta: `/api/admin/outbox` (pozor na **kontextovou cestu** aplikace `/popisujeme`, plná
cesta je tedy `/popisujeme/api/admin/outbox/...`). Všechny endpointy vyžadují roli **ADMIN**.

| Metoda a cesta | Účel | Odpověď |
|---|---|---|
| `GET /status` | Zdraví fronty | `OutboxStatusDto`: `pending`, `failed`, `done`, `oldestPendingCreatedAt` |
| `GET /failed` | Výpis řádků FAILED (bez trojic) | `OutboxEntryDto[]`: id, operation, aggregateIri, graphName, status, attempts, lastError, createdAt, claimedAt, seq |
| `POST /drain` | Vynutit průchod přenosu nyní | `Integer` — počet aplikovaných řádků |
| `POST /retry/{id}` | Vrátit řádek FAILED na PENDING | `200` při úspěchu; **`409`** pokud řádek chybí nebo není FAILED |

Příklad (status):

```bash
curl -s "http://localhost:8081/popisujeme/api/admin/outbox/status" \
  -H "Authorization: Bearer $TOKEN" | jq .data
# → { "pending": 0, "failed": 0, "done": 12, "oldestPendingCreatedAt": null }
```

## Provozní příručka

- **Zdravé:** `pending` se vyprázdní, `failed` = 0, `oldestPendingCreatedAt` zůstává
  null/aktuální.
- **Relay zaseknutý / vypnutý:** `oldestPendingCreatedAt` stárne. Zkontrolujte `outbox.enabled`, zda
  běží plánovač a dostupnost Fuseki. `POST /drain` vynutí průchod.
- **Řádek je FAILED:** `GET /failed` ukáže id + `lastError`. Odstraňte příčinu (typicky vypnuté
  Fuseki nebo vadná data), obnovte Fuseki, poté `POST /retry/{id}` → `POST /drain`. Agregát je do té
  doby zablokován.
- **Hromadí se řádky DONE:** v rámci retenčního okna očekávané; promazání (`outbox.prune-cron`) je
  odstraní po `outbox.done-retention`.

---

# Část 2 — Rekonciliátor

## Shrnutí pro provoz

Rekonciliátor je kontrola konzistence **pouze pro detekci** (zatím **nemění** žádné úložiště).
Považuje **Postgres za zdroj pravdy**, vyjmenuje **vlastněné** subjekty pojmů v TDB2, porovná je
s PG metadaty a **hlásí rozejití** podle kategorií. Spuštění:

- **Plánovaně:** `reconciler.cron` (výchozí denně ve 03:00), ovládáno přes `reconciler.enabled`
  (výchozí vypnuto — nasazení samo nespustí skenování).
- **Na vyžádání:** admin endpoint funguje **bez ohledu na `reconciler.enabled`**.

Je to záchytná síť za outboxem a jediný mechanismus, který zachytí rozejití z **cesty uploadu**
a **již existující** rozejití (již existující rozejití po ukončení vývoje nebude relevavtní).

### Co znamená „vlastněný“ (klíčové pravidlo)

Pojem je **vlastněn** schématem, které deklaruje, právě tehdy, když nese `skos:inScheme ?scheme`
**a** jeho IRI je řetězcovým prefixem onoho schématu (`STRSTARTS(conceptIri, scheme)`). Jde o jediný
sdílený predikát (`JenaTDB2Repository.OWNED_CONCEPT_PATTERN`) používaný živým resolverem, vstupní
bránou uploadu i rekonciliátorem — jeden zdroj pravdy, takže rekonciliátor nemůže vymyslet osiřelce,
které by resolver neviděl.

Důsledek: **odkazované/externí pojmy** (např. NKD `adresa`, na který ukazuje vztah) se objevují jen
jako *objekty* trojic, nikdy jako vlastněné subjekty → nikdy nejsou označeny. Totéž platí pro pojmy
s cizím IRI, jejichž `inScheme` ukazuje na schéma, jehož prefixem jejich IRI není.

### Kategorie rozejití

| Kategorie | Význam                                                                                                   | Nakládání                                                              |
|---|----------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| `RDF_ORPHAN` | Vlastněný RDF subjekt ve Fuseki, žádný PG řádek                                                          | Jediná **automaticky opravitelná** kategorie (budoucí fáze)            |
| `PG_MISSING_RDF` | PG řádek, jehož IRI není vlastnicky řešitelné v **žádném** grafu                                         | Pouze hlášení (dvě příčiny: neúspěšné smazání vs. neúspěšné vytvoření) |
| `IRI_GRAPH_MISMATCH` | PG IRI je vlastnicky řešitelné, ale ne ve svém deklarovaném `graphName` (nebo je `graphName` null)       | Pouze hlášení                                                          |
| `GRAPH_ORPHAN` | Graf Fuseki drží vlastněné subjekty, ale neodkazuje na něj žádný řádek slovníku                          | Pouze hlášení (smazatelné jen v budoucí hlídané fázi)                  |
| `SUSPECTED_RENAME` | Dvojice `RDF_ORPHAN`(nové IRI) + `PG_MISSING_RDF`(staré IRI), jež vypadá jako jedno nedokončené přejmenování | Pouze hlášení; obě IRI vyloučena z jakékoli budoucí opravy             |
| `EXCLUDED_NO_INSCHEME` | Subjekt v daném namespacu bez `inScheme` (záměrně vyloučený)                                    | Informativní; *zatím se nehlásí*                                       |
| `RDF_NOT_OWNED_RESOLVABLE` | PG IRI má trojice, ale není vlastnicky řešitelné (např. ztracené `inScheme`)                             | Pouze hlášení; *zatím se nehlásí*                                      |

> **Dnes pouze detekce.** Žádná kategorie se neopravuje. Automaticky opravitelná je jen
> `RDF_ORPHAN`, a to v pozdější iteraci.

## Konfigurace

Prefix `reconciler.*` (vázáno v `ReconcilerConfig`).

| Vlastnost | Proměnná prostředí | Výchozí | Význam                                                                                                                   |
|---|---|---|--------------------------------------------------------------------------------------------------------------------------|
| `reconciler.enabled` | `RECONCILER_ENABLED` | `false` | Hlavní vypínač **plánovaného** běhu. Admin endpoint funguje bez ohledu na něj.                                           |
| `reconciler.cron` | `RECONCILER_CRON` | `0 0 3 * * *` | Rozvrh plánovaného běhu (cron Spring). Výchozí denně ve 03:00.                                                           |
| `reconciler.max-concepts` | `RECONCILER_MAX_CONCEPTS` | `200000` | Pojistka: běh raději ukončit s jasnou chybou než dojde k OOM, pokud počet PG řádků překročí tuto mez. `0` = bez omezení. |

> Záměrně **neexistuje příznak `published-only`**: pojmy ve stavu draft (`isPublished=false`)
> nesou plné vlastněné RDF + PG řádek a jsou v rozsahu stejně jako publikované.

## Admin API

Základní cesta: `/api/admin/reconciler` (plná: `/popisujeme/api/admin/reconciler/...`). Všechny
endpointy vyžadují roli **ADMIN**.

| Metoda a cesta | Účel | Odpověď |
|---|---|---|
| `POST /run` | Spustit detekční sken nyní (bez opravy) | `ReconciliationReportDto`; **`409`** pokud již běh probíhá |
| `GET /report` | Poslední dokončený report (v paměti; ztracen při restartu) | `ReconciliationReportDto`, nebo zpráva null, pokud od startu žádný neproběhl |

`ReconciliationReportDto`: `startedAt`, `finishedAt`, `triggeredBy`, `graphsScanned`,
`ownedRdfConceptsScanned`, `pgConceptsScanned`, `totalMismatches`,
`countsByCategory` (všechny kategorie, včetně nul), `mismatches[]` (každý: `category`, `graphName`,
`conceptIri`, `relatedIri`, `detail`).

Příklad (spuštění skenu):

```bash
curl -s -X POST "http://localhost:8081/popisujeme/api/admin/reconciler/run" \
  -H "Authorization: Bearer $TOKEN" | jq '.data.countsByCategory'
```

## Provozní příručka

- **Čisté:** `totalMismatches` = 0.
- **`PG_MISSING_RDF`:** pouze hlášení. Dvě příčiny, které detail nedokáže plně rozlišit — **neúspěšné
  smazání** (RDF neexistuje, PG řádek zůstal; ta častější) nebo **neúspěšné vytvoření** (PG commit proběhl,
  RDF nikdy nezapsáno). Řešte ručně; **nepředpokládejte** „promítnout zpět do TDB2“ — u neúspěšného
  smazání může být správná oprava smazání PG řádku.
- **`RDF_ORPHAN`:** vlastněné RDF bez PG řádku. Dnes: pouze hlášení. Pokud jej vidíte hned po zápisu,
  spusťte sken znovu — může jít o přechodný stav (PG commit dorazil těsně po PG snímku skenu).
- **`SUSPECTED_RENAME`:** pravděpodobně nedokončené přejmenování; obě IRI jsou hlášena spolu. Pouze
  hlášení.
- **`409` na `POST /run`:** plánovaný nebo jiný ruční běh ještě probíhá. Použijte `GET /report`.
- **Běh skončí chybou max-concepts:** dataset překračuje `reconciler.max-concepts`. Zvyšte limit (nebo
  stránkujte snímek).

---

# Architektura a poznámky k návrhu (pro vývojáře)

## Proč vůbec outbox

Postgres commit nelze učinit atomickým s HTTP zápisem do Fuseki: TDB2 je vlastní transakční doménou
bez zahrnutí XA/JTA a přes Fuseki HTTP je atomický jen jeden SPARQL požadavek. Vzor transakčního
outboxu to obchází zápisem *záměru* (RDF delty) do téže PG transakce jako metadata a následnou
asynchronní aplikací s opakováním. PG commit je jediným zdrojem pravdy o tom, „zda zápis nastal“;
relay zaručí, že RDF se nakonec srovná.

Klíčové vlastnosti očekávaného stavu:

- **Pořadí v rámci agregátu.** Řádky nesou monotónní `seq`; relay aplikuje řádek jen tehdy, neexistuje-li
  dřívější neaplikovaný řádek téhož agregátu. Na cestě zápisu přes outbox je řádek pojmu zamčen
  (`findWithLockById`, `PESSIMISTIC_WRITE`), takže dvě souběžné editace téhož pojmu nemohou zařadit
  prohozené řádky.
- **Přejmenování klíčuje na IRI před editací.** Outbox řádek editace používá IRI pojmu *před* editací,
  takže vytvoření-a-přejmenování sdílejí jeden agregát a řadí se správně.
- **Idempotentní aplikace.** Aplikace řádku je smazání + vložení omezené na pojem, takže opakovaná
  aplikace je bezpečná.
- **Atomické selhání.** Vadná data (např. poškozené N-Triples) označí jako FAILED **jen ten řádek** —
  neodvolá stavy DONE dřívějších řádků ani tiše nezasekne frontu.

## Detekční model rekonciliátoru

- **Pořadí čtení je bezpodmínečně TDB2 první, PG poslední** (nemění se podle `outbox.enabled`). Přínos
  tohoto pořadí závisí na aktivním režimu zápisu a vždy jde jen o drobnou optimalizaci — nikoli
  o mechanismus správnosti:
  - **Přímý zápis (outbox vypnut):** zápisy jsou TDB2 první, poté PG commit. Okno „za letu“ je „RDF
    zapsáno, PG zatím necommitnuto“ → přechodný falešný `RDF_ORPHAN`. Čtení PG jako *posledního* činí
    onen pozdní commit s nejvyšší pravděpodobností viditelným, čímž okno zmenšuje. Pro tento případ
    bylo pořadí zvoleno.
  - **Outbox (outbox zapnut):** pořadí se obrací — nejprve commituje PG (metadata + outbox řádek),
    relay aplikuje TDB2 až poté. Okno „za letu“ se mění na „PG commitnuto, TDB2 zatím neaplikováno“ →
    přechodný falešný `PG_MISSING_RDF`, nikoli sirotek. Pro *tuto* situaci by pomohlo číst TDB2 jako
    poslední, takže TDB2-první je mírně kontraproduktivní — v praxi však posunutí po commitu zapíše
    TDB2 během milisekund od commitu, takže okno je nepatrné, ledaže posunutí selže a řádek čeká na
    záchytný relay.
  - V **obou** režimech je zbytkový souběh v režimu pouze-detekce neškodný (jakýkoli falešný nález se
    při dalším běhu sám zahojí).
- **Jediný PG snímek.** `PgMetadataSnapshot` načte pojmy + slovníky v jedné
  `@Transactional(readOnly=true)`, aby obě čtení byla jedním konzistentním pohledem; žije ve vlastním
  beanu, aby se proxy skutečně uplatnila. Pojistka počtu `max-concepts` ukončí běh dříve, než by
  došlo k zápisu do heap paměti.
- **Množinový průchod.** Mapa vlastněná IRI → grafy se sestaví jednou; porovnání RDF→PG a PG→RDF jsou
  množinové operace, ne dotazy po řádcích.
- **Párování přejmenová.** Před finalizací sirotků se `RDF_ORPHAN`(nové) spáruje
  s `PG_MISSING_RDF`(staré) do jednoho `SUSPECTED_RENAME`, pokud sdílejí graf, IRI se liší jen v koncovce
  `/pojem/<název>` a popisky se shodují — aby budoucí oprava nikdy nesmazala čerstvě přejmenovaný pojem.
  (Známé omezení: přejmenování, které *zároveň* změní popisek, může této heuristice uniknout — tvrdá
  podmínka před nasazením automatické opravy; viz budoucí implementace)

## Perzistence

- **Outbox:** `ismd_schema.outbox_entry` + `outbox_seq` (Liquibase `007-create-outbox.yaml`); index
  `(aggregate_iri, seq)` pro pořadovou bránu (`008-outbox-aggregate-index.yaml`). Řádky DONE slouží
  zároveň jako auditní stopa cesty zápisu, dokud nejsou promazány.
- **Rekonciliátor:** zatím žádná — poslední report je držen v heap paměti (`volatile`, ztracen při
  restartu). Fáze oprav přidá `reconciler_orphan_candidate` / `reconciler_repair_audit` /
  `reconciler_run` uložené v PG.

## Bezpečnost

Obě admin cesty (`/api/admin/outbox/**`, `/api/admin/reconciler/**`) jsou registrovány
v autentizovaném řetězci `SecurityConfig` **a** chráněny `@PreAuthorize("hasRole('ADMIN')")`. Záznam
v allowlistu je nutný — bez něj požadavek narazí na `denyAll()` (403) dříve, než se uplatní
`@PreAuthorize`.

## Vzájemné působení obou

Když jsou oba komponenty zapnuté, spouštějte rekonciliátor pravidelně (cron job), abyste ověřili, že outbox neprotéká.
Jakýkoli nový nález nad rámec známé již existující základní hladiny — zejména `RDF_ORPHAN` nebo
`SUSPECTED_RENAME` — značí únik na cestě zápisu k prošetření. Před zapnutím automatické opravy musí
`reconciler.min-orphan-age` (budoucí vlastnost) překročit nejhorší možnou latenci přenosu relaye,
jinak by se rekonciliátor mohl pokusit opravit řádek, který relay právě chystá aplikovat.

## Plán budoucího rozvoje

- **Outbox:** zapnout v nasazeném prostředí, týden sledovat, poté odstranit nyní mrtvé větve přímého zápisu
  a kompenzátor `rollbackTDB2Data` na cestě vytvoření (úklid cesty uploadu ponechat).
- **Automatická oprava rekonciliátoru:** podmíněna karanténou (stárnutí před smazáním), auditním
  výpisem před smazáním, perzistentními tabulkami běhů/kandidátů, distribuovaným zámkem při více
  instancích a uzavřením mezery přejmenování-se-změnou-popisku. Automaticky opravována bude pouze
  jen `RDF_ORPHAN`; vše ostatní zůstává pouze hlášení.
- **PG řádky odkazovaných pojmů:** rekonciliátor je dnes hlásí jako `PG_MISSING_RDF` (řádky s cizím
  IRI, které neprojdou pravidlem vlastnictví). Správné zpracování je svázáno s plánovaným refaktorem
  publikovaných/draft pojmů.