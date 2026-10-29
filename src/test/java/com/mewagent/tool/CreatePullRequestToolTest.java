package com.mewagent.tool;

import com.mewagent.tool.impl.CreatePullRequestTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CreatePullRequestTool 的前置校验。
 *
 * 只测「拒绝路径」——真正 push / 开 PR 需要远端仓库和网络，不在单元测试
 * 覆盖范围内。这些校验是防止产出垃圾 PR 的最后一道闸，值得逐个锁住。
 */
class CreatePullRequestToolTest {

    private Path repo;
    private CreatePullRequestTool tool;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(gitAvailable(), "git not available, skipping");

        repo = Files.createTempDirectory("mewagent-pr-test");
        git("init", "-q");
        git("symbolic-ref", "HEAD", "refs/heads/main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");

        Files.writeString(repo.resolve("README.md"), "init\n");
        git("add", ".");
        git("commit", "-q", "-m", "init");

        tool = new CreatePullRequestTool(repo.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (repo == null || !Files.exists(repo)) return;
        try (var paths = Files.walk(repo)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 上 .git 里的文件可能短暂被占用
                }
            });
        }
    }

    @Test
    void rejectsMissingTitle() {
        var result = tool.execute(Map.of());
        assertTrue(result.isError());
        assertTrue(result.output().contains("title is required"), result.output());
    }

    @Test
    void rejectsNonGitDirectory() throws Exception {
        Path plain = Files.createTempDirectory("mewagent-plain");
        try {
            // 临时目录若恰好位于某个仓库内，这个用例没有意义
            var probe = run(plain, "git", "rev-parse", "--is-inside-work-tree");
            assumeTrue(!probe.output().contains("true"), "temp dir is inside a git repo");

            var result = new CreatePullRequestTool(plain.toString())
                    .execute(Map.of("title", "feat: something"));
            assertTrue(result.isError());
            assertTrue(result.output().contains("not a git working tree"), result.output());
        } finally {
            Files.deleteIfExists(plain);
        }
    }

    @Test
    void rejectsBaseBranch() {
        var result = tool.execute(Map.of("title", "feat: something"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("usually the base branch"), result.output());
    }

    @Test
    void rejectsDirtyWorkTree() throws Exception {
        git("checkout", "-q", "-b", "feature");
        Files.writeString(repo.resolve("scratch.txt"), "uncommitted\n");

        var result = tool.execute(Map.of("title", "feat: something"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("uncommitted changes"), result.output());
    }

    @Test
    void rejectsWhenNothingAheadOfBase() throws Exception {
        git("checkout", "-q", "-b", "feature");
        // 工作区干净，但 feature 与 main 指向同一个 commit
        var result = tool.execute(Map.of("title", "feat: something"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("no commits ahead"), result.output());
    }

    @Test
    void rejectsDetachedHead() throws Exception {
        git("checkout", "-q", "--detach", "HEAD");
        var result = tool.execute(Map.of("title", "feat: something"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("detached HEAD"), result.output());
    }

    @Test
    void schemaDeclaresRequiredTitleAndCommandCategory() {
        assertEquals("CreatePullRequest", tool.name());
        assertEquals(ToolCategory.COMMAND, tool.category());
        assertFalse(tool.shouldDefer(), "PR creation must stay visible, not deferred");

        @SuppressWarnings("unchecked")
        var input = (Map<String, Object>) tool.schema().get("input_schema");
        @SuppressWarnings("unchecked")
        var required = (List<String>) input.get("required");
        assertTrue(required.contains("title"));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private void git(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        var result = run(repo, command);
        assertEquals(0, result.exitCode(),
                "git " + String.join(" ", args) + " failed: " + result.output());
    }

    private record Result(int exitCode, String output) {}

    private static Result run(Path workDir, String... command) throws Exception {
        return run(workDir, Arrays.asList(command));
    }

    private static Result run(Path workDir, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);
        return new Result(p.exitValue(), out);
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
