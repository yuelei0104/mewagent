#!/usr/bin/env bash
set -uo pipefail
: "${CASE_DIR:?CASE_DIR not set}"
OUT=".verify-out"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -r "$CASE_DIR/checks/." "$OUT/"
cp ./*.java "$OUT/" 2>/dev/null || true
cd "$OUT" || exit 1
if ! javac -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -d . ./*.java 2>&1; then
  echo COMPILE_FAILED
  exit 1
fi
for f in *Test.java; do
  [ -e "$f" ] || continue
  cls="${f%.java}"
  echo "== $cls =="
  java -cp . "$cls" || exit 1
done
