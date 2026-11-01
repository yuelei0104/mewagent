package com.mewagent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ToolRegistry 的延迟加载与协议 schema 适配。 */
class ToolRegistryTest {

    static final class FakeTool implements Tool {
        private final String name;
        private final boolean defer;

        FakeTool(String name, boolean defer) {
            this.name = name;
            this.defer = defer;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "description of " + name; }

        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override public Map<String, Object> schema() {
            return Map.of(
                    "name", name,
                    "description", description(),
                    "input_schema", Map.of("type", "object"));
        }

        @Override public ToolResult execute(Map<String, Object> args) { return ToolResult.success(""); }

        @Override public boolean shouldDefer() { return defer; }
    }

    @Test
    void deferredToolStaysHiddenUntilDiscovered() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Hidden", true));
        registry.register(new FakeTool("Visible", false));

        assertEquals(List.of("Visible"), names(registry.getAllSchemas("anthropic")));
        assertEquals(List.of("Hidden"), registry.getDeferredToolNames());

        registry.markDiscovered("Hidden");

        assertEquals(2, registry.getAllSchemas("anthropic").size());
        assertTrue(registry.getDeferredToolNames().isEmpty(), "discovered tool must not be advertised again");
    }

    @Test
    void openAiProtocolWrapsSchemaAsFunction() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Echo", false));

        var schema = registry.getAllSchemas("openai").get(0);

        assertEquals("function", schema.get("type"));
        assertEquals("Echo", schema.get("name"));
        assertTrue(schema.containsKey("parameters"));
        assertFalse(schema.containsKey("input_schema"), "openai shape must not leak anthropic keys");
    }

    @Test
    void openAiCompatSharesTheOpenAiShape() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Echo", false));

        assertTrue(registry.getAllSchemas("openai-compat").get(0).containsKey("parameters"));
    }

    @Test
    void anthropicProtocolKeepsInputSchema() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Echo", false));

        var schema = registry.getAllSchemas("anthropic").get(0);

        assertTrue(schema.containsKey("input_schema"));
        assertFalse(schema.containsKey("parameters"));
    }

    @Test
    void searchMatchesNameAndDescriptionAndRespectsLimit() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Alpha", true));
        registry.register(new FakeTool("Beta", true));
        registry.register(new FakeTool("Gamma", false));

        assertEquals(List.of("Alpha"), names(registry.searchDeferred("alpha", 10, "anthropic")));
        assertEquals(2, registry.searchDeferred("description", 10, "anthropic").size());
        assertEquals(1, registry.searchDeferred("description", 1, "anthropic").size(), "limit is honoured");
        assertTrue(registry.searchDeferred("gamma", 10, "anthropic").isEmpty(), "non-deferred tools are not searched");
    }

    @Test
    void findDeferredByNamesIsCaseInsensitive() {
        var registry = new ToolRegistry();
        registry.register(new FakeTool("Alpha", true));
        registry.register(new FakeTool("Beta", true));

        assertEquals(List.of("Alpha", "Beta"),
                names(registry.findDeferredByNames(List.of("alpha", "BETA"), "anthropic")));
    }

    private static List<String> names(List<Map<String, Object>> schemas) {
        return schemas.stream().map(s -> String.valueOf(s.get("name"))).toList();
    }
}
