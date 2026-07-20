# Decision Records

Development decision and audit records for this project. Each record captures a **finding**
(bug, drift, design gap) and its **resolution** — the investigation trail, root cause, applied
fix, and verification — so the reasoning survives past the git log and any memory retention window.

## When to write one

- A production/dev incident whose diagnosis took non-trivial investigation.
- A design or architecture decision with trade-offs worth recording.
- Any data repair applied by hand (out-of-band writes bypassing the normal path).
- A root-cause fix where the "why" is not obvious from the diff alone.

## How to use

1. Copy [`TEMPLATE.md`](./TEMPLATE.md) to `NNNN-short-kebab-title.md`, where `NNNN` is the next
   zero-padded sequence number.
2. Fill in every section; delete a section only if it genuinely does not apply.
3. Set the status in the frontmatter (`proposed` / `accepted` / `applied` / `superseded`).
4. Link related records and docs at the bottom.

## Index

| # | Title | Status | Date |
|---|-------|--------|------|
| [0001](./0001-ontology-rename-stale-inscheme.md) | Ontology rename leaves concept `skos:inScheme` on old scheme | applied | 2026-07-13 |