---
id: NNNN
title: <short imperative title>
status: proposed | accepted | applied | superseded
date: YYYY-MM-DD
authors: <name(s)>
tags: [<area>, <component>]
supersedes: <id or ->
superseded_by: <id or ->
---

# NNNN — <title>

## Summary

One or two sentences: what was found and what was decided/done.

## Context

What prompted this record — the symptom, the trigger, the environment (dev/prod), and any
signals that started the investigation. Include the concrete observation (error, metric, report
output) verbatim where useful.

## Investigation

The trail that led to the root cause. Keep it chronological and evidence-based — queries run,
their results, what each step ruled in or out. This is the audit value: someone should be able to
re-derive the conclusion.

## Root cause

The precise defect, cited to `file:line` where applicable. State the mechanism, not just the
location — why the code produced the observed behavior.

## Decision / Fix

What was changed and why this approach over the alternatives. Split into:

- **Code fix** — the change, with file references and the test that guards it.
- **Data repair** (if any) — the exact operation applied, and whether it bypassed the normal
  write path (outbox / migration).

## Alternatives considered

Options weighed and why they were not chosen. Omit if there was only one reasonable path.

## Verification

How the fix was proven — tests run, commands executed, before/after state. Include the acceptance
signal (e.g. the metric that returned to expected).

## Consequences & follow-ups

Residual risk, blast radius on other data, and any deferred work (tickets, retention/config
changes, related bugs this exposed).

## References

- Related records: [[NNNN]]
- Related docs / code / issues