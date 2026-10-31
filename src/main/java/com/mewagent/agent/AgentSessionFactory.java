package com.mewagent.agent;

import com.mewagent.config.HookConfig;
import com.mewagent.config.McpServerConfig;
import com.mewagent.config.ProviderConfig;
import com.mewagent.conversation.ConversationManager;
import com.mewagent.filehistory.FileHistory;
import com.mewagent.hook.HookEngine;
import com.mewagent.llm.LlmClient;
import com.mewagent.mcp.McpManager;
import com.mewagent.permission.PermissionChecker;
import com.mewagent.permission.PermissionMode;
import com.mewagent.plan.PlanFile;
import com.mewagent.session.SessionManager;
import com.mewagent.skill.SkillCatalog;
import com.mewagent.subagent.AgentTool;
import com.mewagent.subagent.SubAgentProgress;
import com.mewagent.subagent.SubAgentTaskManager;
import com.mewagent.task.TaskList;
import com.mewagent.task.TaskTools;
import com.mewagent.teams.TeamManager;
import com.mewagent.teams.TeamTools;
import com.mewagent.teams.TeammateRunner;
import com.mewagent.tool.FileStateCache;
import com.mewagent.tool.Tool;
import com.mewagent.tool.ToolRegistry;
import com.mewagent.tool.impl.AskUserTool;
import com.mewagent.tool.impl.BashTool;
import com.mewagent.tool.impl.EditFileTool;
import com.mewagent.tool.impl.EnterWorktreeTool;
import com.mewagent.tool.impl.ExitPlanModeTool;
import com.mewagent.tool.impl.ExitWorktreeTool;
import com.mewagent.tool.impl.ReadFileTool;
import com.mewagent.tool.impl.ToolSearchTool;
import com.mewagent.tool.impl.WriteFileTool;
import com.mewagent.worktree.WorktreeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 唯一的 Agent 装配入口。
 *
 * <p>把"注册表 + 内置工具 + 子 Agent/任务/团队工具 + 文件历史 + 权限 + Hook + 技能 + MCP"
 * 集中到一处，三个入口（Print / Remote / TUI）只传差异项。
 *
 * <p>要加一个新工具，只改这个类；三个模式同时生效，不会再出现"某个模式里工具悄悄缺失"。
 */
public final class AgentSessionFactory {

    private AgentSessionFactory() {
    }

    /** 各入口之间的差异项。未设置的字段使用默认值。 */
    public static final class Options {
        public String workDir = System.getProperty("user.dir");
        public ProviderConfig provider;
        public String systemPrompt = "";
        public PermissionMode permissionMode = PermissionMode.DEFAULT;
        public List<HookConfig> hooks = List.of();
        public List<McpServerConfig> mcpServers = List.of();
        public boolean coordinatorMode;
        public String instructions = "";
        public String memory = "";
        /** true 时使用三级技能目录（内置 < 用户级 < 项目级）；false 只读项目级。 */
        public boolean threeTierSkills;
        /** 是否在这里同步连接 MCP（Remote 由 initMcpServers 异步处理，传 false）。 */
        public boolean connectMcp = true;
        /** 子 Agent 进度回调；null 表示不需要。 */
        public Consumer<SubAgentProgress> progressListener;
        /** 指定会话 id；null 表示新建。 */
        public String sessionId;
    }

    public static AgentSession create(Options opts) {
        if (opts.provider == null) {
            throw new IllegalArgumentException("AgentSessionFactory: provider is required");
        }
        String workDir = opts.workDir != null ? opts.workDir : System.getProperty("user.dir");
        ProviderConfig provider = opts.provider;
        String protocol = provider.getProtocol();
        String sessionId = opts.sessionId != null ? opts.sessionId : SessionManager.newId();

        LlmClient client = LlmClient.create(provider, opts.systemPrompt);
        ToolRegistry registry = ToolRegistry.createDefault();

        registry.register(new ToolSearchTool(registry, protocol));

        PermissionChecker checker = new PermissionChecker(opts.permissionMode, Path.of(workDir));

        var exitPlanTool = new ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> checker.getMode() == PermissionMode.PLAN);
        exitPlanTool.setPlanExists(PlanFile::planExists);
        registry.register(exitPlanTool);

        AskUserTool askUserTool = new AskUserTool();
        registry.register(askUserTool);

        var agentTool = new AgentTool(client, registry, protocol, provider);
        SubAgentTaskManager subAgentTasks = new SubAgentTaskManager();
        agentTool.setTaskManager(subAgentTasks);
        if (opts.progressListener != null) {
            agentTool.setProgressListener(opts.progressListener);
        }
        registry.register(agentTool);

        WorktreeManager worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentTool.setWorktreeManager(worktreeManager);
        registry.register(new EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new ExitWorktreeTool(worktreeManager));

        TaskList taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        TeamManager teamManager = new TeamManager();
        agentTool.setTeamManager(teamManager);
        registry.register(new TeamTools.TeamCreateTool(teamManager));
        registry.register(new TeamTools.TeamDeleteTool(teamManager));
        registry.register(new TeamTools.SendMessageTool(teamManager, "lead"));

        FileHistory fileHistory = new FileHistory(workDir, sessionId);
        wireFileTools(registry, fileHistory);

        HookEngine hookEngine = new HookEngine();
        List<HookEngine.Hook> hooks = toHooks(opts.hooks);
        if (!hooks.isEmpty()) {
            hookEngine.loadHooks(hooks);
        }

        SkillCatalog skillCatalog = opts.threeTierSkills
                ? SkillCatalog.loadCatalog(workDir)
                : loadProjectSkills(workDir);

        McpManager mcpManager = null;
        String mcpInstructions = "";
        if (opts.connectMcp && opts.mcpServers != null && !opts.mcpServers.isEmpty()) {
            try {
                mcpManager = new McpManager(opts.mcpServers);
                var result = mcpManager.connectAll();
                for (Tool tool : result.tools()) {
                    registry.register(tool);
                }
                for (String error : result.errors()) {
                    System.err.println("MCP error: " + error);
                }
                mcpInstructions = buildMcpInstructions(result, registry);
            } catch (Exception e) {
                System.err.println("MCP init failed: " + e.getMessage());
            }
        }

        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(client, registry, protocol, provider);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(opts.instructions);
        agent.setMemoryContent(opts.memory);
        agent.setChecker(checker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);
        agent.setHookEngine(hookEngine);

        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(TeammateRunner.drainLeadMailbox(teamManager));
            for (var n : subAgentTasks.drainNotifications()) {
                notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                        .formatted(n.taskId(), n.name(), n.status()));
            }
            return notes;
        });

        final boolean coordinatorMode = opts.coordinatorMode;
        agent.setToolNameFilter(name -> {
            if (!coordinatorMode) {
                return true;
            }
            if (teamManager.listTeams().isEmpty()) {
                return true;
            }
            return com.mewagent.teams.Coordinator.isCoordinatorTool(name);
        });

        return new AgentSession(workDir, sessionId, protocol, client, registry, conversation,
                checker, hookEngine, fileHistory, skillCatalog, taskList, teamManager,
                subAgentTasks, agentTool, askUserTool, worktreeManager, mcpManager, mcpInstructions,
                agent);
    }

    /** 把 file history / 文件状态缓存接到三个文件类工具上。 */
    private static void wireFileTools(ToolRegistry registry, FileHistory fileHistory) {
        var fileStateCache = new FileStateCache();
        for (Tool tool : registry.listTools()) {
            if (tool instanceof EditFileTool edit) {
                edit.setFileHistory(fileHistory);
                edit.setFileStateCache(fileStateCache);
            }
            if (tool instanceof WriteFileTool write) {
                write.setFileHistory(fileHistory);
                write.setFileStateCache(fileStateCache);
            }
            if (tool instanceof ReadFileTool read) {
                read.setFileStateCache(fileStateCache);
            }
        }
    }

    /** 把 YAML 里的 hook 配置转成引擎对象；非法项按原有行为回退到默认值。 */
    private static List<HookEngine.Hook> toHooks(List<HookConfig> configs) {
        var hooks = new ArrayList<HookEngine.Hook>();
        if (configs == null) {
            return hooks;
        }
        for (HookConfig hc : configs) {
            HookEngine.EventName event = HookEngine.EventName.fromString(hc.getEvent());
            if (event == null) {
                event = HookEngine.EventName.SESSION_START;
            }
            HookEngine.ActionType actionType = HookEngine.ActionType.fromString(hc.getType());
            if (actionType == null) {
                actionType = HookEngine.ActionType.COMMAND;
            }
            Duration timeout = hc.getTimeout() > 0
                    ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
            var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                    hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
            hooks.add(new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                    hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError()));
        }
        return hooks;
    }

    private static SkillCatalog loadProjectSkills(String workDir) {
        var catalog = new SkillCatalog();
        Path skillDir = Path.of(workDir, ".mewagent", "skills");
        if (Files.isDirectory(skillDir)) {
            catalog.loadFromDirectory(skillDir);
        }
        return catalog;
    }

    /** 生成注入给模型的 MCP 服务器说明段落。 */
    private static String buildMcpInstructions(McpManager.ConnectResult result, ToolRegistry registry) {
        if (result.servers().isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (var server : result.servers()) {
            var sb = new StringBuilder();
            sb.append("## ").append(server.name()).append("\n");
            if (server.instructions() != null && !server.instructions().isBlank()) {
                sb.append(server.instructions()).append("\n");
            }
            var toolNames = registry.listTools().stream()
                    .filter(t -> t.name().startsWith("mcp__" + server.name() + "__"))
                    .map(Tool::name)
                    .toList();
            if (!toolNames.isEmpty()) {
                sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
            }
            parts.add(sb.toString());
        }
        return "# MCP Server Instructions\n\n"
                + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                + String.join("\n\n", parts);
    }

    /** 供入口在需要时把沙箱注入 Bash 工具（TUI 用）。 */
    public static void applySandbox(ToolRegistry registry,
                                    com.mewagent.sandbox.Sandbox sandbox,
                                    com.mewagent.sandbox.SandboxConfig config) {
        if (sandbox == null) {
            return;
        }
        for (Tool tool : registry.listTools()) {
            if (tool instanceof BashTool bash) {
                bash.setSandbox(sandbox);
                bash.setSandboxConfig(config);
            }
        }
    }
}
