
package com.mewagent.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewagent.agent.Agent;
import com.mewagent.agent.AgentSessionFactory;
import com.mewagent.agent.AgentEvent;
import com.mewagent.command.Command;
import com.mewagent.command.CommandContext;
import com.mewagent.command.CommandRegistry;
import com.mewagent.compact.ContextCompactor;
import com.mewagent.config.HookConfig;
import com.mewagent.config.McpServerConfig;
import com.mewagent.config.ProviderConfig;
import com.mewagent.conversation.ConversationManager;
import com.mewagent.filehistory.FileHistory;
import com.mewagent.hook.HookEngine;
import com.mewagent.llm.LlmClient;
import com.mewagent.mcp.McpManager;
import com.mewagent.memory.MemoryManager;
import com.mewagent.permission.PermissionChecker;
import com.mewagent.permission.PermissionMode;
import com.mewagent.permission.PermissionResponse;
import com.mewagent.plan.PlanFile;
import com.mewagent.prompt.PromptBuilder;
import com.mewagent.session.SessionManager;
import com.mewagent.session.SessionTitleGenerator;
import com.mewagent.skill.SkillCatalog;
import com.mewagent.subagent.AgentTool;
import com.mewagent.subagent.SubAgentTaskManager;
import com.mewagent.task.TaskList;
import com.mewagent.task.TaskTools;
import com.mewagent.teams.TeamManager;
import com.mewagent.tool.ToolRegistry;
import com.mewagent.tool.impl.AskUserTool;
import com.mewagent.tool.impl.ToolSearchTool;
import com.mewagent.tui.dialog.AskUserDialog;
import com.mewagent.worktree.WorktreeManager;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remote Control 服务器，桥接 Agent 事件和 WebSocket 客户端。
 * 对标 Go 版 internal/remote/server.go 的完整功能集。
 */
public class RemoteServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 配置 ──────────────────────────────────────────────────────────
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpConfigs;
    private final List<HookConfig> hookConfigs;
    private final String addr;

    // ── WebSocket 连接池 ──────────────────────────────────────────────
    private final ReentrantLock connLock = new ReentrantLock();
    private final Set<WsContext> connections = ConcurrentHashMap.newKeySet();

    // ── Agent 核心组件 ────────────────────────────────────────────────
    private Agent agent;
    private ConversationManager conversation;
    private ToolRegistry registry;
    private LlmClient client;
    private String sessionId;
    private FileHistory fileHistory;
    private PermissionChecker permChecker;

    // ── 流式状态 ──────────────────────────────────────────────────────
    private volatile boolean streaming;
    private volatile Thread streamThread;
    private BlockingQueue<AgentEvent> agentQueue;

    // ── 权限和 ask_user 的待决响应 ────────────────────────────────────
    private final ReentrantLock pendingPermLock = new ReentrantLock();
    private final Map<String, CompletableFuture<PermissionResponse>> pendingPerms = new ConcurrentHashMap<>();

    private final ReentrantLock pendingAskLock = new ReentrantLock();
    private final Map<String, CompletableFuture<Map<String, String>>> pendingAsks = new ConcurrentHashMap<>();

    // ── 命令和功能模块 ────────────────────────────────────────────────
    private CommandRegistry cmdRegistry;
    private SkillCatalog skillCatalog;
    private MemoryManager memoryManager;
    private McpManager mcpManager;
    private TaskList taskList;
    private SubAgentTaskManager subAgentTaskManager;
    private TeamManager teamManager;
    private AskUserTool askUserTool;
    private HookEngine hookEngine;

    private String instructionsContent = "";
    private String memoryContent = "";
    private volatile String mcpInstructions = "";
    private final boolean enableCoordinatorMode;

    // ── 会话标题生成 ─────────────────────────────────────────────────
    /** 首轮结束后异步生成侧栏标题；失败则前端回退到首条消息。 */
    private SessionTitleGenerator titleGenerator;
    /** 已经尝试过生成标题的 session id，避免每轮都重复调用模型。 */
    private volatile String titleAttemptedFor = "";
    /** 本轮用户输入原文，用于生成标题（对话里的首条 user 消息可能被记忆注入污染）。 */
    private volatile String pendingUserPrompt = "";
    /** /retitle 单次最多补多少个历史会话，避免一次烧掉太多调用。 */
    private static final int RETITLE_LIMIT = 20;

    // ── REST 任务接口 ─────────────────────────────────────────────────
    /**
     * 串行保护：Agent 实例和 ConversationManager 都是单例，WebSocket 会话与
     * REST 任务不能同时驱动它们。所有进入 agent.run() 的路径都必须持有此锁。
     */
    private final Object agentLock = new Object();

    /** 最近任务记录。超过 MAX_TASK_HISTORY 时淘汰最旧的已完成任务。 */
    private final Map<String, RemoteTask> tasks = new ConcurrentHashMap<>();
    private static final int MAX_TASK_HISTORY = 100;

    /**
     * 单线程执行器：任务按提交顺序串行跑。用虚拟线程承载，排队期间
     * 不占用平台线程。
     */
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor(
            r -> Thread.ofVirtual().name("remote-task-worker").unstarted(r));

    /**
     * 可选鉴权令牌。设置 MEWAGENT_TASK_TOKEN 后，REST 接口要求
     * Authorization: Bearer &lt;token&gt;；未设置时保持开放（本地开发场景）。
     */
    private final String taskToken = System.getenv().getOrDefault("MEWAGENT_TASK_TOKEN", "");

    public RemoteServer(List<ProviderConfig> providers, List<McpServerConfig> mcpConfigs,
                        List<HookConfig> hookConfigs, String addr, boolean enableCoordinatorMode) {
        this.providers = providers;
        this.mcpConfigs = mcpConfigs;
        this.hookConfigs = hookConfigs;
        this.addr = addr;
        this.enableCoordinatorMode = enableCoordinatorMode;
    }

    /**
     * 启动 HTTP + WebSocket 服务器。
     * 初始化 Agent 后监听指定地址，阻塞直到服务器关闭。
     */
    public void run() throws Exception {
        // 初始化 Agent（复刻 TUI 的 initializeProvider 流程）
        initAgent();
        // 连接 MCP 服务器
        initMcpServers();

        // 解析监听地址（格式 ":18888" 或 "0.0.0.0:18888"）
        int port = parsePort(addr);

        Javalin app = Javalin.create()
                .get("/", ctx -> {
                    ctx.contentType("text/html; charset=utf-8");
                    ctx.result(WebContent.INDEX_HTML);
                })
                // ── REST 任务接口（供 CI / 告警系统调用）──────────────────
                .post("/task", this::handleCreateTask)
                .get("/task/{id}", this::handleGetTask)
                .get("/tasks", this::handleListTasks)
                .ws("/ws", ws -> {
                    ws.onConnect(ctx -> {
                        connections.add(ctx);
                        // 新连接推送 session 信息
                        broadcast(Map.of(
                                "type", "connected",
                                "data", Map.of(
                                        "session", sessionId,
                                        "cwd", System.getProperty("user.dir")
                                )
                        ));
                        // 推送命令列表
                        broadcast(Map.of(
                                "type", "commands",
                                "data", buildCommandList()
                        ));
                    });
                    ws.onClose(ctx -> connections.remove(ctx));
                    ws.onMessage(ctx -> handleWsMessage(ctx, ctx.message()));
                })
                .start("0.0.0.0", port);

        System.out.printf("%n  Remote UI: http://localhost:%d%n%n", port);

        // 阻塞主线程，让服务器持续运行
        Thread.currentThread().join();
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 初始化（镜像 MewAgentModel.initializeProvider）
    // ────────────────────────────────────────────────────────────────────

    private void initAgent() {
        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = providers.get(0);

        // 记忆管理：用户/项目指令 + 长期记忆
        memoryManager = new MemoryManager(workDir);
        instructionsContent = MemoryManager.loadInstructions(workDir);

        // 构建系统提示词
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // 会话标题生成器：独立 client，避免与主对话争用系统提示
        titleGenerator = new SessionTitleGenerator(providerCfg);

        // ── 统一装配：工具 / 权限 / 文件历史 / Hook / 技能 ──────────────
        var opts = new AgentSessionFactory.Options();
        opts.workDir = workDir;
        opts.provider = providerCfg;
        opts.systemPrompt = systemPrompt;
        opts.permissionMode = PermissionMode.DEFAULT;
        opts.hooks = hookConfigs;
        opts.coordinatorMode = enableCoordinatorMode;
        opts.instructions = instructionsContent;
        // 修复漂移：以前这里传的是空串，远程模式实际上没有记忆注入
        opts.memory = MemoryManager.renderMemorySection(workDir);
        opts.connectMcp = false;   // MCP 由 initMcpServers 单独处理
        opts.progressListener = progress -> {};

        var session = AgentSessionFactory.create(opts);

        agent = session.agent;
        registry = session.registry;
        client = session.client;
        conversation = session.conversation;
        permChecker = session.checker;
        fileHistory = session.fileHistory;
        askUserTool = session.askUserTool;
        subAgentTaskManager = session.subAgentTasks;
        teamManager = session.teamManager;
        sessionId = session.sessionId;
        skillCatalog = session.skillCatalog;
        hookEngine = session.hookEngine;

        // 命令注册
        cmdRegistry = new CommandRegistry();
        // 仅 remote 模式提供：为历史会话补生成标题
        cmdRegistry.register(new Command("retitle", "为缺少标题的历史会话生成标题（最多 20 个）",
                new String[0], Command.CommandType.LOCAL_UI, false), null);

    }

    // ────────────────────────────────────────────────────────────────────
    // MCP 服务器连接
    // ────────────────────────────────────────────────────────────────────

    private void initMcpServers() {
        if (mcpConfigs == null || mcpConfigs.isEmpty()) return;

        try {
            mcpManager = new McpManager(mcpConfigs);
            var result = mcpManager.connectAll();
            for (var t : result.tools()) registry.register(t);
            for (var e : result.errors()) System.err.println("MCP error: " + e);

            // 构建 MCP 指令（首次用户消息时注入到对话）
            if (!result.servers().isEmpty()) {
                var mcpParts = new ArrayList<String>();
                for (var s : result.servers()) {
                    var sb = new StringBuilder();
                    sb.append("## ").append(s.name()).append("\n");
                    if (s.instructions() != null && !s.instructions().isBlank()) {
                        sb.append(s.instructions()).append("\n");
                    }
                    var toolNames = registry.listTools().stream()
                            .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                            .map(com.mewagent.tool.Tool::name)
                            .toList();
                    if (!toolNames.isEmpty()) {
                        sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                    }
                    mcpParts.add(sb.toString());
                }
                mcpInstructions = "# MCP Server Instructions\n\n"
                        + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                        + String.join("\n\n", mcpParts);
            }
        } catch (Exception e) {
            System.err.println("MCP init failed: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 消息处理
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleWsMessage(WsContext ctx, String raw) {
        try {
            var msg = MAPPER.readValue(raw, Map.class);
            String type = (String) msg.get("type");
            Object data = msg.get("data");

            switch (type) {
                case "user_message" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");
                        // 用虚拟线程处理用户消息，避免阻塞 WS 读循环
                        Thread.startVirtualThread(() -> handleUserMessage(content));
                    }
                }
                case "permission_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        String response = (String) d.get("response");
                        handlePermissionResponse(id, response);
                    }
                }
                case "ask_user_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> answers = (Map<String, String>) d.get("answers");
                        handleAskUserResponse(id, answers);
                    }
                }
                case "cancel" -> {
                    // 中断当前流式响应
                    Thread t = streamThread;
                    if (t != null) t.interrupt();
                }
                case "ping" -> {
                    // 应用层保活：回复 pong
                    broadcast(Map.of("type", "pong"));
                }
                case "list_sessions" -> broadcastSessions(System.getProperty("user.dir"));
                case "resume_session" -> {
                    if (data instanceof Map<?, ?> d) {
                        String targetId = (String) d.get("id");
                        handleResume(targetId);
                    }
                }
                case "new_session" -> {
                    String wd = System.getProperty("user.dir");
                    // 新 session ID
                    sessionId = SessionManager.newId();
                    if (agent != null) agent.setSessionId(sessionId);
                    // 新对话
                    conversation = new ConversationManager();
                    // 新文件历史
                    fileHistory = new FileHistory(wd, sessionId);
                    // 通知所有连接
                    broadcast(Map.of("type", "clear"));
                    broadcast(Map.of("type", "connected", "data",
                            Map.of("session", sessionId, "cwd", wd)));
                    // 刷新会话列表
                    broadcastSessions(wd);
                }
                case "get_session_history" -> {
                    if (data instanceof Map<?, ?> d) {
                        String targetId = (String) d.get("id");
                        String workDir = System.getProperty("user.dir");
                        var messages = SessionManager.loadSession(workDir, targetId);
                        var history = messages.stream()
                                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                                .map(m -> Map.of("role", m.role(), "content", m.content()))
                                .toList();
                        broadcast(Map.of("type", "session_history", "data",
                                Map.of("id", targetId, "messages", history)));
                    }
                }
                case "delete_session" -> {
                    if (data instanceof Map<?, ?> d) {
                        String targetId = (String) d.get("id");
                        String wd = System.getProperty("user.dir");
                        if (SessionManager.deleteSession(wd, targetId)) {
                            // 删掉的正是当前会话：立刻切到新会话，避免后续消息写进已删文件
                            if (targetId != null && targetId.equals(sessionId)) {
                                sessionId = SessionManager.newId();
                                if (agent != null) agent.setSessionId(sessionId);
                                conversation = new ConversationManager();
                                fileHistory = new FileHistory(wd, sessionId);
                                broadcast(Map.of("type", "clear"));
                                broadcast(Map.of("type", "connected", "data",
                                        Map.of("session", sessionId, "cwd", wd)));
                            }
                            broadcast(Map.of("type", "session_deleted", "data", Map.of("id", targetId)));
                        } else {
                            broadcast(Map.of("type", "system", "data",
                                    Map.of("message", "删除失败，会话不存在: " + targetId)));
                        }
                        broadcastSessions(wd);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket message parse error: " + e.getMessage());
        }
    }

    /** 广播会话列表快照（list/new/delete 三处复用）。 */
    private void broadcastSessions(String workDir) {
        var sessions = SessionManager.listSessions(workDir);
        broadcast(Map.of("type", "sessions", "data",
                sessions.stream().map(s -> Map.of(
                        "id", s.id(),
                        "firstMessage", s.firstMessage().length() > 80 ? s.firstMessage().substring(0, 80) + "..." : s.firstMessage(),
                        "title", s.title() == null ? "" : s.title(),
                        "messageCount", s.messageCount(),
                        "modTime", s.modTime().toString(),
                        "relativeTime", SessionManager.formatRelativeTime(s.modTime()),
                        "fileSize", SessionManager.formatFileSize(s.fileSize())
                )).toList()));
    }

    /**
     * 首轮结束后异步生成会话标题。
     *
     * <p>每个 session 只尝试一次；已有标题（老会话或被恢复的会话）直接跳过。
     * 生成成功才写盘并刷新侧栏，失败静默放弃——前端会回退到首条消息。
     */
    private void maybeGenerateTitle() {
        final String sid = sessionId;
        final String workDir = System.getProperty("user.dir");
        if (sid == null || sid.isBlank() || titleGenerator == null) {
            return;
        }
        if (sid.equals(titleAttemptedFor)) {
            return;
        }
        titleAttemptedFor = sid;
        if (SessionManager.readTitle(workDir, sid) != null) {
            return; // 已有标题，不重复生成
        }
        final String userText = pendingUserPrompt;
        if (userText == null || userText.isBlank()) {
            return;
        }
        final String assistantText = firstAssistantText();
        Thread.startVirtualThread(() -> {
            try {
                String title = titleGenerator.generate(userText, assistantText);
                if (title != null && !title.isBlank()) {
                    SessionManager.saveTitle(workDir, sid, title);
                    broadcastSessions(workDir);
                }
            } catch (Exception ignored) {
                // 标题生成失败不影响主流程
            }
        });
    }

    /** 会话中第一段助手回复，作为生成标题的上下文。 */
    private String firstAssistantText() {
        if (conversation == null) {
            return "";
        }
        for (var message : conversation.getMessages()) {
            if ("assistant".equals(message.getRole())) {
                String content = message.getContent();
                if (content != null && !content.isBlank()) {
                    return content;
                }
            }
        }
        return "";
    }

    /**
     * `/retitle`：为缺少标题的历史会话补生成标题。
     *
     * <p>串行执行并限量（{@value #RETITLE_LIMIT}），每完成一个就推一条进度；
     * 全部结束后广播一次会话列表，侧栏整体刷新。整个过程跑在虚拟线程里，
     * 不阻塞 WebSocket 读循环。
     */
    private void handleRetitle() {
        final String workDir = System.getProperty("user.dir");
        if (titleGenerator == null) {
            broadcast(Map.of("type", "system", "data", Map.of("message", "标题生成器未初始化")));
            broadcast(Map.of("type", "command_done"));
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                var missing = SessionManager.listSessions(workDir).stream()
                        .filter(s -> s.title() == null || s.title().isBlank())
                        .limit(RETITLE_LIMIT)
                        .toList();
                if (missing.isEmpty()) {
                    broadcast(Map.of("type", "system", "data", Map.of("message", "所有会话都已有标题")));
                    broadcast(Map.of("type", "command_done"));
                    return;
                }
                broadcast(Map.of("type", "system", "data", Map.of("message",
                        "开始为 %d 个历史会话生成标题…".formatted(missing.size()))));

                int ok = 0;
                int failed = 0;
                for (int i = 0; i < missing.size(); i++) {
                    var session = missing.get(i);
                    String title = titleGenerator.generate(
                            session.firstMessage(), lastAssistantText(workDir, session.id()));
                    if (title == null || title.isBlank()) {
                        failed++;
                        continue;
                    }
                    SessionManager.saveTitle(workDir, session.id(), title);
                    ok++;
                    broadcast(Map.of("type", "system", "data", Map.of("message",
                            "  %d/%d %s → %s".formatted(i + 1, missing.size(), session.id(), title))));
                }

                broadcastSessions(workDir);
                broadcast(Map.of("type", "system", "data", Map.of("message",
                        "标题生成完成：成功 %d，失败 %d".formatted(ok, failed))));
                broadcast(Map.of("type", "command_done"));
            } catch (Exception e) {
                broadcast(Map.of("type", "system", "data",
                        Map.of("message", "标题生成失败：" + e.getMessage())));
                broadcast(Map.of("type", "command_done"));
            }
        });
    }

    /** 会话中最后一段助手文本，用于给历史会话补标题。 */
    private String lastAssistantText(String workDir, String sessionId) {
        String last = "";
        for (var message : SessionManager.loadSession(workDir, sessionId)) {
            if ("assistant".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                last = message.content();
            }
        }
        return last;
    }

    // ────────────────────────────────────────────────────────────────────
    // 用户消息处理
    // ────────────────────────────────────────────────────────────────────

    private void handleUserMessage(String content) {
        if (streaming) return;

        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;

        // 斜杠命令处理
        if (content.startsWith("/")) {
            handleSlashCommand(content);
            return;
        }

        // Agent 与 Conversation 都是单例，必须与 REST 任务串行
        synchronized (agentLock) {
            if (streaming) return;

            streaming = true;
            streamThread = Thread.currentThread();
            String workDir = System.getProperty("user.dir");
            pendingUserPrompt = content;
            SessionManager.saveMessage(workDir, sessionId, "user", content);
            conversation.addUserMessage(content);

            // 首次消息时注入 MCP 指令
            if (!mcpInstructions.isEmpty()) {
                conversation.addSystemReminder(mcpInstructions);
                mcpInstructions = "";
            }

            // 启动 Agent 并消费事件
            agentQueue = agent.run(conversation);
            if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

            try {
                consumeAgentEvents();
            } finally {
                streaming = false;
                streamThread = null;
                agentQueue = null;
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // REST 任务接口
    // ────────────────────────────────────────────────────────────────────

    /** 单次事件等待上限：Agent 若长时间无任何事件，判定任务卡死。 */
    private static final long TASK_IDLE_TIMEOUT_SEC = 600;

    /** wait=true 时同步等待的上限，超过后返回 202 交给调用方轮询。 */
    private static final long TASK_WAIT_TIMEOUT_MS = 300_000;

    /**
     * POST /task
     * body: {"prompt": "...", "wait": false}
     *
     * 立即返回 202 + taskId，任务在单线程队列里串行执行；wait=true 时最多
     * 同步等待 5 分钟，拿到结果直接返回 200。
     */
    private void handleCreateTask(Context ctx) {
        if (!authorized(ctx)) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return;
        }

        String prompt;
        boolean waitForResult;
        try {
            String raw = ctx.body();
            var body = MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            prompt = body.path("prompt").asText("");
            waitForResult = body.path("wait").asBoolean(false);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON body: " + e.getMessage()));
            return;
        }

        if (prompt == null || prompt.isBlank()) {
            ctx.status(400).json(Map.of("error", "prompt is required"));
            return;
        }

        evictOldTasks();

        String id = "task_" + System.nanoTime();
        var task = new RemoteTask(id, prompt.strip());
        tasks.put(id, task);
        taskExecutor.submit(() -> runTask(task));

        if (!waitForResult) {
            ctx.status(202).json(Map.of("taskId", id, "status", task.status,
                    "poll", "/task/" + id));
            return;
        }

        long deadline = System.currentTimeMillis() + TASK_WAIT_TIMEOUT_MS;
        while (!task.isFinished() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ctx.status(task.isFinished() ? 200 : 202).json(task.toJson());
    }

    /** GET /task/{id} */
    private void handleGetTask(Context ctx) {
        if (!authorized(ctx)) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return;
        }
        var task = tasks.get(ctx.pathParam("id"));
        if (task == null) {
            ctx.status(404).json(Map.of("error", "task not found"));
            return;
        }
        ctx.json(task.toJson());
    }

    /** GET /tasks —— 最近 50 条任务，按创建时间倒序。 */
    private void handleListTasks(Context ctx) {
        if (!authorized(ctx)) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return;
        }
        var list = tasks.values().stream()
                .sorted(java.util.Comparator.comparingLong((RemoteTask t) -> t.createdAt).reversed())
                .limit(50)
                .map(RemoteTask::toJson)
                .toList();
        ctx.json(Map.of("count", list.size(), "tasks", list));
    }

    /**
     * 执行一次无人值守任务：把 prompt 输入 Agent，收集流式文本直到循环结束。
     *
     * 权限处理：REST 调用没有界面可弹窗，权限请求一律按 ALLOW 放行（单次，
     * 不写入 allow-always 规则）。因此该接口只应在受信任环境中暴露——生产
     * 部署请设置 MEWAGENT_TASK_TOKEN，并配合沙箱或容器隔离。
     */
    private void runTask(RemoteTask task) {
        synchronized (agentLock) {
            if (streaming) {
                task.status = RemoteTask.FAILED;
                task.error = "agent is busy with another session; retry later";
                task.finishedAt = System.currentTimeMillis();
                return;
            }

            streaming = true;
            task.status = RemoteTask.RUNNING;
            task.startedAt = System.currentTimeMillis();
            var text = new StringBuilder();

            try {
                String workDir = System.getProperty("user.dir");
                SessionManager.saveMessage(workDir, sessionId, "user", task.prompt);
                conversation.addUserMessage(task.prompt);

                if (!mcpInstructions.isEmpty()) {
                    conversation.addSystemReminder(mcpInstructions);
                    mcpInstructions = "";
                }

                agentQueue = agent.run(conversation);
                if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

                outer:
                while (true) {
                    AgentEvent event = agentQueue.poll(TASK_IDLE_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (event == null) {
                        task.error = "timed out waiting for agent events";
                        break;
                    }
                    switch (event) {
                        case AgentEvent.StreamText e -> text.append(e.text());
                        case AgentEvent.UsageEvent e -> {
                            task.inputTokens = e.inputTokens();
                            task.outputTokens = e.outputTokens();
                        }
                        case AgentEvent.TurnComplete e -> task.turns = e.turn();
                        case AgentEvent.ErrorEvent e -> task.error = e.message();
                        case AgentEvent.LoopComplete e -> {
                            if (e.totalTurns() > 0) task.turns = e.totalTurns();
                            task.result = text.toString().strip();
                            break outer;
                        }
                        case AgentEvent.PermissionRequestEvent e ->
                                e.future().complete(PermissionResponse.ALLOW);
                        case AgentEvent.AskUserRequestEvent e ->
                                e.future().complete(Map.of());
                        default -> {
                            // ThinkingText / ToolUse / ToolResult / Compact / Retry
                            // 对 REST 调用方没有价值，忽略
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.error = "interrupted";
            } catch (Exception e) {
                task.error = "task error: " + e.getMessage();
            } finally {
                if (task.error == null) {
                    task.status = RemoteTask.COMPLETED;
                    if (task.result != null && !task.result.isEmpty()) {
                        try {
                            SessionManager.saveMessage(System.getProperty("user.dir"),
                                    sessionId, "assistant", task.result);
                        } catch (Exception ignored) {
                            // 落盘失败不应让已成功的任务变成失败
                        }
                    }
                } else {
                    task.status = RemoteTask.FAILED;
                }
                task.finishedAt = System.currentTimeMillis();
                streaming = false;
                streamThread = null;
                agentQueue = null;
            }
        }
    }

    /** 校验 Bearer token；未配置 MEWAGENT_TASK_TOKEN 时放行。 */
    private boolean authorized(Context ctx) {
        if (taskToken.isBlank()) return true;
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] provided = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = taskToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    /** 任务表满额时淘汰最旧的已完成任务，保留运行中的记录。 */
    private void evictOldTasks() {
        if (tasks.size() < MAX_TASK_HISTORY) return;
        int toRemove = tasks.size() - MAX_TASK_HISTORY + 1;
        tasks.values().stream()
                .filter(RemoteTask::isFinished)
                .sorted(java.util.Comparator.comparingLong((RemoteTask t) -> t.createdAt))
                .limit(toRemove)
                .map(t -> t.id)
                .toList()
                .forEach(tasks::remove);
    }

    // ────────────────────────────────────────────────────────────────────
    // 斜杠命令处理
    // ────────────────────────────────────────────────────────────────────

    private void handleSlashCommand(String input) {
        try {
            // 解析命令名和参数
            String trimmed = input.substring(1).strip();
            String name, args;
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx < 0) {
                name = trimmed;
                args = "";
            } else {
                name = trimmed.substring(0, spaceIdx);
                args = trimmed.substring(spaceIdx + 1).strip();
            }

            if (name.isEmpty()) return;

            var cmd = cmdRegistry.find(name);
            if (cmd.isEmpty()) {
                broadcast(Map.of("type", "error", "data",
                        Map.of("message", "Unknown command: /" + name + " -- type /help to see available commands")));
                broadcast(Map.of("type", "command_done"));
                return;
            }

            Command c = cmd.get();
            var ctx = buildCommandContext(args);

            switch (c.type()) {
                case LOCAL -> {
                    String result = cmdRegistry.execute(name, ctx);
                    if (result != null && !result.isEmpty()) {
                        broadcast(Map.of("type", "system", "data", Map.of("message", result)));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case LOCAL_UI -> {
                    switch (name) {
                        case "clear" -> {
                            conversation = new ConversationManager();
                            broadcast(Map.of("type", "clear"));
                        }
                        case "compact" -> {
                            handleCompact();
                            return; // compact 自己管 command_done
                        }
                        case "plan" -> handlePlan(args);
                        case "resume" -> {
                            handleResume(args);
                            return; // resume 自己管 command_done
                        }
                        case "rewind" -> broadcast(Map.of("type", "system", "data",
                                Map.of("message", "Rewind is not yet supported in remote mode.")));
                        case "retitle" -> {
                            handleRetitle();
                            return; // retitle 自己管 command_done
                        }
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case PROMPT -> {
                    String prompt = cmdRegistry.execute(name, ctx);
                    if (prompt == null || prompt.isBlank()) return;

                    String displayText = "/" + name;
                    if (!args.isEmpty()) displayText += " " + args;

                    // PROMPT 命令生成 prompt 注入给 Agent
                    streaming = true;
                    streamThread = Thread.currentThread();
                    String workDir = System.getProperty("user.dir");
                    SessionManager.saveMessage(workDir, sessionId, "user", displayText);
                    conversation.addUserMessage(prompt);

                    if (!mcpInstructions.isEmpty()) {
                        conversation.addSystemReminder(mcpInstructions);
                        mcpInstructions = "";
                    }

                    agentQueue = agent.run(conversation);
                    if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

                    try {
                        consumeAgentEvents();
                    } finally {
                        streaming = false;
                        streamThread = null;
                        agentQueue = null;
                    }
                }
            }
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Command error: " + e.getMessage())));
        }
    }

    private CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = providers.get(0).getModel();
        return new CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{0, 0},
                () -> memoryManager != null ? memoryManager.getMemories() : List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : List.of(),
                () -> 0,
                () -> mcpManager != null ? "MCP connected" : "",
                () -> "不可用",
                null
        );
    }

    // ────────────────────────────────────────────────────────────────────
    // 特殊命令处理
    // ────────────────────────────────────────────────────────────────────

    /** /compact 命令：强制压缩对话上下文 */
    private void handleCompact() {
        if (client == null || conversation == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact requires an active provider.")));
            broadcast(Map.of("type", "command_done"));
            return;
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", "Compacting conversation...")));
        try {
            String workDir = System.getProperty("user.dir");
            int contextWindow = providers.get(0).resolvedContextWindow();
            var schemas = registry.getAllSchemas(providers.get(0).getProtocol());
            String msg = ContextCompactor.forceCompact(
                    conversation, client, contextWindow, workDir, sessionId,
                    agent.getRecoveryState(), schemas);
            broadcast(Map.of("type", "system", "data", Map.of("message", "Compacted: " + msg)));
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact failed: " + e.getMessage())));
        }
        broadcast(Map.of("type", "command_done"));
    }

    /** /plan 命令：进入计划模式 */
    private void handlePlan(String args) {
        if (permChecker == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Agent not initialized.")));
            return;
        }
        String workDir = System.getProperty("user.dir");
        permChecker.setMode(PermissionMode.PLAN);
        String planPath = PlanFile.getOrCreatePlanPath(workDir);
        broadcast(Map.of("type", "system", "data",
                Map.of("message", "Entered Plan mode. Plan file: " + planPath
                        + "\nExplore the codebase and design your approach.")));

        // 带参数直接发给 Agent
        if (args != null && !args.isEmpty()) {
            streaming = true;
            streamThread = Thread.currentThread();
            SessionManager.saveMessage(workDir, sessionId, "user", "/plan " + args);
            conversation.addUserMessage(args);

            agentQueue = agent.run(conversation);
            if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

            try {
                consumeAgentEvents();
            } finally {
                streaming = false;
                streamThread = null;
                agentQueue = null;
            }
        }
    }

    /** /resume 命令：恢复历史会话 */
    private void handleResume(String args) {
        String workDir = System.getProperty("user.dir");
        var sessions = SessionManager.listSessions(workDir);

        if (args == null || args.isEmpty()) {
            // 列出可选会话
            if (sessions.isEmpty()) {
                broadcast(Map.of("type", "system", "data", Map.of("message", "No previous sessions found.")));
                broadcast(Map.of("type", "command_done"));
                return;
            }
            var sb = new StringBuilder("Available sessions (%d):\n\n".formatted(sessions.size()));
            int limit = Math.min(sessions.size(), 20);
            for (int i = 0; i < limit; i++) {
                var sess = sessions.get(i);
                String first = sess.firstMessage();
                if (first.length() > 60) first = first.substring(0, 60) + "...";
                sb.append("  %d. [%s] %s (%d msgs)\n".formatted(i + 1, sess.id(), first, sess.messageCount()));
            }
            if (sessions.size() > 20) {
                sb.append("  ... and %d more\n".formatted(sessions.size() - 20));
            }
            sb.append("\nUsage: /resume <number> or /resume <session-id>");
            broadcast(Map.of("type", "system", "data", Map.of("message", sb.toString())));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 恢复指定会话
        String targetId = args.strip();
        try {
            int idx = Integer.parseInt(targetId);
            if (idx >= 1 && idx <= sessions.size()) {
                targetId = sessions.get(idx - 1).id();
            }
        } catch (NumberFormatException ignored) {}

        var messages = SessionManager.loadSession(workDir, targetId);
        if (messages.isEmpty()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Session '%s' not found or empty.".formatted(targetId))));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 重建对话
        conversation = SessionManager.rebuildConversation(messages);
        sessionId = targetId;
        if (agent != null) agent.setSessionId(sessionId);

        // 清除旧 UI 并重放消息
        broadcast(Map.of("type", "clear"));
        var scan = SessionManager.findLastCompactBoundary(messages);
        List<SessionManager.SessionMessage> replay;
        if (scan.found()) {
            replay = new ArrayList<>();
            replay.add(new SessionManager.SessionMessage("user", scan.boundary().summary(), 0));
            for (var k : scan.boundary().keep()) {
                replay.add(new SessionManager.SessionMessage(k.role(), k.content(), 0));
            }
            replay.addAll(scan.after());
        } else {
            replay = messages;
        }

        for (var msg : replay) {
            switch (msg.role()) {
                case "user" -> broadcast(Map.of("type", "replay_user", "data",
                        Map.of("content", msg.content())));
                case "assistant" -> broadcast(Map.of("type", "replay_assistant", "data",
                        Map.of("content", msg.content())));
            }
        }

        String restored;
        if (scan.found()) {
            restored = "Session %s restored from compacted state (summary + %d kept + %d newer)."
                    .formatted(targetId, scan.boundary().keep().size(), scan.after().size());
        } else {
            restored = "Session %s restored (%d messages).".formatted(targetId, replay.size());
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", restored)));
        broadcast(Map.of("type", "command_done"));
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 事件消费
    // ────────────────────────────────────────────────────────────────────

    /** 从 Agent 事件队列中消费所有事件，推送到 WebSocket 客户端 */
    private void consumeAgentEvents() {
        var streamBuf = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (true) {
            AgentEvent event;
            try {
                event = agentQueue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                broadcast(Map.of("type", "error", "data", Map.of("message", "Stream timeout")));
                break;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    streamBuf.append(e.text());
                    broadcast(Map.of("type", "stream_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingText e -> {
                    broadcast(Map.of("type", "thinking_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingComplete e -> {
                    // 前端自行处理 thinking 完成状态
                }
                case AgentEvent.ToolUseEvent e -> {
                    broadcast(Map.of("type", "tool_use", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "args", e.args() != null ? e.args() : Map.of()
                    )));
                }
                case AgentEvent.ToolResultEvent e -> {
                    // 工具结果前先结束当前流式文本
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "tool_result", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "output", e.output() != null ? e.output() : "",
                            "isError", e.isError(),
                            "elapsed", e.elapsed()
                    )));
                }
                case AgentEvent.PermissionRequestEvent e -> {
                    // 生成唯一 ID，通过 CompletableFuture 桥接前端响应
                    String id = "perm_" + System.nanoTime();
                    pendingPerms.put(id, e.future());
                    broadcast(Map.of("type", "permission_request", "data", Map.of(
                            "id", id,
                            "toolName", e.toolName(),
                            "description", e.description()
                    )));
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    String id = "ask_" + System.nanoTime();
                    pendingAsks.put(id, e.future());
                    // 将 Question 转换为前端可解析的 JSON 结构
                    var questions = e.questions().stream().map(q -> Map.of(
                            "question", q.text() != null ? q.text() : "",
                            "options", q.options() != null
                                    ? q.options().stream().map(o -> Map.of(
                                    "label", o.label() != null ? o.label() : "",
                                    "description", o.description() != null ? o.description() : ""
                            )).toList()
                                    : List.of()
                    )).toList();
                    broadcast(Map.of("type", "ask_user", "data", Map.of(
                            "id", id,
                            "questions", questions
                    )));
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "turn_complete", "data", Map.of("turn", e.turn())));
                }
                case AgentEvent.LoopComplete e -> {
                    // 最后一段流式文本持久化到 session
                    if (!streamBuf.isEmpty()) {
                        String workDir = System.getProperty("user.dir");
                        SessionManager.saveMessage(workDir, sessionId, "assistant", streamBuf.toString());
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    broadcast(Map.of("type", "loop_complete", "data", Map.of(
                            "totalTurns", e.totalTurns(),
                            "elapsed", elapsed
                    )));
                    maybeGenerateTitle();
                    return; // Agent 循环结束
                }
                case AgentEvent.UsageEvent e -> {
                    broadcast(Map.of("type", "usage", "data", Map.of(
                            "inputTokens", e.inputTokens(),
                            "outputTokens", e.outputTokens()
                    )));
                }
                case AgentEvent.ErrorEvent e -> {
                    broadcast(Map.of("type", "error", "data", Map.of("message", e.message())));
                }
                case AgentEvent.CompactEvent e -> {
                    broadcast(Map.of("type", "compact", "data", Map.of("message", e.message())));
                }
                case AgentEvent.RetryEvent e -> {
                    broadcast(Map.of("type", "retry", "data", Map.of(
                            "reason", e.reason(),
                            "waitMs", e.waitMs()
                    )));
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 权限和 AskUser 响应处理
    // ────────────────────────────────────────────────────────────────────

    /** 处理前端权限按钮的回复 */
    private void handlePermissionResponse(String id, String response) {
        var future = pendingPerms.remove(id);
        if (future == null) return;

        PermissionResponse resp = switch (response) {
            case "allow" -> PermissionResponse.ALLOW;
            case "allowAlways" -> PermissionResponse.ALLOW_ALWAYS;
            default -> PermissionResponse.DENY;
        };
        future.complete(resp);
    }

    /** 处理前端 AskUser 对话框的回复 */
    private void handleAskUserResponse(String id, Map<String, String> answers) {
        var future = pendingAsks.remove(id);
        if (future == null) return;
        future.complete(answers != null ? answers : Map.of());
    }

    // ────────────────────────────────────────────────────────────────────
    // 命令列表
    // ────────────────────────────────────────────────────────────────────

    /** 构建命令列表供前端斜杠菜单使用 */
    private List<Map<String, String>> buildCommandList() {
        var list = new ArrayList<Map<String, String>>();
        for (var cmd : cmdRegistry.listVisible()) {
            list.add(Map.of(
                    "name", cmd.name(),
                    "description", cmd.description()
            ));
        }
        return list;
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 广播
    // ────────────────────────────────────────────────────────────────────

    /** 向所有已连接的 WebSocket 客户端广播 JSON 消息 */
    private void broadcast(Map<String, Object> msg) {
        if (connections.isEmpty()) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            for (var ctx : connections) {
                try {
                    ctx.send(json);
                } catch (Exception e) {
                    System.err.println("[ws] send error: " + e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            System.err.println("[ws] JSON serialize error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 工具方法
    // ────────────────────────────────────────────────────────────────────

    /** 从地址字符串解析端口号（支持 ":18888" 和 "0.0.0.0:18888" 格式） */
    private static int parsePort(String addr) {
        if (addr == null || addr.isEmpty()) return 18888;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx >= 0) {
            try {
                return Integer.parseInt(addr.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 18888;
            }
        }
        try {
            return Integer.parseInt(addr);
        } catch (NumberFormatException e) {
            return 18888;
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 MewAgentModel） ──────────────
    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
