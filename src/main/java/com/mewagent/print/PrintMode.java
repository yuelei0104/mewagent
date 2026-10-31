
package com.mewagent.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewagent.agent.Agent;
import com.mewagent.agent.AgentEvent;
import com.mewagent.config.AppConfig;
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
import com.mewagent.prompt.PromptBuilder;
import com.mewagent.session.SessionManager;
import com.mewagent.skill.SkillCatalog;
import com.mewagent.subagent.AgentTool;
import com.mewagent.subagent.SubAgentTaskManager;
import com.mewagent.task.TaskList;
import com.mewagent.task.TaskTools;
import com.mewagent.teams.TeamManager;
import com.mewagent.tool.ToolRegistry;
import com.mewagent.tool.impl.AskUserTool;
import com.mewagent.tool.impl.ToolSearchTool;
import com.mewagent.worktree.WorktreeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Print 模式（-p）：非交互式运行 Agent，将结果输出到 stdout。
 * 支持两种输出格式：
 *   - text（默认）：只输出最终文本
 *   - stream-json：每个事件输出一行 JSON
 */
public class PrintMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 输出格式枚举
     */
    public enum OutputFormat {
        TEXT,
        STREAM_JSON
    }

    /**
     * 运行 print 模式的入口
     */
    public static void run(AppConfig config, String prompt, OutputFormat format) {
        long startTime = System.currentTimeMillis();

        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = config.getProviders().get(0);
        List<McpServerConfig> mcpConfigs = config.getMcpServers() != null ? config.getMcpServers() : List.of();
        List<HookConfig> hookConfigs = config.getHooks() != null ? config.getHooks() : List.of();

        // ── 记忆管理 ──────────────────────────────────────────────────
        MemoryManager memoryManager = new MemoryManager(workDir);
        String instructionsContent = MemoryManager.loadInstructions(workDir);

        // ── 构建系统提示词 ──────────────────────────────────────────────
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // ── 统一装配：工具 / 权限 / 文件历史 / Hook / 技能 / MCP ───────────
        var opts = new com.mewagent.agent.AgentSessionFactory.Options();
        opts.workDir = workDir;
        opts.provider = providerCfg;
        opts.systemPrompt = systemPrompt;
        opts.permissionMode = PermissionMode.BYPASS;   // print 模式无人值守，自动放行
        opts.hooks = hookConfigs;
        opts.mcpServers = mcpConfigs;
        opts.coordinatorMode = config.isEnableCoordinatorMode();
        opts.instructions = instructionsContent;
        var session = com.mewagent.agent.AgentSessionFactory.create(opts);

        Agent agent = session.agent;
        ConversationManager conversation = session.conversation;
        AskUserTool askUserTool = session.askUserTool;

        // ── 注入用户消息并启动 Agent ─────────────────────────────────────
        conversation.addUserMessage(prompt);
        if (!session.mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(session.mcpInstructions);
        }

        BlockingQueue<AgentEvent> queue = agent.run(conversation);
        if (askUserTool != null) askUserTool.setEventQueue(queue);

        // ── 消费事件循环 ────────────────────────────────────────────────
        var resultText = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalTurns = 0;
        var toolCalls = new ArrayList<Map<String, Object>>();

        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                System.err.println("Stream timeout after 120s");
                System.exit(1);
                return;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    resultText.append(e.text());
                    if (format == OutputFormat.STREAM_JSON) {
                        // stream-json 模式不输出 stream_text 事件（太碎片化）
                    }
                }

                case AgentEvent.ThinkingText e -> {
                    // print 模式不输出 thinking 文本
                }

                case AgentEvent.ThinkingComplete e -> {
                    // print 模式不输出 thinking 完成
                }

                case AgentEvent.ToolUseEvent e -> {
                    if (format == OutputFormat.STREAM_JSON && e.args() != null && !e.args().isEmpty()) {
                        // 只输出带完整参数的 ToolUseEvent（即 ToolCallComplete）
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_use");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("args", e.args());
                        printJson(obj);
                        toolCalls.add(Map.of("tool_name", e.toolName(), "tool_id", e.toolId()));
                    }
                }

                case AgentEvent.ToolResultEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_result");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("output", e.output() != null ? e.output() : "");
                        obj.put("is_error", e.isError());
                        obj.put("elapsed", e.elapsed());
                        printJson(obj);
                    }
                }

                case AgentEvent.PermissionRequestEvent e -> {
                    // BYPASS 模式下不应收到权限请求，但安全起见自动批准
                    e.future().complete(PermissionResponse.ALLOW);
                }

                case AgentEvent.AskUserRequestEvent e -> {
                    // 非交互模式自动返回空答案
                    e.future().complete(Map.of());
                }

                case AgentEvent.UsageEvent e -> {
                    totalInputTokens = e.inputTokens();
                    totalOutputTokens = e.outputTokens();
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "usage");
                        obj.put("input_tokens", e.inputTokens());
                        obj.put("output_tokens", e.outputTokens());
                        printJson(obj);
                    }
                }

                case AgentEvent.TurnComplete e -> {
                    totalTurns = e.turn();
                    // text 模式下清空已累积文本（中间 turn 的文本不是最终结果）
                    if (format == OutputFormat.TEXT) {
                        resultText.setLength(0);
                    }
                }

                case AgentEvent.LoopComplete e -> {
                    if (e.totalTurns() > 0) totalTurns = e.totalTurns();
                    long durationMs = System.currentTimeMillis() - startTime;

                    if (format == OutputFormat.TEXT) {
                        // 纯文本模式：输出最终结果
                        System.out.print(resultText);
                        // 确保末尾换行
                        if (resultText.length() > 0 && resultText.charAt(resultText.length() - 1) != '\n') {
                            System.out.println();
                        }
                    } else {
                        // stream-json 模式：输出最终 result 事件
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "result");
                        obj.put("result", resultText.toString());
                        obj.put("duration_ms", durationMs);
                        obj.put("num_turns", totalTurns);
                        obj.put("tool_calls", toolCalls);
                        obj.put("usage", Map.of(
                                "input_tokens", totalInputTokens,
                                "output_tokens", totalOutputTokens
                        ));
                        printJson(obj);
                    }
                    System.out.flush();
                    return;
                }

                case AgentEvent.ErrorEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "error");
                        obj.put("message", e.message());
                        printJson(obj);
                    } else {
                        System.err.println("Error: " + e.message());
                    }
                }

                case AgentEvent.CompactEvent e -> {
                    // print 模式静默处理 compact
                }

                case AgentEvent.RetryEvent e -> {
                    // print 模式静默处理 retry
                }
            }
        }
    }

    /**
     * 将对象序列化为 JSON 并输出一行到 stdout
     */
    private static void printJson(Object obj) {
        try {
            System.out.println(MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            System.err.println("JSON serialization error: " + e.getMessage());
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 RemoteServer） ──────────────
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
