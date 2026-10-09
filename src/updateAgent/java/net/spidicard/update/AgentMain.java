package net.spidicard.update;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Runs outside the game; never attempts to replace a loaded Windows JAR. */
public final class AgentMain {
    public static void main(String[] args) {
        Path log = null;
        try {
            UpdatePlan plan = UpdatePlan.read(System.in);
            SafeInstall.validate(plan);
            Path directory = plan.gameDir().resolve(".spidicard");
            log = directory.resolve("update.log");
            try (var channel = FileChannel.open(directory.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("An update is already pending");
                ProcessHandle parent = ProcessHandle.of(plan.parentPid()).orElseThrow();
                System.out.println("READY"); System.out.flush();
                // Also supports waiting for ordinary user shutdown when a launcher cannot be replayed.
                parent.onExit().get(7, TimeUnit.DAYS);
                SafeInstall.install(plan);
                Files.writeString(log, "SpidiBoost: update installed successfully.\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (!plan.command().isEmpty()) {
                    // Give the launcher time to observe the child's exit before its next --launch.
                    Thread.sleep(1500);
                    new ProcessBuilder(plan.command()).directory(plan.workingDir().toFile())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                }
            }
        } catch (Exception e) {
            // Messages/argv can contain credentials; log only exception type and a fixed diagnostic.
            String detail = "SpidiBoost: update or restart failed (" + e.getClass().getSimpleName()
                    + "). Original JAR or backup is preserved.\n";
            if (log != null) try { Files.writeString(log, detail, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception ignored) {}
            System.out.println("ERROR"); System.out.flush();
            System.exit(1);
        }
    }
}
