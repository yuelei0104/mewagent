package com.mewagent.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceInfoTest {
    @TempDir Path directory;

    @Test void missingRepositoryDoesNotInventAMainBranch() {
        assertEquals("", WorkspaceInfo.branch(directory));
    }

    @Test void readsActualBranchAndDetachedCommit() throws Exception {
        git("init", "-b", "feature/ui-test");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "init");
        assertEquals("feature/ui-test", WorkspaceInfo.branch(directory));
        git("checkout", "--detach");
        assertTrue(WorkspaceInfo.branch(directory).startsWith("detached@"));
    }

    private void git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(directory.toString());
        command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
