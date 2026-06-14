#!/usr/bin/env bash
# Dry-run the REAL Pathline timeline builder over an exported backup, offline (no device/emulator).
# Unpacks a backup directory or .zip into CSVs, runs TimelineDryRunTest over the production
# TimelineRebuilder/TimelineMerger/VisitDetector/TripSegmenter, and prints the annotated report
# (each unconfirmed trip tagged real-walk / in-place-drift / wide-scatter-drift by the Doppler probe).
#
# Usage:
#   scripts/timeline-dryrun.sh <backup-dir-or-zip> [--since YYYY-MM-DD] [--until YYYY-MM-DD] \
#                                                   [--tz America/New_York] [--out DIR]
#
#   --since/--until  restrict the printed REPORT to a window (the rebuild always runs over the full
#                    data so lookback + confirmed-visit context is intact). Date in --tz, or epoch ms.
#   --tz             the device's zone (days are bucketed by it). Default America/New_York.
#   --out            where to write the CSVs + report.txt (default /tmp/pathline-dryrun). Persists.
#
# The backup must be unencrypted (manifest crypto.mode = NONE).
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,18p' "$0"; exit 2
fi
SRC="$1"; shift
SINCE=""; UNTIL=""; TZ_ID="America/New_York"; OUT="/tmp/pathline-dryrun"
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift 2;;
    --until) UNTIL="$2"; shift 2;;
    --tz)    TZ_ID="$2"; shift 2;;
    --out)   OUT="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

# 1) Resolve the backup directory (unzip a .zip into a temp dir) and locate manifest.json.
SRCDIR="$SRC"
if [ -f "$SRC" ]; then
  echo "unzipping $SRC ..."
  unzip -oq "$SRC" -d "$WORK/unz"
  SRCDIR="$WORK/unz"
fi
MANIFEST="$(find "$SRCDIR" -maxdepth 4 -name manifest.json | head -1 || true)"
[ -n "$MANIFEST" ] || { echo "no manifest.json found under $SRCDIR" >&2; exit 1; }
DUMP="$(dirname "$MANIFEST")"
echo "backup: $DUMP"

# 2) Flatten the gzipped JSONL + snapshot tables into the CSVs the test reads.
python3 "$ROOT/scripts/dump_to_csv.py" "$DUMP" "$OUT"

# 3) Run the dry run, forwarding the CSV dir, the device zone, and the optional window.
GP=(-DdryRunDir="$OUT" -Duser.timezone="$TZ_ID")
[ -n "$SINCE" ] && GP+=(-DdryRunSince="$SINCE")
[ -n "$UNTIL" ] && GP+=(-DdryRunUntil="$UNTIL")
echo "running dry run (tz=$TZ_ID${SINCE:+ since=$SINCE}${UNTIL:+ until=$UNTIL}) ..."
LOG="$WORK/gradle.log"
if ! ( cd "$ROOT" && ./gradlew :app:testDebugUnitTest --tests "*TimelineDryRunTest" \
        "${GP[@]}" --console=plain --rerun-tasks >"$LOG" 2>&1 ); then
  echo "gradle/test failed:" >&2; tail -40 "$LOG" >&2; exit 1
fi

echo
echo "================== report ($OUT/report.txt) =================="
cat "$OUT/report.txt"
