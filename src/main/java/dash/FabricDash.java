package dash;

import dash.bridge.ConsoleCatcher;
import dash.data.AuditDataManager;
import dash.data.BackupManager;
import dash.data.GlobalSettingsManager;
import dash.data.PlayerDataManager;
import dash.data.ScheduledTaskManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;

public class FabricDash implements DedicatedServerModInitializer {

    public static final String MOD_ID = "fabricdash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static FabricDash instance;
    private static MinecraftServer server;
    private static FabricConfig config;

    private AdminWebServer webServer;
    private static StatsCollector statsCollector;
    private static PlayerDataManager playerDataManager;
    private static BackupManager backupManager;
    private static RegistrationManager registrationManager;
    private static RegistrationApprovalManager registrationApprovalManager;
    private static DiscordWebhookManager discordWebhookManager;
    private static AuditDataManager auditDataManager;
    private static ScheduledTaskManager scheduledTaskManager;
    private static GithubUpdater githubUpdater;
    private static GlobalSettingsManager globalSettingsManager;
    private static int webPort;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updaterFuture;

    @Override
    public void onInitializeServer() {
        instance = this;

        // Load config
        config = new FabricConfig(getConfigDir().resolve("config.yml"));
        config.saveDefaultConfig();
        config.reload();

        // Set defaults if missing
        setDefaultIfAbsent("bridge.enabled", "true");
        setDefaultIfAbsent("bridge.secret", "your-super-secret-key");
        setDefaultIfAbsent("bridge.master_url", "");
        setDefaultIfAbsent("discord.webhook_url", "");
        setDefaultIfAbsent("updater.github-repo", "Framepersecond/FabricDash");
        config.save();

        webPort = config.getInt("port", 8080);
        WebActionLogger.init();

        if (config.getBoolean("bridge.enabled", true)) {
            ConsoleCatcher.register();
            LOGGER.info("NeoBridge mode enabled.");
        }

        logStartupBetaBanner();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DashCommand.register(dispatcher));

        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(FreezeManager::handleTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
            if (playerDataManager != null) {
                playerDataManager.onPlayerJoin(
                        handler.player.getUuid(),
                        handler.player.getName().getString(),
                        extractPlayerIp(handler));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, minecraftServer) -> {
            if (playerDataManager != null) {
                playerDataManager.onPlayerQuit(
                        handler.player.getUuid(),
                        handler.player.getName().getString());
            }
            FreezeManager.onDisconnect(handler.player.getUuid());
        });
    }

    private void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "FabricDash-Scheduler");
            t.setDaemon(true);
            return t;
        });

        playerDataManager = new PlayerDataManager(getDataDir());
        backupManager = new BackupManager(getDataDir(), getServerRootDirectory().toPath(), config);
        registrationManager = new RegistrationManager();
        registrationApprovalManager = new RegistrationApprovalManager();
        auditDataManager = new AuditDataManager(getDataDir());
        discordWebhookManager = new DiscordWebhookManager(config, LOGGER);
        scheduledTaskManager = new ScheduledTaskManager(minecraftServer, getDataDir());
        githubUpdater = new GithubUpdater(getDataDir(), LOGGER, getModVersion());
        globalSettingsManager = new GlobalSettingsManager(getDataDir());

        for (var p : minecraftServer.getPlayerManager().getPlayerList()) {
            playerDataManager.onPlayerJoin(
                    p.getUuid(),
                    p.getName().getString(),
                    p.networkHandler != null ? extractPlayerIp(p.networkHandler) : "Unknown");
        }

        WebActionLogger.setAuditManager(auditDataManager);
        WebActionLogger.setDiscordWebhookManager(discordWebhookManager);

        statsCollector = new StatsCollector();
        statsCollector.start();

        webServer = new AdminWebServer(minecraftServer, webPort);
        webServer.start();

        startUpdaterSchedule();

        if (!webServer.hasPortBindFailed()) {
            LOGGER.info("FabricDash Admin started on port {}", webPort);
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Started", "The Minecraft server is now online.", 0x10B981);
        }
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        if (scheduledTaskManager != null) scheduledTaskManager.close();
        if (auditDataManager != null) auditDataManager.close();
        if (globalSettingsManager != null) globalSettingsManager.close();
        if (backupManager != null) backupManager.stop();
        if (playerDataManager != null) playerDataManager.close();
        if (statsCollector != null) statsCollector.stop();
        if (webServer != null) webServer.stop();

        stopUpdaterSchedule();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (githubUpdater != null) {
            githubUpdater.applyUpdateOnShutdown();
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Stopped", "The Minecraft server has been shut down.", 0xF43F5E);
        }

        LOGGER.info("FabricDash Admin stopped");
        server = null;
    }

    private void logStartupBetaBanner() {
        LOGGER.info("=======================");
        LOGGER.info(" 222222         11     ");
        LOGGER.info("      22       111     ");
        LOGGER.info("  22222   ..    11     ");
        LOGGER.info(" 22       ..    11     ");
        LOGGER.info(" 2222222      111111   ");
        LOGGER.info("=======================");
    }

    private void setDefaultIfAbsent(String path, String value) {
        if (config.getString(path, null) == null) {
            config.set(path, value);
        }
    }

    // --- Updater scheduling ---

    private void startUpdaterSchedule() {
        stopUpdaterSchedule();
        if (githubUpdater == null || !githubUpdater.isEnabled()) return;

        long intervalMinutes = 120L;
        if (globalSettingsManager != null) {
            String raw = globalSettingsManager.getGlobalSetting("update_interval_minutes", "120");
            try {
                intervalMinutes = Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
            }
        }
        intervalMinutes = Math.max(20L, Math.min(10080L, intervalMinutes));

        updaterFuture = scheduler.scheduleAtFixedRate(this::runUpdaterScan,
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOGGER.info("[Updater] Scheduled update scan every {} minutes.", intervalMinutes);
    }

    public void rescheduleUpdater() {
        startUpdaterSchedule();
    }

    private void stopUpdaterSchedule() {
        if (updaterFuture != null) {
            updaterFuture.cancel(false);
            updaterFuture = null;
        }
    }

    private void runUpdaterScan() {
        try {
            if (githubUpdater == null || !githubUpdater.isEnabled()) return;
            if (!githubUpdater.isUpdateAvailable()) return;
            if (githubUpdater.isUpdatePrepared()) return;
            LOGGER.info("[FabricDash] New version available. Auto-downloading update in the background...");
            boolean ok = githubUpdater.downloadUpdate();
            if (!ok) {
                LOGGER.warn("[FabricDash] Auto-download failed. You can retry from the admin panel under Updates.");
            }
        } catch (Exception ex) {
            LOGGER.warn("[Updater] Scheduled update scan failed: {}", ex.getMessage());
        }
    }

    private static String extractPlayerIp(ServerPlayNetworkHandler handler) {
        if (handler == null || handler.getConnectionAddress() == null) {
            return "Unknown";
        }
        String raw = handler.getConnectionAddress().toString();
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String ip = raw;
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        int colon = ip.lastIndexOf(':');
        if (colon > 0) {
            String maybePort = ip.substring(colon + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                ip = ip.substring(0, colon);
            }
        }
        return ip;
    }

    // --- Static getters ---

    public static FabricDash getInstance() {
        return instance;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static FabricConfig getConfig() {
        return config;
    }

    public static Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    public static Path getDataDir() {
        return getConfigDir();
    }

    public static File getServerRootDirectory() {
        MinecraftServer activeServer = server;
        File fallback = new File(".").getAbsoluteFile();
        if (activeServer == null) {
            return fallback;
        }

        List<File> candidates = new ArrayList<>();
        try {
            candidates.add(activeServer.getRunDirectory().toFile());
        } catch (Exception ignored) {
        }
        try {
            Path worldRoot = activeServer.getSavePath(WorldSavePath.ROOT);
            if (worldRoot != null) {
                File worldRootFile = worldRoot.toFile();
                if (worldRootFile != null) {
                    File parent = worldRootFile.getParentFile();
                    if (parent != null) {
                        candidates.add(parent);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        candidates.add(fallback);

        File best = fallback;
        int bestScore = Integer.MIN_VALUE;
        for (File candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            File absolute = candidate.getAbsoluteFile();
            int score = scoreServerRootCandidate(absolute);
            if (score > bestScore) {
                bestScore = score;
                best = absolute;
            }
        }
        return best;
    }

    private static int scoreServerRootCandidate(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (new File(dir, "server.properties").isFile()) {
            score += 50;
        }
        if (new File(dir, "mods").isDirectory()) {
            score += 25;
        }
        if (new File(dir, "config").isDirectory()) {
            score += 12;
        }
        if (new File(dir, "world").isDirectory()) {
            score += 10;
        }
        if (new File(dir, "ops.json").isFile()) {
            score += 6;
        }
        if (new File(dir, "whitelist.json").isFile()) {
            score += 4;
        }
        String[] children = dir.list();
        if (children != null) {
            score += Math.min(20, children.length);
        }
        return score;
    }

    public static String getModVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }

    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public static BackupManager getBackupManager() {
        return backupManager;
    }

    public static RegistrationManager getRegistrationManager() {
        return registrationManager;
    }

    public static RegistrationApprovalManager getRegistrationApprovalManager() {
        return registrationApprovalManager;
    }

    public static DiscordWebhookManager getDiscordWebhookManager() {
        return discordWebhookManager;
    }

    public static AuditDataManager getAuditDataManager() {
        return auditDataManager;
    }

    public static ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    public static GithubUpdater getGithubUpdater() {
        return githubUpdater;
    }

    public static GlobalSettingsManager getGlobalSettingsManager() {
        return globalSettingsManager;
    }

    public static int getWebPort() {
        return webPort;
    }

    public AdminWebServer getWebServer() {
        return webServer;
    }

    /** Called by /dash port command to change the port at runtime. */
    public void changeWebPort(int newPort) {
        webPort = newPort;
        config.set("port", String.valueOf(newPort));
        config.save();
        if (webServer != null) {
            webServer.rebindToPort(newPort);
        }
    }
}
