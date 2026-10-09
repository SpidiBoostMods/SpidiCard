package net.spidicard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Opens the result in a separate Java process, so desktop UI cannot block Minecraft. */
public final class ResultViewer {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SpidiCard-result-viewer"); thread.setDaemon(true); return thread;
    });
    private volatile Process previous;
    private AtomicBoolean previousClosing;

    public void reopen(Path file, Consumer<Throwable> onError) {
        Path absolute = file.toAbsolutePath().normalize();
        worker.execute(() -> {
            try {
                if (!Files.isRegularFile(absolute)) throw new IOException("Result file is missing: " + absolute);
                Path directory = absolute.getParent().resolve("config").resolve("spidicard");
                Files.createDirectories(directory);
                Path helper;
                try (var resource = ResultViewer.class.getResourceAsStream("/spidicard-result-viewer.jar")) {
                    if (resource == null) throw new IOException("Bundled result viewer is missing");
                    byte[] bytes = resource.readAllBytes();
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    helper = directory.resolve("result-viewer-" + hash.substring(0, 16) + ".jar");
                    if (!Files.exists(helper) || !java.util.Arrays.equals(Files.readAllBytes(helper), bytes)) {
                        Files.write(helper, bytes);
                    }
                }
                closePrevious();
                Path log = absolute.getParent().resolve("logs").resolve("spidicard-viewer.log");
                Files.createDirectories(log.getParent());
                Process current = new ProcessBuilder(command(Path.of(System.getProperty("java.home")),
                        System.getProperty("os.name"), helper, absolute))
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
                previous = current;
                AtomicBoolean closing = new AtomicBoolean();
                previousClosing = closing;
                current.onExit().thenAccept(done -> {
                    if (done.exitValue() != 0 && !closing.get()) onError.accept(new IOException("Result viewer exited with code "
                            + done.exitValue() + "; log: " + log));
                });
            } catch (Exception error) { onError.accept(error); }
        });
    }

    private void closePrevious() throws InterruptedException {
        if (previous == null || !previous.isAlive()) return;
        previousClosing.set(true);
        try {
            previous.getOutputStream().write("close\n".getBytes(StandardCharsets.UTF_8));
            previous.getOutputStream().flush();
        } catch (IOException ignored) { /* The user may already have closed the window. */ }
        if (!previous.waitFor(3, TimeUnit.SECONDS)) {
            previous.destroy();
            if (!previous.waitFor(2, TimeUnit.SECONDS)) previous.destroyForcibly().waitFor();
        }
        previous = null;
    }

    public static List<String> command(Path javaHome, String os, Path helper, Path file) {
        boolean windows = os.toLowerCase(Locale.ROOT).startsWith("windows");
        return List.of(javaHome.resolve("bin").resolve(windows ? "java.exe" : "java").toString(),
                "-Dapple.awt.application.name=SpidiCard", "-jar", helper.toAbsolutePath().normalize().toString(),
                file.toAbsolutePath().normalize().toString());
    }
}
