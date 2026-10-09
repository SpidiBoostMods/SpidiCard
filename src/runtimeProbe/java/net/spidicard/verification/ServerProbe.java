package net.spidicard.verification;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.spidicard.ServerMenus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ServerProbe implements ModInitializer {
    public static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    public static final List<Integer> VISITED = new CopyOnWriteArrayList<>();
    public static final List<String> NAVIGATION = new CopyOnWriteArrayList<>();
    public static int SILENT_REQUEST = -1;
    public static boolean SPEED_WINDOW_REPLACED_EARLY;
    public static long LAST_SCAN_CLOSED_AT, MIN_RETURN_QUIET_MS = Long.MAX_VALUE;
    private static final List<UUID> TAB_IDS = new ArrayList<>();
    private static int currentGrief, firstTransferClicks;
    private static boolean warmupFailed, arrowIgnored;
    private static long lateAtNanos;
    private static Runnable lateOpen;
    public static boolean LATE_SENT;
    public static int CONFIGURATION_COUNT;
    private static int pendingConfiguration;
    private static final java.util.Set<net.minecraft.server.network.ServerConfigurationNetworkHandler> CONFIG_STARTED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static boolean hubConfigured, griefConfigured, modeDropped, rootDelayed, pageDropped, transferDropped;
    private record Scheduled(long due, Runnable action) {}
    private static final List<Scheduled> SCHEDULED = new ArrayList<>();
    private static void later(long milliseconds, Runnable action) {
        SCHEDULED.add(new Scheduled(System.nanoTime() + milliseconds * 1_000_000L, action));
    }
    private static boolean loadingQa() { return Boolean.getBoolean("spidicard.qa.loading"); }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if ((loadingQa() || Boolean.getBoolean("spidicard.qa.configKick") || Boolean.getBoolean("spidicard.qa.fullFrom"))
                    && server instanceof net.minecraft.server.integrated.IntegratedServer) {
                try {
                    // Emulate a dedicated server's non-pausing clock without opening a LAN socket.
                    var field = server.getClass().getDeclaredField("lanPort"); field.setAccessible(true); field.setInt(server, 0);
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if ((loadingQa() || Boolean.getBoolean("spidicard.qa.configKick")) && pendingConfiguration != 0) {
                // Vanilla reconfigure switches protocol but intentionally leaves the new
                // configuration handler idle. The server initiating it must start its tasks.
                for (var connection : server.getNetworkIo().getConnections()) {
                    if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerConfigurationNetworkHandler handler
                            && CONFIG_STARTED.add(handler)) {
                        System.out.println("SPIDICARD_QA_CONFIGURATION_START " + pendingConfiguration);
                        handler.sendConfigurations();
                    }
                }
            }
            for (var task : List.copyOf(SCHEDULED)) if (System.nanoTime() >= task.due()) {
                SCHEDULED.remove(task); task.action().run();
            }
            if (lateOpen != null && System.nanoTime() >= lateAtNanos) {
                Runnable action = lateOpen; lateOpen = null; LATE_SENT = true; action.run();
            }
        });
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if ((!loadingQa() && !Boolean.getBoolean("spidicard.qa.configKick")) || pendingConfiguration == 0) return;
            CONFIGURATION_COUNT++;
            System.out.println("SPIDICARD_QA_CONFIGURATION_TASK " + pendingConfiguration);
            long delay = pendingConfiguration < 0 ? 5000 : 6000;
            handler.addTask(new ServerPlayerConfigurationTask() {
                private final Key key = new Key("spidicard:delayed-configuration");
                public Key getKey() { return key; }
                public void sendPacket(java.util.function.Consumer<Packet<?>> sender) {
                    // The integrated server pauses world ticks while no player is in play state.
                    // Network tasks still run; schedule completion on its main executor using wall time.
                    java.util.concurrent.CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> server.execute(() -> {
                                System.out.println("SPIDICARD_QA_CONFIGURATION_COMPLETE " + pendingConfiguration);
                                if (Boolean.getBoolean("spidicard.qa.configKick")) {
                                    handler.disconnect(Text.literal("SpidiCard QA configuration disconnect"));
                                } else handler.completeTask(key);
                            }));
                }
            });
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("qa_kick").executes(ctx -> {
                ctx.getSource().getPlayerOrThrow().networkHandler.disconnect(Text.literal("SpidiCard QA disconnect"));
                return 1;
            }));
            dispatcher.register(literal("qa_small_tab").executes(ctx -> {
                var player = ctx.getSource().getPlayerOrThrow();
                var keep = List.of(UUID.nameUUIDFromBytes("QAPlayer000".getBytes(StandardCharsets.UTF_8)),
                        UUID.nameUUIDFromBytes("QAPlayer001".getBytes(StandardCharsets.UTF_8)));
                var remove = TAB_IDS.stream().filter(id -> !keep.contains(id)).toList();
                player.networkHandler.sendPacket(new PlayerRemoveS2CPacket(remove));
                return 1;
            }));
            dispatcher.register(literal("qa_speed_tab").executes(ctx -> {
                setTab(ctx.getSource().getPlayerOrThrow(), List.of("SpeedAdd", "SpeedRemove", "SpeedNoncard", "SpeedRepeat", "SpidiBoost"));
                return 1;
            }));
            dispatcher.register(literal("hub").executes(ctx -> {
                hub(ctx.getSource().getPlayerOrThrow()); return 1;
            }));
            dispatcher.register(literal("invsee").then(argument("name", StringArgumentType.word()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                REQUESTS.add(name);
                if (REQUESTS.size() == SILENT_REQUEST) return 1;
                var player = ctx.getSource().getPlayerOrThrow();
                if (name.equals("SpidiBoost") || name.startsWith("Live")) throw new AssertionError("Excluded player checked: " + name);
                if (name.startsWith("Speed")) { stagedInventory(player, name); return 1; }
                if ((Boolean.getBoolean("spidicard.qa.full") || Boolean.getBoolean("spidicard.qa.late")) && currentGrief > 0) {
                    if (!warmupFailed) {
                        warmupFailed = true;
                        if (Boolean.getBoolean("spidicard.qa.late")) {
                            // Wall time is essential: an integrated server can catch up ticks in bursts.
                            lateAtNanos = System.nanoTime() + 2_000_000_000L;
                            lateOpen = () -> player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                                    (id, own, ignored) -> GenericContainerScreenHandler.createGeneric9x6(id, own, new SimpleInventory(54)),
                                    Text.literal("Инвентарь " + name)));
                        }
                        return 1;
                    } // Simulate /invsee not loaded yet or a very late reply.
                    if (name.startsWith("MissingG")) {
                        player.sendMessage(Text.literal("Игрок " + name + " не в сети"), false); return 1;
                    }
                }
                var inventory = new SimpleInventory(54);
                if (name.startsWith("QAPlayer")) {
                    int i = Integer.parseInt(name.substring(8));
                    inventory.setStack(0, card("Синяя Ключ-Карта", i % 2 == 0 ? 2 : 1, Formatting.BLUE));
                    inventory.setStack(13, card("Красная Ключ-Карта", 1, Formatting.RED));
                    inventory.setStack(25, new ItemStack(Items.NAME_TAG, 64));
                } else if (name.startsWith("CardG") || name.startsWith("LowG")) {
                    inventory.setStack(0, card("Синяя Ключ-Карта", name.startsWith("CardG") ? 2 : 1, Formatting.BLUE));
                    inventory.setStack(13, card("Красная Ключ-Карта", 1, Formatting.RED));
                }
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                        (syncId, own, ignored) -> new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, own, inventory, 6) {
                            @Override public void onClosed(PlayerEntity player) {
                                super.onClosed(player); LAST_SCAN_CLOSED_AT = System.nanoTime();
                            }
                        },
                        Text.literal("Инвентарь " + name)));
                return 1;
            })));
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity actual && hand == Hand.MAIN_HAND
                    && player.getStackInHand(hand).isOf(Items.COMPASS) && currentGrief == 0) {
                root(actual); return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var actual = handler.player;
            if (pendingConfiguration != 0) {
                int destination = pendingConfiguration; pendingConfiguration = 0;
                if (destination < 0) applyHub(actual); else applyGrief(actual, destination);
                return;
            }
            actual.getInventory().setStack(0, card("Зелёная Ключ-Карта", 64, Formatting.GREEN));
            actual.playerScreenHandler.sendContentUpdates();
            var names = new ArrayList<String>();
            for (int i = 0; i < 299; i++) {
                names.add(String.format("QAPlayer%03d", i));
            }
            names.add("CardQA"); // Launch username belongs to a DIFFERENT UUID in this fixture.
            names.add("SpidiBoost");
            setTab(actual, names);
        });
    }

    private static void stagedInventory(ServerPlayerEntity player, String name) {
        var inventory = new SimpleInventory(54);
        if (name.equals("SpeedRemove") || name.equals("SpeedRepeat"))
            inventory.setStack(0, card("Синяя Ключ-Карта", 3, Formatting.BLUE));
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (id, own, ignored) -> GenericContainerScreenHandler.createGeneric9x6(id, own, inventory),
                Text.literal("Инвентарь " + name)));
        var handler = player.currentScreenHandler;
        Runnable changed = () -> {
            if (player.currentScreenHandler != handler) { SPEED_WINDOW_REPLACED_EARLY = true; return; }
            if (name.equals("SpeedNoncard")) inventory.setStack(12, new ItemStack(Items.DIRT));
            else if (name.equals("SpeedRemove") || name.equals("SpeedRepeat")) inventory.setStack(0, ItemStack.EMPTY);
            else inventory.setStack(0, card("Синяя Ключ-Карта", 2, Formatting.BLUE));
            if (name.equals("SpeedRepeat")) handler.syncState(); else handler.sendContentUpdates();
        };
        later(20, changed);
        if (!name.equals("SpeedRemove")) later(35, () -> {
            if (player.currentScreenHandler != handler) { SPEED_WINDOW_REPLACED_EARLY = true; return; }
            if (!name.equals("SpeedAdd")) inventory.setStack(0, card("Синяя Ключ-Карта", 2, Formatting.BLUE));
            inventory.setStack(1, card("Красная Ключ-Карта", 1, Formatting.RED));
            handler.sendContentUpdates();
        });
    }

    private static void setTab(ServerPlayerEntity actual, List<String> names) {
        var remove = TAB_IDS.stream().filter(id -> !id.equals(actual.getUuid())).toList();
        if (!remove.isEmpty()) actual.networkHandler.sendPacket(new PlayerRemoveS2CPacket(remove));
        TAB_IDS.clear();
        var players = new ArrayList<ServerPlayerEntity>();
        var self = new ServerPlayerEntity(actual.getServer(), actual.getServerWorld(),
                new GameProfile(actual.getUuid(), currentGrief == 0 ? "LiveCardQA" : String.format("LiveGrief%02d", currentGrief)), actual.getClientOptions());
        self.networkHandler = actual.networkHandler; players.add(self); TAB_IDS.add(actual.getUuid());
        for (String name : names) {
            var id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            var fake = new ServerPlayerEntity(actual.getServer(), actual.getServerWorld(),
                    new GameProfile(id, name), actual.getClientOptions());
            fake.networkHandler = actual.networkHandler; players.add(fake); TAB_IDS.add(id);
        }
        actual.networkHandler.sendPacket(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,
                PlayerListS2CPacket.Action.UPDATE_LISTED, PlayerListS2CPacket.Action.UPDATE_GAME_MODE), players));
    }

    private static void hub(ServerPlayerEntity player) {
        int previousGrief = currentGrief;
        currentGrief = 0; NAVIGATION.add("hub");
        if (Boolean.getBoolean("spidicard.qa.configKick") && previousGrief > 0) {
            if (player.currentScreenHandler != player.playerScreenHandler)
                throw new AssertionError("Hub overlapped an open invsee");
            long quiet = (System.nanoTime() - LAST_SCAN_CLOSED_AT) / 1_000_000;
            MIN_RETURN_QUIET_MS = Math.min(MIN_RETURN_QUIET_MS, quiet);
            // Server ticks quantize processing of close and command packets separately.
            if (quiet < 400) throw new AssertionError("Hub arrived without return quiet period: " + quiet);
        }
        if (Boolean.getBoolean("spidicard.qa.configKick") && previousGrief == 2 && !hubConfigured) {
            hubConfigured = true; pendingConfiguration = -1;
            player.closeHandledScreen(); player.networkHandler.reconfigure(); return;
        }
        if (loadingQa() && !hubConfigured) {
            hubConfigured = true; pendingConfiguration = -1;
            player.closeHandledScreen(); player.networkHandler.reconfigure(); return;
        }
        applyHub(player);
    }

    private static void applyHub(ServerPlayerEntity player) {
        currentGrief = 0;
        player.closeHandledScreen();
        for (int i = 0; i < player.getInventory().size(); i++) player.getInventory().setStack(i, ItemStack.EMPTY);
        var compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponentTypes.CUSTOM_NAME, Text.literal("ВЫБОР СЕРВЕРА (ПКМ)").formatted(Formatting.GOLD));
        player.getInventory().setStack(0, compass);
        player.playerScreenHandler.sendContentUpdates();
        setTab(player, List.of("HubDummy", "SpidiBoost"));
        sidebar(player, 0);
    }

    private static void root(ServerPlayerEntity player) {
        NAVIGATION.add("compass");
        var inventory = new SimpleInventory(45);
        var tnt = new ItemStack(Items.TNT);
        tnt.set(DataComponentTypes.CUSTOM_NAME, Text.literal("ГРИФ ВЫЖИВАНИЕ (1.21.11)").formatted(Formatting.GOLD, Formatting.BOLD));
        inventory.setStack(21, tnt);
        boolean delayed = loadingQa() && !rootDelayed;
        if (delayed) { rootDelayed = true; inventory.setStack(21, ItemStack.EMPTY); NAVIGATION.add("root-delayed"); }
        menu(player, inventory, "» Выбор сервера", slot -> {
            if (slot != 21) throw new AssertionError("Wrong mode slot " + slot);
            NAVIGATION.add("mode:21");
            if (loadingQa() && !modeDropped) {
                modeDropped = true; NAVIGATION.add("mode-dropped"); player.closeHandledScreen(); return;
            }
            griefs(player, 1);
        });
        if (delayed) {
            var screen = player.currentScreenHandler;
            later(2500, () -> {
                if (player.currentScreenHandler == screen) { inventory.setStack(21, tnt); screen.sendContentUpdates(); }
            });
        }
    }

    private static void griefs(ServerPlayerEntity player, int page) {
        var inventory = new SimpleInventory(45);
        for (int grief = page == 1 ? 1 : 33; grief <= (page == 1 ? 32 : 56); grief++) {
            var head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.CUSTOM_NAME, Text.literal("ГРИФ #" + grief + " (1.21.11)").formatted(Formatting.GOLD));
            inventory.setStack(ServerMenus.slotFor(grief), head);
        }
        if (page == 1) {
            var arrow = new ItemStack(Items.ARROW);
            arrow.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Следующая страница").formatted(Formatting.GOLD));
            inventory.setStack(44, arrow);
        }
        menu(player, inventory, "» Выбор мира грифа " + page + "/2", slot -> {
            if (page == 1 && slot == 44) {
                NAVIGATION.add("arrow:44");
                if (!arrowIgnored) { arrowIgnored = true; return; } // Test retry on the first second-page transition.
                if (loadingQa() && !pageDropped) {
                    pageDropped = true; NAVIGATION.add("page-dropped"); player.closeHandledScreen(); return;
                }
                griefs(player, 2); return;
            }
            int grief = ServerMenus.griefNumber(inventory.getStack(slot).getName().getString());
            if (grief < 1 || grief > 56 || ServerMenus.pageFor(grief) != page || ServerMenus.slotFor(grief) != slot)
                throw new AssertionError("Wrong grief click " + page + ":" + slot);
            NAVIGATION.add("grief:" + grief + ":" + slot);
            if (grief == 1 && ++firstTransferClicks < 3) return; // Connecting can require repeated clicks.
            if (loadingQa() && grief == 2 && !transferDropped) {
                transferDropped = true; NAVIGATION.add("transfer-dropped"); player.closeHandledScreen(); return;
            }
            currentGrief = grief; VISITED.add(grief);
            player.closeHandledScreen();
            if (loadingQa() && !griefConfigured) {
                griefConfigured = true; pendingConfiguration = grief;
                player.networkHandler.reconfigure(); return;
            }
            if (loadingQa() && grief == 3) {
                NAVIGATION.add("tab-delayed");
                player.getInventory().setStack(0, ItemStack.EMPTY); player.playerScreenHandler.sendContentUpdates();
                setTab(player, List.of()); // Player list arrives after the world/inventory.
                later(3000, () -> applyGrief(player, grief)); return;
            }
            applyGrief(player, grief);
        });
    }

    private static void applyGrief(ServerPlayerEntity player, int grief) {
        currentGrief = grief;
        player.getInventory().setStack(0, card("Зелёная Ключ-Карта", 64, Formatting.GREEN));
        player.playerScreenHandler.sendContentUpdates();
        String suffix = String.format("%02d", grief);
        setTab(player, Boolean.getBoolean("spidicard.qa.configKick")
                ? List.of("CardG" + suffix, "LowG" + suffix, "SpidiBoost")
                : List.of("CardG" + suffix, "LowG" + suffix, "MissingG" + suffix, "SpidiBoost"));
        sidebar(player, grief % 2 == 0 ? 0 : grief);
    }

    private static void sidebar(ServerPlayerEntity player, int grief) {
        var board = player.getServerWorld().getScoreboard();
        var previous = board.getNullableObjective("qa_grief");
        if (previous != null) board.removeObjective(previous);
        if (grief > 0) {
            var objective = board.addObjective("qa_grief", ScoreboardCriterion.DUMMY,
                    Text.literal("ГРИФ #" + grief), ScoreboardCriterion.RenderType.INTEGER, false, null);
            board.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
        }
    }

    private static void menu(ServerPlayerEntity player, SimpleInventory inventory, String title, IntConsumer click) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, own, ignored) ->
                new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X5, id, own, inventory, 5) {
                    @Override public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity actor) {
                        // Emulate Bukkit menu cancellation; do not actually move the selector item.
                        player.getServer().execute(() -> click.accept(slot));
                        this.syncState();
                    }
                }, Text.literal(title)));
    }

    private static ItemStack card(String name, int count, Formatting color) {
        var stack = new ItemStack(Items.NAME_TAG, count);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(color, Formatting.BOLD));
        return stack;
    }
}
