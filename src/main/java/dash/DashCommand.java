package dash;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class DashCommand {

    private DashCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("dash")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() ->
                                    Text.literal("Usage: /dash register [user] [rank] | /dash update | /dash port <port>"), false);
                            return 1;
                        })
                        .then(CommandManager.literal("update")
                                .executes(DashCommand::handleUpdate))
                        .then(CommandManager.literal("port")
                                .requires(source -> CommandManager.OWNERS_CHECK.allows(source.getPermissions()) || source.getEntity() == null)
                                .then(CommandManager.argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(DashCommand::handlePort)))
                        .then(CommandManager.literal("register")
                                .requires(source -> CommandManager.OWNERS_CHECK.allows(source.getPermissions()) || source.getEntity() == null)
                                .executes(DashCommand::handleRegisterSelf)
                                .then(CommandManager.argument("user", StringArgumentType.word())
                                        .executes(ctx -> handleRegisterTarget(ctx, null))
                                        .then(CommandManager.argument("rank", StringArgumentType.word())
                                                .executes(ctx -> handleRegisterTarget(ctx,
                                                        StringArgumentType.getString(ctx, "rank"))))))
        );
    }

    private static int handleRegisterSelf(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(() -> Text.literal("This command can only be used by players.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        if (!CommandManager.OWNERS_CHECK.allows(source.getPermissions())) {
            source.sendFeedback(() -> Text.literal("Only OPs can use this command.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        RegistrationManager regManager = FabricDash.getRegistrationManager();
        String code = regManager.generateCode(player.getUuidAsString(), player.getName().getString());
        String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), code);

        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA));
        player.sendMessage(Text.literal(" Dash Web Registration").formatted(Formatting.WHITE, Formatting.BOLD));
        player.sendMessage(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA));
        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal(" Your registration code:").formatted(Formatting.GRAY));
        player.sendMessage(Text.literal(" " + code).formatted(Formatting.GREEN, Formatting.BOLD));
        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal(" Setup URL: ").formatted(Formatting.GRAY)
                .append(Text.literal(setupUrl).formatted(Formatting.AQUA)));
        player.sendMessage(Text.empty());
        player.sendMessage(Text.literal(" This code expires in 5 minutes.").formatted(Formatting.YELLOW));
        player.sendMessage(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.AQUA));
        player.sendMessage(Text.empty());

        WebActionLogger.log("REGISTER_CODE_GENERATED",
                "Player " + player.getName().getString() + " generated registration code");
        return 1;
    }

    private static int handleRegisterTarget(CommandContext<ServerCommandSource> ctx, String rankArg) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(() -> Text.literal("This command can only be used by players.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        if (!CommandManager.OWNERS_CHECK.allows(source.getPermissions())) {
            source.sendFeedback(() -> Text.literal("Only OPs can use this command.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "user");
        String requestedRank = (rankArg != null) ? rankArg : "MODERATOR";
        String rank = requestedRank.toUpperCase(Locale.ROOT);

        RegistrationManager regManager = FabricDash.getRegistrationManager();
        String code = regManager.generateCode("UNBOUND", targetName, rank, List.of());
        String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), code);

        MinecraftServer server = FabricDash.getServer();
        ServerPlayerEntity onlineTarget = server.getPlayerManager().getPlayer(targetName);
        if (onlineTarget != null) {
            onlineTarget.sendMessage(Text.empty());
            onlineTarget.sendMessage(
                    Text.literal("[Dash] ").formatted(Formatting.AQUA)
                            .append(Text.literal("Du wurdest von " + player.getName().getString()
                                    + " in das Dash-Panel eingeladen! Dein Rang: " + rank
                                    + ". Klicke hier, um dich zu registrieren.")
                                    .formatted(Formatting.YELLOW, Formatting.BOLD)
                                    .styled(s -> s.withClickEvent(
                                            new ClickEvent.OpenUrl(URI.create(setupUrl))))));
            onlineTarget.sendMessage(Text.literal("Setup URL: " + setupUrl).formatted(Formatting.GRAY));
            onlineTarget.sendMessage(Text.literal("Code expires in 5 minutes.").formatted(Formatting.RED));
        }

        player.sendMessage(Text.literal("Invite generated for " + targetName + " (" + rank + ").")
                .formatted(Formatting.GREEN));
        player.sendMessage(Text.literal("Code: " + code).formatted(Formatting.AQUA));
        player.sendMessage(Text.literal("Setup URL: " + setupUrl).formatted(Formatting.AQUA));
        if (onlineTarget == null) {
            player.sendMessage(Text.literal("Target player not online; invite remains code-based.")
                    .formatted(Formatting.YELLOW));
        }

        WebActionLogger.log("REGISTER_CODE_GENERATED",
                "Player " + player.getName().getString() + " generated invite for " + targetName + " role=" + rank);
        return 1;
    }

    private static int handlePort(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        int newPort = IntegerArgumentType.getInteger(ctx, "port");

        source.sendFeedback(() -> Text.literal("[FabricDash] Changing web server port to " + newPort + "...")
                .formatted(Formatting.YELLOW), true);

        FabricDash.getInstance().changeWebPort(newPort);

        AdminWebServer ws = FabricDash.getInstance().getWebServer();
        if (ws != null && !ws.hasPortBindFailed()) {
            source.sendFeedback(() -> Text.literal("[FabricDash] Web server now running on port " + newPort)
                    .formatted(Formatting.GREEN), true);
        } else {
            source.sendFeedback(() -> Text.literal("[FabricDash] Failed to bind to port " + newPort + ". Try another port.")
                    .formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int handleUpdate(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        boolean isConsole = source.getEntity() == null;
        if (!isConsole && !CommandManager.OWNERS_CHECK.allows(source.getPermissions())) {
            source.sendFeedback(() -> Text.literal("Only OPs or console can use /dash update.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        GithubUpdater updater = FabricDash.getGithubUpdater();
        if (updater == null || !updater.isEnabled()) {
            source.sendFeedback(() -> Text.literal("Updater is currently unavailable. Check console logs for details.")
                    .formatted(Formatting.RED), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Downloading Dash update...").formatted(Formatting.YELLOW), false);
        CompletableFuture.runAsync(() -> {
            boolean ok = updater.downloadUpdate();
            MinecraftServer server = FabricDash.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (ok) {
                        source.sendFeedback(() -> Text.literal(
                                "Update downloaded! Dash will be updated on the next server restart.")
                                .formatted(Formatting.GREEN), false);
                    } else {
                        source.sendFeedback(() -> Text.literal(
                                "Update download failed. Check console logs for updater errors.")
                                .formatted(Formatting.RED), false);
                    }
                });
            }
        });
        return 1;
    }
}
