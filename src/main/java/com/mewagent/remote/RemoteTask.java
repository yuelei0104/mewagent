package com.mewagent.remote;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次通过 REST 触发的 Agent 任务。
 *
 * 字段是可变的：状态由执行线程推进，HTTP 线程只读。volatile 保证可见性，
 * 不做进一步的同步 —— 读到的每个字段都是独立快照，拼出来的 JSON 允许
 * 在极端情况下跨状态（例如 status 已是 completed 而 finishedAt 尚未写入），
 * 调用方按 status 判断即可。
 */
final class RemoteTask {

    static final String QUEUED = "queued";
    static final String RUNNING = "running";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";

    final String id;
    final String prompt;
    final long createdAt;

    volatile String status = QUEUED;
    volatile String result;
    volatile String error;
    volatile int inputTokens;
    volatile int outputTokens;
    volatile int turns;
    volatile long startedAt;
    volatile long finishedAt;

    RemoteTask(String id, String prompt) {
        this.id = id;
        this.prompt = prompt;
        this.createdAt = System.currentTimeMillis();
    }

    /** 序列化为 API 响应。用 LinkedHashMap 而非 Map.of，因为结果字段可能为 null。 */
    Map<String, Object> toJson() {
        var json = new LinkedHashMap<String, Object>();
        json.put("taskId", id);
        json.put("status", status);
        json.put("prompt", prompt);
        json.put("createdAt", createdAt);
        if (startedAt > 0) json.put("startedAt", startedAt);
        if (finishedAt > 0) {
            json.put("finishedAt", finishedAt);
            json.put("durationMs", finishedAt - startedAt);
        }
        if (result != null) json.put("result", result);
        if (error != null) json.put("error", error);
        if (inputTokens > 0) json.put("inputTokens", inputTokens);
        if (outputTokens > 0) json.put("outputTokens", outputTokens);
        if (turns > 0) json.put("turns", turns);
        return json;
    }

    boolean isFinished() {
        return COMPLETED.equals(status) || FAILED.equals(status);
    }
}
