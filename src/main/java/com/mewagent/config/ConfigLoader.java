
package com.mewagent.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class ConfigLoader {

    private static final Set<String> VALID_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    /**
     * Marker file written into a legacy {@code .mewcode/} directory after its
     * contents have been copied to {@code .mewagent/}. Prevents repeated
     * migration on every launch.
     */
    private static final String MIGRATED_MARKER = ".migrated-to-mewagent";

    public static AppConfig load(String path) throws ConfigException {
        // 启动时迁移老的 .mewcode/ 数据到新的 .mewagent/（一次性，幂等）
        try {
            migrateLegacyConfigDirs();
        } catch (Exception e) {
            // 迁移失败不应该阻塞主流程 —— 继续走 fallback（老目录）即可
            System.err.println("[mewagent] config migration warning: " + e.getMessage());
        }

        if (path != null && !path.isEmpty()) {
            var cfg = loadSingleFile(Path.of(path));
            validate(cfg);
            return cfg;
        }

        var cwd = Path.of(System.getProperty("user.dir"));
        var home = Path.of(System.getProperty("user.home"));
        var candidates = List.of(
                home.resolve(".mewagent").resolve("config.yaml"),
                cwd.resolve(".mewagent").resolve("config.yaml"),
                cwd.resolve(".mewagent").resolve("config.local.yaml")
        );

        AppConfig merged = null;
        for (var p : candidates) {
            if (!Files.exists(p)) continue;
            var layer = loadSingleFile(p);
            if (merged == null) {
                merged = layer;
            } else {
                mergeConfig(merged, layer);
            }
        }

        if (merged == null) {
            throw new ConfigException(
                    "No config file found. Expected .mewagent/config.yaml in project or ~/.mewagent/config.yaml");
        }

        validate(merged);
        return merged;
    }

    /**
     * 启动时迁移老的 {@code .mewcode/} 数据到新的 {@code .mewagent/}。
     * <p>
     * 策略：复制（不删除老目录），老目录写 {@code .migrated-to-mewagent} 标记文件防重复。
     * 失败不抛 —— 用户可以手动迁移。
     * <p>
     * 检查两个位置：{@code ~/.mewcode/}（用户级）和 {@code ./.mewcode/}（项目级）。
     */
    private static void migrateLegacyConfigDirs() throws IOException {
        var cwd = Path.of(System.getProperty("user.dir"));
        var home = Path.of(System.getProperty("user.home"));
        // (老路径, 新路径) 对
        var pairs = List.of(
                java.util.Map.entry(home.resolve(".mewcode"), home.resolve(".mewagent")),
                java.util.Map.entry(cwd.resolve(".mewcode"), cwd.resolve(".mewagent"))
        );
        for (var entry : pairs) {
            migrateOne(entry.getKey(), entry.getValue());
        }
    }

    private static void migrateOne(Path legacyDir, Path newDir) throws IOException {
        if (!Files.isDirectory(legacyDir)) return;                       // 老目录不存在 → 跳过
        if (Files.exists(legacyDir.resolve(MIGRATED_MARKER))) return;   // 已经迁过 → 跳过
        if (Files.exists(newDir)) {                                     // 新目录已存在 → 不要覆盖
            // 写个标记，避免每次启动都重试
            writeMarker(legacyDir);
            return;
        }
        // 复制整个目录树
        Files.createDirectories(newDir);
        try (Stream<Path> walk = Files.walk(legacyDir)) {
            walk.forEach(src -> {
                try {
                    Path dst = newDir.resolve(legacyDir.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy " + src + " -> " + e.getMessage(), e);
                }
            });
        }
        writeMarker(legacyDir);
        System.err.println("[mewagent] Migrated legacy config: " + legacyDir + " -> " + newDir);
    }

    private static void writeMarker(Path legacyDir) throws IOException {
        try {
            String ts = Instant.now().toString();
            Files.writeString(legacyDir.resolve(MIGRATED_MARKER),
                    "Migrated to .mewagent/ at " + ts + "\nOriginal .mewcode/ directory preserved for rollback.\n");
        } catch (IOException e) {
            // 标记写不进去不要紧，下次启动还会重试（幂等）
        }
    }

    private static AppConfig loadSingleFile(Path configPath) throws ConfigException {
        if (!Files.exists(configPath)) {
            throw new ConfigException("Config file not found: " + configPath);
        }
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            throw new ConfigException("Failed to read config " + configPath + ": " + e.getMessage());
        }
        var loaderOptions = new LoaderOptions();
        var constructor = new Constructor(AppConfig.class, loaderOptions);
        constructor.setPropertyUtils(new SnakeCasePropertyUtils());
        var yaml = new Yaml(constructor);
        AppConfig cfg;
        try {
            cfg = yaml.load(content);
        } catch (Exception e) {
            throw new ConfigException("Failed to parse config " + configPath + ": " + e.getMessage());
        }
        if (cfg == null) cfg = new AppConfig();
        return cfg;
    }

    private static void mergeConfig(AppConfig base, AppConfig override) {
        if (override.getProviders() != null && !override.getProviders().isEmpty()) {
            base.setProviders(override.getProviders());
        }
        if (override.getPermissionMode() != null && !override.getPermissionMode().isBlank()) {
            base.setPermissionMode(override.getPermissionMode());
        }
        if (override.getMcpServers() != null && !override.getMcpServers().isEmpty()) {
            var servers = base.getMcpServers() != null
                    ? new ArrayList<>(base.getMcpServers()) : new ArrayList<McpServerConfig>();
            var byName = new java.util.LinkedHashMap<String, Integer>();
            for (int i = 0; i < servers.size(); i++) {
                byName.put(servers.get(i).getName(), i);
            }
            for (var s : override.getMcpServers()) {
                var idx = byName.get(s.getName());
                if (idx != null) {
                    servers.set(idx, s);
                } else {
                    servers.add(s);
                    byName.put(s.getName(), servers.size() - 1);
                }
            }
            base.setMcpServers(servers);
        }
        if (override.getHooks() != null) {
            var hooks = base.getHooks() != null
                    ? new ArrayList<>(base.getHooks()) : new ArrayList<HookConfig>();
            hooks.addAll(override.getHooks());
            base.setHooks(hooks);
        }
        // 沙箱配置：override 层级可以覆盖
        if (override.getSandbox() != null) {
            if (base.getSandbox() == null) {
                base.setSandbox(override.getSandbox());
            } else {
                if (override.getSandbox().isEnabled()) base.getSandbox().setEnabled(true);
                if (override.getSandbox().isNetworkEnabled()) base.getSandbox().setNetworkEnabled(true);
            }
        }
        if (override.isEnableCoordinatorMode()) {
            base.setEnableCoordinatorMode(true);
        }
    }

    private static void validate(AppConfig cfg) throws ConfigException {
        for (int i = 0; i < cfg.getProviders().size(); i++) {
            var p = cfg.getProviders().get(i);
            var missing = new ArrayList<String>();

            if (isBlank(p.getName())) missing.add("name");
            if (isBlank(p.getProtocol())) missing.add("protocol");
            if (isBlank(p.getBaseUrl())) missing.add("base_url");
            if (isBlank(p.getModel())) missing.add("model");

            if (!missing.isEmpty()) {
                throw new ConfigException(
                        "Provider #%d: missing fields: %s".formatted(i + 1, String.join(", ", missing))
                );
            }

            if (!VALID_PROTOCOLS.contains(p.getProtocol())) {
                throw new ConfigException(
                        "Provider #%d: invalid protocol '%s', must be one of: anthropic, openai, openai-compat"
                                .formatted(i + 1, p.getProtocol())
                );
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static class SnakeCasePropertyUtils extends PropertyUtils {

        @Override
        public Property getProperty(Class<?> type, String name) {
            String camel = snakeToCamel(name);
            return super.getProperty(type, camel);
        }

        private static String snakeToCamel(String snake) {
            if (!snake.contains("_")) return snake;
            var sb = new StringBuilder();
            boolean upper = false;
            for (char c : snake.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return sb.toString();
        }
    }

    public static class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }
    }
}
