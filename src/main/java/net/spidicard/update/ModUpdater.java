package net.spidicard.update;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.spidicard.ChatTheme;
import net.spidicard.SpidiCardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModUpdater {
    private static final Logger LOG = LoggerFactory.getLogger("SpidiCard-updater");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "SpidiCard-update-check"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile boolean pending;
    private final GitHubDownload download = new GitHubDownload();
    private final Path gameDir;
    private final Path installed;
    private final String version;
    private final MinecraftClient client;
    private ModUpdater(Path gameDir, Path installed, String version, MinecraftClient client) {
        this.gameDir = gameDir; this.installed = installed; this.version = version; this.client = client;
    }
    public static void initialize() {
        var loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) { LOG.info("Update checks disabled in development environment"); return; }
        try {
            var mod = loader.getModContainer("spidicard").orElseThrow();
            var paths = mod.getOrigin().getPaths();
            if (paths.size() != 1) throw new IOException("Updater requires a standalone JAR");
            Path game = loader.getGameDir().toRealPath(), jar = paths.getFirst().toRealPath();
            if (!Files.isRegularFile(jar) || !jar.startsWith(game) || !jar.getParent().equals(game.resolve("mods").toRealPath())
                    || Files.isSymbolicLink(paths.getFirst()) || !jar.getFileName().toString().endsWith(".jar"))
                throw new IOException("Mod is outside current instance mods directory");
            var updater = new ModUpdater(game, jar, mod.getMetadata().getVersion().getFriendlyString(), MinecraftClient.getInstance());
            registerChecks(updater::check);
            LOG.info("SpidiBoost automatic update checks enabled for this instance");
        } catch (Exception e) { LOG.warn("SpidiCard automatic updates unavailable: {}", e.getMessage()); }
    }
    public static void registerChecks(Runnable check) {
        ClientLifecycleEvents.CLIENT_STARTED.register(ignored -> check.run());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, ignored) -> check.run());
    }
    private void check() {
        if (pending || !checking.compareAndSet(false, true)) return;
        worker.execute(() -> {
            Path staged = null; Process helper = null;
            try {
                var release = ReleaseManifest.parse(new String(download.get(ReleaseManifest.LATEST, 16384), StandardCharsets.UTF_8));
                if (!release.newerThan(version)) return;
                Path directory = gameDir.resolve(".spidicard"); Files.createDirectories(directory);
                if (!directory.toRealPath().startsWith(gameDir)) throw new IOException("External update directory");
                String oldHash = SafeInstall.hash(installed);
                staged = Files.createTempFile(directory, "release-", ".jar");
                Files.write(staged, download.get(release.download(), 16 * 1024 * 1024));
                UpdateArtifact.validate(staged, release);
                Path agent = extractAgent(directory);
                List<String> restart = RestartCommand.detect(gameDir);
                Path java = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
                Path agentLog = directory.resolve("agent.log");
                helper = new ProcessBuilder(java.toString(), "-jar", agent.getFileName().toString())
                        .directory(directory.toFile())
                        .redirectError(ProcessBuilder.Redirect.appendTo(agentLog.toFile())).start();
                var plan = new UpdatePlan(ProcessHandle.current().pid(), gameDir, installed, staged,
                        oldHash, release.sha256(), Path.of(System.getProperty("user.dir")).toAbsolutePath(), restart);
                try (var pipe = helper.getOutputStream()) { plan.write(pipe); }
                Process currentHelper = helper;
                var ready = CompletableFuture.supplyAsync(() -> {
                    try { return new BufferedReader(new InputStreamReader(currentHelper.getInputStream(), StandardCharsets.UTF_8)).readLine(); }
                    catch (IOException e) { throw new CompletionException(e); }
                });
                if (!"READY".equals(ready.get(15, TimeUnit.SECONDS))) throw new IOException("Update helper not ready");
                pending = true;
                LOG.info("SpidiCard {} downloaded and verified; restart supported: {}", release.version(), !restart.isEmpty());
                client.execute(() -> {
                    if (restart.isEmpty()) {
                        client.inGameHud.getChatHud().addMessage(ChatTheme.message("Версия " + release.version()
                                + " готова. Она установится после выхода из Minecraft. Перезапустите игру через лаунчер."));
                        client.getToastManager().add(new UpdateToast("Обновление готово", "Перезапустите Minecraft через лаунчер"));
                    } else {
                        SpidiCardClient.INSTANCE.stopForUpdate();
                        client.setScreen(new UpdateScreen(release.version(), client));
                        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> client.execute(client::scheduleStop));
                    }
                });
                // The independent helper owns this file now; keep it even after client shutdown.
                staged = null; helper = null;
            } catch (Exception e) {
                // Offline GitHub must never interrupt scans or joining a server.
                LOG.debug("SpidiCard update check failed ({})", e.getClass().getSimpleName());
            } finally {
                if (helper != null) helper.destroyForcibly();
                if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
                checking.set(false);
            }
        });
    }
    private static Path extractAgent(Path directory) throws IOException {
        byte[] bytes;
        try (var resource = ModUpdater.class.getResourceAsStream("/spidicard-update-agent.jar")) {
            if (resource == null) throw new IOException("Missing update helper");
            bytes = resource.readNBytes(1048577);
            if (bytes.length > 1048576) throw new IOException("Update helper too large");
        }
        Path agent = Files.createTempFile(directory, "agent-", ".jar"); Files.write(agent, bytes); return agent;
    }
}
