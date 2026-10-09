package net.spidicard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ResultViewerTest {
    @TempDir Path directory;
    @Test void windowsUsesJavaExeAndPreservesUnquotedSpecialPathsAsSingleArguments() {
        Path home = directory.resolve("Java 21 Кириллица");
        Path helper = directory.resolve("instance & folder").resolve("viewer.jar");
        Path result = directory.resolve("Другой инстанс ' $ (тест)").resolve("spidicard.txt");
        var args = ResultViewer.command(home, "Windows 11", helper, result);
        assertEquals(home.resolve("bin/java.exe").toString(), args.getFirst());
        assertEquals(result.toAbsolutePath().normalize().toString(), args.getLast());
        assertEquals(helper.toAbsolutePath().normalize().toString(), args.get(3));
        assertEquals(5, args.size());
    }
    @Test void macAndDarwinUseJavaAndDoNotAccidentallySelectWindowsExecutable() {
        for (String os : new String[]{"Mac OS X", "Darwin", "Linux"}) {
            var args = ResultViewer.command(directory, os, directory.resolve("viewer.jar"), directory.resolve("spidicard.txt"));
            assertEquals(directory.resolve("bin/java").toString(), args.getFirst());
            assertFalse(args.contains("-XstartOnFirstThread"));
        }
    }
    @Test void missingResultReportsFailureWithoutOpeningStaleFile() throws Exception {
        var failures = new ArrayBlockingQueue<Throwable>(1);
        new ResultViewer().reopen(directory.resolve("not-existing.txt"), failures::add);
        Throwable error = failures.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertTrue(error.getMessage().contains("missing"));
    }
}
