package dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRules;

import dash.web.*;
import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AdminWebServer {

    private static final String SESSION_COOKIE_NAME = "dash_auth";
    private static final long SESSION_TTL_MS = 3600000L;
    private static final long SSO_SIGNATURE_MAX_AGE_SECONDS = 300L;

    private final MinecraftServer server;
    private final WebAuth auth;
    private HttpServer httpServer;
    private int port;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> usedSsoSignatures = new ConcurrentHashMap<>();

    private static class SessionInfo {
        final String username;
        final long expiresAt;
        final boolean bridgeBound;
        final String bridgeSecretSnapshot;

        SessionInfo(String username, long expiresAt, boolean bridgeBound, String bridgeSecretSnapshot) {
            this.username = username;
            this.expiresAt = expiresAt;
            this.bridgeBound = bridgeBound;
            this.bridgeSecretSnapshot = bridgeSecretSnapshot == null ? "" : bridgeSecretSnapshot.trim();
        }
    }

    private boolean portBindFailed = false;

    public AdminWebServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
        this.auth = new WebAuth(FabricDash.getConfigDir(), FabricDash.LOGGER);
    }

    public boolean hasPortBindFailed() {
        return portBindFailed;
    }

    public int getPort() {
        return port;
    }

    public void start() {
        ConsoleLogAppender.register();
        ensureUpdaterConfigStructure();
        portBindFailed = false;

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            registerContexts();
            httpServer.setExecutor(null);
            httpServer.start();
            FabricDash.LOGGER.info("Web admin started on port " + port);
        } catch (java.net.BindException e) {
            portBindFailed = true;
            FabricDash.LOGGER.error("================================================================");
            FabricDash.LOGGER.error(" [FabricDash] Port {} is already in use!", port);
            FabricDash.LOGGER.error(" Use /dash port <port> in-game or console to change the port.");
            FabricDash.LOGGER.error("================================================================");
            notifyOpsPortConflict();
        } catch (IOException e) {
            FabricDash.LOGGER.error("Failed to start web server: " + e.getMessage());
        }
    }

    private void registerContexts() {
        httpServer.createContext("/", new PageHandler());
        httpServer.createContext("/login", new PageHandler());
        httpServer.createContext("/console", new PageHandler());
        httpServer.createContext("/players", new PageHandler());
        httpServer.createContext("/files", new PageHandler());
        httpServer.createContext("/files/edit", new PageHandler());
        httpServer.createContext("/plugins", new PageHandler());
        httpServer.createContext("/users", new PageHandler());
        httpServer.createContext("/permissions", new PageHandler());
        httpServer.createContext("/settings", new PageHandler());
        httpServer.createContext("/setup", new PageHandler());
        httpServer.createContext("/waiting-room", new PageHandler());
        httpServer.createContext("/audit", new PageHandler());
        httpServer.createContext("/plugin-settings", new PageHandler());
        httpServer.createContext("/scheduled-tasks", new PageHandler());
        httpServer.createContext("/updates", new PageHandler());

        httpServer.createContext("/api/console", new ConsoleApiHandler());
        httpServer.createContext("/api/health", new HealthApiHandler());
        httpServer.createContext("/api/server/state", new HealthApiHandler());
        httpServer.createContext("/api/ping", new HealthApiHandler());
        httpServer.createContext("/api/logout", new LogoutApiHandler());
        httpServer.createContext("/api/stats", new StatsApiHandler());
        httpServer.createContext("/api/stats/history", new StatsHistoryHandler());
        httpServer.createContext("/api/settings", new SettingsApiHandler());
        httpServer.createContext("/api/files/save", new FileSaveHandler());
        httpServer.createContext("/api/files/download", new FileDownloadHandler());
        httpServer.createContext("/api/backups/download", new BackupDownloadHandler());
        httpServer.createContext("/api/upload/icon", new IconUploadHandler());
        httpServer.createContext("/api/upload/datapack", new DatapackUploadHandler());
        httpServer.createContext("/api/upload/file", new FileUploadHandler());
        httpServer.createContext("/api/upload/plugin", new PluginUploadHandler());
        httpServer.createContext("/api/update/download", new UpdateDownloadHandler());
        httpServer.createContext("/api/update/restart", new UpdateRestartHandler());
        httpServer.createContext("/updates/check", new UpdateCheckHandler());
        httpServer.createContext("/api/settings/global", new GlobalSettingsApiHandler());
        httpServer.createContext("/api/players/profile", new PlayerProfileHandler());
        httpServer.createContext("/api/bridge/console", new BridgeConsoleHandler());
        httpServer.createContext("/api/webhook/approve", new WebhookApproveHandler());
        httpServer.createContext("/sso", new SsoAuthHandler(auth, this));

        httpServer.createContext("/action", new ActionHandler());
    }

    private void notifyOpsPortConflict() {
        server.execute(() -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (server.getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()))) {
                    player.sendMessage(Text.empty());
                    player.sendMessage(Text.literal("[FabricDash] ").formatted(Formatting.RED, Formatting.BOLD)
                            .append(Text.literal("Port " + port + " is already in use!").formatted(Formatting.RED)));
                    player.sendMessage(Text.literal("[FabricDash] ").formatted(Formatting.RED, Formatting.BOLD)
                            .append(Text.literal("Use ").formatted(Formatting.YELLOW))
                            .append(Text.literal("/dash port <port>").formatted(Formatting.AQUA, Formatting.BOLD))
                            .append(Text.literal(" to change and retry.").formatted(Formatting.YELLOW)));
                    player.sendMessage(Text.empty());
                }
            }
        });
    }

    /** Stops the current server, rebinds on a new port, and starts again. */
    public void rebindToPort(int newPort) {
        stop();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}
        this.port = newPort;
        start();
    }

    public void stop() {
        if (httpServer != null)
            httpServer.stop(0);
    }

    private boolean isAuthenticated(HttpExchange t) {
        return resolveSession(t) != null;
    }

    private String getSessionUser(HttpExchange t) {
        SessionInfo session = resolveSession(t);
        return session == null ? null : session.username;
    }

    private void setSession(HttpExchange t, String username) {
        WebAuth.UserInfo userInfo = auth.getUsers().get(username);
        String bridgeSecret = (userInfo != null && userInfo.bridgeUser())
                ? FabricDash.getConfig().getString("bridge.secret", "")
                : null;
        setSession(t, username, bridgeSecret);
    }

    private void setSession(HttpExchange t, String username, String bridgeSecretSnapshot) {
        String token = UUID.randomUUID().toString();
        boolean bridgeBound = bridgeSecretSnapshot != null && !bridgeSecretSnapshot.isBlank();
        sessions.put(token, new SessionInfo(username, System.currentTimeMillis() + SESSION_TTL_MS, bridgeBound,
                bridgeSecretSnapshot));
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    public void createAuthenticatedSession(HttpExchange t, String username) {
        setSession(t, username);
    }

    private SessionInfo resolveSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token == null) {
            return null;
        }

        SessionInfo session = sessions.get(token);
        if (session == null) {
            return null;
        }

        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }

        if (session.bridgeBound && !bridgeSecretStillValid(session.bridgeSecretSnapshot)) {
            sessions.remove(token);
            return null;
        }

        return session;
    }

    private boolean bridgeSecretStillValid(String snapshot) {
        String current = FabricDash.getConfig().getString("bridge.secret", "");
        if (current == null || current.isBlank() || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        return BridgeSecurity.equalsConstantTime(snapshot.trim().getBytes(StandardCharsets.UTF_8),
                current.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String getSessionToken(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(SESSION_COOKIE_NAME + "=")) {
                String value = trimmed.substring((SESSION_COOKIE_NAME + "=").length()).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private void clearSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token != null) {
            sessions.remove(token);
        }
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private boolean bootstrapBridgeSessionFromSignedQuery(HttpExchange t, String path) throws IOException {
        if (isAuthenticated(t)) {
            return false;
        }

        if (!FabricDash.getConfig().getBoolean("bridge.enabled", true)) {
            return false;
        }

        String rawQuery = t.getRequestURI().getRawQuery();
        String user = getQueryParam(rawQuery, "user");
        String timestampRaw = getQueryParam(rawQuery, "timestamp");
        String signature = getQueryParam(rawQuery, "signature");

        if (signature == null || user == null || timestampRaw == null) {
            return false;
        }

        if (user.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }
        String normalizedUser = user.trim();

        String bridgeSecret = FabricDash.getConfig().getString("bridge.secret", "").trim();
        if (bridgeSecret.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long incomingTimestamp;
        try {
            incomingTimestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long now = Instant.now().getEpochSecond();
        long timeDelta = now - incomingTimestamp;
        if (Math.abs(timeDelta) > SSO_SIGNATURE_MAX_AGE_SECONDS) {
            redirect(t, "/login?error=sso_expired");
            return true;
        }

        String localHmacInput = normalizedUser.toLowerCase(Locale.ROOT) + ":" + timestampRaw;
        String expected = hmacSha256Hex(localHmacInput, bridgeSecret);
        String provided = BridgeSecurity.normalizeHex(signature);
        if (expected == null
                || provided.length() != expected.length()
                || !BridgeSecurity.equalsConstantTime(expected, provided)
                || isReplaySignature(provided, System.currentTimeMillis())) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        WebAuth.BridgeSsoResult result = auth.getOrCreateBridgeUserForSso(normalizedUser);
        String sessionUser = result.username() == null ? normalizedUser : result.username();
        WebAuth.UserInfo bridgeUserInfo = auth.getUsers().get(sessionUser);
        boolean pendingBridgeUser = bridgeUserInfo != null && bridgeUserInfo.bridgeUser() && !bridgeUserInfo.bridgeApproved();
        if (!result.approved() || pendingBridgeUser || !isApprovedBridgeUser(sessionUser)) {
            if (bridgeUserInfo == null || !bridgeUserInfo.bridgeUser()) {
                redirect(t, "/login?error=sso_invalid");
                return true;
            }
            redirect(t, "/waiting-room?user=" + encodeForQuery(normalizedUser));
            return true;
        }

        setSession(t, sessionUser, bridgeSecret);
        String cleanedQuery = stripAuthBootstrapParams(rawQuery);
        String redirectPath = path;
        if (cleanedQuery != null && !cleanedQuery.isBlank()) {
            redirectPath = path + "?" + cleanedQuery;
        }
        if ("/login".equals(path) && (cleanedQuery == null || cleanedQuery.isBlank())) {
            redirectPath = "/";
        }
        redirect(t, redirectPath);
        return true;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isReplaySignature(String signature, long now) {
        cleanupUsedSignatures(now);
        Long existing = usedSsoSignatures.putIfAbsent(signature, now);
        return existing != null && (now - existing) <= (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L);
    }

    private void cleanupUsedSignatures(long now) {
        usedSsoSignatures.entrySet().removeIf(e -> (now - e.getValue()) > (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L));
    }

    private boolean isApprovedBridgeUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return info != null && info.bridgeUser() && info.bridgeApproved();
    }

    private String stripAuthBootstrapParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String[] parts = rawQuery.split("&");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String key = pair.length > 0 ? pair[0] : "";
            if ("user".equals(key) || "timestamp".equals(key) || "signature".equals(key) || "token".equals(key)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("&");
            }
            out.append(part);
        }
        return out.toString();
    }

    private String getClientIp(HttpExchange t) {
        String forwarded = t.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0];
            String cleaned = sanitizeIp(first);
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        String realIp = t.getRequestHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            String cleaned = sanitizeIp(realIp);
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        if (t.getRemoteAddress() != null && t.getRemoteAddress().getAddress() != null) {
            return sanitizeIp(t.getRemoteAddress().getAddress().getHostAddress());
        }
        return "";
    }

    private String sanitizeIp(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        while ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replace("\\\"", "\"");
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        int portSep = cleaned.lastIndexOf(':');
        if (portSep > 0 && cleaned.indexOf(':') == portSep) {
            String portPart = cleaned.substring(portSep + 1);
            if (portPart.chars().allMatch(Character::isDigit)) {
                cleaned = cleaned.substring(0, portSep);
            }
        }
        return cleaned;
    }

    private String decodePathComponent(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    private boolean ensurePermission(HttpExchange t, String permission, boolean jsonResponse) throws IOException {
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }

        String username = getSessionUser(t);
        if (username == null || !auth.userHasPermission(username, permission)) {
            WebActionLogger.log("ACCESS_DENIED",
                    "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                            + " path=" + t.getRequestURI().getPath() + " required=" + permission);
            t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
            if (jsonResponse) {
                sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
            } else {
                sendResponseWithStatus(t, 403,
                        "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                                + permission + "</p></div></body></html>");
            }
            return false;
        }

        return true;
    }

    private boolean ensureAnyPermission(HttpExchange t, boolean jsonResponse, String... permissions) throws IOException {
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }

        String username = getSessionUser(t);
        if (username != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank() && auth.userHasPermission(username, permission)) {
                    return true;
                }
            }
        }

        String required = String.join(" OR ", Arrays.stream(permissions)
                .filter(p -> p != null && !p.isBlank())
                .toList());
        WebActionLogger.log("ACCESS_DENIED",
                "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                        + " path=" + t.getRequestURI().getPath() + " requiredAny=" + required);
        t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
        if (jsonResponse) {
            sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
        } else {
            sendResponseWithStatus(t, 403,
                    "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                            + required + "</p></div></body></html>");
        }
        return false;
    }

    private boolean ensureBridgeBearer(HttpExchange t) throws IOException {
        if (!FabricDash.getConfig().getBoolean("bridge.enabled", true)) {
            sendResponseWithStatus(t, 404, "Bridge disabled");
            return false;
        }

        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        String secret = FabricDash.getConfig().getString("bridge.secret", "");
        if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
            sendResponseWithStatus(t, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            String query = t.getRequestURI().getQuery();

            if (bootstrapBridgeSessionFromSignedQuery(t, path)) {
                return;
            }

            if (path.equals("/setup")) {
                String code = null;
                String msg = null;
                if (query != null && query.startsWith("code=")) {
                    code = URLDecoder.decode(query.substring(5), "UTF-8");
                }
                msg = getQueryParam(query, "msg");
                sendResponse(t, SetupPage.render(code, auth.isSetupRequired(), msg));
                return;
            }

            if (path.equals("/waiting-room")) {
                String pendingUser = getQueryParam(query, "user");
                if (pendingUser != null && !pendingUser.isBlank()) {
                    auth.getOrCreateBridgeUserForSso(pendingUser.trim());
                }
                sendResponse(t, WaitingRoomPage.render(pendingUser));
                return;
            }

            if (auth.isSetupRequired()) {
                sendResponse(t, SetupPage.render(null, true, null));
                return;
            }

            if (path.equals("/login")) {
                serveLogin(t);
                return;
            }

            if (!isAuthenticated(t)) {
                serveLogin(t);
                return;
            }

            String sessionUser = getSessionUser(t);
            HtmlTemplate.setUiPermissions(auth.getEffectivePermissions(sessionUser));
            WebAuth.UserInfo sessionUserInfo = auth.getUsers().get(sessionUser);
            boolean sessionIsBridgeUser = sessionUserInfo != null && sessionUserInfo.bridgeUser();
            String bridgeMasterUrl = FabricDash.getConfig().getString("bridge.master_url", "");
            HtmlTemplate.setBridgeContext(sessionIsBridgeUser, bridgeMasterUrl);

            String html;
            try {
                if (path.equals("/")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    html = DashboardPage.render();
                } else if (path.equals("/console")) {
                    if (!ensurePermission(t, "dash.web.console.read", false))
                        return;
                    html = dash.web.ConsolePage.render();
                } else if (path.equals("/players")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    html = PlayersPage.render();
                } else if (path.startsWith("/players/") && path.endsWith("/inventory")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/inventory", ""));
                    html = InventoryPage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/enderchest")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/enderchest", ""));
                    html = InventoryPage.renderEnderChest(playerName);
                } else if (path.equals("/plugins")) {
                    if (!ensurePermission(t, "dash.web.plugins.read", false))
                        return;
                    html = PluginsPage.render();
                } else if (path.equals("/users")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String inviteCode = getQueryParam(query, "code");
                    String message = getQueryParam(query, "msg");
                    html = UsersPage.render(auth.getUsers(), auth.getRoleNames(), auth.getRoleValues(), sessionUser,
                            auth.isMainAdmin(sessionUser), inviteCode, message,
                            FabricDash.getRegistrationApprovalManager() == null ? List.of()
                                    : FabricDash.getRegistrationApprovalManager().listPending(),
                            auth.getPendingBridgeUsers());
                } else if (path.equals("/permissions")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String selectedRole = getQueryParam(query, "role");
                    String message = getQueryParam(query, "msg");
                    html = PermissionsPage.render(auth.getRolesWithPermissions(), auth.getRoleValues(), selectedRole,
                            message, auth.isMainAdmin(sessionUser), auth.getActorRoleValue(sessionUser));
                } else if (path.equals("/settings")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    int updateIntervalMinutes = 120;
                    dash.data.GlobalSettingsManager gsm = FabricDash.getGlobalSettingsManager();
                    if (gsm != null) {
                        try {
                            updateIntervalMinutes = Integer.parseInt(gsm.getGlobalSetting("update_interval_minutes", "120"));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    try {
                        html = SettingsPage.render(sessionUser, auth.isMainAdmin(sessionUser), msg, updateIntervalMinutes);
                    } catch (Exception ex) {
                        FabricDash.LOGGER.warn("Failed to render settings page: " + ex.getMessage());
                        html = HtmlTemplate.page("Settings", "/settings",
                                "<main class=\"p-4 sm:p-6 flex-1 w-full\"><div class=\"rounded-2xl border border-rose-500/30 bg-rose-500/10 p-4 text-rose-200\">"
                                        + "Settings are temporarily unavailable on this server type. Check missing config files and try again."
                                        + "</div></main>");
                    }
                } else if (path.equals("/audit")) {
                    if (!ensurePermission(t, "dash.web.audit.read", false))
                        return;
                    String searchQ = getQueryParam(query, "q");
                    html = AuditLogPage.render(searchQ);
                } else if (path.equals("/plugin-settings")) {
                    if (!ensurePermission(t, "dash.web.pluginsettings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = PluginSettingsPage.render(msg);
                } else if (path.equals("/scheduled-tasks")) {
                    if (!ensurePermission(t, "dash.web.tasks.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = ScheduledTasksPage.render(msg);
                } else if (path.equals("/updates")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    GithubUpdater updater = FabricDash.getGithubUpdater();
                    String currentVersion = FabricDash.getModVersion();
                    String latestVersion = currentVersion;
                    boolean updateAvailable = false;
                    boolean updatePrepared = false;
                    boolean updaterEnabled = updater != null && updater.isEnabled();
                    if (updaterEnabled) {
                        latestVersion = updater.getLatestVersion();
                        updateAvailable = updater.isUpdateAvailable();
                        updatePrepared = updater.isUpdatePrepared();
                    }
                    html = UpdatesPage.render(currentVersion, latestVersion, updateAvailable, updatePrepared,
                            updaterEnabled);
                } else if (path.equals("/files")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = getQueryParam(query, "path");
                    if (filePath == null) {
                        filePath = "";
                    }
                    html = FilesPage.render(filePath);
                } else if (path.equals("/files/edit")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = getQueryParam(query, "path");
                    if (filePath == null) {
                        filePath = "";
                    }
                    html = FilesPage.renderEditor(filePath);
                } else if (path.startsWith("/players/") && path.endsWith("/profile")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/profile", ""));
                    html = PlayerProfilePage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/teleport")) {
                    if (!ensurePermission(t, "dash.web.players.moderate", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/teleport", ""));
                    html = TeleportPage.render(playerName);
                } else if (path.startsWith("/players/")
                        && !path.substring("/players/".length()).contains("/")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", ""));
                    if (!playerName.isEmpty()) {
                        html = PlayerProfilePage.render(playerName);
                    } else {
                        html = PlayersPage.render();
                    }
                } else {
                    html = DashboardPage.render();
                }
            } finally {
                HtmlTemplate.clearUiPermissions();
                HtmlTemplate.clearBridgeContext();
            }

            sendResponse(t, html);
        }
    }

    private void serveRegistration(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Setup</title>"
                +
                "<script src=\"https://cdn.tailwindcss.com\"></script></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
                +
                "<div class=\"bg-slate-800 p-8 rounded-xl shadow-2xl w-96 border border-slate-700\">" +
                "<h2 class=\"text-2xl font-bold mb-6 text-center text-sky-400\">Dash Admin Setup</h2>" +
                "<p class=\"text-sm text-slate-400 mb-4 text-center\">Enter the registration code from /dash register</p>"
                +
                "<form action=\"/action\" method=\"post\" class=\"flex flex-col gap-4\">" +
                "<input type=\"hidden\" name=\"action\" value=\"register\">" +
                "<div><label class=\"text-sm text-slate-400\">Registration Code</label><input type=\"text\" name=\"code\" required placeholder=\"XXXXXXXX\" class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none uppercase tracking-widest text-center font-mono\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Username</label><input type=\"text\" name=\"username\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Password</label><input type=\"password\" name=\"password\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<button type=\"submit\" class=\"bg-sky-500 hover:bg-sky-600 text-white font-bold py-2 rounded transition\">Complete Setup</button>"
                +
                "</form></div></body></html>";
        sendResponse(t, html);
    }

    private void serveLogin(HttpExchange t) throws IOException {
        String error = getQueryParam(t.getRequestURI().getRawQuery(), "error");
        sendResponse(t, LoginPage.render(error));
    }

    private class ConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.console.read", true)) {
                return;
            }
            List<String> logs = ConsoleLogAppender.getLogs();
            String response = String.join("\n", logs);
            sendResponse(t, response);
        }
    }

    private class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                    t.sendResponseHeaders(405, -1);
                    t.close();
                    return;
                }

                if (!isSetupTelemetryBypass(t)) {
                    String authHeader = t.getRequestHeaders().getFirst("Authorization");
                    String bridgeSecret = FabricDash.getConfig().getString("bridge.secret", "");
                    boolean bridgeAuthorized = BridgeSecurity.bearerMatchesSecret(authHeader, bridgeSecret);
                    if (!bridgeAuthorized && !ensurePermission(t, "dash.web.stats.read", true)) {
                        return;
                    }
                }

                double currentTps = 20.0;
                try {
                    double avgTickNs = Arrays.stream(server.tickTimes).average().orElse(50_000_000.0);
                    double mspt = avgTickNs / 1_000_000.0;
                    currentTps = Math.min(20.0, 1000.0 / mspt);
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
                    if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                        double load = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                        cpuPercent = (load < 0.0) ? 0.0 : (load * 100.0);
                    }
                } catch (Exception e) {
                }

                String uptimeStr = formatUptime();
                String dashVersion = FabricDash.getModVersion();

                String json = String.format(
                        "{\"tps\": %.2f, \"ram_used\": %d, \"ram_max\": %d, \"ramUsed\": %d, \"ramMax\": %d, \"cpu_percent\": %.2f, \"cpuPercent\": %.2f, \"cpuUsage\": %.2f, \"uptime\": \"%s\", \"dashVersion\": \"%s\"}",
                        currentTps, usedMem, maxMem, usedMem, maxMem, cpuPercent, cpuPercent, cpuPercent,
                        jsonEscape(uptimeStr), jsonEscape(dashVersion));

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "{\"error\": \"Internal Error\"}");
            }
        }
    }

    private class HealthApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            if (!isSetupTelemetryBypass(t)) {
                String authHeader = t.getRequestHeaders().getFirst("Authorization");
                String secret = FabricDash.getConfig().getString("bridge.secret", "");
                if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
                    sendResponseWithStatus(t, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
            }

            String dashVersion = FabricDash.getModVersion();
            String json = "{\"status\":\"online\",\"uptime\":\"" + jsonEscape(formatUptime())
                    + "\",\"dashVersion\":\"" + jsonEscape(dashVersion) + "\"}";
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json);
        }
    }

    private class LogoutApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod()) && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            clearSession(t);
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, "{\"success\":true}");
        }
    }

    private class StatsHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.stats.read", true)) {
                return;
            }
            try {
                StatsCollector collector = FabricDash.getStatsCollector();
                String json = collector != null ? collector.getHistoryJson() : "[]";
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "[]");
            }
        }
    }

    private class UpdateDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.write", true)) {
                return;
            }

            GithubUpdater updater = FabricDash.getGithubUpdater();
            if (updater == null || !updater.isEnabled()) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Updater disabled\"}");
                return;
            }

            boolean downloaded = updater.downloadUpdate();
            if (downloaded) {
                sendResponseWithStatus(t, 200,
                        "{\"success\":true,\"message\":\"Update downloaded and staged. It will be applied automatically when the server stops.\"}");
            } else {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"Update download failed\"}");
            }
        }
    }

    private class UpdateRestartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.server.control", true)) {
                return;
            }

            FabricDash.getInstance().getScheduler().schedule(() -> server.stop(false), 2, TimeUnit.SECONDS);
            sendResponseWithStatus(t, 200, "{\"success\":true,\"message\":\"Server stop scheduled — update will be applied automatically.\"}");
        }
    }

    private class UpdateCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.read", true)) {
                return;
            }

            GithubUpdater updater = FabricDash.getGithubUpdater();
            if (updater == null || !updater.isEnabled()) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Updater disabled\"}");
                return;
            }

            String latestVersion = updater.checkForUpdates();
            boolean updateAvailable = updater.isUpdateAvailable();
            sendResponseWithStatus(t, 200,
                    "{\"success\":true,\"latestVersion\":\"" + jsonEscape(latestVersion) + "\",\"updateAvailable\":" + updateAvailable + "}");
        }
    }

    private class GlobalSettingsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.write", true)) {
                return;
            }

            String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);

            String intervalRaw = params.get("update_interval_minutes");
            if (intervalRaw != null && !intervalRaw.isBlank()) {
                try {
                    int minutes = Integer.parseInt(intervalRaw.trim());
                    if (minutes < 20 || minutes > 10080) {
                        sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Interval must be between 20 and 10080 minutes\"}");
                        return;
                    }
                    dash.data.GlobalSettingsManager gsm = FabricDash.getGlobalSettingsManager();
                    if (gsm != null) {
                        gsm.setGlobalSetting("update_interval_minutes", String.valueOf(minutes));
                    }
                    FabricDash.getInstance().rescheduleUpdater();
                    WebActionLogger.logSettingChange("update_interval_minutes", String.valueOf(minutes), getClientIp(t));
                    sendResponseWithStatus(t, 200, "{\"success\":true}");
                } catch (NumberFormatException e) {
                    sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Invalid interval value\"}");
                }
                return;
            }

            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"No recognised settings provided\"}");
        }
    }

    private class BridgeConsoleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureBridgeBearer(t)) {
                return;
            }

            if ("GET".equalsIgnoreCase(t.getRequestMethod())) {
                List<String> logs = ConsoleCatcher.getRecentLogs();
                String response = String.join("\n", logs);
                t.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                sendResponse(t, response);
                return;
            }

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String cmd = extractBridgeCommand(body, t.getRequestHeaders().getFirst("Content-Type"));
                if (cmd == null || cmd.isBlank()) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Missing command\"}");
                    return;
                }

                server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
                WebActionLogger.log("BRIDGE_COMMAND", "cmd=" + cmd + " ip=" + getClientIp(t));
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\":true}");
                return;
            }

            t.sendResponseHeaders(405, -1);
            t.close();
        }
    }

    private class WebhookApproveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String token = getQueryParam(t.getRequestURI().getRawQuery(), "token");
            String action = getQueryParam(t.getRequestURI().getRawQuery(), "action");
            boolean allow = "allow".equalsIgnoreCase(action);
            boolean deny = "deny".equalsIgnoreCase(action);
            if (!allow && !deny) {
                sendResponseWithStatus(t, 400, "Invalid action");
                return;
            }

            WebAuth.AuthResult result = auth.approveBridgeByToken(token, allow, "MODERATOR");
            if (!result.success()) {
                sendResponseWithStatus(t, 400, "Request failed: " + result.message());
                return;
            }

            String auditMsg = allow ? "allowed user=" + result.message() : "denied token=" + token;
            WebActionLogger.log("BRIDGE_WEBHOOK_APPROVAL", auditMsg + " from " + getClientIp(t));
            sendResponseWithStatus(t, 200, "Success");
        }
    }

    private class FileSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }

            if ("POST".equals(t.getRequestMethod())) {
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseFormData(body);

                String filePath = params.get("path");
                String content = params.get("content");

                if (filePath == null || content == null) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"Missing parameters\"}");
                    return;
                }

                File serverDir = FabricDash.getServerRootDirectory();
                File file = new File(serverDir, filePath);

                try {
                    if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": false, \"error\": \"Access denied\"}");
                        return;
                    }
                    if (isProtectedLockFile(file)) {
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": false, \"error\": \"Protected lock file cannot be edited\"}");
                        return;
                    }

                    if (file.exists()) {
                        File backup = new File(file.getPath() + ".bak");
                        java.nio.file.Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    java.nio.file.Files.writeString(file.toPath(), content);
                    WebActionLogger.logFileEdit(filePath, getClientIp(t));
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": true}");
                } catch (Exception e) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                }
            }
        }
    }

    private class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String relativePath = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("path=")) {
                        relativePath = URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }

            if (relativePath == null || relativePath.isBlank()) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            File serverDir = FabricDash.getServerRootDirectory();
            File file = new File(serverDir, relativePath);

            try {
                if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                    t.sendResponseHeaders(403, 0);
                    t.close();
                    return;
                }
            } catch (IOException e) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            if (!file.exists() || !file.isFile()) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            String fileName = file.getName();
            String contentType = resolveContentType(fileName);
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            t.getResponseHeaders().set("Content-Type", contentType);
            t.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + fileName.replace("\"", "_") + "\"; filename*=UTF-8''" + encodedName);
            t.sendResponseHeaders(200, file.length());

            try (OutputStream os = t.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }

        private String resolveContentType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".jar")) return "application/java-archive";
            if (lower.endsWith(".zip")) return "application/zip";
            if (lower.endsWith(".gz") || lower.endsWith(".tar.gz")) return "application/gzip";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text/yaml";
            if (lower.endsWith(".xml")) return "application/xml";
            if (lower.endsWith(".log") || lower.endsWith(".txt") || lower.endsWith(".properties")
                    || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".ini")
                    || lower.endsWith(".toml") || lower.endsWith(".md")) return "text/plain";
            return "application/octet-stream";
        }
    }


    private class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String action = params.get("action");

            if (action == null || action.isBlank()) {
                redirect(t, "/?error=missing_action");
                return;
            }

            if ("register_code".equals(action)) {
                String code = params.getOrDefault("code", "").trim().toUpperCase();
                String username = params.getOrDefault("username", "").trim();
                String password = params.getOrDefault("password", "");

                if (code.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    redirect(t, "/setup?msg=" + encodeForQuery("All fields are required."));
                    return;
                }

                boolean registered;
                if (auth.isSetupRequired()) {
                    registered = auth.registerFirstAdminWithCode(code, username, password);
                } else {
                    registered = auth.registerWithCode(code, username, password);
                }
                if (registered) {
                    setSession(t, username);
                    WebActionLogger.log("REGISTER", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/");
                } else {
                    redirect(t, "/setup?msg=" + encodeForQuery("Registration failed. Check your code."));
                }
                return;
            }

            if ("login".equals(action)) {
                String username = params.getOrDefault("username", "").trim();
                String password = params.getOrDefault("password", "");
                String twoFa = params.getOrDefault("2fa", "").trim();

                boolean authenticated = auth.check(username, password);
                if (authenticated) {
                    if (!twoFa.isBlank()) {
                        if (!auth.verifyOwner2faCode(twoFa)) {
                            WebActionLogger.log("LOGIN_FAILED", "user=" + username + " ip=" + getClientIp(t) + " reason=bad_2fa");
                            redirect(t, "/login?error=" + encodeForQuery("Invalid 2FA code"));
                            return;
                        }
                    }
                    setSession(t, username);
                    WebActionLogger.log("LOGIN", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/");
                } else {
                    WebActionLogger.log("LOGIN_FAILED", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/login?error=" + encodeForQuery("Invalid username or password"));
                }
                return;
            }

            if ("logout".equals(action)) {
                String username = getSessionUser(t);
                clearSession(t);
                WebActionLogger.log("LOGOUT", "user=" + (username == null ? "unknown" : username) + " ip=" + getClientIp(t));
                redirect(t, "/login");
                return;
            }

            if (!isAuthenticated(t)) {
                redirect(t, "/login");
                return;
            }

            if ("set_motd".equals(action)) {
                if (!ensureAnyPermission(t, false, "dash.web.settings.write", "dash.web.settings.motd.write")) {
                    return;
                }
            } else if ("set_distance".equals(action)) {
                if (!ensureAnyPermission(t, false, "dash.web.settings.write",
                        "dash.web.settings.distance.view", "dash.web.settings.distance.simulation")) {
                    return;
                }
            } else {
                String requiredPermission = requiredPermissionForAction(action);
                if (requiredPermission != null && !requiredPermission.isBlank()) {
                    if (!ensurePermission(t, requiredPermission, false)) {
                        return;
                    }
                }
            }

            String sessionUser = getSessionUser(t);

            switch (action) {
                case "invite_generate" -> {
                    RegistrationManager regManager = FabricDash.getRegistrationManager();
                    String inviteCode = regManager.generateCode("UNBOUND", sessionUser, "MODERATOR", List.of());
                    if (inviteCode != null) {
                        WebActionLogger.log("INVITE_GENERATE", "user=" + sessionUser + " ip=" + getClientIp(t));
                        for (ServerWorld world : server.getWorlds()) {
                            for (ServerPlayerEntity player : world.getPlayers()) {
                                if (CommandManager.OWNERS_CHECK.allows(player.getPermissions())) {
                                    String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), inviteCode);
                                    Text msg = Text.literal("[Dash] ").formatted(Formatting.GOLD)
                                            .append(Text.literal("Invitation code generated: ").formatted(Formatting.YELLOW))
                                            .append(Text.literal(inviteCode).formatted(Formatting.GREEN, Formatting.BOLD))
                                            .append(Text.literal(" — ").formatted(Formatting.GRAY))
                                            .append(Text.literal("Click to open setup").styled(s ->
                                                    s.withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                                                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(setupUrl)))));
                                    player.sendMessage(msg);
                                }
                            }
                        }
                        redirect(t, "/users?code=" + encodeForQuery(inviteCode) + "&msg=" + encodeForQuery("Invite code: " + inviteCode));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Failed to generate invite code"));
                    }
                }

                case "role_permissions_save" -> {
                    String role = params.getOrDefault("role", "").trim();
                    if (role.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role"));
                        return;
                    }
                    List<String> permissions = new ArrayList<>();
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (entry.getKey().startsWith("perm_") && "on".equals(entry.getValue())) {
                            permissions.add(entry.getKey().substring(5));
                        }
                    }
                    WebAuth.AuthResult result = auth.updateRolePermissionsSafe(sessionUser, role, permissions, List.of());
                    if (result.success()) {
                        WebActionLogger.log("ROLE_PERMISSIONS_SAVE", "user=" + sessionUser + " role=" + role + " perms=" + permissions.size() + " ip=" + getClientIp(t));
                    }
                    String msg = result.success() ? "Permissions saved for role: " + role : humanizeRolePermissionError(result.message());
                    redirect(t, "/permissions?role=" + encodeForQuery(role) + "&msg=" + encodeForQuery(msg));
                }

                case "role_create" -> {
                    String roleName = params.getOrDefault("role_name", "").trim();
                    String roleValueStr = params.getOrDefault("role_value", "").trim();
                    if (roleName.isBlank() || roleValueStr.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Role name and value required"));
                        return;
                    }
                    int roleValue;
                    try {
                        roleValue = Integer.parseInt(roleValueStr);
                    } catch (NumberFormatException e) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Invalid role value"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.createRoleSafe(sessionUser, roleName, "MODERATOR");
                    String msg = result.success() ? "Role created: " + roleName : humanizeRoleCreationError(result.message());
                    WebActionLogger.log("ROLE_CREATE", "user=" + sessionUser + " role=" + roleName + " value=" + roleValue + " ip=" + getClientIp(t));
                    redirect(t, "/permissions?msg=" + encodeForQuery(msg));
                }

                case "role_set_value" -> {
                    String role = params.getOrDefault("role", "").trim();
                    String newValueStr = params.getOrDefault("new_value", "").trim();
                    if (role.isBlank() || newValueStr.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role or value"));
                        return;
                    }
                    int newValue;
                    try {
                        newValue = Integer.parseInt(newValueStr);
                    } catch (NumberFormatException e) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Invalid value"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.setRoleValueSafe(sessionUser, role, newValue);
                    if (result.success()) {
                        WebActionLogger.log("ROLE_SET_VALUE", "user=" + sessionUser + " role=" + role + " value=" + newValue + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/permissions?msg=" + encodeForQuery(result.success() ? "Role value updated" : result.message()));
                }

                case "role_delete" -> {
                    String role = params.getOrDefault("role", "").trim();
                    if (role.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.deleteRoleSafe(sessionUser, role);
                    if (result.success()) {
                        WebActionLogger.log("ROLE_DELETE", "user=" + sessionUser + " role=" + role + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/permissions?msg=" + encodeForQuery(result.success() ? "Role deleted" : result.message()));
                }

                case "user_set_role" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    String role = params.getOrDefault("role", "").trim();
                    if (targetUser.isBlank() || role.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username or role"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.setUserRoleSafe(sessionUser, targetUser, role);
                    if (result.success()) {
                        WebActionLogger.log("USER_SET_ROLE", "user=" + sessionUser + " target=" + targetUser + " role=" + role + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Role updated for " + targetUser : result.message()));
                }

                case "user_make_main_admin" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    if (targetUser.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.transferMainAdmin(sessionUser, targetUser);
                    if (result.success()) {
                        WebActionLogger.log("USER_MAKE_MAIN_ADMIN", "from=" + sessionUser + " to=" + targetUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Main admin transferred to " + targetUser : result.message()));
                }

                case "user_delete" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    if (targetUser.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.deleteUserSafe(sessionUser, targetUser);
                    if (result.success()) {
                        WebActionLogger.log("USER_DELETE", "user=" + sessionUser + " deleted=" + targetUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "User deleted: " + targetUser : result.message()));
                }

                case "save_plugin_settings" -> {
                    applyPluginSettings(params, t);
                    redirect(t, "/plugin-settings?msg=" + encodeForQuery("Settings saved"));
                }

                case "task_add" -> {
                    String taskType = firstNonBlank(params.get("task_type"), params.get("task_name")).trim();
                    String taskPayload = firstNonBlank(params.get("payload"), params.get("task_command")).trim();
                    String taskInterval = firstNonBlank(params.get("interval"), params.get("task_interval")).trim();
                    String taskUnit = params.getOrDefault("task_unit", "MINUTES").trim();
                    if (taskType.isBlank() || taskPayload.isBlank() || taskInterval.isBlank()) {
                        redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Missing required fields"));
                        return;
                    }
                    long intervalVal;
                    try {
                        intervalVal = Long.parseLong(taskInterval);
                    } catch (NumberFormatException e) {
                        redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Invalid interval"));
                        return;
                    }
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null) {
                        int intervalMinutes = (int) intervalVal;
                        if ("HOURS".equalsIgnoreCase(taskUnit)) intervalMinutes = (int) (intervalVal * 60);
                        else if ("SECONDS".equalsIgnoreCase(taskUnit)) intervalMinutes = Math.max(1, (int) (intervalVal / 60));
                        if ("message".equalsIgnoreCase(taskType)) {
                            taskType = dash.data.ScheduledTaskManager.TYPE_BROADCAST;
                        }
                        int createdId = stm.addTask(taskType, intervalMinutes, taskPayload, true);
                        if (createdId > 0) {
                            WebActionLogger.log("TASK_ADD", "user=" + sessionUser + " task=" + taskType + " payload=" + taskPayload + " ip=" + getClientIp(t));
                        } else {
                            redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Invalid task values"));
                            return;
                        }
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task added"));
                }

                case "task_toggle" -> {
                    String taskId = params.getOrDefault("task_id", "").trim();
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null && !taskId.isBlank()) {
                        try {
                            int id = Integer.parseInt(taskId);
                            dash.data.ScheduledTaskManager.ScheduledTask task = stm.getTask(id);
                            if (task != null) {
                                stm.setEnabled(id, !task.enabled());
                            }
                        } catch (NumberFormatException ignored) {}
                        WebActionLogger.log("TASK_TOGGLE", "user=" + sessionUser + " task=" + taskId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task toggled"));
                }

                case "task_delete" -> {
                    String taskId = params.getOrDefault("task_id", "").trim();
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null && !taskId.isBlank()) {
                        try { stm.deleteTask(Integer.parseInt(taskId)); } catch (NumberFormatException ignored) {}
                        WebActionLogger.log("TASK_DELETE", "user=" + sessionUser + " task=" + taskId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task deleted"));
                }

                case "registration_approve" -> {
                    String pendingId = params.getOrDefault("username", "").trim();
                    dash.RegistrationApprovalManager ram = FabricDash.getRegistrationApprovalManager();
                    if (ram != null && !pendingId.isBlank()) {
                        RegistrationApprovalManager.PendingRegistration pending = ram.consume(pendingId);
                        if (pending != null) {
                            auth.registerApprovedPending(pending);
                            WebActionLogger.log("REGISTRATION_APPROVE", "user=" + sessionUser + " approved=" + pending.username() + " ip=" + getClientIp(t));
                        }
                    }
                    redirect(t, "/users?msg=" + encodeForQuery("Registration approved"));
                }

                case "registration_deny" -> {
                    String pendingId = params.getOrDefault("username", "").trim();
                    dash.RegistrationApprovalManager ram = FabricDash.getRegistrationApprovalManager();
                    if (ram != null && !pendingId.isBlank()) {
                        ram.deny(pendingId);
                        WebActionLogger.log("REGISTRATION_DENY", "user=" + sessionUser + " denied=" + pendingId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery("Registration denied"));
                }

                case "bridge_user_allow" -> {
                    String bridgeUser = params.getOrDefault("username", "").trim();
                    String bridgeRole = params.getOrDefault("role", "MODERATOR").trim();
                    if (!bridgeUser.isBlank()) {
                        WebAuth.AuthResult result = auth.approveBridgeUserSafe(sessionUser, bridgeUser, bridgeRole, List.of());
                        if (result.success()) {
                            WebActionLogger.log("BRIDGE_USER_ALLOW", "user=" + sessionUser + " allowed=" + bridgeUser + " role=" + bridgeRole + " ip=" + getClientIp(t));
                        }
                        redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user approved: " + bridgeUser : result.message()));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                    }
                }

                case "bridge_user_deny" -> {
                    String bridgeUser = params.getOrDefault("username", "").trim();
                    if (!bridgeUser.isBlank()) {
                        WebAuth.AuthResult result = auth.denyBridgeUserSafe(sessionUser, bridgeUser);
                        if (result.success()) {
                            WebActionLogger.log("BRIDGE_USER_DENY", "user=" + sessionUser + " denied=" + bridgeUser + " ip=" + getClientIp(t));
                        }
                        redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user denied: " + bridgeUser : result.message()));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                    }
                }

                case "owner_2fa_regen" -> {
                    String regenResult = auth.regenerateOwner2faSecret(sessionUser);
                    boolean success = regenResult != null && !regenResult.isBlank();
                    if (success) {
                        WebActionLogger.log("2FA_REGEN", "user=" + sessionUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/settings?msg=" + encodeForQuery(success ? "2FA secret regenerated" : "2FA regeneration failed"));
                }

                case "file_delete" -> {
                    String filePath = params.getOrDefault("path", "").trim();
                    if (filePath.isBlank()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Missing path"));
                        return;
                    }
                    File serverDir = FabricDash.getServerRootDirectory();
                    File file = new File(serverDir, filePath);
                    try {
                        if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        if (isProtectedLockFile(file)) {
                            redirect(t, "/files?msg=" + encodeForQuery("Protected file cannot be deleted"));
                            return;
                        }
                        if (file.isDirectory()) {
                            deleteRecursively(file);
                        } else {
                            file.delete();
                        }
                        WebActionLogger.log("FILE_DELETE", "path=" + filePath + " ip=" + getClientIp(t));
                        String parentDir = filePath.contains("/") ? filePath.substring(0, filePath.lastIndexOf('/')) : "";
                        redirect(t, "/files?path=" + encodeForQuery(parentDir));
                    } catch (Exception e) {
                        redirect(t, "/files?msg=" + encodeForQuery("Delete failed: " + e.getMessage()));
                    }
                }

                case "file_rename" -> {
                    String oldPath = params.getOrDefault("path", "").trim();
                    String newName = params.getOrDefault("new_name", "").trim();
                    if (oldPath.isBlank() || newName.isBlank()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Missing path or name"));
                        return;
                    }
                    File serverDir = FabricDash.getServerRootDirectory();
                    File oldFile = new File(serverDir, oldPath);
                    try {
                        if (!oldFile.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        File newFile = new File(oldFile.getParentFile(), newName);
                        if (!newFile.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        oldFile.renameTo(newFile);
                        WebActionLogger.log("FILE_RENAME", "from=" + oldPath + " to=" + newName + " ip=" + getClientIp(t));
                        String parentDir = oldPath.contains("/") ? oldPath.substring(0, oldPath.lastIndexOf('/')) : "";
                        redirect(t, "/files?path=" + encodeForQuery(parentDir));
                    } catch (Exception e) {
                        redirect(t, "/files?msg=" + encodeForQuery("Rename failed: " + e.getMessage()));
                    }
                }

                case "plugin_delete" -> {
                    String pluginName = params.getOrDefault("plugin", "").trim();
                    if (pluginName.isBlank()) {
                        redirect(t, "/plugins?msg=" + encodeForQuery("Missing mod name"));
                        return;
                    }
                    File modsDir = new File(FabricDash.getServerRootDirectory(), "mods");
                    File[] modFiles = modsDir.listFiles((d, name) -> name.toLowerCase().contains(pluginName.toLowerCase()) && name.endsWith(".jar"));
                    if (modFiles != null && modFiles.length > 0) {
                        for (File mf : modFiles) {
                            mf.delete();
                        }
                        WebActionLogger.log("MOD_DELETE", "user=" + sessionUser + " mod=" + pluginName + " ip=" + getClientIp(t));
                        redirect(t, "/plugins?msg=" + encodeForQuery("Mod file(s) deleted: " + pluginName + " (restart required)"));
                    } else {
                        redirect(t, "/plugins?msg=" + encodeForQuery("No matching mod files found for: " + pluginName));
                    }
                }

                case "command", "restart", "stop", "kick", "ban", "freeze",
                     "tp_to_coords", "tp_player_to_player",
                     "gamerule",
                     "gamerule_keep_inventory", "gamerule_mob_spawning", "gamerule_daylight_cycle",
                     "gamerule_weather_cycle", "gamerule_mob_griefing", "gamerule_fire_tick",
                     "gamerule_natural_regeneration",
                     "whitelist_add", "whitelist_remove", "whitelist_toggle",
                     "plugin_enable", "plugin_disable",
                     "chat", "mute",
                     "backup_create", "backup_delete", "backup_schedule",
                     "datapack_toggle", "datapack_delete",
                     "set_motd", "spark_profile",
                     "add_note", "delete_note", "set_distance",
                     "give_item", "give_enderchest" -> {
                    handleServerAction(action, params, t, sessionUser);
                }

                default -> redirect(t, "/?error=unknown_action");
            }
        }
    }

    private void handleServerAction(String action, Map<String, String> params, HttpExchange t, String sessionUser) throws IOException {
        server.execute(() -> {
            try {
                switch (action) {
                    case "command" -> {
                        String cmd = firstNonBlank(params.get("command"), params.get("cmd")).trim();
                        if (!cmd.isBlank()) {
                            server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd);
                            WebActionLogger.log("COMMAND", "user=" + sessionUser + " cmd=" + cmd + " ip=" + getClientIp(t));
                        }
                    }

                    case "restart" -> {
                        WebActionLogger.log("RESTART", "user=" + sessionUser + " ip=" + getClientIp(t));
                        server.getPlayerManager().broadcast(
                                Text.literal("[Dash] Server is restarting...").formatted(Formatting.YELLOW), false);
                        FabricDash.getInstance().getScheduler().schedule(() -> server.stop(false), 2, TimeUnit.SECONDS);
                    }

                    case "stop" -> {
                        WebActionLogger.log("STOP", "user=" + sessionUser + " ip=" + getClientIp(t));
                        server.getPlayerManager().broadcast(
                                Text.literal("[Dash] Server is stopping...").formatted(Formatting.RED), false);
                        FabricDash.getInstance().getScheduler().schedule(() -> server.stop(false), 2, TimeUnit.SECONDS);
                    }

                    case "kick" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String reason = params.getOrDefault("reason", "Kicked by admin").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                            if (player != null) {
                                player.networkHandler.disconnect(Text.literal(reason));
                                WebActionLogger.log("KICK", "user=" + sessionUser + " player=" + playerName + " reason=" + reason + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "ban" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String reason = params.getOrDefault("reason", "Banned by admin").trim();
                        if (!playerName.isBlank()) {
                            Optional<PlayerConfigEntry> profileOpt = server.getApiServices().nameToIdCache().findByName(playerName);
                            if (profileOpt.isPresent()) {
                                PlayerConfigEntry profile = profileOpt.get();
                                server.getPlayerManager().getUserBanList().add(
                                        new BannedPlayerEntry(profile, null, sessionUser, null, reason));
                                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                                if (player != null) {
                                    player.networkHandler.disconnect(Text.literal("Banned: " + reason));
                                }
                                WebActionLogger.log("BAN", "user=" + sessionUser + " player=" + playerName + " reason=" + reason + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "freeze" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                            if (player != null) {
                                boolean frozenNow = FreezeManager.toggleFreeze(player.getUuid());
                                if (frozenNow) {
                                    server.getCommandManager().parseAndExecute(server.getCommandSource(),
                                            "title " + playerName + " title {\"text\":\"You have been frozen\",\"color\":\"red\",\"bold\":true}");
                                } else {
                                    server.getCommandManager().parseAndExecute(server.getCommandSource(),
                                            "title " + playerName + " title {\"text\":\"You are unfrozen\",\"color\":\"green\",\"bold\":true}");
                                }
                                WebActionLogger.log("FREEZE", "user=" + sessionUser + " player=" + playerName + " state=" + (frozenNow ? "frozen" : "unfrozen") + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "tp_to_coords" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String xStr = params.getOrDefault("x", "").trim();
                        String yStr = params.getOrDefault("y", "").trim();
                        String zStr = params.getOrDefault("z", "").trim();
                        if (!playerName.isBlank() && !xStr.isBlank() && !yStr.isBlank() && !zStr.isBlank()) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                            if (player != null) {
                                try {
                                    double x = Double.parseDouble(xStr);
                                    double y = Double.parseDouble(yStr);
                                    double z = Double.parseDouble(zStr);
                                    player.requestTeleport(x, y, z);
                                    WebActionLogger.log("TELEPORT", "user=" + sessionUser + " player=" + playerName
                                            + " to=" + x + "," + y + "," + z + " ip=" + getClientIp(t));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }

                    case "tp_player_to_player" -> {
                        String target = firstNonBlank(params.get("player"), params.get("target")).trim();
                        String dest = params.getOrDefault("destination", "").trim();
                        if (!target.isBlank() && !dest.isBlank()) {
                            server.getCommandManager().parseAndExecute(server.getCommandSource(), "tp " + target + " " + dest);
                            WebActionLogger.log("TELEPORT_TO_PLAYER", "user=" + sessionUser + " target=" + target + " dest=" + dest + " ip=" + getClientIp(t));
                        }
                    }

                    case "gamerule" -> {
                        String ruleName = params.getOrDefault("rule", "").trim();
                        switch (ruleName) {
                            case "keepInventory" ->
                                    setGameRuleBool(params, GameRules.KEEP_INVENTORY, "keepInventory", sessionUser, t);
                            case "doMobSpawning" ->
                                    setGameRuleBool(params, GameRules.DO_MOB_SPAWNING, "doMobSpawning", sessionUser, t);
                            case "doDaylightCycle" ->
                                    setGameRuleBool(params, GameRules.ADVANCE_TIME, "doDaylightCycle", sessionUser, t);
                            case "doWeatherCycle" ->
                                    setGameRuleBool(params, GameRules.ADVANCE_WEATHER, "doWeatherCycle", sessionUser, t);
                            case "mobGriefing" ->
                                    setGameRuleBool(params, GameRules.DO_MOB_GRIEFING, "mobGriefing", sessionUser, t);
                            case "naturalRegeneration" ->
                                    setGameRuleBool(params, GameRules.NATURAL_HEALTH_REGENERATION, "naturalRegeneration", sessionUser, t);
                            default -> {
                            }
                        }
                    }

                    case "gamerule_keep_inventory" -> setGameRuleBool(params, GameRules.KEEP_INVENTORY, "keepInventory", sessionUser, t);
                    case "gamerule_mob_spawning" -> setGameRuleBool(params, GameRules.DO_MOB_SPAWNING, "doMobSpawning", sessionUser, t);
                    case "gamerule_daylight_cycle" -> setGameRuleBool(params, GameRules.ADVANCE_TIME, "doDaylightCycle", sessionUser, t);
                    case "gamerule_weather_cycle" -> setGameRuleBool(params, GameRules.ADVANCE_WEATHER, "doWeatherCycle", sessionUser, t);
                    case "gamerule_mob_griefing" -> setGameRuleBool(params, GameRules.DO_MOB_GRIEFING, "mobGriefing", sessionUser, t);
                    case "gamerule_natural_regeneration" -> setGameRuleBool(params, GameRules.NATURAL_HEALTH_REGENERATION, "naturalRegeneration", sessionUser, t);

                    case "whitelist_add" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            Optional<PlayerConfigEntry> profileOpt = server.getApiServices().nameToIdCache().findByName(playerName);
                            if (profileOpt.isPresent()) {
                                server.getPlayerManager().getWhitelist().add(new WhitelistEntry(profileOpt.get()));
                                WebActionLogger.log("WHITELIST_ADD", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "whitelist_remove" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            Optional<PlayerConfigEntry> profileOpt = server.getApiServices().nameToIdCache().findByName(playerName);
                            if (profileOpt.isPresent()) {
                                server.getPlayerManager().getWhitelist().remove(profileOpt.get());
                                WebActionLogger.log("WHITELIST_REMOVE", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "whitelist_toggle" -> {
                        boolean current = server.getPlayerManager().isWhitelistEnabled();
                        if (server instanceof net.minecraft.server.dedicated.MinecraftDedicatedServer dedicated) {
                            dedicated.setUseAllowlist(!current);
                        }
                        WebActionLogger.log("WHITELIST_TOGGLE", "user=" + sessionUser + " enabled=" + !current + " ip=" + getClientIp(t));
                    }

                    case "plugin_enable" -> {
                        FabricDash.LOGGER.warn("Fabric mods cannot be toggled at runtime. Ignoring plugin_enable.");
                    }

                    case "plugin_disable" -> {
                        FabricDash.LOGGER.warn("Fabric mods cannot be toggled at runtime. Ignoring plugin_disable.");
                    }

                    case "chat" -> {
                        String message = params.getOrDefault("message", "").trim();
                        if (!message.isBlank()) {
                            server.getPlayerManager().broadcast(
                                    Text.literal("<Dash> " + message).formatted(Formatting.LIGHT_PURPLE), false);
                            WebActionLogger.log("CHAT", "user=" + sessionUser + " msg=" + message + " ip=" + getClientIp(t));
                        }
                    }

                    case "mute" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            FabricDash.LOGGER.warn("Mute is not natively supported in Fabric. Consider using a mute mod or command.");
                            WebActionLogger.log("MUTE_ATTEMPT", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                        }
                    }

                    case "backup_create" -> {
                        dash.data.BackupManager bm = FabricDash.getBackupManager();
                        if (bm != null) {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    bm.createBackup();
                                    WebActionLogger.log("BACKUP_CREATE", "user=" + sessionUser + " ip=" + getClientIp(t));
                                } catch (Exception e) {
                                    FabricDash.LOGGER.error("Backup failed: " + e.getMessage());
                                }
                            });
                        }
                    }

                    case "backup_delete" -> {
                        String backupName = firstNonBlank(params.get("backup"), params.get("name")).trim();
                        dash.data.BackupManager bm = FabricDash.getBackupManager();
                        if (bm != null && !backupName.isBlank()) {
                            bm.deleteBackup(backupName);
                            WebActionLogger.log("BACKUP_DELETE", "user=" + sessionUser + " backup=" + backupName + " ip=" + getClientIp(t));
                        }
                    }

                    case "backup_schedule" -> {
                        String intervalStr = firstNonBlank(params.get("interval"), params.get("hours")).trim();
                        dash.data.BackupManager bm = FabricDash.getBackupManager();
                        if (bm != null && !intervalStr.isBlank()) {
                            try {
                                int interval = Integer.parseInt(intervalStr);
                                if (interval <= 0) {
                                    bm.stopSchedule();
                                } else {
                                    bm.startSchedule(interval);
                                }
                                WebActionLogger.log("BACKUP_SCHEDULE", "user=" + sessionUser + " interval=" + interval + " ip=" + getClientIp(t));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    case "datapack_toggle" -> {
                        String packName = firstNonBlank(params.get("datapack"), params.get("name")).trim();
                        boolean enable = Boolean.parseBoolean(params.getOrDefault("enable", "true"));
                        if (!packName.isBlank()) {
                            if (dash.data.DatapackManager.toggleDatapack(packName, enable)) {
                                WebActionLogger.log("DATAPACK_TOGGLE", "user=" + sessionUser + " pack=" + packName + " enable=" + enable + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "datapack_delete" -> {
                        String packName = firstNonBlank(params.get("datapack"), params.get("name")).trim();
                        if (!packName.isBlank()) {
                            if (dash.data.DatapackManager.deleteDatapack(packName)) {
                                WebActionLogger.log("DATAPACK_DELETE", "user=" + sessionUser + " pack=" + packName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "set_motd" -> {
                        String motd = params.getOrDefault("motd", "");
                        File propsFile = new File(FabricDash.getServerRootDirectory(), "server.properties");
                        Properties props = new Properties();
                        if (propsFile.exists()) {
                            try (FileInputStream in = new FileInputStream(propsFile)) {
                                props.load(in);
                            }
                        }
                        props.setProperty("motd", motd);
                        try (FileOutputStream out = new FileOutputStream(propsFile)) {
                            props.store(out, "Updated by FabricDash");
                        }
                        try {
                            server.setMotd(motd);
                        } catch (Exception ignored) {
                        }
                        WebActionLogger.log("SET_MOTD", "user=" + sessionUser + " ip=" + getClientIp(t));
                    }

                    case "spark_profile" -> {
                        FabricDash.LOGGER.info("Spark profiling action is disabled in FabricDash.");
                    }

                    case "add_note" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String uuidParam = params.getOrDefault("uuid", "").trim();
                        String note = params.getOrDefault("note", "").trim();
                        if ((!playerName.isBlank() || !uuidParam.isBlank()) && !note.isBlank()) {
                            dash.data.PlayerDataManager pdm = FabricDash.getPlayerDataManager();
                            if (pdm != null) {
                                String uuid = uuidParam;
                                if (uuid.isBlank()) {
                                    uuid = playerName;
                                    Optional<PlayerConfigEntry> profile = server.getApiServices().nameToIdCache().findByName(playerName);
                                    if (profile.isPresent()) {
                                        uuid = profile.get().id().toString();
                                    }
                                }
                                pdm.addNote(uuid, sessionUser, note);
                                WebActionLogger.log("ADD_NOTE", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "delete_note" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String noteIndex = firstNonBlank(params.get("note_index"), params.get("id")).trim();
                        if (!noteIndex.isBlank()) {
                            dash.data.PlayerDataManager pdm = FabricDash.getPlayerDataManager();
                            if (pdm != null) {
                                try {
                                    int noteId = Integer.parseInt(noteIndex);
                                    pdm.deleteNote(noteId);
                                    WebActionLogger.log("DELETE_NOTE", "user=" + sessionUser + " player=" + playerName + " noteId=" + noteId + " ip=" + getClientIp(t));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }

                    case "set_distance" -> {
                        String viewStr = firstNonBlank(params.get("view"), params.get("distance")).trim();
                        String simStr = params.getOrDefault("sim", "").trim();
                        if (!viewStr.isBlank() || !simStr.isBlank()) {
                            try {
                                File propsFile = new File(FabricDash.getServerRootDirectory(), "server.properties");
                                Properties props = new Properties();
                                if (propsFile.exists()) {
                                    try (FileInputStream in = new FileInputStream(propsFile)) {
                                        props.load(in);
                                    }
                                }
                                if (!viewStr.isBlank()) {
                                    int view = Math.max(2, Math.min(32, Integer.parseInt(viewStr)));
                                    try {
                                        server.getPlayerManager().setViewDistance(view);
                                    } catch (Exception ignored) {
                                    }
                                    props.setProperty("view-distance", String.valueOf(view));
                                }
                                if (!simStr.isBlank()) {
                                    int sim = Math.max(2, Math.min(32, Integer.parseInt(simStr)));
                                    try {
                                        server.getPlayerManager().getClass()
                                                .getMethod("setSimulationDistance", int.class)
                                                .invoke(server.getPlayerManager(), sim);
                                    } catch (Exception ignored) {
                                    }
                                    props.setProperty("simulation-distance", String.valueOf(sim));
                                }
                                try (FileOutputStream out = new FileOutputStream(propsFile)) {
                                    props.store(out, "Updated by FabricDash");
                                }
                                WebActionLogger.log("SET_DISTANCE", "user=" + sessionUser + " view=" + viewStr + " sim=" + simStr + " ip=" + getClientIp(t));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    case "give_item" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String itemName = firstNonBlank(params.get("item"), params.get("material")).trim();
                        String amountStr = params.getOrDefault("amount", "1").trim();
                        if (!playerName.isBlank() && !itemName.isBlank()) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                            if (player != null) {
                                String normalizedItem = itemName.toLowerCase().replace(" ", "_");
                                if (!normalizedItem.contains(":")) {
                                    normalizedItem = "minecraft:" + normalizedItem;
                                }
                                Identifier id = Identifier.tryParse(normalizedItem);
                                if (id != null) {
                                    Item item = Registries.ITEM.get(id);
                                    if (item != Items.AIR) {
                                        int amount = 1;
                                        try { amount = Math.max(1, Math.min(64, Integer.parseInt(amountStr))); } catch (NumberFormatException ignored) {}
                                        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item, amount);
                                        player.getInventory().insertStack(stack);
                                        WebActionLogger.log("GIVE_ITEM", "user=" + sessionUser + " player=" + playerName + " item=" + itemName + " amount=" + amount + " ip=" + getClientIp(t));
                                    }
                                }
                            }
                        }
                    }

                    case "give_enderchest" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String itemName = firstNonBlank(params.get("item"), params.get("material")).trim();
                        String amountStr = params.getOrDefault("amount", "1").trim();
                        if (!playerName.isBlank() && !itemName.isBlank()) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                            if (player != null) {
                                String normalizedItem = itemName.toLowerCase().replace(" ", "_");
                                if (!normalizedItem.contains(":")) {
                                    normalizedItem = "minecraft:" + normalizedItem;
                                }
                                Identifier id = Identifier.tryParse(normalizedItem);
                                if (id != null) {
                                    Item item = Registries.ITEM.get(id);
                                    if (item != Items.AIR) {
                                        int amount = 1;
                                        try { amount = Math.max(1, Math.min(64, Integer.parseInt(amountStr))); } catch (NumberFormatException ignored) {}
                                        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item, amount);
                                        player.getEnderChestInventory().addStack(stack);
                                        WebActionLogger.log("GIVE_ENDERCHEST", "user=" + sessionUser + " player=" + playerName + " item=" + itemName + " amount=" + amount + " ip=" + getClientIp(t));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                FabricDash.LOGGER.error("Error executing server action '" + action + "': " + ex.getMessage());
            }
        });

        String redirectTarget = switch (action) {
            case "command" -> "/console";
            case "restart", "stop" -> "/";
            case "kick", "ban", "freeze", "tp_to_coords", "tp_player_to_player", "mute",
                 "add_note", "delete_note", "give_item", "give_enderchest" -> {
                String player = params.getOrDefault("player", "");
                yield player.isBlank() ? "/players" : "/players/" + encodeForQuery(player) + "/profile";
            }
            case "chat" -> "/console";
            case "set_motd" -> "/settings?msg=" + encodeForQuery("MOTD updated");
            case "spark_profile" -> "/settings?msg=" + encodeForQuery("Spark profiling started");
            case "set_distance" -> "/settings?msg=" + encodeForQuery("View distance updated");
            case "backup_create", "backup_delete", "backup_schedule" -> "/settings";
            case "datapack_toggle", "datapack_delete" -> "/settings";
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "/settings";
            case "plugin_enable", "plugin_disable" -> "/plugins";
            default -> "/settings";
        };

        redirect(t, redirectTarget);
    }

    private void setGameRuleBool(Map<String, String> params, GameRule<Boolean> rule, String name, String sessionUser, HttpExchange t) {
        String valueStr = params.getOrDefault("value", "true").trim();
        boolean value = "true".equalsIgnoreCase(valueStr) || "on".equalsIgnoreCase(valueStr) || "1".equals(valueStr);
        for (ServerWorld world : server.getWorlds()) {
            world.getGameRules().setValue(rule, value, server);
        }
        WebActionLogger.log("GAMERULE", "user=" + sessionUser + " rule=" + name + " value=" + value + " ip=" + getClientIp(t));
    }


    private void ensureUpdaterConfigStructure() {
        FabricConfig config = FabricDash.getConfig();
        if (!config.contains("updater.enabled")) {
            config.set("updater.enabled", false);
        }
        if (!config.contains("updater.repo")) {
            config.set("updater.repo", "");
        }
        if (!config.contains("updater.check_interval_minutes")) {
            config.set("updater.check_interval_minutes", 120);
        }
        config.save();
    }

    private void applyPluginSettings(Map<String, String> params, HttpExchange t) {
        FabricConfig config = FabricDash.getConfig();

        if (params.containsKey("bridge_enabled")
                || params.containsKey("bridge_secret")
                || params.containsKey("bridge_master_url")) {
            config.set("bridge.enabled", params.containsKey("bridge_enabled"));
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("action".equals(key) || key.startsWith("wh_url_") || key.startsWith("wh_evt_") || "wh_count".equals(key)) {
                continue;
            }

            if ("bridge_enabled".equals(key)) {
                config.set("bridge.enabled", "on".equalsIgnoreCase(value));
            } else if ("bridge_secret".equals(key)) {
                if (value != null && !value.isBlank()) {
                    config.set("bridge.secret", value.trim());
                }
            } else if ("bridge_master_url".equals(key)) {
                config.set("bridge.master_url", value == null ? "" : value.trim());
            } else if ("panel_url".equals(key)) {
                config.set("panel-url", value == null ? "" : value.trim());
            } else if ("web_port".equals(key)) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed >= 1 && parsed <= 65535) {
                        config.set("port", parsed);
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if ("max_backups".equals(key)) {
                try {
                    int parsed = Integer.parseInt(value);
                    config.set("backups.max-backups", Math.max(1, Math.min(100, parsed)));
                } catch (NumberFormatException ignored) {
                }
            } else if ("registration_approval_required".equals(key)) {
                config.set("registration.approval_required", "on".equalsIgnoreCase(value));
            } else if ("audit_enabled".equals(key)) {
                config.set("audit.enabled", "on".equalsIgnoreCase(value));
            } else if ("updater_enabled".equals(key)) {
                config.set("updater.enabled", "on".equalsIgnoreCase(value));
            } else if ("updater_repo".equals(key)) {
                config.set("updater.repo", value);
            } else if (key.startsWith("webhook_") && key.endsWith("_url")) {
                // legacy webhook_* compatibility
                String webhookName = key.substring(8, key.length() - 4);
                config.set("webhooks." + webhookName + ".url", value);
                String enabledKey = "webhook_" + webhookName + "_enabled";
                boolean enabled = "on".equals(params.getOrDefault(enabledKey, "off"));
                config.set("webhooks." + webhookName + ".enabled", enabled);
            } else if (key.startsWith("webhook_") && key.endsWith("_enabled")) {
                // handled with webhook url key
            } else {
                config.set(key, value);
            }
        }

        // New webhook editor format: wh_url_<idx> + wh_evt_<idx>_<event>
        List<DiscordWebhookManager.WebhookEntry> webhookEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("wh_url_")) {
                continue;
            }
            String idx = key.substring("wh_url_".length()).trim();
            if (idx.isBlank()) {
                continue;
            }
            String url = entry.getValue() == null ? "" : entry.getValue().trim();
            if (url.isBlank()) {
                continue;
            }
            List<String> events = new ArrayList<>();
            for (String evt : DiscordWebhookManager.ALL_EVENTS) {
                String eventKey = "wh_evt_" + idx + "_" + evt;
                if ("on".equalsIgnoreCase(params.get(eventKey))) {
                    events.add(evt);
                }
            }
            if (events.isEmpty()) {
                events.add(DiscordWebhookManager.EVENT_AUDIT);
            }
            webhookEntries.add(new DiscordWebhookManager.WebhookEntry(url, events));
        }
        DiscordWebhookManager webhookManager = FabricDash.getDiscordWebhookManager();
        if (webhookManager != null) {
            webhookManager.saveWebhooks(webhookEntries);
        } else {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (DiscordWebhookManager.WebhookEntry entry : webhookEntries) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("url", entry.url());
                row.put("events", new ArrayList<>(entry.events()));
                serialized.add(row);
            }
            config.set("discord-webhooks", serialized);
        }

        config.save();
        WebActionLogger.logSettingChange("plugin_settings", "bulk", getClientIp(t));
    }

    private class SettingsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String method = t.getRequestMethod();
            if ("POST".equalsIgnoreCase(method)) {
                if (!ensurePermission(t, "dash.web.pluginsettings.write", true)) {
                    return;
                }
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseFormData(body);
                applyPluginSettings(params, t);
                t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                sendResponseWithStatus(t, 200, "{\"success\":true}");
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String key = getQueryParam(query, "key");
            if (key == null || key.isBlank()) {
                t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Missing key\"}");
                return;
            }

            String value = FabricDash.getConfig().getString(key, "");
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponse(t, "{\"success\":true,\"key\":\"" + jsonEscape(key) + "\",\"value\":\"" + jsonEscape(value) + "\"}");
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private boolean isSetupTelemetryBypass(HttpExchange t) {
        return auth.isSetupRequired();
    }

    private String requiredPermissionForAction(String action) {
        return switch (action) {
            case "command" -> "dash.web.console.command";
            case "restart", "stop" -> "dash.web.server.control";
            case "kick", "ban", "freeze", "mute" -> "dash.web.players.moderate";
            case "tp_to_coords", "tp_player_to_player" -> "dash.web.players.moderate";
            case "chat" -> "dash.web.console.command";
            case "give_item", "give_enderchest" -> "dash.web.players.inventory.write";
            case "gamerule" -> "dash.web.settings.write";
            case "gamerule_keep_inventory", "gamerule_mob_spawning", "gamerule_daylight_cycle",
                 "gamerule_weather_cycle", "gamerule_mob_griefing", "gamerule_fire_tick",
                 "gamerule_natural_regeneration" -> "dash.web.settings.write";
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "dash.web.settings.write";
            case "plugin_enable", "plugin_disable", "plugin_delete" -> "dash.web.plugins.manage";
            case "set_motd" -> "dash.web.settings.write";
            case "spark_profile" -> "dash.web.settings.write";
            case "set_distance" -> "dash.web.settings.write";
            case "backup_create", "backup_delete", "backup_schedule" -> "dash.web.settings.write";
            case "datapack_toggle", "datapack_delete" -> "dash.web.settings.write";
            case "file_delete", "file_rename" -> "dash.web.files.write";
            case "save_plugin_settings" -> "dash.web.pluginsettings.write";
            case "task_add", "task_toggle", "task_delete" -> "dash.web.tasks.write";
            case "invite_generate" -> "dash.web.users.manage";
            case "role_permissions_save", "role_create", "role_set_value", "role_delete" -> "dash.web.users.manage";
            case "user_set_role", "user_make_main_admin", "user_delete" -> "dash.web.users.manage";
            case "registration_approve", "registration_deny" -> "dash.web.users.manage";
            case "bridge_user_allow", "bridge_user_deny" -> "dash.web.users.manage";
            case "owner_2fa_regen" -> "dash.web.settings.write";
            case "add_note", "delete_note" -> "dash.web.players.moderate";
            default -> null;
        };
    }

    private String requiredPermissionForGamerule(String action) {
        return "dash.web.settings.write";
    }

    static String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(key + "=")) {
                try {
                    return URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(key.length() + 1);
                }
            }
        }
        return null;
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private String humanizeRolePermissionError(String error) {
        if (error == null) return "Unknown error";
        if (error.contains("not_found")) return "Role not found";
        if (error.contains("insufficient_rank")) return "You cannot modify a role with equal or higher rank";
        return error;
    }

    private String humanizeRoleCreationError(String error) {
        if (error == null) return "Unknown error";
        if (error.contains("exists")) return "A role with that name already exists";
        if (error.contains("insufficient_rank")) return "You cannot create a role with equal or higher rank";
        return error;
    }

    static String encodeForQuery(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void redirect(HttpExchange t, String location) throws IOException {
        t.getResponseHeaders().set("Location", location);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    void sendResponse(HttpExchange t, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        if (!t.getResponseHeaders().containsKey("Content-Type")) {
            t.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        }
        try {
            t.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // stream closed — browser disconnected
        }
    }

    private void sendResponseWithStatus(HttpExchange t, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        if (!t.getResponseHeaders().containsKey("Content-Type")) {
            t.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        }
        try {
            t.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // stream closed
        }
    }

    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private String extractBridgeCommand(String body, String contentType) {
        if (contentType != null && contentType.contains("application/json")) {
            return extractJsonStringField(body, "command");
        }
        Map<String, String> form = parseFormData(body);
        return form.get("command");
    }

    private String formatUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        return minutes + "m " + (seconds % 60) + "s";
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractJsonStringField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String pattern = "\"" + field + "\"\\s*:\\s*\"";
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + field.length() + 2);
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '\\') {
                quoteEnd += 2;
                continue;
            }
            if (json.charAt(quoteEnd) == '"') break;
            quoteEnd++;
        }
        if (quoteEnd >= json.length()) return null;
        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
    }

    private class BackupDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.backups.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String backupName = getQueryParam(query, "name");
            if (backupName == null || backupName.isBlank()) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            dash.data.BackupManager bm = FabricDash.getBackupManager();
            if (bm == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            File backupFile = bm.getBackupFile(backupName);
            if (backupFile == null || !backupFile.exists()) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            String encodedName = URLEncoder.encode(backupFile.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            t.getResponseHeaders().set("Content-Type", "application/zip");
            t.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + backupFile.getName().replace("\"", "_") + "\"; filename*=UTF-8''" + encodedName);
            t.sendResponseHeaders(200, backupFile.length());
            try (OutputStream os = t.getResponseBody();
                 FileInputStream fis = new FileInputStream(backupFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }
    }

    private class IconUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.settings.write", "dash.web.settings.icon.write")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileData().length == 0) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }
                File serverIcon = new File(FabricDash.getServerRootDirectory(), "server-icon.png");
                Files.write(serverIcon.toPath(), upload.fileData());
                WebActionLogger.log("ICON_UPLOAD", "user=" + getSessionUser(t) + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Server icon updated");
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class DatapackUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.settings.write", "dash.web.datapacks.write")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileData().length == 0 || upload.fileName() == null) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }
                String fileName = upload.fileName();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    sendJson(t, 400, false, "Datapacks must be .zip files");
                    return;
                }
                boolean ok = dash.data.DatapackManager.uploadDatapack(fileName, upload.fileData());
                if (!ok) {
                    sendJson(t, 500, false, "Datapack upload failed");
                    return;
                }
                WebActionLogger.log("DATAPACK_UPLOAD", "user=" + getSessionUser(t) + " file=" + fileName + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Datapack uploaded: " + fileName);
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class PlayerProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.players.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String playerName = getQueryParam(query, "name");
            if (playerName == null || playerName.isBlank()) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\":\"Missing player name\"}");
                return;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
            String uuid = "";
            boolean online = false;
            double x = 0, y = 0, z = 0;
            String world = "";
            double health = 0;
            int food = 0;
            int xpLevel = 0;

            if (player != null) {
                online = true;
                uuid = player.getUuid().toString();
                x = player.getX();
                y = player.getY();
                z = player.getZ();
                world = player.getEntityWorld().getRegistryKey().getValue().toString();
                health = player.getHealth();
                food = player.getHungerManager().getFoodLevel();
                xpLevel = player.experienceLevel;
            } else {
                Optional<PlayerConfigEntry> profile = server.getApiServices().nameToIdCache().findByName(playerName);
                if (profile.isPresent()) {
                    uuid = profile.get().id().toString();
                }
            }

            String json = String.format(
                    "{\"name\":\"%s\",\"uuid\":\"%s\",\"online\":%s,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"world\":\"%s\",\"health\":%.1f,\"food\":%d,\"xpLevel\":%d}",
                    jsonEscape(playerName), jsonEscape(uuid), online, x, y, z, jsonEscape(world), health, food, xpLevel);
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json);
        }
    }

    private MultipartUpload parseMultipartUpload(HttpExchange t) throws IOException {
        String contentType = t.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return null;
        }

        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring(9);
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null) return null;

        byte[] bodyBytes = t.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter));

        Map<String, String> fields = new LinkedHashMap<>();
        byte[] fileData = null;
        String fileName = null;

        for (String part : parts) {
            if (!part.contains("Content-Disposition")) {
                continue;
            }
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String content = part.substring(headerEnd + 4);
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            }

            Matcher nameMatcher = Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
            String fieldName = nameMatcher.find() ? nameMatcher.group(1) : null;
            if (fieldName == null) {
                continue;
            }

            Matcher fileMatcher = Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
            if (fileMatcher.find()) {
                String parsedName = fileMatcher.group(1);
                if (parsedName != null && !parsedName.isBlank()) {
                    parsedName = parsedName.replace("\\", "/");
                    int slash = parsedName.lastIndexOf('/');
                    fileName = slash >= 0 ? parsedName.substring(slash + 1) : parsedName;
                }
                fileData = content.getBytes(StandardCharsets.ISO_8859_1);
            } else {
                fields.put(fieldName, content);
            }
        }
        if (fileData == null) {
            return null;
        }
        return new MultipartUpload(fileName, fileData, fields);
    }

    private record MultipartUpload(String fileName, byte[] fileData, Map<String, String> fields) {
    }

    private void sendJson(HttpExchange t, int status, boolean success, String message) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        StringBuilder response = new StringBuilder();
        response.append("{\"success\":").append(success);
        if (message != null) {
            if (success) {
                response.append(",\"message\":\"").append(jsonEscape(message)).append("\"");
            } else {
                response.append(",\"error\":\"").append(jsonEscape(message)).append("\"");
            }
        }
        response.append("}");
        sendResponseWithStatus(t, status, response.toString());
    }

    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileName() == null) {
                    sendJson(t, 400, false, "Invalid upload");
                    return;
                }

                String fullPath = firstNonBlank(upload.fields().get("fullpath"), upload.fields().get("path"));
                if (fullPath == null) {
                    fullPath = "";
                }
                fullPath = fullPath.replace("\\", "/").trim();
                while (fullPath.startsWith("/")) {
                    fullPath = fullPath.substring(1);
                }
                if (fullPath.isBlank()) {
                    fullPath = upload.fileName();
                }

                File serverDir = FabricDash.getServerRootDirectory();
                File targetFile = new File(serverDir, fullPath);
                if (isProtectedLockFile(targetFile)) {
                    sendJson(t, 400, false, "Protected lock files cannot be uploaded");
                    return;
                }

                if (!targetFile.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                    sendJson(t, 403, false, "Access denied");
                    return;
                }

                File targetDir = targetFile.getParentFile();
                targetDir.mkdirs();
                Files.write(targetFile.toPath(), upload.fileData());
                WebActionLogger.log("FILE_UPLOAD", "user=" + getSessionUser(t) + " file=" + upload.fileName() + " path=" + fullPath + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "File uploaded: " + upload.fileName());
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class PluginUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.plugins.manage", true)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileName() == null) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }

                String fileName = upload.fileName();
                if (!fileName.endsWith(".jar")) {
                    sendJson(t, 400, false, "Only .jar files are accepted");
                    return;
                }

                File modsDir = new File(FabricDash.getServerRootDirectory(), "mods");
                modsDir.mkdirs();
                File targetFile = new File(modsDir, fileName);
                Files.write(targetFile.toPath(), upload.fileData());
                WebActionLogger.log("MOD_UPLOAD", "user=" + getSessionUser(t) + " file=" + fileName + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Mod uploaded: " + fileName + " (restart required)");
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private String extractFormField(String body, String boundary, String fieldName) {
        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter));
        for (String part : parts) {
            if (part.contains("name=\"" + fieldName + "\"") && !part.contains("filename=")) {
                int headerEnd = part.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    String value = part.substring(headerEnd + 4).trim();
                    if (value.endsWith("\r\n")) {
                        value = value.substring(0, value.length() - 2);
                    }
                    return value;
                }
            }
        }
        return null;
    }

    private boolean isProtectedLockFile(File file) {
        String name = file.getName().toLowerCase();
        return name.equals("session.lock") || name.equals("server.lock") || name.endsWith(".lck");
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public WebAuth getAuth() {
        return auth;
    }
}
