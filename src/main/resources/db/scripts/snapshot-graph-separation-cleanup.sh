#!/usr/bin/env bash
#
# One-off cleanup for the NKD snapshot.
#
# Removes every materialized NKD snapshot copy from the owner graphs in Fuseki. Copies are identified
# by the provenance marker <https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of>. After feat/local-snapshot-graph-separation
# ships (copies are PG-only, never written to TDB2), these are stale duplicates; the PG rows
# (nkd_concept_snapshots.materialized_triples) remain the source of truth, so nothing is lost.
#
# Run AFTER feature ships, on DEV first, then test. Verify afterwards with the
# reconciler dry-run: snapshot subjects must no longer report RDF_ORPHAN.
#
# ENVIRONMENT-AGNOSTIC: the only required input is the Fuseki dataset base URL. It talks to Fuseki
# over HTTP exactly as the app does. Point FUSEKI_URL at whichever deployed
# dataset you are cleaning.
#
#   dev:  FUSEKI_URL = the app's ${FUSEKI_URL}                 (e.g. http://<dev-host>:3030/<dataset>)
#   prod: FUSEKI_URL = the app's jena.fuseki.url               (e.g. http://localhost:3030/production-dataset)
#
# Usage:
#   FUSEKI_URL=<dataset-base> ./snapshot-graph-separation-cleanup.sh count    # dry-run: report only
#   FUSEKI_URL=<dataset-base> ./snapshot-graph-separation-cleanup.sh delete   # execute (prompts to confirm)
#   FUSEKI_URL=<dataset-base> ./snapshot-graph-separation-cleanup.sh verify   # count and FAIL if any remain
#
# Optional env:
#   CURL_AUTH        extra curl auth args, e.g. '-u user:pass' or a bearer setup (default: none)
#   QUERY_PATH       query sub-path under the base   (default: sparql — matches the app)
#   UPDATE_PATH      update sub-path under the base   (default: update — matches the app)
#   ASSUME_YES=1     skip the delete confirmation prompt (for non-interactive/CI runs)
#
# Requires: bash, curl.

set -euo pipefail

MARKER="https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of"
: "${FUSEKI_URL:?set FUSEKI_URL to the Fuseki dataset base, e.g. http://host:3030/production-dataset}"

MODE="${1:-count}"
AUTH="${CURL_AUTH:-}"
QUERY_PATH="${QUERY_PATH:-sparql}"
UPDATE_PATH="${UPDATE_PATH:-update}"

# Strip any trailing slash so "<base>/sparql" is well-formed regardless of how FUSEKI_URL was given.
BASE="${FUSEKI_URL%/}"
QUERY_URL="${BASE}/${QUERY_PATH}"
UPDATE_URL="${BASE}/${UPDATE_PATH}"

# A copy subject may carry MANY nkd-snapshot-of markers (one per owner that shares the copy), so we
# match on subject EXISTENCE of the marker, not on the marker triple itself — otherwise the delete
# solution-set cross-joins every (?p ?v) against every ?o marker binding and blows up ~N-fold before
# dedup. EXISTS deletes each copy triple once. copySubjects counts distinct copy subjects.
count_query="SELECT (COUNT(DISTINCT ?s) AS ?copySubjects)
WHERE { GRAPH ?g { ?s ?p ?v . FILTER EXISTS { ?s <${MARKER}> ?o } } }"

delete_update="DELETE { GRAPH ?g { ?s ?p ?v } }
WHERE { GRAPH ?g { ?s ?p ?v . FILTER EXISTS { ?s <${MARKER}> ?o } } }"

# Runs the count query and echoes the number of distinct copy subjects (empty string on parse miss).
copy_subject_count() {
  local json
  # shellcheck disable=SC2086
  json="$(curl -sS --fail-with-body $AUTH "${QUERY_URL}" \
    -H "Accept: application/sparql-results+json" \
    --data-urlencode "query=${count_query}")"
  # Extract the first "value" following "copySubjects" without requiring jq (keep deps minimal).
  printf '%s' "$json" \
    | tr -d '\n' \
    | sed -n 's/.*"copySubjects"[^}]*"value"[[:space:]]*:[[:space:]]*"\([0-9]*\)".*/\1/p'
}

case "$MODE" in
  count)
    echo "Counting materialized NKD snapshot copies across all graphs in ${BASE} ..."
    n="$(copy_subject_count)"
    echo "Copy subjects (nkd-snapshot-of) remaining: ${n:-<unknown — check response>}"
    ;;

  delete)
    n="$(copy_subject_count)"
    echo "About to delete ${n:-?} materialized NKD snapshot copy subject(s) from ${BASE}."
    if [[ "${ASSUME_YES:-}" != "1" ]]; then
      read -r -p "Proceed? This is irreversible in Fuseki (PG rows are unaffected). [y/N] " reply
      [[ "$reply" == "y" || "$reply" == "Y" ]] || { echo "Aborted."; exit 1; }
    fi
    echo "Deleting ..."
    # shellcheck disable=SC2086
    curl -sS --fail-with-body $AUTH "${UPDATE_URL}" \
      --data-urlencode "update=${delete_update}"
    after="$(copy_subject_count)"
    echo "Done. Copy subjects remaining now: ${after:-<unknown>}"
    echo "Next: run the reconciler dry-run and confirm snapshot subjects no longer report RDF_ORPHAN."
    ;;

  verify)
    # For CI / post-run gating: exit non-zero if any copies remain.
    n="$(copy_subject_count)"
    echo "Copy subjects remaining: ${n:-<unknown>}"
    if [[ -z "$n" ]]; then
      echo "Could not parse the count response." >&2
      exit 3
    fi
    if [[ "$n" -ne 0 ]]; then
      echo "FAIL: ${n} copy subject(s) still present." >&2
      exit 1
    fi
    echo "OK: no materialized copies remain."
    ;;

  *)
    echo "Unknown mode '$MODE' (expected 'count', 'delete', or 'verify')" >&2
    exit 2
    ;;
esac