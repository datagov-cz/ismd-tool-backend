#!/usr/bin/env bash
# Backward-compatibility gate for Liquibase changelogs.
#
# Fails a PR that introduces a change the PREVIOUS app version cannot tolerate, so that rolling the
# container image back to the prior tag never requires a database rollback.
#
# Destructive = the old code's SELECT/INSERT would break against the new schema:
#   dropColumn / dropTable / renameColumn / renameTable / modifyDataType / dropNotNullConstraint's
#   inverse (addNotNullConstraint on an existing column), delete, and raw UPDATE/DELETE/DROP/ALTER.
#
# Exit 0 = safe to roll the image back. Exit 1 = destructive change needs an explicit waiver.

set -uo pipefail

CHANGELOG_DIR="${CHANGELOG_DIR:-src/main/resources/db/changelog}"
BASE_REF="${1:-origin/dev}"

# Only inspect changelog files ADDED or MODIFIED in this PR.
# Newline-delimited rather than mapfile: bash 3.2 (macOS default) has no mapfile.
CHANGED=$(git diff --name-only --diff-filter=AM "$BASE_REF"...HEAD -- "$CHANGELOG_DIR" 2>/dev/null | grep -E '\.(yaml|yml|sql|xml)$' || true)

# The master changelog uses an explicit include: list rather than includeAll (see
# docs/MIGRATION_ROLLBACK_AUDIT.md), so a changelog that is never listed silently never runs. Check
# unconditionally: a PR can add a changelog without modifying any other changelog file.
MASTER="$CHANGELOG_DIR/db.changelog-master.yaml"
# Match the YAML key, not the bare word: the master's own explanatory comment mentions includeAll.
if [ -f "$MASTER" ] && ! grep -qE '^[[:space:]]*-?[[:space:]]*includeAll[[:space:]]*:' "$MASTER"; then
  ORPHANS=""
  for onDisk in "$CHANGELOG_DIR"/v1/*.yaml; do
    [ -f "$onDisk" ] || continue
    base=$(basename "$onDisk")
    grep -qF "$base" "$MASTER" || ORPHANS="$ORPHANS  $base
"
  done
  if [ -n "$ORPHANS" ]; then
    echo "❌ Changelog(s) on disk but absent from $MASTER — they will NEVER run:"
    printf '%s' "$ORPHANS"
    echo "   Add each to the include: list, at the end."
    exit 1
  fi
fi

if [ -z "$CHANGED" ]; then
  echo "✅ No changelog files touched — image rollback is unaffected."
  exit 0
fi

echo "Inspecting changed changelog file(s) against $BASE_REF:"
echo "$CHANGED" | sed 's/^/  /'
echo

VIOLATIONS=0
WAIVED=0

# Structural change types that break the previous app version.
DESTRUCTIVE_TYPES='dropColumn|dropTable|dropSequence|renameColumn|renameTable|modifyDataType|addNotNullConstraint|dropPrimaryKey|dropUniqueConstraint|addUniqueConstraint'

while IFS= read -r f; do
  [ -n "$f" ] || continue
  [ -f "$f" ] || continue

  # An explicit, reviewed waiver makes this release a rollback floor. Skipping the whole file keeps
  # the waiver a deliberate per-changelog decision rather than a blanket off-switch.
  if grep -qE '^[[:space:]]*#[[:space:]]*ROLLBACK-UNSAFE:' "$f"; then
    echo "🟡 $f: ROLLBACK-UNSAFE waiver present — image rollback past this release requires PITR."
    WAIVED=$((WAIVED+1))
    continue
  fi

  # Consider only lines ADDED by this PR, so pre-existing history never re-trips the gate.
  # '[+]' not '\+': BSD/ugrep reject a quantifier applied to an escaped '+' in the '^+++' filter.
  ADDED=$(git diff -U0 "$BASE_REF"...HEAD -- "$f" | grep -E '^[+]' | grep -v '^[+][+][+]' | sed 's/^[+]//')
  [ -z "$ADDED" ] && continue

  # 1. Destructive structural change types.
  while IFS= read -r line; do
    if echo "$line" | grep -qE "^[[:space:]]*-?[[:space:]]*($DESTRUCTIVE_TYPES)[[:space:]]*:"; then
      TYPE=$(echo "$line" | grep -oE "($DESTRUCTIVE_TYPES)")
      echo "❌ $f: '$TYPE' breaks the previous app version."
      VIOLATIONS=$((VIOLATIONS+1))
    fi
  done <<< "$ADDED"

  # 2. Raw SQL that mutates or drops. CREATE/CREATE INDEX/CREATE EXTENSION are additive and fine.
  #    Skip YAML comments and 'id:'/'author:' keys so prose like "drop the old column" or a
  #    changeset id of '010-comments-drop-iri-columns' does not trip the gate.
  while IFS= read -r line; do
    echo "$line" | grep -qE '^[[:space:]]*#' && continue
    echo "$line" | grep -qE '^[[:space:]]*-?[[:space:]]*(id|author|indexName|constraintName|tableName|columnName):' && continue
    if echo "$line" | grep -qiE '(^|[[:space:]"'"'"'>])(DROP[[:space:]]+(TABLE|COLUMN|INDEX|SEQUENCE|CONSTRAINT)|TRUNCATE[[:space:]])'; then
      echo "❌ $f: raw SQL contains a destructive statement: $(echo "$line" | tr -s ' ' | cut -c1-90)"
      VIOLATIONS=$((VIOLATIONS+1))
    fi
  done <<< "$ADDED"

  # 3. Data-destroying changes: <delete> and raw UPDATE/DELETE. These are irreversible regardless of
  #    schema compatibility — no rollback script can restore the prior row values.
  while IFS= read -r line; do
    echo "$line" | grep -qE '^[[:space:]]*#' && continue
    if echo "$line" | grep -qE '^[[:space:]]*-?[[:space:]]*delete[[:space:]]*:' || echo "$line" | grep -qiE '^[[:space:]]*(sql:[[:space:]]*)?(UPDATE[[:space:]]|DELETE[[:space:]]+FROM)'; then
      echo "⚠️  $f: irreversible DATA change — cannot be undone by any rollback script."
      VIOLATIONS=$((VIOLATIONS+1))
    fi
  done <<< "$ADDED"
done <<< "$CHANGED"

echo
if [ "$VIOLATIONS" -gt 0 ]; then
  cat <<'EOF'
─────────────────────────────────────────────────────────────────────
FAILED: this PR contains migrations that make image rollback unsafe.

Fix by splitting the change across two releases (expand/contract):
  Release N   — add the new column/table, dual-write, backfill. Additive only.
  Release N+1 — once N is confirmed stable and you will not roll back past it,
                drop the old column in a follow-up PR.

If the change is genuinely required now, add an explicit waiver line to the
changelog file and have DevOps ack that this release becomes a rollback floor:

  # ROLLBACK-UNSAFE: <reason> — image rollback past this release requires PITR.
─────────────────────────────────────────────────────────────────────
EOF
  exit 1
fi

if [ "$WAIVED" -gt 0 ]; then
  echo "✅ Passed with $WAIVED waived file(s). DevOps: this release is a ROLLBACK FLOOR."
else
  echo "✅ All changed migrations are backward-compatible — image rollback is safe."
fi
exit 0
