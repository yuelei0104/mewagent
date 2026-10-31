package com.mewagent.agent;

import com.mewagent.conversation.ConversationManager;
import com.mewagent.filehistory.FileHistory;
import com.mewagent.hook.HookEngine;
import com.mewagent.llm.LlmClient;
import com.mewagent.mcp.McpManager;
import com.mewagent.permission.PermissionChecker;
import com.mewagent.skill.SkillCatalog;
import com.mewagent.subagent.AgentTool;
import com.mewagent.subagent.SubAgentTaskManager;
import com.mewagent.task.TaskList;
import com.mewagent.teams.TeamManager;
import com.mewagent.tool.ToolRegistry;
import com.mewagent.tool.impl.AskUserTool;
import com.mewagent.worktree.WorktreeManager;

/**
 * 一次性装配好的 Agent 运行时快照。
 *
 * <p>以前 {@code PrintMode} / {@code RemoteServer} / {@code MewAgentModel} 各写了一遍
 * "建注册表 → 注册工具 → 接线 file history → 构造 Agent → 装 hook → 连 MCP"，
 * 加一个工具要改三处，而且已经出现过能力漂移（远程模式漏了记忆注入）。
 * 现在统一由 {@link AgentSessionFactory} 产出这个对象，入口只负责接自己的 UI 适配器。
 *
 * <p>字段全部 {@code final}：装配完成后不再改变。模式差异（授权方式、进度回调、沙箱、
 * 是否连 MCP）通过 {@link AgentSessionFactory.Options} 传入。
 */
public final class AgentSession {

    public final String workDir;
    public final String sessionId;
    public final String protocol;

    public final LlmClient client;
    public final ToolRegistry registry;
    public final ConversationManager conversation;
    public final PermissionChecker checker;
    public final HookEngine hookEngine;
    public final FileHistory fileHistory;
    public final SkillCatalog skillCatalog;
    public final TaskList taskList;
    public final TeamManager teamManager;
    public final SubAgentTaskManager subAgentTasks;
    public final AgentTool agentTool;
    public final AskUserTool askUserTool;
    public final WorktreeManager worktreeManager;

    /** {@code null} 当 MCP 未启用或连接失败。 */
    public final McpManager mcpManager;
    /** 连接 MCP 后生成的指令段落；空串表示没有。 */
    public final String mcpInstructions;

    public final Agent agent;

    AgentSession(String workDir, String sessionId, String protocol,
                 LlmClient client, ToolRegistry registry, ConversationManager conversation,
                 PermissionChecker checker, HookEngine hookEngine, FileHistory fileHistory,
                 SkillCatalog skillCatalog, TaskList taskList, TeamManager teamManager,
                 SubAgentTaskManager subAgentTasks, AgentTool agentTool, AskUserTool askUserTool,
                 WorktreeManager worktreeManager, McpManager mcpManager, String mcpInstructions,
                 Agent agent) {
        this.workDir = workDir;
        this.sessionId = sessionId;
        this.protocol = protocol;
        this.client = client;
        this.registry = registry;
        this.conversation = conversation;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.fileHistory = fileHistory;
        this.skillCatalog = skillCatalog;
        this.taskList = taskList;
        this.teamManager = teamManager;
        this.subAgentTasks = subAgentTasks;
        this.agentTool = agentTool;
        this.askUserTool = askUserTool;
        this.worktreeManager = worktreeManager;
        this.mcpManager = mcpManager;
        this.mcpInstructions = mcpInstructions;
        this.agent = agent;
    }
}
