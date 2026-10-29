package com.mewagent.prompt;

import com.mewagent.tool.ToolCategory;
import com.mewagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证「提交前必须跑测试」的约定确实进了系统提示词，并且排在工具说明之前。
 *
 * 这类约定很容易在后续重构中被悄悄丢掉 —— 提示词没有编译期检查，
 * 只能靠测试兜住。
 */
class PromptVerificationTest {

    private static String buildPrompt() {
        var env = new PromptBuilder.EnvironmentContext(
                ".", "windows", "amd64", "bash", false, "", "test-model", "2026-10-29");
        return PromptBuilder.buildSystemPrompt(env,
                new PromptBuilder.BuildOptions(null, null, null));
    }

    @Test
    void verificationSectionIsPresent() {
        String prompt = buildPrompt();

        assertTrue(prompt.contains("# Verifying your work"),
                "系统提示词缺少验证章节");
        assertTrue(prompt.contains("Never report a change as done based on inspection alone"),
                "缺少「不能只靠看代码就宣称完成」的要求");
        assertTrue(prompt.contains("Do not commit or open a pull request while a check you can run is failing"),
                "缺少「检查未通过不得提交/开 PR」的硬约束");
        assertTrue(prompt.contains("Delete nothing to make a check pass"),
                "缺少「不得删测试来让检查变绿」的约束");
    }

    @Test
    void verificationFallsBetweenActionsAndTools() {
        String prompt = buildPrompt();

        int actions = prompt.indexOf("# Executing actions with care");
        int verify = prompt.indexOf("# Verifying your work");
        int tools = prompt.indexOf("# Using your tools");

        assertTrue(actions > 0, "缺少 ExecutingActions 章节");
        assertTrue(verify > 0, "缺少 Verification 章节");
        assertTrue(tools > 0, "缺少 UsingTools 章节");

        assertTrue(actions < verify, "Verification 应排在 ExecutingActions 之后");
        assertTrue(verify < tools, "Verification 应排在 UsingTools 之前");
    }

    @Test
    void defaultRegistryExposesPullRequestTool() {
        var registry = ToolRegistry.createDefault();
        var tool = registry.get("CreatePullRequest");

        assertNotNull(tool, "CreatePullRequest 未注册到默认工具集");
        assertEquals(ToolCategory.COMMAND, tool.category(),
                "推送代码属于对外可见操作，必须走权限确认");
        assertFalse(tool.shouldDefer(),
                "PR 工具不应延迟加载 —— 模型需要一开始就知道能力边界");
    }
}
