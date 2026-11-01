package com.mewagent.agent;

import com.mewagent.config.ProviderConfig;
import com.mewagent.conversation.ConversationManager;
import com.mewagent.llm.LlmClient;
import com.mewagent.llm.StreamEvent;
import com.mewagent.tool.Tool;
import com.mewagent.tool.ToolCategory;
import com.mewagent.tool.ToolRegistry;
import com.mewagent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 主循环的行为测试。
 *
 * <p>用脚本化的假 LlmClient 驱动：它按预置顺序返回事件，不发起任何网络请求。
 * 这样能覆盖"文本响应 / 工具调用 / 迭代上限 / 流错误 / 未知工具"这些分支，
 * 而不用真的调模型。
 */
class AgentLoopTest {

    @TempDir
    Path workDir;

    // ── 测试替身 ──────────────────────────────────────────────────────

    /** 按预置脚本返回事件；脚本耗尽后返回一个空结束。 */
    static final class ScriptedClient implements LlmClient {
        private final Deque<List<StreamEvent>> scripts = new ArrayDeque<>();
        private List<StreamEvent> repeat;

        void respond(StreamEvent... events) {
            scripts.add(List.of(events));
        }

        void repeatAlways(StreamEvent... events) {
            repeat = List.of(events);
        }

        int calls = 0;

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            calls++;
            List<StreamEvent> events = scripts.poll();
            if (events == null) {
                events = repeat != null ? repeat : List.of(new StreamEvent.StreamEnd("end_turn", 1, 1));
            }
            var queue = new LinkedBlockingQueue<StreamEvent>(events);
            return queue;
        }

        @Override
        public void setSystemPrompt(String prompt) {
        }
    }

    static final class EchoTool implements Tool {
        int calls = 0;

        @Override public String name() { return "Echo"; }
        @Override public String description() { return "echo back"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override public Map<String, Object> schema() {
            return Map.of("name", "Echo", "description", "echo back",
                    "input_schema", Map.of("type", "object"));
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            calls++;
            return ToolResult.success("echo-ok");
        }
    }

    private static ProviderConfig provider() {
        var cfg = new ProviderConfig();
        cfg.setName("test");
        cfg.setProtocol("anthropic");
        cfg.setModel("claude-3-5-sonnet");
        cfg.setApiKey("test-key");
        return cfg;
    }

    private Agent newAgent(LlmClient client, ToolRegistry registry, int maxIterations) {
        var agent = new Agent(client, registry, "anthropic", provider());
        agent.setWorkDir(workDir.toString());
        agent.setMaxIterations(maxIterations);
        return agent;
    }

    /** 收集事件直到 LoopComplete（或超时）。 */
    private static List<AgentEvent> drain(BlockingQueue<AgentEvent> queue) throws InterruptedException {
        var events = new ArrayList<AgentEvent>();
        while (true) {
            AgentEvent event = queue.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                break;
            }
            events.add(event);
            if (event instanceof AgentEvent.LoopComplete) {
                break;
            }
        }
        return events;
    }

    // ── 用例 ──────────────────────────────────────────────────────────

    @Test
    void textOnlyResponseCompletesInOneTurn() throws Exception {
        var client = new ScriptedClient();
        client.respond(new StreamEvent.TextDelta("hello"), new StreamEvent.StreamEnd("end_turn", 12, 4));

        var conversation = new ConversationManager();
        var events = drain(newAgent(client, new ToolRegistry(), 3).run(conversation));

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete),
                "should finish with LoopComplete");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StreamText t && "hello".equals(t.text())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.UsageEvent));

        var messages = conversation.getMessages();
        assertEquals(1, messages.size(), "only the assistant reply should be stored");
        assertEquals("hello", messages.get(0).getContent());
        assertEquals("assistant", messages.get(0).getRole());
    }

    @Test
    void toolCallIsExecutedAndResultFedBack() throws Exception {
        var tool = new EchoTool();
        var registry = new ToolRegistry();
        registry.register(tool);

        var client = new ScriptedClient();
        client.respond(
                new StreamEvent.ToolCallComplete("t1", "Echo", Map.of()),
                new StreamEvent.StreamEnd("tool_use", 10, 5));
        client.respond(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 12, 3));

        var conversation = new ConversationManager();
        var events = drain(newAgent(client, registry, 5).run(conversation));

        assertEquals(1, tool.calls, "tool should run exactly once");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent r
                && "echo-ok".equals(r.output()) && !r.isError()));

        // 对话里应有：assistant(tool_use) + user(tool_result) + assistant(text)
        var messages = conversation.getMessages();
        assertEquals(3, messages.size(), "assistant tool_use, tool result, final assistant");
        assertNotNull(messages.get(1).getToolResults(), "tool result message");
        assertEquals(1, messages.get(1).getToolResults().size());
        assertEquals("done", messages.get(2).getContent());
    }

    @Test
    void unknownToolBecomesErrorResultInsteadOfCrash() throws Exception {
        var client = new ScriptedClient();
        client.respond(
                new StreamEvent.ToolCallComplete("t9", "NoSuchTool", Map.of()),
                new StreamEvent.StreamEnd("tool_use", 5, 5));
        client.respond(new StreamEvent.TextDelta("ok"), new StreamEvent.StreamEnd("end_turn", 5, 2));

        var conversation = new ConversationManager();
        var events = drain(newAgent(client, new ToolRegistry(), 5).run(conversation));

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent r && r.isError()),
                "unknown tool must surface as an error result");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete));
        assertEquals("ok", conversation.getMessages().get(conversation.getMessages().size() - 1).getContent());
    }

    @Test
    void stopsWithErrorAfterMaxIterations() throws Exception {
        var tool = new EchoTool();
        var registry = new ToolRegistry();
        registry.register(tool);

        var client = new ScriptedClient();
        client.repeatAlways(
                new StreamEvent.ToolCallComplete("t1", "Echo", Map.of()),
                new StreamEvent.StreamEnd("tool_use", 5, 5));

        var events = drain(newAgent(client, registry, 2).run(new ConversationManager()));

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent err
                        && err.message().contains("maximum iterations")),
                "should stop at the iteration cap: " + events);
        assertEquals(2, tool.calls, "exactly maxIterations tool rounds");
    }

    @Test
    void streamErrorIsReportedAndLoopEnds() throws Exception {
        var client = new ScriptedClient();
        client.respond(new StreamEvent.Error("boom"));

        var events = drain(newAgent(client, new ToolRegistry(), 3).run(new ConversationManager()));

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ErrorEvent err
                        && "boom".equals(err.message())),
                "stream error must be forwarded: " + events);
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.LoopComplete),
                "loop must still emit LoopComplete so callers do not hang");
    }

    @Test
    void toolResultIsTruncatedAtRegistryLimit() throws Exception {
        var huge = "x".repeat(ToolRegistry.MAX_OUTPUT_CHARS + 500);
        var tool = new Tool() {
            @Override public String name() { return "Huge"; }
            @Override public String description() { return "huge output"; }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public Map<String, Object> schema() {
                return Map.of("name", "Huge", "description", "huge", "input_schema", Map.of("type", "object"));
            }
            @Override public ToolResult execute(Map<String, Object> args) { return ToolResult.success(huge); }
        };
        var registry = new ToolRegistry();
        registry.register(tool);

        var client = new ScriptedClient();
        client.respond(
                new StreamEvent.ToolCallComplete("t1", "Huge", Map.of()),
                new StreamEvent.StreamEnd("tool_use", 5, 5));
        client.respond(new StreamEvent.TextDelta("ok"), new StreamEvent.StreamEnd("end_turn", 5, 2));

        var conversation = new ConversationManager();
        drain(newAgent(client, registry, 5).run(conversation));

        var result = conversation.getMessages().get(1).getToolResults().get(0).content();
        assertTrue(result.length() <= ToolRegistry.MAX_OUTPUT_CHARS + 32,
                "tool output should be truncated, was " + result.length());
        assertTrue(result.contains("truncated"), "truncation marker expected");
    }
}
