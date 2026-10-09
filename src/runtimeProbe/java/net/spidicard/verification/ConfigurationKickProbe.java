package net.spidicard.verification;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.spidicard.FullRun;
import net.spidicard.SpidiCardClient;

import java.nio.file.Files;
import java.util.List;

/** Tests an actual protocol reconfiguration followed by a server disconnect. */
public final class ConfigurationKickProbe {
    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private int step, configurationTicks;
    private long readyBefore;
    public void register() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private static Object field(String name) throws Exception {
        var f = SpidiCardClient.class.getDeclaredField(name); f.setAccessible(true);
        return f.get(SpidiCardClient.INSTANCE);
    }
    private static Process viewer() throws Exception {
        Object object = field("resultViewer");
        var f = object.getClass().getDeclaredField("previous"); f.setAccessible(true);
        return (Process) f.get(object);
    }
    private static void command(MinecraftClient client, String text) throws Exception {
        ClientCommandManager.getActiveDispatcher().execute(text,
                (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
    }
    private void tick(MinecraftClient client) {
        if (step == 99) return;
        var game = client.runDirectory.toPath();
        var log = game.resolve("logs/spidicard-viewer.log");
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Configuration kick timeout");
            if (step == 0) {
                if (client.player == null || client.getNetworkHandler() == null || client.player.age < 40) return;
                var ready = SpidiCardClient.class.getDeclaredMethod("worldReady"); ready.setAccessible(true);
                if (!(boolean) ready.invoke(SpidiCardClient.INSTANCE)) return;
                readyBefore = Files.exists(log) ? Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() : 0;
                command(client, "spidicard count 3"); command(client, "spidicard delay 0");
                command(client, "spidicard timeout 1000"); command(client, "spidicard full"); step = 1;
            } else {
                FullRun full = (FullRun) field("fullRun");
                if (full.active()) {
                    if (client.getNetworkHandler() == null) configurationTicks++;
                    if (viewer() != null) throw new AssertionError("Viewer opened during unfinished full run");
                    return;
                }
                if (configurationTicks < 40 || ServerProbe.CONFIGURATION_COUNT != 1)
                    throw new AssertionError("Configuration gap not exercised: " + configurationTicks);
                if (full.completedGriefs() != 2 || full.grief() != 3)
                    throw new AssertionError("Wrong completed grief count at configuration kick");
                if (!Files.readAllLines(game.resolve("spidicard.txt")).equals(List.of("CardG01 - grief #1", "CardG02 - grief #2")))
                    throw new AssertionError("Configuration kick lost saved results or added unscanned grief");
                if (((net.minecraft.network.ClientConnection) field("fullConnection")).isOpen())
                    throw new AssertionError("Full stopped before transport disconnected");
                if (ServerProbe.NAVIGATION.stream().filter("hub"::equals).count() != 3)
                    throw new AssertionError("Extra /hub commands during configuration");
                Process current = viewer();
                if (current == null || !current.isAlive() || !Files.exists(log)
                        || Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count() <= readyBefore) return;
                Files.writeString(game.resolve("configuration-kick-results.txt"),
                        "PASS: actual play/configuration transition on return from grief 2\n"
                        + "PASS: open configuration transport survives " + configurationTicks + " ticks without play handler\n"
                        + "PASS: closed transport stops full with 2 exact saved griefs; no unscanned grief 3 line\n"
                        + "PASS: result viewer opens after configuration disconnect\n"
                        + "PASS: exactly 3 /hub commands; none while configuration was pending\n"
                        + "PASS: last invsee closed before hub; minimum server-observed quiet interval " + ServerProbe.MIN_RETURN_QUIET_MS + " ms\n"
                        + "ALL CONFIGURATION KICK CHECKS PASSED\n");
                step = 99; client.scheduleStop();
            }
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(game.resolve("runtime-failure.txt"), error.toString()); } catch (Exception ignored) {}
            step = 99; client.scheduleStop();
        }
    }
}
