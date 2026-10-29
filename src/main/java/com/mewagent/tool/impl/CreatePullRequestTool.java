package com.mewagent.tool.impl;

import com.mewagent.tool.Tool;
import com.mewagent.tool.ToolCategory;
import com.mewagent.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 创建 Pull Request：把当前分支推到远端，然后开 PR。
 *
 * 优先走 {@code gh pr create}；未安装 gh 时降级为 git push + 返回 GitHub
 * compare 链接，用户点一次即可创建。降级路径是刻意的：CI 容器里通常没有
 * gh，但 git 一定有。
 *
 * 前置校验（任一不满足直接拒绝，避免产出垃圾 PR）：
 *  - 当前目录是 git 仓库
 *  - 不在 detached HEAD 上
 *  - 当前分支不是基线分支（main/master）
 *  - 工作区没有未提交改动
 *  - 相对基线至少有一个 commit
 *
 * 工具只负责「推送 + 开 PR」，不做 add/commit —— 提交信息应该由模型根据
 * 实际改动生成，这里代劳会丢失语义。
 */
public class CreatePullRequestTool implements Tool {

    private static final String DESCRIPTION = """
            Push the current branch to the remote and open a pull request.
            
            Usage notes:
            - Run this only after the change is verified (tests/build pass) and committed.
            - The current branch must not be the base branch (main/master).
            - The working tree must be clean; uncommitted changes are rejected.
            - Uses `gh pr create` when the GitHub CLI is available, otherwise pushes the \
            branch and returns a compare URL for manual creation.
            - Never force-pushes and never skips hooks.
            - Requires explicit user approval: pushing code is visible to others.""";

    private static final Pattern HTTPS_REMOTE =
            Pattern.compile("https?://(?:[^@/]+@)?([^/]+)/([^/]+)/(.+?)(?:\\.git)?/?$");
    private static final Pattern SSH_REMOTE =
            Pattern.compile("git@([^:]+):([^/]+)/(.+?)(?:\\.git)?/?$");

    private static final Pattern URL_IN_OUTPUT =
            Pattern.compile("https://\\S+");

    private String workDir;

    public CreatePullRequestTool() {
    }

    public CreatePullRequestTool(String workDir) {
        this.workDir = workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "CreatePullRequest";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "Pull request title"),
                                "body", Map.of("type", "string",
                                        "description", "Pull request description in markdown. "
                                                + "Summarize what changed and why."),
                                "base", Map.of("type", "string",
                                        "description", "Target branch. Defaults to the remote's default branch."),
                                "draft", Map.of("type", "boolean",
                                        "description", "Open as draft PR", "default", false),
                                "remote", Map.of("type", "string",
                                        "description", "Git remote name", "default", "origin")
                        ),
                        "required", List.of("title")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String title = strArg(args, "title", "");
        String body = strArg(args, "body", "");
        String remote = strArg(args, "remote", "origin");
        boolean draft = boolArg(args, "draft", false);

        if (title.isBlank()) {
            return ToolResult.error("Error: title is required");
        }

        String wd = workDir != null && !workDir.isBlank()
                ? workDir
                : System.getProperty("user.dir");

        // ── 1. 必须在 git 仓库内 ────────────────────────────────────────
        var isRepo = run(List.of("git", "rev-parse", "--is-inside-work-tree"), wd, 15);
        if (!isRepo.ok() || !"true".equals(isRepo.output().strip())) {
            return ToolResult.error("Error: not a git working tree: " + wd);
        }

        // ── 2. 当前分支合法 ─────────────────────────────────────────────
        var branchResult = run(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), wd, 15);
        if (!branchResult.ok()) {
            return ToolResult.error("Error: cannot determine current branch: " + branchResult.output());
        }
        String branch = branchResult.output().strip();
        if ("HEAD".equals(branch)) {
            return ToolResult.error(
                    "Error: repository is in detached HEAD state. Check out a branch first.");
        }
        if ("main".equals(branch) || "master".equals(branch)) {
            return ToolResult.error(
                    "Error: current branch is '%s', which is usually the base branch. "
                            .formatted(branch)
                            + "Create a feature branch (`git checkout -b <name>`) before opening a PR.");
        }

        String base = resolveBaseBranch(wd, remote, strArg(args, "base", ""));
        if (base.equals(branch)) {
            return ToolResult.error("Error: head and base branch are both '" + branch + "'.");
        }

        // ── 3. 工作区必须干净 ───────────────────────────────────────────
        var status = run(List.of("git", "status", "--porcelain"), wd, 30);
        if (!status.ok()) {
            return ToolResult.error("Error: `git status` failed: " + status.output());
        }
        if (!status.output().isBlank()) {
            return ToolResult.error(
                    "Error: working tree has uncommitted changes. Commit them first, then retry.\n"
                            + truncate(status.output(), 1500));
        }

        // ── 4. 相对基线至少一个 commit ──────────────────────────────────
        int ahead = commitsAhead(wd, remote, base);
        if (ahead == 0) {
            return ToolResult.error(
                    "Error: no commits ahead of '%s'. Nothing to open a pull request for."
                            .formatted(base));
        }

        // ── 5. 推送分支 ─────────────────────────────────────────────────
        var push = run(List.of("git", "push", "-u", remote, "HEAD"), wd, 300);
        if (!push.ok()) {
            return ToolResult.error("Error: `git push` failed:\n" + truncate(push.output(), 2000));
        }

        // ── 6. 创建 PR：gh 优先，否则给 compare 链接 ────────────────────
        if (!ghAvailable(wd)) {
            return ToolResult.success(
                    "Branch '%s' pushed to %s (%d commit ahead of %s).\n".formatted(branch, remote, ahead, base)
                            + "GitHub CLI (`gh`) is not installed, so the PR was not created automatically.\n"
                            + "Open it here: " + compareUrl(wd, remote, base, branch));
        }

        Path bodyFile = null;
        try {
            bodyFile = Files.createTempFile("mewagent-pr-", ".md");
            String content = body.isBlank() ? defaultBody(branch, base, ahead) : body;
            Files.writeString(bodyFile, content, StandardCharsets.UTF_8);

            var cmd = new ArrayList<>(List.of(
                    "gh", "pr", "create",
                    "--base", base,
                    "--head", branch,
                    "--title", title,
                    "--body-file", bodyFile.toString()));
            if (draft) {
                cmd.add("--draft");
            }

            var pr = run(cmd, wd, 120);
            if (pr.ok()) {
                String url = firstUrl(pr.output());
                return ToolResult.success("Pull request created: "
                        + (url.isEmpty() ? pr.output() : url));
            }

            // gh 失败时不让整次操作失败——分支已经推上去了，给出可点击的链接
            return ToolResult.success(
                    "Branch '%s' pushed, but `gh pr create` failed:\n%s\n\nCreate it manually: %s"
                            .formatted(branch, truncate(pr.output(), 1500),
                                    compareUrl(wd, remote, base, branch)));
        } catch (IOException e) {
            return ToolResult.success(
                    "Branch '%s' pushed. Could not write PR body file (%s).\nCreate it manually: %s"
                            .formatted(branch, e.getMessage(), compareUrl(wd, remote, base, branch)));
        } finally {
            if (bodyFile != null) {
                try {
                    Files.deleteIfExists(bodyFile);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    // ── git 辅助 ────────────────────────────────────────────────────────

    /**
     * 解析目标基线分支：显式指定 > 远端 HEAD 指向 > main/master 探测 > main。
     */
    private static String resolveBaseBranch(String wd, String remote, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        var symRef = run(List.of("git", "symbolic-ref", "--short", remote + "/HEAD"), wd, 15);
        if (symRef.ok()) {
            String ref = symRef.output().strip();
            int slash = ref.indexOf('/');
            if (slash >= 0 && slash < ref.length() - 1) {
                return ref.substring(slash + 1);
            }
        }
        for (String candidate : List.of("main", "master")) {
            if (run(List.of("git", "rev-parse", "--verify", "--quiet",
                    remote + "/" + candidate), wd, 15).ok()) {
                return candidate;
            }
        }
        return "main";
    }

    /** 当前分支相对基线领先多少个 commit；远端引用不存在时回退到本地基线。 */
    private static int commitsAhead(String wd, String remote, String base) {
        var remoteCount = run(List.of("git", "rev-list", "--count",
                remote + "/" + base + "..HEAD"), wd, 30);
        if (remoteCount.ok()) {
            return parseIntSafe(remoteCount.output().strip());
        }
        var localCount = run(List.of("git", "rev-list", "--count", base + "..HEAD"), wd, 30);
        return localCount.ok() ? parseIntSafe(localCount.output().strip()) : 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean ghAvailable(String wd) {
        return run(List.of("gh", "--version"), wd, 15).ok();
    }

    /** 由远端 URL 生成 GitHub compare 链接；解析失败返回说明文本。 */
    private static String compareUrl(String wd, String remote, String base, String head) {
        var urlResult = run(List.of("git", "remote", "get-url", remote), wd, 15);
        if (!urlResult.ok()) {
            return "(cannot read remote '%s')".formatted(remote);
        }
        String raw = urlResult.output().strip();

        Matcher https = HTTPS_REMOTE.matcher(raw);
        Matcher ssh = SSH_REMOTE.matcher(raw);
        String host;
        String repo;
        if (https.matches()) {
            host = https.group(1);
            repo = https.group(2) + "/" + https.group(3);
        } else if (ssh.matches()) {
            host = ssh.group(1);
            repo = ssh.group(2) + "/" + ssh.group(3);
        } else {
            return "(cannot parse remote url: %s)".formatted(raw);
        }
        return "https://%s/%s/compare/%s...%s?expand=1".formatted(host, repo, base, head);
    }

    private static String firstUrl(String output) {
        Matcher m = URL_IN_OUTPUT.matcher(output);
        return m.find() ? m.group() : "";
    }

    private static String defaultBody(String branch, String base, int ahead) {
        return """
                Automated pull request opened by MewAgent.

                - Branch: `%s`
                - Base: `%s`
                - Commits: %d
                """.formatted(branch, base, ahead);
    }

    // ── 进程执行 ────────────────────────────────────────────────────────

    private record CmdResult(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 执行外部命令。输出由虚拟线程异步读取，避免「进程写满管道缓冲区后
     * 阻塞、而主线程还在等 exit」的经典死锁。
     */
    private static CmdResult run(List<String> command, String workDir, long timeoutSeconds) {
        try {
            var pb = new ProcessBuilder(command).redirectErrorStream(true);
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            var output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = process.getInputStream()) {
                    output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 进程被强杀时流可能提前关闭
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(2000);
                return new CmdResult(-1,
                        output + "\n(command timed out after " + timeoutSeconds + "s)");
            }
            reader.join(5000);
            return new CmdResult(process.exitValue(), output.toString().strip());

        } catch (IOException e) {
            return new CmdResult(-1, "Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CmdResult(-1, "Error: interrupted");
        }
    }

    // ── 参数解析 ────────────────────────────────────────────────────────

    private static String strArg(Map<String, Object> args, String key, String def) {
        var v = args == null ? null : args.get(key);
        return v instanceof String s ? s : def;
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean def) {
        var v = args == null ? null : args.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}
