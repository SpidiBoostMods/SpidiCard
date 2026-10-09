package net.spidicard.verification;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.spidicard.CardNames;
import net.spidicard.ScanSession;
import net.spidicard.SpidiCardClient;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;

/** Real packet updates and inventory GUI interruption checks; excluded from the release JAR. */
public final class SpeedSafetyProbe {
    private static final List<String> TARGETS = List.of("SpeedAdd", "SpeedNoncard", "SpeedRemove", "SpeedRepeat");
    private static final List<String> MATCHES = List.of("SpeedAdd", "SpeedNoncard", "SpeedRepeat");

    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private int step;
    private boolean observedOwnedInventory;
    private boolean testedQ;
    private long readyBefore;
    private Process previousViewer;
    private List<String> partial = List.of();

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static Object field(String name) throws Exception {
        var field = SpidiCardClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(SpidiCardClient.INSTANCE);
    }

    private static ScanSession scan() throws Exception {
        return (ScanSession) field("session");
    }

    private static Process viewer() throws Exception {
        Object object = field("resultViewer");
        var field = object.getClass().getDeclaredField("previous");
        field.setAccessible(true);
        return (Process) field.get(object);
    }

    private static boolean worldReady() throws Exception {
        var method = SpidiCardClient.class.getDeclaredMethod("worldReady");
        method.setAccessible(true);
        return (boolean) method.invoke(SpidiCardClient.INSTANCE);
    }

    private static int command(MinecraftClient client, String command) throws Exception {
        return ClientCommandManager.getActiveDispatcher().execute(command,
                (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
    }

    private static long readyCount(Path game) throws Exception {
        Path log = game.resolve("logs/spidicard-viewer.log");
        return Files.exists(log)
                ? Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() : 0;
    }

    private boolean newViewerReady(Path game) throws Exception {
        Process current = viewer();
        long count = readyCount(game);
        if (current == null || current == previousViewer || !current.isAlive() || count <= readyBefore) return false;
        if (previousViewer != null && previousViewer.isAlive()) throw new AssertionError("Previous viewer stayed open");
        readyBefore = count;
        previousViewer = current;
        return true;
    }

    private static void pass(Path report, String message) throws Exception {
        Files.writeString(report, "PASS: " + message + "\n", StandardOpenOption.APPEND);
    }

    private static void assertOwnCards(MinecraftClient client) {
        var own = client.player.getInventory().getStack(0);
        if (own.getCount() != 64 || !CardNames.isCard(own.getName().getString()))
            throw new AssertionError("Fixture own stack changed or was dropped");
    }

    private void tick(MinecraftClient client) {
        if (step == 99) return;
        Path game = client.runDirectory.toPath();
        Path result = game.resolve("spidicard.txt");
        Path report = game.resolve("speed-safety-results.txt");
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Speed safety timeout at step " + step);
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;
            if ((step == 2 || step == 3) && scan() != null && scan().active()) {
                if (client.currentScreen instanceof HandledScreen<?> screen
                        && scan().owns(screen.getScreenHandler().syncId)) observedOwnedInventory = true;
            }
            switch (step) {
                case 0 -> {
                    if (client.player.age < 40 || !client.player.isLoaded() || !worldReady()) return;
                    MixinEnvironment.getCurrentEnvironment().audit();
                    Files.writeString(report, "SpidiCard native scan speed safety verification\n");
                    Files.writeString(result, "OldNickMustDisappear");
                    previousViewer = viewer();
                    readyBefore = readyCount(game);
                    client.getNetworkHandler().sendChatCommand("qa_speed_tab");
                    step = 1;
                }
                case 1 -> {
                    var names = client.getNetworkHandler().getListedPlayerListEntries().stream()
                            .map(entry -> entry.getProfile().getName()).toList();
                    if (names.size() != 6 || !names.containsAll(TARGETS) || !names.contains("SpidiBoost")) return;
                    assertOwnCards(client);
                    ServerProbe.REQUESTS.clear();
                    command(client, "spidicard count 3");
                    command(client, "spidicard delay 0");
                    command(client, "spidicard timeout 1000");
                    if (command(client, "spidicard start") != 1) throw new AssertionError("First start rejected");
                    step = 2;
                }
                case 2 -> {
                    ScanSession scan = scan();
                    if (scan.active()) return;
                    if (scan.failure() != ScanSession.Failure.NONE || scan.checked() != 4
                            || scan.processed() != 4 || !scan.matches().equals(MATCHES))
                        throw new AssertionError("Wrong staged scan results: " + scan.matches() + ", " + scan.failure());
                    var requests = List.copyOf(ServerProbe.REQUESTS);
                    if (!requests.equals(TARGETS) || new HashSet<>(requests).size() != 4)
                        throw new AssertionError("Duplicate, missing, or excluded-player invsee: " + requests);
                    if (ServerProbe.SPEED_WINDOW_REPLACED_EARLY)
                        throw new AssertionError("A staged fill was cancelled by early inventory replacement");
                    if (!observedOwnedInventory) throw new AssertionError("Owned inventory GUI was not observed");
                    if (!Files.readString(result).equals(String.join(" ", MATCHES)))
                        throw new AssertionError("Final file does not contain exact staged matches");
                    assertOwnCards(client);
                    if (!newViewerReady(game)) return;
                    pass(report, "real delayed add, delayed removal, noncard-then-card updates and repeated full snapshot preserve exact results");
                    pass(report, "4 unique invsee commands, own nickname and SpidiBoost excluded, own 64 cards ignored");
                    pass(report, "all staged updates finish before their inventory is replaced; owned inventory GUI observed");
                    Files.writeString(game.resolve("speed-safety-saved-results.txt"), Files.readString(result));
                    ServerProbe.REQUESTS.clear();
                    if (command(client, "spidicard start") != 1) throw new AssertionError("Second start rejected");
                    step = 3;
                }
                case 3 -> {
                    ScanSession scan = scan();
                    if (!scan.active()) throw new AssertionError("Second scan finished before Escape interruption");
                    if (!(client.currentScreen instanceof HandledScreen<?> screen)
                            || !scan.owns(screen.getScreenHandler().syncId)) return;
                    if (!testedQ) {
                        if (!screen.keyPressed(81, 0, 0) || client.currentScreen != screen || !scan.active())
                            throw new AssertionError("Q did not stay safely consumed by the owned inventory GUI");
                        assertOwnCards(client);
                        testedQ = true;
                    }
                    if (scan.matches().isEmpty()) return;
                    if (scan.processed() == scan.total()) throw new AssertionError("Interruption did not happen before completion");
                    partial = scan.matches();
                    if (!screen.keyPressed(256, 0, 0) || scan.active()
                            || scan.failure() != ScanSession.Failure.USER_STOP)
                        throw new AssertionError("Escape did not stop the inventory GUI scan");
                    if (client.currentScreen instanceof HandledScreen<?>)
                        throw new AssertionError("Escape left an owned scan screen open");
                    if (!Files.readString(result).equals(String.join(" ", partial)))
                        throw new AssertionError("Escape lost matches found before interruption");
                    step = 4;
                }
                case 4 -> {
                    if (scan().active() || scan().failure() != ScanSession.Failure.USER_STOP || partial.isEmpty()
                            || !Files.readString(result).equals(String.join(" ", partial)))
                        throw new AssertionError("Partial results changed after inventory GUI interruption");
                    assertOwnCards(client);
                    if (!newViewerReady(game)) return;
                    Files.writeString(game.resolve("speed-safety-partial-results.txt"), Files.readString(result));
                    pass(report, "Q is consumed without item drops; Escape stops before completion and preserves detected nicknames");
                    pass(report, "Escape opens a fresh result viewer and closes the preceding viewer");
                    Files.writeString(report, "ALL SPEED SAFETY CHECKS PASSED\n", StandardOpenOption.APPEND);
                    step = 99;
                    client.scheduleStop();
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(game.resolve("runtime-failure.txt"), error.toString()); } catch (Exception ignored) {}
            step = 99;
            client.scheduleStop();
        }
    }
}
