package dash;

import dash.web.ServerSettingsPage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SetupNotifier {

    private final FabricConfig config;
    private final Logger logger;
    private final WebAuth auth;
    private final RegistrationManager registrationManager;

    public SetupNotifier(FabricConfig config, Logger logger, WebAuth auth, RegistrationManager registrationManager) {
        this.config = config;
        this.logger = logger;
        this.auth = auth;
        this.registrationManager = registrationManager;
    }

    /**
     * Called when a player joins the server. In FabricDash, register this via
     * {@code ServerPlayConnectionEvents.JOIN} and call this method.
     */
    public void notifyPlayer(ServerPlayerEntity player) {
        if (!auth.isSetupRequired() || !CommandManager.OWNERS_CHECK.allows(player.getPermissions())) {
            return;
        }

        String code = registrationManager.generateCode(
                player.getUuidAsString(), player.getName().getString(), "ADMIN", List.of());
        String setupUrl = buildSetupUrl(code);
        String baseUrl = buildSetupUrl(null);

        Text clickable = Text.literal("[Dash] ").formatted(Formatting.AQUA)
                .append(Text.literal("Panel setup is still required. Click here to open setup.")
                        .formatted(Formatting.YELLOW, Formatting.BOLD)
                        .styled(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(setupUrl)))));

        // Delay by one tick so the player's connection is fully initialized.
        MinecraftServer server = FabricDash.getServer();
        if (server != null) {
            server.execute(() -> {
                if (player.isDisconnected()) return;

                player.sendMessage(Text.empty());
                player.sendMessage(clickable);
                player.sendMessage(Text.literal("Setup URL: " + setupUrl).formatted(Formatting.GRAY));
                player.sendMessage(Text.literal("Code expires in 5 minutes.").formatted(Formatting.RED));
                player.sendMessage(Text.empty());
                player.sendMessage(Text.literal("If clicking the link doesn't work:").formatted(Formatting.GRAY));
                player.sendMessage(Text.literal("1. Go to: ").formatted(Formatting.GRAY)
                        .append(Text.literal(baseUrl).formatted(Formatting.WHITE)));
                player.sendMessage(Text.literal("2. Enter code: ").formatted(Formatting.GRAY)
                        .append(Text.literal(code).formatted(Formatting.GREEN, Formatting.BOLD)));
                player.sendMessage(Text.empty());
            });
        }

        WebActionLogger.log("SETUP_CODE_GENERATED", "Player " + player.getName().getString() + " received setup link");
    }

    public void sendDiscordSetupNotificationIfConfigured() {
        String webhookUrl = config.getString("setup-discord-webhook-url", "").trim();
        if (webhookUrl.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String setupUrl = buildSetupUrl(null);
                String payload = "{\"content\":\"FabricDash setup required on server. Open: "
                        + escapeJson(setupUrl) + "\"}";

                HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                WebActionLogger.log("SETUP_DISCORD_NOTIFY", "Webhook status=" + status);
            } catch (Exception ex) {
                logger.warn("Failed to send setup webhook: {}", ex.getMessage());
            }
        });
    }

    private String buildSetupUrl(String code) {
        return buildSetupUrlStatic(config, code);
    }

    public static String buildSetupUrlStatic(FabricConfig config, String code) {
        String fallback = config.getString("panel-url", "").trim();
        String host = normalizeHost(config.getString("server-ip", ""));
        if (!isUsableHost(host)) {
            host = normalizeHost(ServerSettingsPage.readServerPropertySafe("server-ip", ""));
        }
        if (!isUsableHost(host)) {
            host = autoDetectHost();
        }
        if (!isUsableHost(host)) {
            host = "localhost";
        }

        String base = "http://" + host + ":" + FabricDash.getWebPort() + "/setup";
        if (!fallback.isEmpty()) {
            base = fallback.endsWith("/") ? fallback + "setup" : fallback + "/setup";
        }

        if (code == null || code.isBlank()) {
            return base;
        }

        return base + (base.contains("?") ? "&" : "?") + "code=" + code;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeHost(String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw.trim();
        for (int i = 0; i < 6; i++) {
            String before = host;
            while ((host.startsWith("\"") && host.endsWith("\"")) || (host.startsWith("'") && host.endsWith("'"))) {
                host = host.substring(1, host.length() - 1).trim();
            }
            host = host.replace("\\\"", "\"").replace("\\\\", "\\");
            if (host.equals(before)) {
                break;
            }
        }
        if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        } else if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.trim();
    }

    private static boolean isUsableHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.trim().toLowerCase();
        return !("localhost".equals(lower)
                || "127.0.0.1".equals(lower)
                || "0.0.0.0".equals(lower)
                || "::1".equals(lower));
    }

    private static String autoDetectHost() {
        String publicIp = tryFetchPublicIp();
        if (isUsableHost(publicIp)) {
            return publicIp;
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (isUsableHost(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local != null) {
                String ip = local.getHostAddress();
                if (isUsableHost(ip)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String tryFetchPublicIp() {
        String[] services = { "https://api.ipify.org", "https://icanhazip.com" };
        for (String endpoint : services) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestProperty("User-Agent", "FabricDash/" + FabricDash.getModVersion());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null) {
                        String ip = line.trim();
                        if (ip.matches("^[0-9a-fA-F:.]{3,}$")) {
                            return ip;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }
}
