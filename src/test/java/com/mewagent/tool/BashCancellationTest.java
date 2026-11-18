package com.mewagent.tool;

import com.mewagent.tool.impl.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.LINUX, OS.MAC})
class BashCancellationTest {
    @TempDir Path directory;

    @Test void timeoutIncludesCommandsThatKeepStdoutOpen() {
        assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
            var result = new BashTool(directory.toString()).execute(Map.of("command", "sleep 30", "timeout", 1));
            assertTrue(result.isError()); assertTrue(result.output().contains("timed out"));
        });
    }

    @Test void interruptionStopsTheShellBeforeItCanWriteLaterFiles() throws Exception {
        var result = new AtomicReference<ToolResult>();
        var thread = Thread.startVirtualThread(() -> result.set(new BashTool(directory.toString())
                .execute(Map.of("command", "echo started > ready; sleep 30; echo unexpected > later", "timeout", 60))));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(directory.resolve("ready")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(directory.resolve("ready")));
            thread.interrupt(); thread.join(3000);
            assertFalse(thread.isAlive());
            assertNotNull(result.get()); assertTrue(result.get().isError());
            assertFalse(Files.exists(directory.resolve("later")));
        } finally { thread.interrupt(); }
    }
}
