#!/usr/bin/env bash
# 由 EvalRunner 在工作区根目录执行；CASE_DIR 指向用例目录（含隐藏测试）。
set -uo pipefail
: "${CASE_DIR:?CASE_DIR 未设置}"
: "${WORK_DIR:?WORK_DIR 未设置}"

OUT=".verify-out"
rm -rf "$OUT"
mkdir -p "$OUT"

cp -r "$CASE_DIR/checks/." "$OUT/"
cp ./*.java "$OUT/" 2>/dev/null || true

cd "$OUT" || exit 1
if ! javac -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -d . ./*.java 2>&1; then
  echo "COMPILE_FAILED"
  exit 1
fi

# workdir 用于让测试回读源码文本，检查 Javadoc 规范
java -Dworkdir="$WORK_DIR" -cp . ConventionTest
