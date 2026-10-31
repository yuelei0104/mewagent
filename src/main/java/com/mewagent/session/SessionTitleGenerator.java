package com.mewagent.session;

import com.mewagent.config.ProviderConfig;
import com.mewagent.conversation.ConversationManager;
import com.mewagent.llm.LlmClient;
import com.mewagent.llm.StreamEvent;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 会话标题生成器：首轮对话结束后，用一次短请求把一个会话概括成十几个字的标题，
 * 供侧栏展示（替代"首条消息原文"）。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>独立客户端</b>：{@link LlmClient#setSystemPrompt} 是全局状态，复用主对话的
 *       client 会在主流程流式中改写它的系统提示，所以这里单独建一个实例并复用。</li>
 *   <li><b>只在首轮后调用</b>：此时已有助手回复，标题比只给一句 prompt 猜要准得多。</li>
 *   <li><b>失败静默</b>：返回 {@code null}，调用方回退到首条消息，不影响主流程。</li>
 * </ul>
 */
public final class SessionTitleGenerator {

    private static final String SYSTEM_PROMPT = """
            你是会话标题生成器。根据用户请求和助手回复，写一个不超过 15 个汉字的任务标题。
            只输出标题本身：不要引号、不要句号、不要"标题："前缀、不要解释、不要换行。
            标题要说明这次会话产出了什么，用"动词 + 对象"的形式，例如：
              重构工具执行为读写分批并发
              修复压缩后上下文丢失
              排查 Windows 路径匹配失败
            """;

    private static final int MAX_USER_CHARS = 600;
    private static final int MAX_ASSISTANT_CHARS = 900;
    private static final int MAX_TITLE_CHARS = 24;
    private static final long TIMEOUT_MS = 20_000;
    private static final long IDLE_POLLS_BEFORE_GIVING_UP = 6; // 6 × 500ms = 3s 无输出即放弃

    private final ProviderConfig providerConfig;
    private LlmClient client;

    public SessionTitleGenerator(ProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    private synchronized LlmClient client() {
        if (client == null) {
            client = LlmClient.create(providerConfig, SYSTEM_PROMPT);
        }
        return client;
    }

    /**
     * Generates a title from the first user message and the assistant's reply.
     *
     * @return a cleaned title, or {@code null} when generation failed or produced
     *         nothing usable
     */
    public synchronized String generate(String userMessage, String assistantMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        try {
            var prompt = new ConversationManager();
            prompt.addUserMessage("用户请求：\n" + clip(userMessage, MAX_USER_CHARS)
                    + "\n\n助手回复：\n" + clip(assistantMessage, MAX_ASSISTANT_CHARS));

            BlockingQueue<StreamEvent> queue = client().stream(prompt, List.of());
            StringBuilder text = new StringBuilder();
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            long idlePolls = 0;

            while (System.currentTimeMillis() < deadline) {
                StreamEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event == null) {
                    // 已经有正文且连续空转，说明流已结束但没收到终止事件
                    if (!text.isEmpty() && ++idlePolls >= IDLE_POLLS_BEFORE_GIVING_UP) {
                        break;
                    }
                    continue;
                }
                idlePolls = 0;
                if (event instanceof StreamEvent.TextDelta delta) {
                    text.append(delta.text());
                    if (text.length() > 200) {
                        break;
                    }
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
            return sanitize(text.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 标题是锦上添花：任何异常都不向上抛，调用方回退到首条消息
            return null;
        }
    }

    /** Cleans model output into a single short title line. */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        // 只取第一行非空内容
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                text = line.strip();
                break;
            }
        }
        text = text.replaceAll("^(标题|任务标题|Title)\\s*[:：]\\s*", "");
        // 引号和句号可能交替出现（例如 "「标题」。"），所以清洗两轮：
        // 先去首尾引号再去尾部标点，第二轮处理被标点包住的引号。
        for (int pass = 0; pass < 2; pass++) {
            text = text.replaceAll("^[\"'“”『』「」《》]+", "")
                       .replaceAll("[\"'“”『』「」《》]+$", "");
            text = text.replaceAll("[。．.!！?？;；,，、\\s]+$", "");
        }
        text = text.replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_TITLE_CHARS ? text.substring(0, MAX_TITLE_CHARS) : text;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
