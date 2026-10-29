# syntax=docker/dockerfile:1
#
# MewAgent 容器镜像
#
# 运行阶段基于 Linux，所以 OS 级沙箱（bubblewrap）可以真正启用——
# Windows 宿主上 SandboxFactory 返回 null 的降级路径在这里不再触发。
#
# 构建：  docker build -t mewagent:latest .
# 运行：  docker run --rm -it -p 18888:18888 \
#           -v "$PWD:/workspace" -v mewagent-config:/home/agent/.mewagent \
#           -e MEWAGENT_TASK_TOKEN=secret mewagent:latest --remote

# ─────────────────────────────────────────────────────────────────────
# 阶段 1：构建 fat jar
# ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# 先只拷贝构建脚本：源码变动时不会重新下载依赖，只重跑编译
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew \
 && ./gradlew --no-daemon --quiet dependencies > /dev/null 2>&1 || true

COPY src/ src/
RUN ./gradlew --no-daemon shadowJar -x test

# ─────────────────────────────────────────────────────────────────────
# 阶段 2：运行时
# ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# 缺少 gh 时 CreatePullRequest 会降级为 compare 链接；需要离线构建可设为 false
ARG INSTALL_GH=true

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash \
      git \
      curl \
      ca-certificates \
      bubblewrap \
      tini \
 && rm -rf /var/lib/apt/lists/*

RUN if [ "$INSTALL_GH" = "true" ]; then \
      mkdir -p /usr/share/keyrings \
      && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
           -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
      && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
      && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
           > /etc/apt/sources.list.d/github-cli.list \
      && apt-get update \
      && apt-get install -y --no-install-recommends gh \
      && rm -rf /var/lib/apt/lists/*; \
    fi

RUN useradd --create-home --shell /bin/bash --uid 1000 agent

COPY --from=build /src/build/libs/mewagent.jar /opt/mewagent/mewagent.jar
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER agent
WORKDIR /workspace

# 控制台代码页在容器里是 UTF-8，显式指定编码避免中文输出乱码
ENV JAVA_OPTS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -XX:MaxRAMPercentage=75"

EXPOSE 18888

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:18888/ > /dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["--remote"]
