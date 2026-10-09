package net.spidicard;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.ReconfiguringScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.network.ClientConnection;
import net.minecraft.util.Hand;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SpidiCardClient implements ClientModInitializer {
    public static SpidiCardClient INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger("SpidiCard");
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    private final MinecraftClient client = MinecraftClient.getInstance();
    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("spidicard.properties");
    private final Path resultsFile = FabricLoader.getInstance().getGameDir().resolve("spidicard.txt");
    private final ResultViewer resultViewer = new ResultViewer();
    private int minimumCount = 1;
    private int delayMs = 100;
    private int timeoutMs = 5000;
    private ScanSession session;
    private ClientPlayNetworkHandler scanConnection;
    private long restartAfter;
    private final ScanExclusions exclusions = new ScanExclusions(this::liveSelfName);
    private FullRun fullRun;
    private boolean scanForFull;
    private int fullCount, fullDelay, fullTimeout;
    private int menuSync = -1;
    private String menuTitle = "";
    private long worldEpoch, tabRevision;
    private long positionRevision, worldReadySince = -1, readyEpoch = -1;
    private ClientConnection fullConnection;
    private InventoryCardCounter scanCardCounter;
    private InventoryCardCounter.NameCache scanNameCache;
    private final TabSnapshotCache tabSnapshots = new TabSnapshotCache();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var command = literal("spidicard")
                    .executes(ctx -> help())
                    .then(literal("start").executes(ctx -> start()))
                    .then(literal("full").executes(ctx -> full(GriefSelection.from(1)))
                            .then(argument("griefs", StringArgumentType.greedyString())
                                    .executes(ctx -> full(StringArgumentType.getString(ctx, "griefs")))))
                    .then(literal("stop").executes(ctx -> stop()))
                    .then(literal("status").executes(ctx -> status()))
                    .then(literal("menu").executes(ctx -> menu()))
                    .then(literal("count").executes(ctx -> { message("Минимум карт: " + minimumCount); return 1; })
                            .then(argument("count", IntegerArgumentType.integer(1))
                                    .executes(ctx -> setting("count", IntegerArgumentType.getInteger(ctx, "count")))))
                    .then(literal("delay").then(argument("milliseconds", IntegerArgumentType.integer(0, 10000))
                            .executes(ctx -> setting("delay", IntegerArgumentType.getInteger(ctx, "milliseconds")))))
                    .then(literal("timeout").then(argument("milliseconds", IntegerArgumentType.integer(500, 60000))
                            .executes(ctx -> setting("timeout", IntegerArgumentType.getInteger(ctx, "milliseconds")))));
            dispatcher.register(command);
            // Accept the spelling in the example too.
            dispatcher.register(literal("spidicart").redirect(dispatcher.getRoot().getChild("spidicard")));
        });
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
        net.spidicard.update.ModUpdater.initialize();
        LOGGER.info("SpidiCard initialized; results: {}", resultsFile.toAbsolutePath());
    }

    private int start() {
        if (fullActive()) { error("Идёт полный обход. /spidicard stop остановит его."); return 0; }
        return startScan(false);
    }

    /** Called only for our exact command roots; other client/server commands keep their normal path. */
    public boolean handleLocalCommand(String command) {
        String input = command.stripLeading();
        int end = 0;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) end++;
        String root = input.substring(0, end);
        if (!root.equals("spidicard") && !root.equals("spidicart")) return false;
        var dispatcher = ClientCommandManager.getActiveDispatcher();
        if (dispatcher == null || client.getNetworkHandler() == null) {
            error("Подключитесь к серверу перед использованием команды."); return true;
        }
        try {
            dispatcher.execute(input, (FabricClientCommandSource) client.getNetworkHandler().getCommandSource());
        } catch (CommandSyntaxException e) {
            String subcommand = input.substring(end).stripLeading().split("\\s+", 2)[0];
            String detail = switch (subcommand) {
                case "count" -> "Количество карт: целое число от 1.";
                case "delay" -> "Задержка: целое число от 0 до 10000 мс.";
                case "timeout" -> "Таймаут: целое число от 500 до 60000 мс.";
                case "full" -> e.getRawMessage().getString();
                default -> "Неизвестная команда или аргумент. Справка: /spidicard.";
            };
            boolean selection = subcommand.equals("full");
            error(selection ? "ОШИБКА СПИСКА" : "ОШИБКА КОМАНДЫ",
                    selection ? "Пример: 3-5, 8-12, 53, 56" : "Проверьте аргументы команды", detail);
        } catch (Exception e) {
            LOGGER.error("Cannot execute SpidiCard command", e);
            error("ОШИБКА КОМАНДЫ", "Команда не выполнена",
                    e.getMessage() == null ? "Не удалось выполнить команду." : e.getMessage());
        }
        return true;
    }

    private int full(String specification) throws CommandSyntaxException {
        final List<Integer> route;
        try { route = GriefSelection.parse(specification); }
        catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(Text.literal(e.getMessage())).create();
        }
        return full(route);
    }

    private int full(List<Integer> route) {
        if (fullActive() || active()) { error("Сначала остановите текущую проверку: /spidicard stop"); return 0; }
        if (client.player == null || client.getNetworkHandler() == null || client.world == null) {
            error("Сначала подключитесь к серверу."); return 0;
        }
        fullCount = minimumCount;
        fullDelay = delayMs;
        fullTimeout = timeoutMs;
        fullConnection = client.getNetworkHandler().getConnection();
        fullRun = new FullRun(new FullRun.Port() {
            private boolean lastWriteSucceeded;
            @Override public void hub() {
                closeMenuIfPresent();
                if (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection().isOpen())
                    client.getNetworkHandler().sendChatCommand("hub");
            }
            @Override public void useCompass() {
                if (client.player != null && client.interactionManager != null && client.getNetworkHandler() != null
                        && client.getNetworkHandler().getConnection().isOpen() && ServerMenus.compass(item(client.player.getInventory().getStack(0)))) {
                    client.player.getInventory().selectedSlot = 0;
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                }
            }
            @Override public void click(int syncId, int slot) {
                if (client.player != null && client.interactionManager != null && client.getNetworkHandler() != null
                        && client.getNetworkHandler().getConnection().isOpen()
                        && client.player.currentScreenHandler.syncId == syncId) {
                    client.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, client.player);
                }
            }
            @Override public void closeMenu() { closeMenuIfPresent(); }
            @Override public boolean startScan() { return SpidiCardClient.this.startScan(true) == 1; }
            @Override public void cancelScan() {
                if (active()) session.stop(ScanSession.Failure.USER_STOP, "Полный обход остановлен.");
                closeMenuIfPresent();
            }
            @Override public List<String> scanMatches() {
                return scanForFull && session != null ? session.matches() : List.of();
            }
            @Override public boolean persist(List<String> lines) {
                lastWriteSucceeded = false;
                try { ResultsFile.writeLines(resultsFile, lines); lastWriteSucceeded = true; return true; }
                catch (IOException e) { LOGGER.error("Cannot save full results", e); error("Ошибка записи: " + e.getMessage()); return false; }
            }
            @Override public void status(String text) { message(text); }
            @Override public void finished(boolean complete, String reason) {
                tabSnapshots.clear(); fullConnection = null;
                if (!complete && !reason.contains("пользователем"))
                    error("ОБХОД ПРЕРВАН", lastWriteSucceeded ? "Найденные ники сохранены" : "Ошибка сохранения",
                            reason + " Файл: " + resultsFile.toAbsolutePath());
                else message(reason + " Файл: " + resultsFile.toAbsolutePath());
                LOGGER.info("Full run finished; complete={}, griefs={}", complete, fullRun.completedGriefs());
                if (lastWriteSucceeded) openResults();
            }
        }, fullTimeout, route);
        message("Выбрано грифов: " + route.size() + ". Маршрут: "
                + route.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
        fullRun.begin(now());
        return 1;
    }

    private boolean fullActive() { return fullRun != null && fullRun.active(); }

    private void closeMenuIfPresent() {
        if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
            client.player.closeHandledScreen();
            menuSync = -1;
        }
    }

    private void pumpFull() {
        if (!fullActive()) return;
        var network = client.getNetworkHandler();
        // During play -> configuration -> play, getNetworkHandler() is null but the socket remains open.
        // A loading screen can outlive a closed socket. Only the retained transport
        // decides connectivity; an open CONFIGURATION connection still keeps full alive.
        boolean connected = fullConnection != null && fullConnection.isOpen()
                && (network == null || network.getConnection() == fullConnection);
        boolean ready = worldReady();
        TabSnapshotCache.Snapshot tab;
        if (network == null) {
            tabSnapshots.clear();
            tab = new TabSnapshotCache.Snapshot(0, false, 0);
        } else {
            tab = tabSnapshots.get(network, worldEpoch, tabRevision, liveSelfName(),
                    () -> network.getListedPlayerListEntries().stream()
                            .map(e -> new TabSnapshotCache.Entry(e.getProfile().getId(), e.getProfile().getName())).toList(),
                    name -> name != null && PLAYER_NAME.matcher(name).matches() && !exclusions.excludes(name));
        }
        boolean tabPresent = network != null && client.player != null
                && network.getPlayerListEntry(client.player.getUuid()) != null && tab.present();
        fullRun.tick(new FullRun.View(connected, ready,
                ready && ServerMenus.compass(item(client.player.getInventory().getStack(0))),
                worldEpoch, tabRevision, tab.fingerprint(), scoreboardGrief(), menuSnapshot(),
                positionRevision, tabPresent, tab.eligible()), now());
    }

    private boolean worldReady() {
        boolean base = client.getNetworkHandler() != null && client.player != null && client.world != null
                && client.interactionManager != null && client.player.isLoaded() && !client.player.isRemoved()
                && client.world.isChunkLoaded(client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4)
                && client.getOverlay() == null && !(client.currentScreen instanceof DownloadingTerrainScreen)
                && !(client.currentScreen instanceof ReconfiguringScreen) && !(client.currentScreen instanceof ConnectScreen);
        if (!base) { worldReadySince = -1; return false; }
        long time = now();
        if (worldReadySince < 0 || readyEpoch != worldEpoch) {
            worldReadySince = time; readyEpoch = worldEpoch;
        }
        return time - worldReadySince >= 500;
    }

    private ServerMenus.Menu menuSnapshot() {
        if (client.player == null || client.player.currentScreenHandler == client.player.playerScreenHandler
                || client.player.currentScreenHandler.syncId != menuSync) return null;
        var items = new ArrayList<ServerMenus.Item>();
        for (var slot : client.player.currentScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory()) break;
            items.add(item(slot.getStack()));
        }
        return new ServerMenus.Menu(menuSync, menuTitle, items);
    }

    private static ServerMenus.Item item(net.minecraft.item.ItemStack stack) {
        return new ServerMenus.Item(Registries.ITEM.getId(stack.getItem()).toString(),
                stack.isEmpty() ? "" : stack.getName().getString());
    }

    private int scoreboardGrief() {
        if (client.getNetworkHandler() == null) return 0;
        var scoreboard = client.getNetworkHandler().getScoreboard();
        var sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        return sidebar == null ? 0 : ServerMenus.griefNumber(sidebar.getDisplayName().getString());
    }

    private String liveSelfName() {
        if (client.player == null) return null;
        if (client.getNetworkHandler() != null) {
            var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null && entry.getProfile().getName() != null) return entry.getProfile().getName();
        }
        return client.player.getGameProfile().getName();
    }

    public void worldChanged() {
        tabSnapshots.clear();
        worldEpoch++; menuSync = -1; worldReadySince = -1;
        scanCardCounter = null;
        if (fullActive()) LOGGER.info("Full world transition: grief={}, phase={}; keeping run active",
                fullRun.grief(), fullRun.phase());
    }
    public void positionChanged() { positionRevision++; worldReadySince = -1; }
    public void tabChanged() { tabRevision++; }

    private int startScan(boolean fromFull) {
        if (active()) { if (!fromFull) error("Проверка уже идёт. /spidicard status или /spidicard stop"); return 0; }
        if (client.player == null || client.getNetworkHandler() == null || client.world == null) {
            if (!fromFull) error("Сначала подключитесь к серверу."); return 0;
        }
        if (!worldReady()) {
            if (!fromFull) error("Мир ещё загружается. Дождитесь загрузки перед /spidicard start.");
            return 0;
        }
        if (now() < restartAfter) {
            if (!fromFull) error("Подождите несколько секунд после остановки предыдущего запроса.");
            return 0;
        }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
            if (!fromFull) error("Закройте открытый инвентарь перед запуском."); return 0;
        }
        scanConnection = client.getNetworkHandler();
        // Listed entries are exactly the server tab list, rather than decorative display names.
        var names = scanConnection.getListedPlayerListEntries().stream()
                .map(entry -> entry.getProfile().getName())
                .filter(name -> name != null && PLAYER_NAME.matcher(name).matches())
                .filter(name -> !exclusions.excludes(name))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        scanForFull = fromFull;
        scanNameCache = new InventoryCardCounter.NameCache();
        scanCardCounter = null;
        int count = fromFull ? fullCount : minimumCount;
        int delay = fromFull ? fullDelay : delayMs;
        int timeout = fromFull ? fullTimeout : timeoutMs;
        session = new ScanSession(names, count, delay, timeout, now(), new ScanSession.Listener() {
            @Override public void request(String name) {
                if (client.player == null || client.getNetworkHandler() != scanConnection
                        || !scanConnection.getConnection().isOpen()) {
                    session.stop(ScanSession.Failure.DISCONNECTED, "Соединение с сервером изменилось."); return;
                }
                if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
                    session.stop("Открыт посторонний инвентарь."); return;
                }
                LOGGER.debug("Checking {} ({}/{})", name, session.processed() + 1, session.total());
                scanConnection.sendChatCommand("invsee " + name);
            }
            @Override public void close(int syncId) {
                if (client.player != null && client.getNetworkHandler() == scanConnection
                        && client.player.currentScreenHandler.syncId == syncId) {
                    client.player.closeHandledScreen();
                    scanCardCounter = null;
                }
            }
            @Override public void progress() { status(); }
            @Override public void finish(boolean complete, String reason) {
                session.finishTimings(now());
                scanCardCounter = null; scanNameCache = null; scanConnection = null;
                LOGGER.info("Scan timings: {}", session.timings(now()).summary());
                // After an interrupted request, drain late packets before allowing a new scan.
                if (!complete) restartAfter = now() + timeout;
                if (scanForFull) {
                    if (fullRun != null) fullRun.scanFinished(complete, session.matches(), reason, now());
                    return;
                }
                try {
                    ResultsFile.write(resultsFile, session.matches());
                    String summary = reason + " " + (complete ? "Результат" : "Частичный результат")
                            + ": " + session.matches().size() + " ник(ов), проверено " + session.checked()
                            + "/" + session.total() + ", недоступно " + session.skipped() + ".";
                    if (!complete && session.failure() != ScanSession.Failure.USER_STOP)
                        error("ПРЕРВАНО", "Найденные ники сохранены", summary);
                    else message(summary);
                    message("Файл: " + resultsFile.toAbsolutePath());
                    openResults();
                } catch (IOException e) {
                    LOGGER.error("Cannot write SpidiCard results", e);
                    error("Не удалось записать " + resultsFile + ": " + e.getMessage()
                            + ". Ники: " + String.join(" ", session.matches()));
                }
                LOGGER.info("{} Checked {}/{}, skipped {}, matches {}", reason,
                        session.checked(), session.total(), session.skipped(), session.matches().size());
            }
        }, exclusions::excludes);
        message((fromFull ? "Гриф #" + fullRun.grief() + ": " : "Начинаю: ")
                + names.size() + " игроков, сумма карт ≥ " + count
                + ", задержка " + delay + " мс. Текущий ник и SpidiBoost исключены.");
        return 1;
    }

    private void tick() {
        worldReady();
        pumpFull();
        pumpScan();
    }

    /** Also called after the client's queued packet tasks on frames between game ticks. */
    public void pumpScan() {
        if (!active()) return;
        if (client.player == null || client.world == null || client.getNetworkHandler() != scanConnection
                || !scanConnection.getConnection().isOpen()) {
            session.stop(ScanSession.Failure.DISCONNECTED, "Проверка прервана: выход с сервера."); return;
        }
        if ((session.phase() == ScanSession.Phase.WAIT_CONTENTS || session.phase() == ScanSession.Phase.SETTLING)
                && !session.owns(client.player.currentScreenHandler.syncId)) {
            session.stop("Окно /invsee закрыто до окончания чтения."); return;
        }
        session.tick(now());
    }

    public void opened(int syncId, String title) {
        menuSync = syncId;
        menuTitle = title;
        if (fullActive() && fullRun.phase() != FullRun.Phase.SCANNING) {
            if (fullRun.phase() == FullRun.Phase.RETRY_SCAN) {
                // A timed-out /invsee may open late. Drain it, without treating its slots as a new target.
                closeMenuIfPresent();
            }
            pumpFull(); return;
        }
        if (!active() || client.player == null) return;
        if (CardNames.normalize(title).contains("выбор сервера") || CardNames.normalize(title).contains("выбор мира грифа")) {
            session.stop("Вместо /invsee открылось меню выбора сервера."); return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler.syncId != syncId || !(handler instanceof GenericContainerScreenHandler
                || handler instanceof Generic3x3ContainerScreenHandler || handler instanceof HopperScreenHandler)) {
            session.stop("/invsee открыл неподдерживаемый тип окна. Проверка остановлена."); return;
        }
        scanCardCounter = null;
        session.opened(syncId, title, now());
        if (fullActive() && fullRun.phase() == FullRun.Phase.RETRY_SCAN) closeMenuIfPresent();
    }

    public void contents(int syncId, int packetSize) {
        if (fullActive() && fullRun.phase() != FullRun.Phase.SCANNING) { pumpFull(); return; }
        if (!owns(syncId) || client.player == null) return;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler.syncId != syncId || packetSize != handler.slots.size()) {
            session.stop("Сервер прислал неполный список слотов /invsee."); return;
        }
        var slots = new ArrayList<CardNames.Stack>(handler.slots.size());
        for (var slot : handler.slots) {
            if (slot.inventory == client.player.getInventory()) {
                slots.add(new CardNames.Stack("", 0, true));
                continue;
            }
            var stack = slot.getStack();
            slots.add(new CardNames.Stack(stack.isEmpty() ? "" : stack.getName().getString(),
                    stack.getCount(), false));
        }
        scanCardCounter = new InventoryCardCounter(slots, scanNameCache);
        session.contents(syncId, scanCardCounter.total(), now());
    }

    public void slotUpdated(int syncId, int slot) {
        if (fullActive() && fullRun.phase() != FullRun.Phase.SCANNING) { pumpFull(); return; }
        if (!owns(syncId) || client.player == null) return;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler.syncId == syncId && slot >= 0 && slot < handler.slots.size()
                && handler.slots.get(slot).inventory != client.player.getInventory()) {
            if (scanCardCounter == null) return; // A slot packet never replaces the mandatory full snapshot.
            var stack = handler.slots.get(slot).getStack();
            scanCardCounter.update(slot, new CardNames.Stack(stack.isEmpty() ? "" : stack.getName().getString(),
                    stack.getCount(), false));
            session.slotUpdated(syncId, scanCardCounter.total(), now());
        }
    }

    public void serverClosed(int syncId) {
        if (menuSync == syncId) menuSync = -1;
        if (fullActive()) fullRun.menuClosed(syncId);
        if (owns(syncId)) session.stop("Сервер закрыл /invsee до окончания чтения.");
    }

    public void serverMessage(String raw, boolean overlay) {
        if (!active() || overlay || session.phase() != ScanSession.Phase.WAIT_OPEN) return;
        String message = CardNames.normalize(raw);
        if (message.contains("нет прав") || message.contains("недостаточно прав")
                || message.contains("no permission") || message.contains("do not have permission")
                || message.contains("неизвестная команда") || message.contains("unknown command")) {
            session.stop("Сервер не разрешил /invsee: " + raw);
        } else if ((message.contains(CardNames.normalize(session.current()))
                && (message.contains("не найден") || message.contains("не в сети") || message.contains("оффлайн")
                || message.contains("offline") || message.contains("not online") || message.contains("not found")))
                || message.matches(".*(?:игрок не найден|игрок не в сети|player not found|player is not online)[.! ]*")) {
            message("Недоступен: " + session.current());
            session.unavailable(now());
        }
    }

    public boolean owns(int syncId) {
        return (active() && session.owns(syncId)) || (fullActive() && syncId == menuSync
                && (CardNames.normalize(menuTitle).contains("выбор сервера")
                || CardNames.normalize(menuTitle).contains("выбор мира грифа")));
    }
    private boolean active() { return session != null && session.active(); }
    private static long now() { return System.nanoTime() / 1_000_000; }

    private int stop() {
        if (fullActive()) fullRun.stop("Полный обход остановлен пользователем.");
        else if (active()) session.stop(ScanSession.Failure.USER_STOP, "Проверка остановлена пользователем.");
        else message("Сейчас проверка не идёт.");
        return 1;
    }

    public void stopFromScreen() { stop(); }

    public void stopForUpdate() { if (fullActive() || active()) stop(); }

    private int menu() {
        if (!Files.isRegularFile(resultsFile)) {
            error("Сохранённых результатов ещё нет. Запустите /spidicard start или /spidicard full.");
            return 0;
        }
        openResults();
        return 1;
    }

    private int status() {
        if (fullActive()) message("Full: гриф #" + fullRun.grief() + "/56, завершено "
                + fullRun.completedGriefs() + ", " + fullRun.phaseLabel());
        if (session == null) { message("Проверка ещё не запускалась. Минимум карт: " + minimumCount); return 1; }
        message((active() ? "Проверка: " : "Последняя проверка: ") + session.processed() + "/" + session.total()
                + ", найдено " + session.matches().size() + ", недоступно " + session.skipped()
                + ", минимум " + session.threshold() + (active() ? ", сейчас " + session.current() : ""));
        return 1;
    }

    private int setting(String key, int value) {
        switch (key) {
            case "count" -> minimumCount = value;
            case "delay" -> delayMs = value;
            case "timeout" -> timeoutMs = value;
            default -> throw new IllegalArgumentException(key);
        }
        boolean saved = saveConfig();
        message(key + " = " + value + (active() || fullActive() ? ". Применится к следующему запуску." : "."));
        if (!saved) error("Настройка действует, но не сохранена на диск.");
        return 1;
    }

    private int help() {
        message("/spidicard count <количество> — общая сумма карт (по умолчанию 1)");
        message("/spidicard start | full | stop | status | menu");
        message("/spidicard full <1–56> — начать обход с указанного грифа до 56-го");
        message("/spidicard full 3-5, 8-12, 53, 56 — проверить выбранные грифы");
        message("/spidicard delay <мс> — пауза между игроками, сейчас " + delayMs);
        message("/spidicard timeout <мс> — ожидание ответа, сейчас " + timeoutMs);
        return 1;
    }

    private void message(String text) {
        LOGGER.debug("{}", text);
        client.inGameHud.getChatHud().addMessage(ChatTheme.message(text));
        if (client.player == null) LOGGER.info("{}", text);
    }

    private void error(String detail) {
        error("ОШИБКА", "Подробности в чате", detail);
    }
    private void error(String heading, String hint, String detail) {
        LOGGER.warn("{}", detail);
        client.inGameHud.getChatHud().addMessage(ChatTheme.error(detail));
        if (client.player == null) return;
        Text title = ChatTheme.title(heading);
        Text subtitle = ChatTheme.subtitle(hint);
        int width = client.getWindow().getScaledWidth() - 24;
        String fittedTitle = client.textRenderer.trimToWidth(title, Math.max(24, width / 4)).getString();
        String fittedSubtitle = client.textRenderer.trimToWidth(subtitle, Math.max(24, width / 2)).getString();
        client.inGameHud.setTitleTicks(8, 52, 18);
        client.inGameHud.setSubtitle(ChatTheme.subtitle(fittedSubtitle));
        client.inGameHud.setTitle(ChatTheme.title(fittedTitle));
    }

    private void openResults() {
        resultViewer.reopen(resultsFile, error -> client.execute(() -> {
            LOGGER.error("Cannot open result viewer", error);
            error("Результат сохранён, но окно не открылось: " + error.getMessage());
        }));
    }

    private void loadConfig() {
        if (!Files.exists(configFile)) { saveConfig(); return; }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(configFile)) {
            properties.load(reader);
            minimumCount = readInt(properties, "count", 1, 1, Integer.MAX_VALUE);
            delayMs = readInt(properties, "delayMs", 100, 0, 10000);
            timeoutMs = readInt(properties, "timeoutMs", 5000, 500, 60000);
        } catch (IOException e) { LOGGER.error("Cannot read config; using defaults", e); }
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try { return Math.clamp(Integer.parseInt(properties.getProperty(key, "")), min, max); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private boolean saveConfig() {
        var properties = new Properties();
        properties.setProperty("count", Integer.toString(minimumCount));
        properties.setProperty("delayMs", Integer.toString(delayMs));
        properties.setProperty("timeoutMs", Integer.toString(timeoutMs));
        try {
            Files.createDirectories(configFile.getParent());
            try (var writer = Files.newBufferedWriter(configFile)) {
                properties.store(writer, "SpidiCard: total key cards / request delay / response timeout");
            }
            return true;
        } catch (IOException e) { LOGGER.error("Cannot save config", e); return false; }
    }
}
