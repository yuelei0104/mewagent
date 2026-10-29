#!/usr/bin/env bash
#
# 容器入口：准备 git / gh 环境，然后启动 MewAgent。
set -euo pipefail

# ── 定位配置文件 ──────────────────────────────────────────────────────
# ConfigLoader 自身按 $HOME/.mewagent → $CWD/.mewagent 的顺序查找；
# 这里先探测一遍，好在缺失时给出明确的修复指引而不是让它自己报错。
if [ -z "${MEWAGENT_CONFIG:-}" ]; then
  for candidate in "$HOME/.mewagent/config.yaml" "/workspace/.mewagent/config.yaml"; do
    if [ -f "$candidate" ]; then
      export MEWAGENT_CONFIG="$candidate"
      break
    fi
  done
fi

if [ -z "${MEWAGENT_CONFIG:-}" ] || [ ! -f "$MEWAGENT_CONFIG" ]; then
  cat >&2 <<'EOF'
[mewagent] 找不到配置文件，容器无法启动。

需要提供 .mewagent/config.yaml，三种方式任选其一：

  1. 挂载宿主机的配置目录
     -v "$HOME/.mewagent:/home/agent/.mewagent"

  2. 使用命名卷，首次启动后把配置复制进去
     docker compose up -d
     docker compose cp .mewagent/config.yaml mewagent:/home/agent/.mewagent/config.yaml
     docker compose restart

  3. 直接挂载工作区（工作区里自带 .mewagent/config.yaml）
     -v "$PWD:/workspace"

或显式指定路径：-e MEWAGENT_CONFIG=/abs/path/config.yaml
EOF
  exit 1
fi

echo "[mewagent] config: $MEWAGENT_CONFIG"

# ── git 身份 ──────────────────────────────────────────────────────────
# 容器内没有全局 git 配置，Agent 提交时会因缺少身份直接失败
if ! git config --global user.email >/dev/null 2>&1; then
  git config --global user.email "${GIT_AUTHOR_EMAIL:-mewagent@localhost}"
  git config --global user.name "${GIT_AUTHOR_NAME:-MewAgent}"
fi

# 挂载目录的属主通常与容器内 uid 不一致，git 会拒绝操作
git config --global --add safe.directory "${GIT_SAFE_DIR:-/workspace}"
git config --global --add safe.directory '*'

# ── gh 认证 ───────────────────────────────────────────────────────────
# 有 token 才能由 CreatePullRequest 直接开 PR；否则该工具降级为 compare 链接
if [ -n "${GITHUB_TOKEN:-}" ] && command -v gh >/dev/null 2>&1; then
  if echo "$GITHUB_TOKEN" | gh auth login --with-token >/dev/null 2>&1; then
    echo "[mewagent] gh authenticated"
  else
    echo "[mewagent] gh auth failed; CreatePullRequest will fall back to compare URL" >&2
  fi
fi

# JAVA_OPTS 故意不加引号：需要按空格拆成多个 JVM 参数
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /opt/mewagent/mewagent.jar "$@"
