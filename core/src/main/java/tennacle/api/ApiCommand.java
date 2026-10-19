package tennacle.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.net.Network;
import tennacle.net.Topics;

/** {@code /api new} and {@code /api revoke}: a player's key to the public API. */
public final class ApiCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCommand.class);

    private static volatile ApiKeys keys = ApiKeys.NONE;

    private ApiCommand() {}

    public static void keys(@NotNull ApiKeys store) {
        keys = store;
    }

    public static void register() {
        AppCommand api = Commands.player("api", "A key for the public API, for your own bots and sites.")
                .usage("/api new", "a new key; any older one stops working")
                .usage("/api revoke", "turn your key off");
        api.addSyntax((sender, ctx) -> {
            if (sender instanceof Player me) issue(me);
        }, ArgumentType.Literal("new"));
        api.addSyntax((sender, ctx) -> {
            if (sender instanceof Player me) revoke(me);
        }, ArgumentType.Literal("revoke"));
        Commands.register(api);
    }

    private static void issue(Player me) {
        keys.issue(me.getUuid(), System.currentTimeMillis()).whenComplete((key, failed) -> {
            if (failed != null) {
                if (!(failed.getCause() instanceof UnsupportedOperationException)) LOGGER.warn("no API key for {}", me.getUsername(), failed);
                Msg.err(me, "api", failed.getCause() instanceof UnsupportedOperationException
                        ? "The API isn't running on this server." : "That didn't work. Try again in a moment.");
                return;
            }
            Network.bus().publish(Topics.KEY_REVOKED, new Topics.KeyRevoked(me.getUuid()));
            Msg.tag(me, "api", "Your new key. It's shown this once, and any older key has stopped working:");
            me.sendMessage(Component.text(key, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.copyToClipboard(key))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to copy"))));
            Msg.say(me, "Send it as the API-Key header. Never share it: anyone holding it uses your quota.");
        });
    }

    private static void revoke(Player me) {
        keys.revoke(me.getUuid(), System.currentTimeMillis()).whenComplete((had, failed) -> {
            if (failed != null) {
                LOGGER.warn("{}'s API key was not revoked", me.getUsername(), failed);
                Msg.err(me, "api", "That didn't work. Try again in a moment.");
            } else if (had) {
                Network.bus().publish(Topics.KEY_REVOKED, new Topics.KeyRevoked(me.getUuid()));
                Msg.tag(me, "api", "Your key no longer works.");
            } else {
                Msg.err(me, "api", "You don't have a key. ", Msg.run("/api new"), " makes one.");
            }
        });
    }
}
