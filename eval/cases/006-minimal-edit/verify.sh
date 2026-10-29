#!/usr/bin/env bash
# 最小改动校验：整份文件必须与"只修了拼写"的期望内容逐字节一致。
# 比 diff 行数更严格——改多、改少、动格式都会失败。
set -uo pipefail
: "${CASE_DIR:?CASE_DIR 未设置}"

EXPECTED="$CASE_DIR/checks/README.expected.md"
if [ ! -f README.md ]; then
  echo "MISSING: README.md 不见了"
  exit 1
fi

if ! cmp -s README.md "$EXPECTED"; then
  echo "CONTENT_MISMATCH: README.md 与期望内容不一致（改多了、改错了或没改）"
  diff -u "$EXPECTED" README.md | head -20
  exit 1
fi

echo "MINIMAL_EDIT_OK"
