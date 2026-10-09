package net.spidicard.verification;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Isolated native command/viewer verification; never included in the release. */
public final class MenuProbe {
    private int step;
    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private Process previous;
    private long readyCount;
    private ScanSession completed;
    private FileTime savedTime;

    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(String name) throws Exception {
        var field = SpidiCardClient.class.getDeclaredField(name); field.setAccessible(true);
        return field.get(SpidiCardClient.INSTANCE);
    }
    private static Process process() throws Exception {
        Object viewer = field("resultViewer");
        var field = viewer.getClass().getDeclaredField("previous"); field.setAccessible(true);
        return (Process) field.get(viewer);
    }
    private static int command(MinecraftClient client, String command) throws Exception {
        return ClientCommandManager.getActiveDispatcher().execute(command,
                (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
    }
    private static long readyCount(Path game) throws Exception {
        Path log = game.resolve("logs/spidicard-viewer.log");
        return Files.exists(log) ? Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() : 0;
    }
    private boolean ready(Path game) throws Exception {
        Process current = process(); long count = readyCount(game);
        if (current == null || current == previous || !current.isAlive() || count <= readyCount) return false;
        if (previous != null && previous.isAlive()) throw new AssertionError("Old viewer still open");
        readyCount = count; previous = current; return true;
    }
    private void closeWindow() throws Exception {
        previous.getOutputStream().write("close\n".getBytes(StandardCharsets.UTF_8));
        previous.getOutputStream().flush();
    }
    private void unchanged(Path result, String expected, int requests) throws Exception {
        if (!Files.readString(result).equals(expected) || !Files.getLastModifiedTime(result).equals(savedTime))
            throw new AssertionError("Menu changed the saved results");
        if (ServerProbe.REQUESTS.size() != requests) throw new AssertionError("Menu sent new server requests");
    }
    private static void pass(Path report, String message) throws Exception {
        Files.writeString(report, "PASS: " + message + "\n", java.nio.file.StandardOpenOption.APPEND);
    }
    private void tick(MinecraftClient client) {
        if (step == 99) return;
        Path game = client.runDirectory.toPath(), result = game.resolve("spidicard.txt"), report = game.resolve("menu-results.txt");
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Menu verification timeout at step " + step);
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            switch (step) {
                case 0 -> {
                    var method = SpidiCardClient.class.getDeclaredMethod("worldReady"); method.setAccessible(true);
                    if (!(boolean) method.invoke(SpidiCardClient.INSTANCE) || client.player.age < 40) return;
                    Files.writeString(report, "SpidiCard native menu command verification\n");
                    Files.deleteIfExists(result); readyCount = readyCount(game);
                    if (command(client, "spidicard menu") != 0 || Files.exists(result) || process() != null)
                        throw new AssertionError("Missing results should show feedback without creating a file/window");
                    pass(report, "missing result file is handled without a new scan or stale viewer");
                    Files.writeString(result, "SavedNick - grief #56"); savedTime = Files.getLastModifiedTime(result);
                    if (field("session") != null || command(client, "spidicard menu") != 1)
                        throw new AssertionError("Saved results should open without a session in memory");
                    step = 1;
                }
                case 1 -> {
                    if (!ready(game)) return;
                    unchanged(result, "SavedNick - grief #56", 0);
                    pass(report, "menu opens previously saved full-format result without an in-memory scan");
                    closeWindow(); step = 2;
                }
                case 2 -> {
                    if (previous.isAlive()) return;
                    command(client, "spidicart menu"); step = 3;
                }
                case 3 -> {
                    if (!ready(game)) return;
                    unchanged(result, "SavedNick - grief #56", 0);
                    pass(report, "closed viewer reopens through the alias; file and server requests unchanged");
                    command(client, "spidicard menu"); step = 4;
                }
                case 4 -> {
                    if (!ready(game)) return;
                    unchanged(result, "SavedNick - grief #56", 0);
                    pass(report, "repeated menu closes its old viewer and opens one fresh viewer");
                    Files.writeString(result, ""); savedTime = Files.getLastModifiedTime(result);
                    command(client, "spidicard menu"); step = 5;
                }
                case 5 -> {
                    if (!ready(game)) return;
                    unchanged(result, "", 0); pass(report, "empty saved result opens without a new scan");
                    client.getNetworkHandler().sendChatCommand("qa_small_tab"); step = 6;
                }
                case 6 -> {
                    if (client.getNetworkHandler().getListedPlayerListEntries().size() != 2) return;
                    command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                    if (command(client, "spidicard start") != 1) throw new AssertionError("Start rejected");
                    step = 7;
                }
                case 7 -> {
                    ScanSession scan = (ScanSession) field("session");
                    if (scan.active() || !ready(game)) return;
                    if (!scan.matches().equals(List.of("QAPlayer000")) || scan.checked() != 2)
                        throw new AssertionError("Ordinary scan result incorrect");
                    completed = scan; savedTime = Files.getLastModifiedTime(result);
                    unchanged(result, "QAPlayer000", 2); closeWindow(); step = 8;
                }
                case 8 -> {
                    if (previous.isAlive()) return;
                    command(client, "spidicard menu"); step = 9;
                }
                case 9 -> {
                    if (!ready(game)) return;
                    unchanged(result, "QAPlayer000", 2);
                    if (field("session") != completed || completed.active()) throw new AssertionError("Menu restarted scan");
                    pass(report, "after real start finishes and viewer closes, menu reopens its exact saved nicknames");
                    Files.writeString(report, "ALL MENU CHECKS PASSED\n", java.nio.file.StandardOpenOption.APPEND);
                    step = 99; client.scheduleStop();
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(game.resolve("runtime-failure.txt"), error.toString()); } catch (Exception ignored) {}
            step = 99; client.scheduleStop();
        }
    }
}
