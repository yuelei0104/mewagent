package com.mewagent.agent;

import com.mewagent.config.ProviderConfig;
import com.mewagent.conversation.ConversationManager;
import com.mewagent.llm.LlmClient;
import com.mewagent.llm.StreamEvent;
import com.mewagent.llm.StreamEventQueue;
import com.mewagent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentCancellationTest {
    @TempDir Path directory;

    private Agent agent(LlmClient client, ToolRegistry tools) {
        var config = new ProviderConfig();
        config.setModel("test"); config.setProtocol("anthropic"); config.setApiKey("test-key");
        var agent = new Agent(client, tools, "anthropic", config);
        agent.setWorkDir(directory.toString());
        return agent;
    }

    private void awaitCompletion(BlockingQueue<AgentEvent> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event instanceof AgentEvent.LoopComplete) return;
        }
        fail("Cancellation must publish LoopComplete so the UI can leave its stopping state");
    }

    @Test void cancelClosesTheModelStreamAndStillPublishesCompletion() throws Exception {
        var started = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        var producerStopped = new CountDownLatch(1);
        LlmClient client = new LlmClient() {
            public void setSystemPrompt(String prompt) {}
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                var events = new StreamEventQueue();
                events.attach(() -> closed.set(true));
                events.start(() -> {
                    started.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { producerStopped.countDown(); }
                });
                return events;
            }
        };
        var events = new LinkedBlockingQueue<AgentEvent>();
        var thread = agent(client, new ToolRegistry()).start(new ConversationManager(), events);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            thread.interrupt();
            awaitCompletion(events);
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(closed.get());
            assertTrue(producerStopped.await(2, TimeUnit.SECONDS));
        } finally { thread.interrupt(); }
    }

    @Test void cancelDoesNotExecuteLaterWriteTools() throws Exception {
        var entered = new CountDownLatch(1);
        var laterWrites = new AtomicInteger();
        var tools = new ToolRegistry();
        tools.register(tool("Wait", () -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        tools.register(tool("LaterWrite", laterWrites::incrementAndGet));
        LlmClient client = new LlmClient() {
            public void setSystemPrompt(String prompt) {}
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> schemas) {
                return new LinkedBlockingQueue<>(List.of(
                        new StreamEvent.ToolCallComplete("1", "Wait", Map.of()),
                        new StreamEvent.ToolCallComplete("2", "LaterWrite", Map.of()),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)));
            }
        };
        var events = new LinkedBlockingQueue<AgentEvent>();
        var thread = agent(client, tools).start(new ConversationManager(), events);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            thread.interrupt(); awaitCompletion(events); thread.join(2000);
            assertFalse(thread.isAlive()); assertEquals(0, laterWrites.get());
        } finally { thread.interrupt(); }
    }

    private Tool tool(String name, Runnable action) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public ToolCategory category() { return ToolCategory.WRITE; }
            public Map<String, Object> schema() { return Map.of("name", name, "description", name,
                    "input_schema", Map.of("type", "object")); }
            public ToolResult execute(Map<String, Object> arguments) { action.run(); return ToolResult.success("done"); }
        };
    }
}
