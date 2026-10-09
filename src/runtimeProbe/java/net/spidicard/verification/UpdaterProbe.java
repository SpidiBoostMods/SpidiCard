package net.spidicard.verification;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.spidicard.update.ModUpdater;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises real client-start and integrated-server JOIN hooks without remote downloads. */
final class UpdaterProbe {
    private final AtomicInteger checks = new AtomicInteger();
    private int step, ticks;
    void register() {
        ModUpdater.registerChecks(checks::incrementAndGet);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    void tick(MinecraftClient client) {
        if (step == 99) return;
        var directory = client.runDirectory.toPath();
        try {
            if (++ticks > 2400) throw new AssertionError("Updater integration timeout");
            if (step == 0 && client.world != null && client.player != null && client.player.age > 40 && client.currentScreen == null) {
                if (checks.get() != 2) throw new AssertionError("Expected startup + local-server JOIN checks: " + checks);
                var constructor = Class.forName("net.spidicard.update.UpdateScreen").getDeclaredConstructor(String.class, MinecraftClient.class);
                constructor.setAccessible(true);
                Screen screen = (Screen) constructor.newInstance("1.4.1", client);
                client.setScreen(screen);
                if (screen.shouldCloseOnEsc()) throw new AssertionError("Update screen can be accidentally dismissed");
                ticks = 0; step = 1;
            } else if (step == 1 && ticks > 20) {
                try (var screenshot = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) { screenshot.writeTo(directory.resolve("updater-screen.png")); }
                Files.writeString(directory.resolve("updater-results.txt"), "PASS actual CLIENT_STARTED event\nPASS actual integrated-server JOIN event\nPASS update screen rendered without runtime errors\nALL UPDATER CLIENT CHECKS PASSED\n");
                step = 99; client.scheduleStop();
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(directory.resolve("updater-results.txt"), "FAIL " + error); } catch (Exception ignored) {}
            step = 99; client.scheduleStop();
        }
    }
}
