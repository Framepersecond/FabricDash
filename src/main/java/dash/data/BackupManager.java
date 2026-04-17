package dash.data;

import dash.FabricConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
    private final Path dataFolder;
    private final Path serverRoot;
    private final FabricConfig config;
    private final File backupDir;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FabricDash-Backup");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> scheduledTask;
    private int scheduleHours = 0;

    public BackupManager(Path dataFolder, Path serverRoot, FabricConfig config) {
        this.dataFolder = dataFolder;
        this.serverRoot = serverRoot;
        this.config = config;
        this.backupDir = dataFolder.resolve("backups").toFile();
        if (!backupDir.exists())
            backupDir.mkdirs();

        scheduleHours = config.getInt("backup-schedule-hours", 0);
        if (scheduleHours > 0) {
            startSchedule(scheduleHours);
        }
    }

    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        executor.shutdown();
    }

    public void startSchedule(int hours) {
        this.scheduleHours = hours;
        config.set("backup-schedule-hours", hours);
        config.save();

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        if (hours > 0) {
            scheduledTask = executor.scheduleAtFixedRate(this::createBackup, hours, hours, TimeUnit.HOURS);
        }
    }

    public void stopSchedule() {
        scheduleHours = 0;
        config.set("backup-schedule-hours", 0);
        config.save();

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    public int getScheduleHours() {
        return scheduleHours;
    }

    public BackupResult createBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "backup_" + timestamp + ".zip";
        File backupFile = new File(backupDir, fileName);

        try {
            File serverDir = serverRoot.toFile();

            String levelName = resolveLevelName(serverDir);
            LinkedHashSet<String> toBackup = new LinkedHashSet<>(Arrays.asList(
                    "mods",
                    "config",
                    "server.properties",
                    "ops.json",
                    "whitelist.json",
                    "banned-players.json",
                    "banned-ips.json",
                    "world",
                    "world_nether",
                    "world_the_end"));
            if (levelName != null && !levelName.isBlank()) {
                toBackup.add(levelName);
                toBackup.add(levelName + "_nether");
                toBackup.add(levelName + "_the_end");
            }
            int entriesWritten = 0;

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                for (String name : toBackup) {
                    File file = new File(serverDir, name);
                    if (file.exists()) {
                        if (file.isDirectory()) {
                            entriesWritten += zipDirectory(file, name, zos);
                        } else {
                            entriesWritten += zipFile(file, name, zos);
                        }
                    }
                }
            }

            if (entriesWritten == 0) {
                backupFile.delete();
                return new BackupResult(false, null, 0, "No backup files were found in server root: " + serverDir.getAbsolutePath());
            }

            long size = backupFile.length();
            int maxBackups = Math.max(1, config.getInt("backups.max-backups", 10));
            cleanOldBackups(maxBackups);
            return new BackupResult(true, fileName, size, null);
        } catch (Exception e) {
            logger.warn("Backup creation failed: {}", e.getMessage());
            return new BackupResult(false, null, 0, e.getMessage());
        }
    }

    private int zipDirectory(File folder, String parentPath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null)
            return 0;

        int written = 0;
        for (File file : files) {
            String path = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                written += zipDirectory(file, path, zos);
            } else {
                written += zipFile(file, path, zos);
            }
        }
        return written;
    }

    private int zipFile(File file, String path, ZipOutputStream zos) {
        try {
            String name = file.getName().toLowerCase();
            if (name.equals("session.lock") || name.endsWith(".lock") || name.endsWith(".lck")
                    || name.endsWith(".pid") || name.endsWith(".log")
                    || name.endsWith(".db-journal"))
                return 0;

            zos.putNextEntry(new ZipEntry(path));
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
            return 1;
        } catch (IOException ex) {
            logger.debug("Failed to add file to backup {}: {}", path, ex.getMessage());
        }
        return 0;
    }

    private void cleanOldBackups(int keep) {
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null || backups.length <= keep)
            return;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (int i = keep; i < backups.length; i++) {
            backups[i].delete();
        }
    }

    private String resolveLevelName(File serverDir) {
        File propsFile = new File(serverDir, "server.properties");
        if (!propsFile.exists() || !propsFile.isFile()) {
            return "world";
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
            String levelName = props.getProperty("level-name", "world").trim();
            return levelName.isBlank() ? "world" : levelName;
        } catch (Exception ex) {
            return "world";
        }
    }

    public List<BackupInfo> listBackups() {
        List<BackupInfo> list = new ArrayList<>();
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null)
            return list;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (File backup : backups) {
            list.add(new BackupInfo(
                    backup.getName(),
                    backup.lastModified(),
                    backup.length()));
        }

        return list;
    }

    public File getBackupFile(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }
        File file = new File(backupDir, name);
        return file.exists() ? file : null;
    }

    public boolean deleteBackup(String name) {
        File file = getBackupFile(name);
        return file != null && file.delete();
    }

    public boolean restoreBackup(String name) {
        File backup = getBackupFile(name);
        if (backup == null)
            return false;

        File restoreDir = new File(backupDir, "restore_" + System.currentTimeMillis());
        Path restoreRoot = restoreDir.toPath().toAbsolutePath().normalize();

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(backup)) {
            zipFile.stream().forEach(entry -> {
                try {
                    Path destinationPath = restoreRoot.resolve(entry.getName()).normalize();
                    if (!destinationPath.startsWith(restoreRoot)) {
                        throw new IOException("Invalid zip entry: " + entry.getName());
                    }

                    File destFile = destinationPath.toFile();
                    if (entry.isDirectory()) {
                        destFile.mkdirs();
                    } else {
                        destFile.getParentFile().mkdirs();
                        try (InputStream inputStream = zipFile.getInputStream(entry)) {
                            Files.copy(inputStream, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record BackupInfo(String name, long timestamp, long size) {
        public String getFormattedSize() {
            if (size < 1024)
                return size + " B";
            if (size < 1024 * 1024)
                return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024)
                return String.format("%.1f MB", size / (1024.0 * 1024.0));
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    public record BackupResult(boolean success, String fileName, long size, String error) {
    }
}
