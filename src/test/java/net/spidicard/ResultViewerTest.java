package net.spidicard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ResultViewerTest {
    @TempDir Path directory;
    @Test void windowsUsesJavaExeAndKeepsInstancePathsOutOfNativeCommandLine() {
        Path home = directory.resolve("Java 21 Кириллица");
        Path helper = directory.resolve("instance & folder").resolve("viewer.jar");
        var args = ResultViewer.command(home, "Windows 11", helper);
        assertEquals(home.resolve("bin/java.exe").toString(), args.getFirst());
        assertEquals("--stdin", args.getLast());
        assertEquals("viewer.jar", args.get(3));
        assertEquals(5, args.size());
    }
    @Test void macAndDarwinUseJavaAndDoNotAccidentallySelectWindowsExecutable() {
        for (String os : new String[]{"Mac OS X", "Darwin", "Linux"}) {
            var args = ResultViewer.command(directory, os, directory.resolve("viewer.jar"));
            assertEquals(directory.resolve("bin/java").toString(), args.getFirst());
            assertFalse(args.contains("-XstartOnFirstThread"));
        }
    }
    @Test void unicodeResultPathTravelsOverPipeAndLeavesCloseCommandIntact() throws Exception {
        Path result = directory.resolve("Другой инстанс & 中文 (тест)/spidicard.txt").toAbsolutePath();
        var bytes = new java.io.ByteArrayOutputStream();
        var writer = new java.io.DataOutputStream(bytes);
        writer.writeUTF(result.toString()); writer.write("close\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var pipe = new java.io.ByteArrayInputStream(bytes.toByteArray());
        assertEquals(result, net.spidicard.viewer.ResultsWindow.readResultPath(new String[]{"--stdin"}, pipe));
        assertEquals("close\n", new String(pipe.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void missingResultReportsFailureWithoutOpeningStaleFile() throws Exception {
        var failures = new ArrayBlockingQueue<Throwable>(1);
        new ResultViewer().reopen(directory.resolve("not-existing.txt"), failures::add);
        Throwable error = failures.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        assertTrue(error.getMessage().contains("missing"));
    }
}
