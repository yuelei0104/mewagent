import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * MewAgent 回归评测跑分器。
 *
 * <p>每个用例是一个目录，包含：
 * <ul>
 *   <li>{@code case.json} —— 用例元数据：提示词、超时、轮次上限</li>
 *   <li>{@code workspace/} —— 交给 Agent 的初始工作区</li>
 *   <li>{@code verify.sh} —— 断言脚本，退出码 0 视为通过</li>
 * </ul>
 *
 * <p>跑分流程：复制工作区到临时目录 → git 建立基线 → 用 {@code -p ... --output-format
 * stream-json} 驱动真实 Agent → 解析事件流拿指标 → 执行 verify.sh 判定。
 *
 * <p>用法（从项目根目录）：
 * <pre>
 *   java -cp build/libs/mewagent.jar eval/EvalRunner.java
 *   java -cp build/libs/mewagent.jar eval/EvalRunner.java --only=001,002 --limit=3
 * </pre>
 */
public final class EvalRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单个用例的规格。 */
    record CaseSpec(String id, String title, String prompt, int timeoutSec, int maxTurns, Path dir) {}

    /** 单个用例的执行结果。 */
    record Outcome(
            String id,
            String title,
            boolean passed,
            String category,
            int turns,
            int inputTokens,
            int outputTokens,
            long durationMs,
            int toolCalls,
            int filesChanged,
            List<String> tools,
            String detail
    ) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        Path root = Path.of("").toAbsolutePath();

        Path jar = Path.of(opt.getOrDefault("jar", root.resolve("build/libs/mewagent.jar").toString()));
        Path casesDir = Path.of(opt.getOrDefault("cases", root.resolve("eval/cases").toString()));
        Path outDir = Path.of(opt.getOrDefault("out", root.resolve("build/eval").toString()));
        boolean keepWork = Boolean.parseBoolean(opt.getOrDefault("keep", "false"));
        boolean inheritProxy = Boolean.parseBoolean(opt.getOrDefault("inherit-proxy", "false"));
        int limit = Integer.parseInt(opt.getOrDefault("limit", "0"));

        if (!Files.isRegularFile(jar)) {
            die("找不到 Agent jar：" + jar + "\n请先执行 ./gradlew shadowJar");
        }

        String config = opt.get("config");
        if (config == null) config = System.getenv("MEWAGENT_CONFIG");
        if (config == null) {
            Path local = root.resolve(".mewagent/config.yaml");
            if (Files.isRegularFile(local)) config = local.toString();
        }
        if (config == null || !Files.isRegularFile(Path.of(config))) {
            die("找不到配置文件，请用 --config=<path> 或设置 MEWAGENT_CONFIG");
        }

        List<Path> dirs = listCaseDirs(casesDir);
        if (dirs.isEmpty()) die("没有找到用例：" + casesDir);

        List<String> only = opt.containsKey("only")
                ? List.of(opt.get("only").split(","))
                : List.of();
        List<CaseSpec> specs = new ArrayList<>();
        for (Path dir : dirs) {
            CaseSpec spec = readSpec(dir);
            if (!only.isEmpty() && only.stream().noneMatch(k -> spec.id().contains(k.strip()))) continue;
            specs.add(spec);
        }
        if (limit > 0 && specs.size() > limit) specs = specs.subList(0, limit);
        if (specs.isEmpty()) die("过滤后没有可运行的用例");

        System.out.println();
        System.out.println("MewAgent Eval — " + specs.size() + " cases");

        // 评测默认裁掉 mcp_servers：MCP 是外部进程，初始化耗时会污染测量结果
        // （实测 npx 拉 context7 会让单个用例卡上数分钟）。需要连 MCP 时加 --keep-mcp。
        Path evalConfig = outDir.resolve("config.eval.yaml");
        Files.createDirectories(outDir);
        boolean keepMcp = Boolean.parseBoolean(opt.getOrDefault("keep-mcp", "false"));
        if (!keepMcp) {
            String stripped = stripMcpServers(readText(Path.of(config)));
            Files.writeString(evalConfig, stripped, StandardCharsets.UTF_8);
            config = evalConfig.toAbsolutePath().toString();
        }

        System.out.println("jar    : " + jar);
        System.out.println("config : " + config + (keepMcp ? "" : "  (mcp_servers 已禁用)"));
        System.out.println();

        Path workRoot = Path.of(opt.getOrDefault("work-dir",
                Path.of(System.getProperty("java.io.tmpdir"), "mewagent-eval").toString()));
        deleteTree(workRoot);
        Files.createDirectories(workRoot);

        List<Outcome> outcomes = new ArrayList<>();
        int index = 0;
        for (CaseSpec spec : specs) {
            index++;
            System.out.printf("[%d/%d] %-28s", index, specs.size(), spec.id());
            long t0 = System.currentTimeMillis();
            Outcome outcome;
            try {
                outcome = runCase(spec, jar, config, workRoot, keepWork, inheritProxy);
            } catch (Exception e) {
                outcome = new Outcome(spec.id(), spec.title(), false, "harness_error",
                        0, 0, 0, System.currentTimeMillis() - t0, 0, 0, List.of(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            outcomes.add(outcome);
            System.out.println(describe(outcome));
        }

        writeReports(outcomes, outDir);
        printSummary(outcomes, outDir);
    }

    // ── 用例执行 ──────────────────────────────────────────────────────

    private static Outcome runCase(CaseSpec spec, Path jar, String config, Path workRoot,
                                   boolean keepWork, boolean inheritProxy) throws Exception {
        Path work = workRoot.resolve(spec.id());
        deleteTree(work);
        copyTree(spec.dir().resolve("workspace"), work);

        // 刻意不做 git init：工作区一旦是 git 仓库，MewAgent 启动时的仓库探测会额外
        // 耗时数十秒（Windows + 嵌套仓库实测 30s+，正好撞上 LLM 流的 30s 超时），
        // 而且会让被评测的 Agent 用 git 改动结果。改用文件哈希做基线。
        Path baseline = writeBaseline(work, workRoot, spec.id());

        Path stdout = workRoot.resolve(spec.id() + ".stdout.log");
        Path stderr = workRoot.resolve(spec.id() + ".stderr.log");

        List<String> cmd = new ArrayList<>(List.of(
                javaBin(),
                "-Dstdout.encoding=UTF-8",
                "-Dfile.encoding=UTF-8",
                "-jar", jar.toString(),
                "-p", spec.prompt(),
                "--output-format", "stream-json",
                config));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile());
        pb.environment().put("MEWAGENT_CONFIG", config);
        // 评测放宽流式超时：模型首 token 偶发超过默认 30 秒，不该被记成 agent_error
        pb.environment().put("MEWAGENT_STREAM_TIMEOUT_SEC", "180");
        // 宿主 shell 里的 http_proxy 常指向沙箱/本地代理，容器或评测环境里并不存在，
        // 被 SDK 继承后所有 LLM 请求都会卡死。默认清掉，需要时用 --inherit-proxy。
        if (!inheritProxy) {
            for (String key : List.of("http_proxy", "https_proxy", "HTTP_PROXY",
                    "HTTPS_PROXY", "all_proxy", "ALL_PROXY")) {
                pb.environment().remove(key);
            }
        }
        pb.redirectOutput(stdout.toFile());
        pb.redirectError(stderr.toFile());

        long started = System.currentTimeMillis();
        Process process = pb.start();
        boolean finished = process.waitFor(spec.timeoutSec(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new Outcome(spec.id(), spec.title(), false, "timeout",
                    0, 0, 0, System.currentTimeMillis() - started, 0, 0, List.of(),
                    "超过 " + spec.timeoutSec() + "s 未完成");
        }

        Metrics metrics = parseEvents(readText(stdout));
        int filesChanged = countChangedFiles(work, baseline);

        // 判定：断言脚本退出码
        Path verifyLog = workRoot.resolve(spec.id() + ".verify.log");
        int verifyExit;
        try {
            // 断言脚本不进工作区执行（不进目录 = 不污染基线），cwd 仍是工作区。
            // shell 必须走 BashTool 的解析结果：Windows 上裸写 "bash" 会命中
            // C:\Windows\System32\bash.exe（WSL 启动器），被策略拦截后只会得到乱码。
            ProcessBuilder vb = new ProcessBuilder(
                    com.mewagent.tool.impl.BashTool.resolveShell(),
                    spec.dir().resolve("verify.sh").toAbsolutePath().toString())
                    .directory(work.toFile());
            // 断言脚本用 bash 执行，路径统一转成正斜杠（bash 不认反斜杠）
            vb.environment().put("CASE_DIR", spec.dir().toAbsolutePath().toString().replace('\\', '/'));
            vb.environment().put("WORK_DIR", work.toAbsolutePath().toString().replace('\\', '/'));
            vb.environment().put("BASELINE", baseline.toAbsolutePath().toString().replace('\\', '/'));
            vb.redirectErrorStream(true);
            vb.redirectOutput(verifyLog.toFile());
            Process vp = vb.start();
            if (!vp.waitFor(120, TimeUnit.SECONDS)) vp.destroyForcibly();
            verifyExit = vp.exitValue();
        } catch (Exception e) {
            verifyExit = -1;
            Files.writeString(verifyLog, "verify 执行失败: " + e, StandardCharsets.UTF_8);
        }

        String detail = verifyExit == 0
                ? "断言通过"
                : tail(Files.exists(verifyLog) ? readText(verifyLog) : "", 400);

        String category;
        boolean passed;
        if (metrics.error() != null) {
            passed = false;
            category = "agent_error";
            detail = metrics.error();
        } else if (metrics.toolCalls() == 0) {
            passed = false;
            category = "no_action";
            detail = "Agent 未调用任何工具";
        } else if (verifyExit != 0) {
            passed = false;
            category = "check_failed";
        } else {
            passed = true;
            category = "pass";
        }

        long duration = metrics.durationMs() > 0 ? metrics.durationMs() : System.currentTimeMillis() - started;

        if (!keepWork) {
            // 保留 work 目录便于排查，仅清理临时文件
            Files.deleteIfExists(work.resolve("verify.sh"));
        }

        return new Outcome(spec.id(), spec.title(), passed, category,
                metrics.turns(), metrics.inputTokens(), metrics.outputTokens(), duration,
                metrics.toolCalls(), filesChanged, metrics.tools(), detail);
    }

    record Metrics(int turns, int inputTokens, int outputTokens, long durationMs,
                   int toolCalls, List<String> tools, String error) {}

    /** 解析 stream-json 事件流。 */
    private static Metrics parseEvents(String raw) {
        int turns = 0, inTok = 0, outTok = 0, toolCalls = 0;
        long durationMs = 0;
        List<String> tools = new ArrayList<>();
        String error = null;

        for (String line : raw.split("\n")) {
            line = line.strip();
            if (!line.startsWith("{")) continue;
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (Exception e) {
                continue;
            }
            switch (node.path("type").asText()) {
                case "tool_use" -> {
                    toolCalls++;
                    tools.add(node.path("tool_name").asText());
                }
                case "usage" -> {
                    inTok = node.path("input_tokens").asInt(inTok);
                    outTok = node.path("output_tokens").asInt(outTok);
                }
                case "result" -> {
                    turns = node.path("num_turns").asInt(turns);
                    durationMs = node.path("duration_ms").asLong(durationMs);
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode()) {
                        inTok = usage.path("input_tokens").asInt(inTok);
                        outTok = usage.path("output_tokens").asInt(outTok);
                    }
                }
                case "error" -> error = node.path("message").asText("unknown error");
                default -> { }
            }
        }
        return new Metrics(turns, inTok, outTok, durationMs, toolCalls, tools, error);
    }

    // ── 用例读取 ──────────────────────────────────────────────────────

    private static List<Path> listCaseDirs(Path casesDir) throws IOException {
        if (!Files.isDirectory(casesDir)) return List.of();
        try (Stream<Path> s = Files.list(casesDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("case.json")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    private static CaseSpec readSpec(Path dir) throws IOException {
        JsonNode n = JSON.readTree(Files.readString(dir.resolve("case.json"), StandardCharsets.UTF_8));
        return new CaseSpec(
                n.path("id").asText(dir.getFileName().toString()),
                n.path("title").asText(""),
                n.path("prompt").asText(""),
                n.path("timeoutSec").asInt(240),
                n.path("maxTurns").asInt(20),
                dir);
    }

    // ── 报告 ──────────────────────────────────────────────────────────

    private static String describe(Outcome o) {
        String status = o.passed()
                ? String.format(Locale.ROOT, "PASS  %5.1fs  turns=%-2d tok=%d/%d tools=%d files=%d",
                        o.durationMs() / 1000.0, o.turns(), o.inputTokens(), o.outputTokens(),
                        o.toolCalls(), o.filesChanged())
                : String.format(Locale.ROOT, "FAIL(%s)  %5.1fs  turns=%d tools=%d files=%d",
                        o.category(), o.durationMs() / 1000.0, o.turns(), o.toolCalls(), o.filesChanged());
        return status;
    }

    private static void printSummary(List<Outcome> outcomes, Path outDir) {
        long passed = outcomes.stream().filter(Outcome::passed).count();
        int total = outcomes.size();
        int inTok = outcomes.stream().mapToInt(Outcome::inputTokens).sum();
        int outTok = outcomes.stream().mapToInt(Outcome::outputTokens).sum();
        double avgTurns = outcomes.stream().mapToInt(Outcome::turns).average().orElse(0);
        double avgSec = outcomes.stream().mapToLong(Outcome::durationMs).average().orElse(0) / 1000.0;

        System.out.println();
        System.out.println("────────────────────────────────────────────");
        System.out.printf(Locale.ROOT, "通过率      %d/%d  (%.1f%%)%n", passed, total, 100.0 * passed / total);
        System.out.printf(Locale.ROOT, "平均轮次    %.1f%n", avgTurns);
        System.out.printf(Locale.ROOT, "平均耗时    %.1fs%n", avgSec);
        System.out.printf(Locale.ROOT, "Token 合计  in=%d  out=%d%n", inTok, outTok);
        System.out.println("────────────────────────────────────────────");

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            byCategory.merge(o.category(), 1L, Long::sum);
        }
        System.out.println("失败分类    " + byCategory);
        System.out.println();
        System.out.println("报告        " + outDir.resolve("report.md").toAbsolutePath());
        System.out.println("            " + outDir.resolve("report.json").toAbsolutePath());
        System.out.println();
    }

    private static void writeReports(List<Outcome> outcomes, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        ObjectNode root = JSON.createObjectNode();
        root.put("generatedAt", Instant.now().toString());
        root.put("total", outcomes.size());
        root.put("passed", outcomes.stream().filter(Outcome::passed).count());
        root.put("inputTokens", outcomes.stream().mapToInt(Outcome::inputTokens).sum());
        root.put("outputTokens", outcomes.stream().mapToInt(Outcome::outputTokens).sum());

        ArrayNode arr = root.putArray("cases");
        for (Outcome o : outcomes) {
            ObjectNode n = arr.addObject();
            n.put("id", o.id());
            n.put("title", o.title());
            n.put("passed", o.passed());
            n.put("category", o.category());
            n.put("turns", o.turns());
            n.put("inputTokens", o.inputTokens());
            n.put("outputTokens", o.outputTokens());
            n.put("durationMs", o.durationMs());
            n.put("toolCalls", o.toolCalls());
            n.put("filesChanged", o.filesChanged());
            ArrayNode tools = n.putArray("tools");
            o.tools().forEach(tools::add);
            n.put("detail", o.detail());
        }
        Files.writeString(outDir.resolve("report.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);

        long passed = outcomes.stream().filter(Outcome::passed).count();
        StringBuilder md = new StringBuilder();
        md.append("# MewAgent Eval Report\n\n");
        md.append("生成时间：").append(Instant.now()).append("\n\n");
        md.append("| 指标 | 数值 |\n|---|---|\n");
        md.append("| 用例数 | ").append(outcomes.size()).append(" |\n");
        md.append("| 通过 | ").append(passed).append(" (")
                .append(String.format(Locale.ROOT, "%.1f%%", 100.0 * passed / outcomes.size())).append(") |\n");
        md.append("| 平均轮次 | ")
                .append(String.format(Locale.ROOT, "%.1f", outcomes.stream().mapToInt(Outcome::turns).average().orElse(0)))
                .append(" |\n");
        md.append("| 平均耗时 | ")
                .append(String.format(Locale.ROOT, "%.1fs",
                        outcomes.stream().mapToLong(Outcome::durationMs).average().orElse(0) / 1000.0))
                .append(" |\n");
        md.append("| Token | in=").append(outcomes.stream().mapToInt(Outcome::inputTokens).sum())
                .append(" out=").append(outcomes.stream().mapToInt(Outcome::outputTokens).sum()).append(" |\n\n");

        md.append("## 用例明细\n\n");
        md.append("| 用例 | 结果 | 分类 | 轮次 | 工具调用 | 改动文件 | 耗时 |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            md.append("| ").append(o.id()).append(" | ")
                    .append(o.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(o.category()).append(" | ")
                    .append(o.turns()).append(" | ")
                    .append(o.toolCalls()).append(" | ")
                    .append(o.filesChanged()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1fs", o.durationMs() / 1000.0)).append(" |\n");
        }

        List<Outcome> failed = outcomes.stream().filter(o -> !o.passed()).toList();
        if (!failed.isEmpty()) {
            md.append("\n## 失败详情\n\n");
            for (Outcome o : failed) {
                md.append("### ").append(o.id()).append(" — ").append(o.category()).append("\n\n");
                if (!o.title().isBlank()) md.append(o.title()).append("\n\n");
                md.append("```\n").append(o.detail()).append("\n```\n\n");
            }
        }
        Files.writeString(outDir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    /**
     * 去掉顶层 {@code mcp_servers:} 段。按缩进判断段落边界，不依赖 YAML 解析，
     * 避免把注释和格式细节改坏。
     */
    static String stripMcpServers(String yaml) {
        String[] lines = yaml.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (String line : lines) {
            if (!skipping && line.startsWith("mcp_servers:")) {
                skipping = true;
                continue;
            }
            if (skipping) {
                // 顶层键意味着 mcp_servers 段结束
                if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                    skipping = false;
                } else {
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 与基线对比，返回内容变化 / 新增 / 删除的文件数。 */
    private static int countChangedFiles(Path work, Path baseline) {
        try {
            Map<String, String> before = readBaseline(baseline);
            Map<String, String> after = hashTree(work);
            Set<String> all = new TreeMap<>(before).keySet();
            Set<String> union = new java.util.TreeSet<>(all);
            union.addAll(after.keySet());
            int changed = 0;
            for (String rel : union) {
                String a = before.get(rel);
                String b = after.get(rel);
                if (a == null || b == null || !a.equals(b)) changed++;
            }
            return changed;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 记录工作区基线：每行 {@code <sha256>  <相对路径>}，可直接喂给 sha256sum -c。 */
    private static Path writeBaseline(Path work, Path workRoot, String id) throws IOException {
        Map<String, String> hashes = hashTree(work);
        List<String> lines = new ArrayList<>();
        hashes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> lines.add(e.getValue() + "  " + e.getKey()));
        Path out = workRoot.resolve(id + ".baseline.txt");
        Files.write(out, lines, StandardCharsets.UTF_8);
        return out;
    }

    private static Map<String, String> readBaseline(Path baseline) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int idx = line.indexOf("  ");
            if (idx <= 0) continue;
            map.put(line.substring(idx + 2), line.substring(0, idx));
        }
        return map;
    }

    private static Map<String, String> hashTree(Path root) throws IOException {
        Map<String, String> map = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".verify-out/")) continue;
                map.put(rel, sha256(p));
            }
        }
        return map;
    }

    private static String sha256(Path p) throws IOException {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(Files.readAllBytes(p))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                // git 对象文件在 Windows 上是只读的，直接删会 AccessDenied
                if (Files.isRegularFile(p)) {
                    try {
                        Files.setAttribute(p, "dos:readonly", false);
                    } catch (Exception ignored) {
                        // 非 Windows 平台没有 dos 属性视图，忽略
                    }
                }
                Files.deleteIfExists(p);
            }
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(t.length() - max);
    }

    /** 容错解码：子进程日志里可能混入系统代码页的字节，不能让整个用例崩在这。 */
    private static String readText(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            int eq = a.indexOf('=');
            if (eq > 0) {
                map.put(a.substring(2, eq), a.substring(eq + 1));
            } else {
                map.put(a.substring(2), "true");
            }
        }
        return map;
    }

    private static void die(String msg) {
        System.err.println("[eval] " + msg);
        System.exit(2);
    }

    private EvalRunner() {}
}
