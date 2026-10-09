package net.spidicard.verification;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.spidicard.FullRun;
import net.spidicard.SpidiCardClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

/** Native client command and selected-range verification, excluded from the release. */
public final class FullFromProbe {
    private int step;
    private final long deadline = System.nanoTime() + 300_000_000_000L;
    private Process previous;
    private long readyCount;

    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(String name) throws Exception {
        var field = SpidiCardClient.class.getDeclaredField(name); field.setAccessible(true);
        return field.get(SpidiCardClient.INSTANCE);
    }
    private static FullRun full() throws Exception { return (FullRun) field("fullRun"); }
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
    private static void pass(Path report, String text) throws Exception {
        Files.writeString(report, "PASS: " + text + "\n", java.nio.file.StandardOpenOption.APPEND);
    }
    private static void resetEvidence() {
        ServerProbe.REQUESTS.clear(); ServerProbe.VISITED.clear(); ServerProbe.NAVIGATION.clear();
    }
    private static void range(Path game, int first) throws Exception {
        var expected = IntStream.rangeClosed(first, 56)
                .mapToObj(i -> String.format("CardG%02d - grief #%d", i, i)).toList();
        if (full().active() || full().completedGriefs() != expected.size()
                || !Files.readAllLines(game.resolve("spidicard.txt")).equals(expected))
            throw new AssertionError("Wrong saved selected-range results from " + first);
        if (!ServerProbe.VISITED.equals(IntStream.rangeClosed(first, 56).boxed().toList()))
            throw new AssertionError("Wrong selected server order: " + ServerProbe.VISITED);
        if (ServerProbe.NAVIGATION.stream().filter("hub"::equals).count() != expected.size())
            throw new AssertionError("Unexpected extra hub commands");
        if (ServerProbe.REQUESTS.size() != expected.size() * 3 || ServerProbe.REQUESTS.stream()
                .anyMatch(n -> n.startsWith("Live") || n.equalsIgnoreCase("SpidiBoost")))
            throw new AssertionError("Unexpected scan requests: " + ServerProbe.REQUESTS);
    }
    private void tick(MinecraftClient client) {
        if (step == 99) return;
        Path game = client.runDirectory.toPath(), report = game.resolve("full-from-results.txt");
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Full-from timeout at step " + step);
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            switch (step) {
                case 0 -> {
                    var method = SpidiCardClient.class.getDeclaredMethod("worldReady"); method.setAccessible(true);
                    if (!(boolean) method.invoke(SpidiCardClient.INSTANCE) || client.player.age < 40) return;
                    Files.writeString(report, "SpidiCard native full starting-grief verification\n");
                    Files.deleteIfExists(game.resolve("runtime-failure.txt")); readyCount = readyCount(game);
                    Files.writeString(game.resolve("spidicard.txt"), "PreviousSavedNick");
                    for (String bad : List.of("0", "57", "-1", "2147483647", "abc")) {
                        try { command(client, "spidicard full " + bad); throw new AssertionError("Invalid start accepted: " + bad); }
                        catch (CommandSyntaxException expected) { }
                    }
                    if (full() != null || !ServerProbe.NAVIGATION.isEmpty()
                            || !Files.readString(game.resolve("spidicard.txt")).equals("PreviousSavedNick"))
                        throw new AssertionError("Invalid command changed run or file");
                    pass(report, "invalid numbers rejected by real command dispatcher without requests or result changes");
                    command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                    command(client, "spidicard timeout 1000"); command(client, "spidicard full 3");
                    if (full().grief() != 3) throw new AssertionError("Start ignored number 3");
                    step = 1;
                }
                case 1 -> {
                    if (full().completedGriefs() == 0) return;
                    command(client, "spidicard stop");
                    if (!ServerProbe.VISITED.equals(List.of(3))
                            || !Files.readAllLines(game.resolve("spidicard.txt")).equals(List.of("CardG03 - grief #3")))
                        throw new AssertionError("Start 3 / stop saved wrong actual grief");
                    step = 2;
                }
                case 2 -> {
                    if (!ready(game)) return;
                    pass(report, "full 3 selects grief 3 first; stop preserves its exact result and opens viewer");
                    resetEvidence(); command(client, "spidicard full 32"); step = 3;
                }
                case 3 -> {
                    if (full().active() || !ready(game)) return;
                    range(game, 32);
                    if (ServerProbe.NAVIGATION.stream().filter("arrow:44"::equals).count() != 25)
                        throw new AssertionError("Wrong page crossing or arrow retry");
                    Files.writeString(game.resolve("full-from-32-saved.txt"), Files.readString(game.resolve("spidicard.txt")));
                    pass(report, "full 32 completes only 32..56, crosses both pages, retries arrow, writes exactly 25 lines and replaces viewer");
                    resetEvidence(); command(client, "spidicart full 56"); step = 4;
                }
                case 4 -> {
                    if (full().active() || !ready(game)) return;
                    range(game, 56);
                    if (ServerProbe.NAVIGATION.stream().filter("arrow:44"::equals).count() != 1)
                        throw new AssertionError("Direct page 2 selection failed");
                    pass(report, "alias full 56 selects page 2 directly, checks only grief 56, overwrites previous range and opens viewer");
                    Files.writeString(game.resolve("full-from-56-saved.txt"), Files.readString(game.resolve("spidicard.txt")));
                    Files.writeString(report, "ALL FULL-FROM CHECKS PASSED\n", java.nio.file.StandardOpenOption.APPEND);
                    step = 99; client.scheduleStop();
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(game.resolve("runtime-failure.txt"), error.toString()); } catch (Exception ignored) { }
            step = 99; client.scheduleStop();
        }
    }
}
