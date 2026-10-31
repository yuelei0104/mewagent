

package com.mewagent;

import com.mewagent.config.AppConfig;
import com.mewagent.config.ConfigLoader;
import com.mewagent.print.PrintMode;
import com.mewagent.remote.RemoteServer;
import com.mewagent.tui.MewAgentModel;

import com.mewagent.tui.tea.Program;

import java.util.List;
import sun.misc.Signal;

public class MewAgent {

    // 默认 Remote 模式监听端口
    private static final String DEFAULT_REMOTE_ADDR = ":18888";

    public static void main(String[] args) {
        // 解析 CLI 参数：-p "prompt"、--output-format、--remote[=addr] 和配置文件路径
        String configPath = null;
        boolean remoteMode = false;
        String remoteAddr = DEFAULT_REMOTE_ADDR;
        String printPrompt = null;
        String outputFormat = "text";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-p") && i + 1 < args.length) {
                printPrompt = args[++i];
            } else if (arg.startsWith("-p=")) {
                printPrompt = arg.substring("-p=".length());
            } else if (arg.equals("--output-format") && i + 1 < args.length) {
                outputFormat = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring("--output-format=".length());
            } else if (arg.equals("--remote")) {
                remoteMode = true;
            } else if (arg.startsWith("--remote=")) {
                remoteMode = true;
                remoteAddr = arg.substring("--remote=".length());
            } else if (configPath == null) {
                configPath = arg;
            }
        }

        // 环境变量回退：MEWAGENT_CONFIG 优先，MEWCODE_CONFIG 作为兼容回退（已弃用）
        if (configPath == null) {
            String envPath = System.getenv("MEWAGENT_CONFIG");
            if (envPath != null && !envPath.isBlank()) {
                configPath = envPath;
            } else {
                String legacyEnvPath = System.getenv("MEWCODE_CONFIG");
                if (legacyEnvPath != null && !legacyEnvPath.isBlank()) {
                    System.err.println(
                            "[mewagent] WARNING: MEWCODE_CONFIG is deprecated, use MEWAGENT_CONFIG instead.");
                    configPath = legacyEnvPath;
                }
            }
        }

        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // -p 模式：非交互式运行，输出结果到 stdout
        if (printPrompt != null) {
            PrintMode.OutputFormat fmt = "stream-json".equals(outputFormat)
                    ? PrintMode.OutputFormat.STREAM_JSON
                    : PrintMode.OutputFormat.TEXT;
            PrintMode.run(config, printPrompt, fmt);
            // LLM SDK 会创建非守护线程池，run() 正常返回后 JVM 也不会退出。
            // 这里显式退出，否则脚本 / eval 跑分器会一直等到超时，把跑完的用例误判为 timeout。
            System.exit(0);
            return;
        }

        // --remote 模式：启动 HTTP + WebSocket 服务器，不进入 TUI
        if (remoteMode) {
            var server = new RemoteServer(
                    config.getProviders(),
                    config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                    config.getHooks() != null ? config.getHooks() : List.of(),
                    remoteAddr,
                    config.isEnableCoordinatorMode()
            );
            try {
                server.run();
            } catch (Exception e) {
                System.err.println("Remote server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // TUI 模式（默认）
        var model = new MewAgentModel(
                config.getProviders(),
                config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                config.getHooks() != null ? config.getHooks() : List.of(),
                config.isEnableCoordinatorMode()
        );

        var program = new Program(model);

        model.setProgram(program);

        System.out.print("\033[?25l");

        // Re-register SIGINT handler after TUI4J's program.run() starts,
        // overriding the framework's default quit-on-SIGINT behavior.
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                Signal.handle(new Signal("INT"), sig -> model.handleSigint());
            } catch (IllegalArgumentException ignored) {}
        });

        try {
            program.run();
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }
}

