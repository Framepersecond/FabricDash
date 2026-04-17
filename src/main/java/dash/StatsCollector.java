package dash;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StatsCollector {

    private static final int MAX_SAMPLES = 360;
    // 200 ticks = 10 seconds at 20 TPS
    private static final long SAMPLE_INTERVAL_SECONDS = 10;

    private final String dashVersion;
    private final List<StatsSample> history = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> task;

    public StatsCollector() {
        this.dashVersion = FabricLoader.getInstance().getModContainer(FabricDash.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public void start() {
        ScheduledExecutorService scheduler = FabricDash.getInstance().getScheduler();
        if (scheduler != null) {
            task = scheduler.scheduleAtFixedRate(this::collectSample,
                    SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void collectSample() {
        MinecraftServer server = FabricDash.getServer();
        if (server == null) return;

        double tps = 20.0;
        double mspt = 0.0;

        try {
            double avgTickNs = Arrays.stream(server.tickTimes).average().orElse(50_000_000.0);
            mspt = avgTickNs / 1_000_000.0;
            tps = Math.min(20.0, 1000.0 / mspt);
        } catch (Throwable ignored) {
        }

        long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long usedMem = totalMem - freeMem;

        double cpuPercent = 0.0;
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                cpuPercent = (load < 0.0) ? 0.0 : (load * 100.0);
            }
        } catch (Exception e) {
            // Ignore, fallback to 0.0
        }

        int overworldChunks = 0;
        int netherChunks = 0;
        int endChunks = 0;

        try {
            for (ServerWorld world : server.getWorlds()) {
                int chunks = ((ServerChunkManager) world.getChunkManager()).getLoadedChunkCount();
                if (world.getRegistryKey() == World.OVERWORLD) {
                    overworldChunks += chunks;
                } else if (world.getRegistryKey() == World.NETHER) {
                    netherChunks += chunks;
                } else if (world.getRegistryKey() == World.END) {
                    endChunks += chunks;
                }
            }
        } catch (Throwable ignored) {
        }

        StatsSample sample = new StatsSample(
                System.currentTimeMillis(),
                tps,
                mspt,
                usedMem,
                maxMem,
                cpuPercent,
                overworldChunks,
                netherChunks,
                endChunks,
                dashVersion);

        synchronized (history) {
            history.add(sample);
            while (history.size() > MAX_SAMPLES) {
                history.remove(0);
            }
        }
    }

    public List<StatsSample> getHistory() {
        return new LinkedList<>(history);
    }

    public StatsSample getLatest() {
        if (history.isEmpty()) {
            return new StatsSample(System.currentTimeMillis(), 20.0, 0, 0, 0, 0.0, 0, 0, 0, dashVersion);
        }
        return history.get(history.size() - 1);
    }

    public String getHistoryJson() {
        StringBuilder json = new StringBuilder("[");
        List<StatsSample> samples = getHistory();
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0)
                json.append(",");
            json.append(samples.get(i).toJson());
        }
        json.append("]");
        return json.toString();
    }

    public static class StatsSample {
        public final long timestamp;
        public final double tps;
        public final double mspt;
        public final long ramUsed;
        public final long ramMax;
        public final double cpuUsage;
        public final int overworldChunks;
        public final int netherChunks;
        public final int endChunks;
        public final String dashVersion;

        public StatsSample(long timestamp, double tps, double mspt, long ramUsed, long ramMax, double cpuUsage,
                int overworldChunks, int netherChunks, int endChunks, String dashVersion) {
            this.timestamp = timestamp;
            this.tps = tps;
            this.mspt = mspt;
            this.ramUsed = ramUsed;
            this.ramMax = ramMax;
            this.cpuUsage = cpuUsage;
            this.overworldChunks = overworldChunks;
            this.netherChunks = netherChunks;
            this.endChunks = endChunks;
            this.dashVersion = dashVersion == null ? "unknown" : dashVersion;
        }

        public String toJson() {
            return String.format(
                    "{\"t\":%d,\"tps\":%.2f,\"mspt\":%.2f,\"ram\":%d,\"ramMax\":%d,\"cpuUsage\":%.2f,\"ow\":%d,\"nether\":%d,\"end\":%d,\"dashVersion\":\"%s\"}",
                    timestamp, tps, mspt, ramUsed, ramMax, cpuUsage, overworldChunks, netherChunks, endChunks,
                    dashVersion.replace("\\", "\\\\").replace("\"", "\\\""));
        }
    }
}
