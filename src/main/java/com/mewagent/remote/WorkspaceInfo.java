package com.mewagent.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Read-only Git metadata; no shell interpolation and no assumed branch name. */
final class WorkspaceInfo {
    static String branch(Path directory) {
        String branch = git(directory, "symbolic-ref", "--short", "-q", "HEAD");
        if (!branch.isBlank()) return branch;
        String commit = git(directory, "rev-parse", "--short", "HEAD");
        return commit.isBlank() ? "" : "detached@" + commit;
    }

    private static String git(Path directory, String... arguments) {
        Process process = null;
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git"); command.add("-C"); command.add(directory.toString());
            command.addAll(java.util.List.of(arguments));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private WorkspaceInfo() {}
}
