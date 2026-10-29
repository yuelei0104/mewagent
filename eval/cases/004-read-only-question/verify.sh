#!/usr/bin/env bash
# 反向用例：纯问答任务不应产生任何文件改动。
# 基线由 EvalRunner 生成（每行 "<sha256>  相对路径"），不依赖 git。
set -uo pipefail
: "${BASELINE:?BASELINE 未设置}"

if ! sha256sum -c "$BASELINE" --quiet 2>&1; then
  echo "CONTENT_CHANGED: 纯问答不应修改任何文件"
  sha256sum -c "$BASELINE" 2>&1 | grep -v ': OK$' | head -10
  exit 1
fi

BASE_COUNT=$(wc -l < "$BASELINE" | tr -d ' ')
CURRENT_COUNT=$(find . -type f -not -path './.verify-out/*' | wc -l | tr -d ' ')
if [ "$CURRENT_COUNT" -ne "$BASE_COUNT" ]; then
  echo "FILE_COUNT_CHANGED: 基线 ${BASE_COUNT} 个文件，现在 ${CURRENT_COUNT} 个"
  find . -type f -not -path './.verify-out/*' | sort | head -20
  exit 1
fi

echo "WORKSPACE_CLEAN"
