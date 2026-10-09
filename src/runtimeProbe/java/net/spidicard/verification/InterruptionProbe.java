package net.spidicard.verification;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.spidicard.FullRun;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Isolated native packet tests for partial-result windows, by SpidiBoost. */
public final class InterruptionProbe {
    private final boolean startKick;
    private int step;
    private long deadline = System.nanoTime() + 180_000_000_000L, nextStart, readyCount;
    private Process previous;
    private List<String> expected = List.of();
    private boolean completeAtStop;
    private boolean waitingForViewer;

    public InterruptionProbe(boolean startKick) { this.startKick = startKick; }
    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(String name) throws Exception {
        var f = SpidiCardClient.class.getDeclaredField(name); f.setAccessible(true);
        return f.get(SpidiCardClient.INSTANCE);
    }
    private static ScanSession scan() throws Exception { return (ScanSession) field("session"); }
    private static FullRun full() throws Exception { return (FullRun) field("fullRun"); }
    private static boolean worldReady() throws Exception {
        var method = SpidiCardClient.class.getDeclaredMethod("worldReady"); method.setAccessible(true);
        return (boolean) method.invoke(SpidiCardClient.INSTANCE);
    }
    private static Process viewer() throws Exception {
        Object object = field("resultViewer");
        var f = object.getClass().getDeclaredField("previous"); f.setAccessible(true);
        return (Process) f.get(object);
    }
    private static long countReady(Path output) throws Exception {
        Path log = output.resolve("logs/spidicard-viewer.log");
        return Files.exists(log) ? Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() : 0;
    }
    private boolean viewerReady(Path output) throws Exception {
        Process current = viewer(); long count = countReady(output);
        if (current == null || current == previous || !current.isAlive() || count <= readyCount) return false;
        if (previous != null && previous.isAlive()) throw new AssertionError("Previous viewer did not close");
        readyCount = count; previous = current; return true;
    }
    private static void command(MinecraftClient client, String text) throws Exception {
        ClientCommandManager.getActiveDispatcher().execute(text,
                (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
    }
    private static void closeInventory(MinecraftClient client) {
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) client.player.closeHandledScreen();
    }
    private Path report(Path output) { return output.resolve(startKick ? "start-kick-results.txt" : "interruptions-results.txt"); }
    private void pass(Path output, String text) throws Exception {
        Files.writeString(report(output), "PASS: " + text + "\n", StandardOpenOption.APPEND);
    }
    private static void assertOrdinaryFile(Path output, List<String> names) throws Exception {
        if (!Files.readString(output.resolve("spidicard.txt")).equals(String.join(" ", names)))
            throw new AssertionError("Ordinary interruption lost found nicknames");
    }
    private List<String> fullSnapshot() throws Exception {
        var lines = new ArrayList<>(full().lines());
        lines.add(FullRun.format(full().grief(), scan().matches()));
        return List.copyOf(lines);
    }
    private void assertFullFile(Path output) throws Exception {
        if (full().active() || !Files.readAllLines(output.resolve("spidicard.txt")).equals(expected))
            throw new AssertionError("Full interruption lost completed or current grief results");
    }

    private void tick(MinecraftClient client) {
        if (step == 99) return;
        Path output = client.runDirectory.toPath();
        try {
            MemoryLifecycleProbe.assertReleased();
            if (System.nanoTime() > deadline) throw new AssertionError("Interruption verification timed out at step " + step);
            // Check the final window even after kick clears player, world and play handler.
            if (step == 8 || step == 9) {
                if (client.getNetworkHandler() != null) return;
                if (step == 8) {
                    if (full().active()) return;
                    assertFullFile(output);
                }
                else {
                    if (scan().active()) return;
                    if (!scan().matches().containsAll(expected))
                        throw new AssertionError("Ordinary kick lost previously detected players");
                    assertOrdinaryFile(output, scan().matches());
                }
                if (!viewerReady(output)) return;
                pass(output, step == 8 ? "server kick during full opens fresh window with completed and current griefs"
                        : "server kick during ordinary start opens fresh window with detected nicknames");
                Files.writeString(report(output), "ALL INTERRUPTION CHECKS PASSED\n", StandardOpenOption.APPEND);
                step = 99; client.scheduleStop(); return;
            }
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            switch (step) {
                case 0 -> {
                    if (!client.player.isLoaded() || client.player.age < 40
                            || client.getNetworkHandler().getListedPlayerListEntries().size() != 302 || !worldReady()) return;
                    Files.writeString(report(output), "SpidiCard " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("spidicard").orElseThrow().getMetadata().getVersion().getFriendlyString() + " native interruption verification\n");
                    previous = viewer(); readyCount = countReady(output);
                    command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                    command(client, "spidicard timeout 1000"); command(client, "spidicard start"); step = 1;
                }
                case 1 -> {
                    if (scan().matches().size() < 2) return;
                    expected = scan().matches();
                    if (scan().processed() == scan().total()) throw new AssertionError("Test scan completed too early");
                    if (startKick) {
                        client.getNetworkHandler().sendChatCommand("qa_kick"); step = 9;
                    } else {
                        command(client, "spidicard stop");
                        if (scan().active()) throw new AssertionError("Stop did not end ordinary scan");
                        waitingForViewer = true;
                        nextStart = System.nanoTime() + 1_500_000_000L; step = 2;
                    }
                }
                case 2 -> {
                    assertOrdinaryFile(output, expected);
                    if (waitingForViewer) {
                        if (!viewerReady(output)) return;
                        waitingForViewer = false;
                    }
                    if (System.nanoTime() < nextStart) return;
                    if (!worldReady()) return;
                    pass(output, "ordinary /stop preserves found nicknames and opens fresh window before scan completion");
                    closeInventory(client);
                    ServerProbe.SILENT_REQUEST = ServerProbe.REQUESTS.size() + 5;
                    command(client, "spidicard start"); step = 3;
                }
                case 3 -> {
                    if (scan().active()) return;
                    if (scan().failure() != ScanSession.Failure.TIMEOUT || scan().matches().size() < 2)
                        throw new AssertionError("Timeout test did not retain earlier matches");
                    assertOrdinaryFile(output, scan().matches());
                    if (!viewerReady(output)) return;
                    pass(output, "ordinary invsee timeout preserves earlier matches and opens fresh window");
                    ServerProbe.SILENT_REQUEST = -1;
                    nextStart = System.nanoTime() + 1_500_000_000L; step = 4;
                }
                case 4 -> {
                    if (System.nanoTime() < nextStart) return;
                    closeInventory(client); command(client, "spidicard full"); step = 5;
                }
                case 5 -> {
                    if (viewer() != previous) throw new AssertionError("Intermediate full stage reopened viewer");
                    if (full().completedGriefs() != 2 || full().phase() != FullRun.Phase.SCANNING
                            || scan().matches().isEmpty()) return;
                    expected = fullSnapshot(); completeAtStop = full().completedGriefs() == 56;
                    command(client, "spidicard stop"); assertFullFile(output);
                    waitingForViewer = true;
                    nextStart = System.nanoTime() + 1_500_000_000L; step = 6;
                }
                case 6 -> {
                    assertFullFile(output);
                    if (waitingForViewer) {
                        if (!viewerReady(output)) return;
                        waitingForViewer = false;
                    }
                    if (System.nanoTime() < nextStart) return;
                    if (completeAtStop || expected.size() != 3) throw new AssertionError("Full stop was not partial");
                    pass(output, "full /stop preserves griefs 1/2 and current grief 3 matches and opens fresh window");
                    closeInventory(client); command(client, "spidicard full"); step = 7;
                }
                case 7 -> {
                    if (viewer() != previous) throw new AssertionError("Intermediate full stage reopened viewer");
                    if (full().completedGriefs() != 2 || full().phase() != FullRun.Phase.SCANNING
                            || scan().matches().isEmpty()) return;
                    expected = fullSnapshot(); client.getNetworkHandler().sendChatCommand("qa_kick"); step = 8;
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(output.resolve("runtime-failure.txt"), error.toString() + "\n"); }
            catch (Exception ignored) {}
            step = 99; client.scheduleStop();
        }
    }
}
