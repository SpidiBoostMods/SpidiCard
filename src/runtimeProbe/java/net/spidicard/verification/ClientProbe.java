package net.spidicard.verification;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;
import net.spidicard.FullRun;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

public final class ClientProbe implements ClientModInitializer {
    private int step;
    private int ticks;
    private boolean testedKeyboard;
    private long started;
    private long completedAt;
    private Process firstViewer, secondViewer;
    private long viewerReadyCount;
    private int configurationTicks;

    private Process viewerProcess() throws Exception {
        var field = SpidiCardClient.class.getDeclaredField("resultViewer"); field.setAccessible(true);
        Object viewer = field.get(SpidiCardClient.INSTANCE);
        var process = viewer.getClass().getDeclaredField("previous"); process.setAccessible(true);
        return (Process) process.get(viewer);
    }
    private long readyCount(Path output) throws Exception {
        Path log = output.resolve("logs/spidicard-viewer.log");
        return Files.exists(log) ? Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() : 0;
    }
    private boolean newViewerReady(Path output, Process previous) throws Exception {
        Process current = viewerProcess();
        long count = readyCount(output);
        if (current == null || current == previous || !current.isAlive() || count <= viewerReadyCount) return false;
        if (previous != null && previous.isAlive()) throw new AssertionError("Previous result viewer still alive");
        viewerReadyCount = count;
        return true;
    }

    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("spidicard.qa.family")) {
            new FamilyProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.updater")) {
            new UpdaterProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.speedSafety")) {
            new SpeedSafetyProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.style")) {
            new ChatStyleProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.selection")) {
            new GriefSelectionProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.fullFrom")) {
            new FullFromProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.configKick")) {
            new ConfigurationKickProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.menu")) {
            new MenuProbe().register();
        } else if (Boolean.getBoolean("spidicard.qa.interruptions") || Boolean.getBoolean("spidicard.qa.startKick")) {
            new InterruptionProbe(Boolean.getBoolean("spidicard.qa.startKick")).register();
        } else ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void command(MinecraftClient client, String text) throws Exception {
        ClientCommandManager.getActiveDispatcher().execute(text,
                (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
    }

    private ScanSession session() throws Exception {
        var field = SpidiCardClient.class.getDeclaredField("session");
        field.setAccessible(true);
        return (ScanSession) field.get(SpidiCardClient.INSTANCE);
    }

    private FullRun full() throws Exception {
        var field = SpidiCardClient.class.getDeclaredField("fullRun");
        field.setAccessible(true);
        return (FullRun) field.get(SpidiCardClient.INSTANCE);
    }

    private void tick(MinecraftClient client) {
        if (step == 99) return;
        try {
            MemoryLifecycleProbe.assertReleased();
            if (++ticks > 12000) throw new AssertionError("Integration timeout");
            if (Boolean.getBoolean("spidicard.qa.loading") && full() != null
                    && client.getNetworkHandler() == null) {
                if (!full().active()) throw new AssertionError("Full cancelled during configuration");
                configurationTicks++;
                if (configurationTicks > 600) throw new AssertionError("Configuration fixture exceeded 30 seconds: "
                        + ServerProbe.CONFIGURATION_COUNT + " tasks; " + ServerProbe.NAVIGATION);
            }
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            Path output = client.runDirectory.toPath();
            switch (step) {
                case 0 -> {
                    if (client.getNetworkHandler().getListedPlayerListEntries().size() != 302) return;
                    if (!client.player.isLoaded() || client.player.age < 40) return;
                    if (Boolean.getBoolean("spidicard.qa.benchmark") && client.player.age < 160) return;
                    var ready = SpidiCardClient.class.getDeclaredMethod("worldReady"); ready.setAccessible(true);
                    if (!(boolean) ready.invoke(SpidiCardClient.INSTANCE)) return;
                    MixinEnvironment.getCurrentEnvironment().audit();
                    if (Boolean.getBoolean("spidicard.qa.benchmark"))
                        client.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.MINIMIZED);
                    viewerReadyCount = readyCount(output);
                    if (Boolean.getBoolean("spidicard.qa.loading")) {
                        command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                        command(client, "spidicard timeout 1000"); command(client, "spidicard full");
                        step = 20; return;
                    }
                    if (Boolean.getBoolean("spidicard.qa.late")) {
                        command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                        command(client, "spidicard timeout 1000"); command(client, "spidicard full");
                        step = 30; return;
                    }
                    if (Boolean.getBoolean("spidicard.qa.followup")) {
                        if (!Files.readString(output.resolve("runtime-results.txt")).contains("300 unique real /invsee"))
                            throw new AssertionError("Missing successful first full scan evidence");
                        command(client, "spidicard count 1000");
                        client.getNetworkHandler().sendChatCommand("qa_small_tab");
                        step = 10;
                        return;
                    }
                    Files.writeString(output.resolve("spidicard.txt"), "OldNickMustDisappear");
                    Files.deleteIfExists(output.resolve("runtime-failure.txt"));
                    Files.deleteIfExists(output.resolve("runtime-results.txt"));
                    command(client, "spidicart count 3");
                    command(client, "spidicard delay 0");
                    command(client, "spidicard timeout 5000");
                    command(client, "spidicard start");
                    started = System.nanoTime();
                    step = 1;
                }
                case 1 -> {
                    if (client.currentScreen instanceof HandledScreen<?> screen && !testedKeyboard) {
                        screen.keyPressed(81, 0, 0); // Q must not drop anything or close the scan's window.
                        if (client.currentScreen != screen) throw new AssertionError("Hotkey closed owned screen");
                        testedKeyboard = true;
                    }
                    if (session().active()) return;
                    if (completedAt == 0) completedAt = System.nanoTime();
                    var expected = IntStream.range(0, 299).filter(i -> i % 2 == 0)
                            .mapToObj(i -> String.format("QAPlayer%03d", i)).toList();
                    if (session().checked() != 300) throw new AssertionError("Incomplete scan: " + session().checked());
                    if (!session().matches().equals(expected)) throw new AssertionError("Wrong candidates " + session().matches());
                    if (ServerProbe.REQUESTS.size() != 300 || new HashSet<>(ServerProbe.REQUESTS).size() != 300)
                        throw new AssertionError("Duplicate/missing server commands");
                    if (!Files.readString(output.resolve("spidicard.txt")).equals(String.join(" ", expected)))
                        throw new AssertionError("Wrong final file");
                    if (!testedKeyboard) throw new AssertionError("Owned screen key blocker not exercised");
                    if (!newViewerReady(output, null)) return;
                    firstViewer = viewerProcess();
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: Fabric 1.21.4 client loaded standalone SpidiCard + Fabric API\n"
                            + "PASS: mixin audit and real open/full inventory packets\n"
                            + "PASS: alias /spidicart count 3; own 64 green cards excluded\n"
                            + "PASS: current server profile LiveCardQA and SpidiBoost excluded; launch username CardQA checked as a different player\n"
                            + "PASS: 300 unique real /invsee commands; 150 exact mixed-color matches\n"
                            + "PASS: existing file overwritten to one line without OldNickMustDisappear\n"
                            + "PASS: Q hotkey blocked during scan\n"
                            + "Elapsed seconds: " + ((completedAt - started) / 1_000_000_000.0) + "\n"
                            + session().timings(System.nanoTime() / 1_000_000).summary() + "\n");
                    if (Boolean.getBoolean("spidicard.qa.benchmark")) {
                        step = 99; client.scheduleStop(); return;
                    }
                    command(client, "spidicard count 1000");
                    ServerProbe.REQUESTS.clear();
                    client.getNetworkHandler().sendChatCommand("qa_small_tab");
                    step = 10;
                }
                case 10 -> {
                    if (client.getNetworkHandler().getListedPlayerListEntries().size() != 2) return;
                    command(client, "spidicard start");
                    step = 2;
                }
                case 2 -> {
                    if (session().active()) return;
                    if (session().checked() != 2 || !Files.readString(output.resolve("spidicard.txt")).isEmpty())
                        throw new AssertionError("Second run did not reset memory/file");
                    if (!newViewerReady(output, firstViewer)) return;
                    secondViewer = viewerProcess();
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: second full scan resets memory and overwrites same file to empty\n",
                            java.nio.file.StandardOpenOption.APPEND);
                    command(client, "spidicard count 1");
                    command(client, "spidicard start");
                    step = 3;
                }
                case 3 -> {
                    if (!(client.currentScreen instanceof HandledScreen<?>)) return;
                    var screen = client.currentScreen;
                    screen.keyPressed(256, 0, 0);
                    if (session().active() || client.currentScreen instanceof HandledScreen<?>)
                        throw new AssertionError("Escape did not stop and close inventory");
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: Escape stops scan and closes owned inventory\n",
                            java.nio.file.StandardOpenOption.APPEND);
                    step = 4;
                }
                case 4 -> {
                    if (!newViewerReady(output, secondViewer)) return;
                    secondViewer = viewerProcess();
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: Escape opens a fresh viewer with saved partial result\n",
                            java.nio.file.StandardOpenOption.APPEND);
                    if (Boolean.getBoolean("spidicard.qa.full")) {
                        ServerProbe.REQUESTS.clear();
                        ServerProbe.VISITED.clear(); ServerProbe.NAVIGATION.clear();
                        command(client, "spidicard count 3");
                        command(client, "spidicard timeout 1000");
                        command(client, "spidicard full");
                        step = 20;
                    } else {
                        step = 99;
                        client.scheduleStop();
                    }
                }
                case 20 -> {
                    if (full().active()) {
                        if (viewerProcess() != secondViewer) throw new AssertionError("Intermediate grief reopened viewer");
                        return;
                    }
                    if (full().completedGriefs() != 56) throw new AssertionError("Full run stopped at " + full().grief());
                    if (!newViewerReady(output, secondViewer)) return;
                    var expected = IntStream.rangeClosed(1, 56)
                            .mapToObj(i -> String.format("CardG%02d - grief #%d", i, i)).toList();
                    if (!Files.readAllLines(output.resolve("spidicard.txt")).equals(expected))
                        throw new AssertionError("Wrong 56-line full result");
                    if (!ServerProbe.VISITED.equals(IntStream.rangeClosed(1, 56).boxed().toList()))
                        throw new AssertionError("Wrong server order " + ServerProbe.VISITED);
                    if (ServerProbe.NAVIGATION.stream().filter("hub"::equals).count() != 56)
                        throw new AssertionError("Scan retry unexpectedly returned to hub");
                    boolean loading = Boolean.getBoolean("spidicard.qa.loading");
                    if (ServerProbe.NAVIGATION.stream().filter("arrow:44"::equals).count() != (loading ? 26 : 25))
                        throw new AssertionError("Wrong arrow retry/page count");
                    if (ServerProbe.NAVIGATION.stream().filter("grief:1:0"::equals).count() != 3)
                        throw new AssertionError("Missing repeat clicks while transfer menu stayed open");
                    if (ServerProbe.REQUESTS.size() != 169 || ServerProbe.REQUESTS.stream()
                            .anyMatch(name -> name.startsWith("Live") || name.equalsIgnoreCase("SpidiBoost")))
                        throw new AssertionError("Wrong invsee count or exclusions: " + ServerProbe.REQUESTS.size());
                    Files.writeString(output.resolve("full-results.txt"), Files.readString(output.resolve("spidicard.txt")));
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: full native 56-grief run in exact order, both pages and all specified slots\n"
                            + "PASS: root slot 21, next-page slot 44 (including ignored first arrow click)\n"
                            + "PASS: target slot repeated until closure; first grief required 3 clicks\n"
                            + "PASS: /invsee warmup timeout restarts scan on same grief, with exactly 56 /hub commands\n"
                            + "PASS: unavailable individual player skipped on every grief\n"
                            + "PASS: changed live nickname excluded on every grief; SpidiBoost never queried\n"
                            + "PASS: every even grief loads without scoreboard; 56 exact result lines\n"
                            + (loading ? "" : "PASS: start opens native result viewer; next start closes old viewer and opens empty result\n")
                            + "PASS: full completion opens viewer; intermediate griefs do not reopen it\n"
                            + "ALL INTEGRATION CHECKS PASSED\n", java.nio.file.StandardOpenOption.APPEND);
                    if (loading) {
                        if (ServerProbe.CONFIGURATION_COUNT != 2 || configurationTicks < 50)
                            throw new AssertionError("Real configuration gaps were not exercised: " + configurationTicks);
                        for (String marker : List.of("root-delayed", "mode-dropped", "page-dropped", "transfer-dropped", "tab-delayed"))
                            if (!ServerProbe.NAVIGATION.contains(marker)) throw new AssertionError("Missing loading scenario " + marker);
                        Files.writeString(output.resolve("runtime-results.txt"),
                                "PASS: real play/configuration/play transitions for hub (5s) and grief (6s), with null play handler for "
                                        + configurationTicks + " client ticks; full never cancelled\n"
                                + "PASS: delayed root contents, missing grief menu, missing page 2, rejected transfer with closed menu, delayed tab\n"
                                + "ALL REAL LOADING CHECKS PASSED\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                    step = 99; client.scheduleStop();
                }
                case 30 -> {
                    if (!full().active()) throw new AssertionError("Late-reply followup stopped before completing grief 1");
                    if (full().completedGriefs() < 1) return;
                    command(client, "spidicard stop");
                    if (full().active() || !ServerProbe.LATE_SENT) throw new AssertionError("Late packet/stop was not exercised");
                    if (!Files.readAllLines(output.resolve("spidicard.txt")).equals(List.of("CardG01 - grief #1")))
                        throw new AssertionError("Late-reply followup saved wrong partial full output");
                    Files.writeString(output.resolve("runtime-results.txt"),
                            "PASS: delayed /invsee window after timeout drained; same-grief retry succeeds\n"
                            + "PASS: /spidicard stop during full navigation cancels future work and preserves completed grief line\n"
                            + "FINAL FOLLOWUP CHECKS PASSED\n", java.nio.file.StandardOpenOption.APPEND);
                    step = 99; client.scheduleStop();
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(client.runDirectory.toPath().resolve("runtime-failure.txt"), error.toString()); }
            catch (Exception ignored) {}
            step = 99;
            client.scheduleStop();
        }
    }
}
