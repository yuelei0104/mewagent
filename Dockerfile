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

# ── 受限网络下的构建支持 ─────────────────────────────────────────────────
# 1) 离线发行版：wrapper 只认 ~/.gradle/wrapper/dists/<dist>/<hash>/*.ok 这个标记，
#    命中后完全不访问 services.gradle.org。把本机已下载的 gradle-8.13-bin 目录放进
#    docker/gradle-dists/ 即可离线构建（该目录的内容不入库，只留 .gitkeep）。
#    GitHub Releases 与 services.gradle.org 在部分网络下不可达，这一步是必需的。
COPY docker/gradle-dists/ /root/.gradle/wrapper/dists/

# 2) 代理：gradle-wrapper.jar 在 Gradle 启动之前就要下载发行版，那时还读不到
#    gradle.properties，所以代理只能通过 JVM 参数注入；GRADLE_OPTS 会被 gradlew
#    同时传给 wrapper 与 daemon。默认留空 = 直连。
ARG GRADLE_PROXY_HOST=
ARG GRADLE_PROXY_PORT=7897
RUN if [ -n "$GRADLE_PROXY_HOST" ]; then \
      printf -- '-Dhttp.proxyHost=%s -Dhttp.proxyPort=%s -Dhttps.proxyHost=%s -Dhttps.proxyPort=%s' \
        "$GRADLE_PROXY_HOST" "$GRADLE_PROXY_PORT" \
        "$GRADLE_PROXY_HOST" "$GRADLE_PROXY_PORT" > /tmp/gradle.opts \
      && echo "Gradle proxy -> $GRADLE_PROXY_HOST:$GRADLE_PROXY_PORT"; \
    else \
      : > /tmp/gradle.opts; \
    fi

# 先只拷贝构建脚本：源码变动时不会重新下载依赖，只重跑编译
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew \
 && GRADLE_OPTS="$(cat /tmp/gradle.opts)" ./gradlew --no-daemon --quiet dependencies > /dev/null 2>&1 || true

COPY src/ src/
RUN GRADLE_OPTS="$(cat /tmp/gradle.opts)" ./gradlew --no-daemon shadowJar -x test

# ─────────────────────────────────────────────────────────────────────
# 阶段 2：运行时
# ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# 缺少 gh 时 CreatePullRequest 会降级为 compare 链接；需要离线构建可设为 false
ARG INSTALL_GH=true

RUN apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 update \
 && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 \
      install -y --no-install-recommends \
      bash \
      git \
      curl \
      ca-certificates \
      bubblewrap \
      tini \
 && rm -rf /var/lib/apt/lists/*

# gh 装在网络可达的环境里更完整；不可达时不能让整个构建失败——缺少 gh 时
# CreatePullRequestTool 会自动降级为 git push + compare 链接。curl 必须带超时，
# 否则在 GitHub 不可达的网络里会一直等到 TCP 超时。
RUN if [ "$INSTALL_GH" = "true" ]; then \
      ( mkdir -p /usr/share/keyrings \
        && curl -fsSL --connect-timeout 10 --max-time 120 \
             https://cli.github.com/packages/githubcli-archive-keyring.gpg \
             -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
        && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
        && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
             > /etc/apt/sources.list.d/github-cli.list \
        && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=30 update \
        && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=30 \
             install -y --no-install-recommends gh ) \
      || echo "WARNING: gh 未安装，CreatePullRequest 将降级为 git push + compare 链接"; \
      rm -rf /var/lib/apt/lists/*; \
    fi

# 基础镜像自带 uid=1000 的 ubuntu 用户，直接改名复用：既避开 UID 冲突，
# 又保持与常见宿主用户相同的 uid，绑定挂载时不会出现权限错位。
RUN groupmod -n agent ubuntu \
 && usermod -l agent -d /home/agent -m ubuntu

# WORKDIR 由 root 创建，非 root 用户需要写权限
RUN mkdir -p /workspace && chown agent:agent /workspace

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
