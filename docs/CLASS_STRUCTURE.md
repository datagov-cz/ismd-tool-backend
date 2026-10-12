# Class structure

Large classes are split into an **orchestrator** and **delegates**. The rules below are enforced by
`ClassStructureRulesTest`, which runs with the normal test suite.

## The pattern

- **Orchestrator** — keeps the public name and API that callers use. It sequences the work and owns
  the transaction and cache-eviction boundaries (`@Transactional`, `@CacheEvict`).
- **Delegate** — one focused class per operation (`ConceptEditOperation`, `OntologyDetailReader`) or
  per characteristic (`ConceptLabelWriter`, `ConceptSourceJsonMapper`). Package-private, reached only
  through its orchestrator.

| Orchestrator | Delegates |
|---|---|
| `ConceptServiceImpl`, `OntologyServiceImpl` | `*Operation`, `*Reader` in `service/impl` |
| `IsmdSearchProvider` | `Ismd*Search` in `service/search` |
| `JenaTDB2Repository` | `Fuseki*` in `repository` |
| `ConceptCreator` | `*Writer` in `utility/creator` |
| `ConceptJsonMapper` | `Concept*JsonMapper` in `utility/exporter/json` |

Service delegates are Spring beans. Utility delegates are built by their orchestrator, so it keeps a
no-argument constructor.

## Rules

| Rule | Limit |
|---|---|
| Lines per class | 500 |
| Constructor parameters of a Spring bean | 13 |
| Delegates | not `public`; no `@Transactional` or `@CacheEvict` |
| Fields of a singleton bean | `final` (`@Value` and `@Autowired` fields excepted) |

Per-call state goes in a context object passed to the delegates (`ConceptBuildContext`), never in a
field of a singleton.

## Exceptions

Both lists live in `ClassStructureRulesTest`.

- `OVERSIZED_CLASSES` — classes over the line limit today, each capped at its current size. An entry
  may shrink or be removed; none may be added.
- `MUTABLE_SINGLETON_FIELDS` — volatile caches that are reassigned on purpose.

## Adding a delegate

Name it after its package's pattern in the table above so the rules cover it. For a new orchestrator
family, add its package and name pattern to `areDelegates()` in the test.
