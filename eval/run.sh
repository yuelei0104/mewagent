#!/usr/bin/env bash
#
# 评测入口。用法（在项目根目录）：
#   ./eval/run.sh                    # 跑全部用例
#   ./eval/run.sh --only=001,003     # 只跑 id 含 001 或 003 的用例
#   ./eval/run.sh --limit=2          # 只跑前 2 个
#   ./eval/run.sh --config=/path/config.yaml
#
# 会先确保 shadow jar 是最新的，再驱动真实 Agent 执行每个用例。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAR="build/libs/mewagent.jar"
if [ ! -f "$JAR" ] || [ -n "$(find src -newer "$JAR" -name '*.java' -print -quit 2>/dev/null)" ]; then
  echo "[eval] 重新构建 $JAR ..."
  ./gradlew -q shadowJar
fi

exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$JAR" eval/EvalRunner.java "$@"
