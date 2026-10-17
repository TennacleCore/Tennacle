package tennacle.social.messages;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.Seen;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.social.Audience;
import tennacle.social.Choice;
import tennacle.social.blocks.Blocks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import static tennacle.social.Lines.green;
import static tennacle.social.Lines.who;

/**
 * Private messages: {@code /msg} ({@code /w}, {@code /tell}), {@code /r}, and the conversation either opens with no
 * text, where plain chat goes to the other player until {@code /chat a} or five quiet minutes. Worded as Hypixel's are.
 */
public final class Messages {

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    /** Who may message a player. */
    public static final Choice PRIVACY = Choice.of("social:messages", Audience.EVERYONE, Audience.FRIENDS, Audience.PARTY, Audience.NOBODY);

    static final long WINDOW_MILLIS = 5 * 60_000;
    static volatile LongSupplier clock = System::currentTimeMillis;

    private record Last(UUID from, long at) {}

    private record Conversation(UUID with, long until) {}

    private static final Map<UUID, Last> lastFrom = new ConcurrentHashMap<>();
    private static final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();

    private Messages() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> forget(e.getPlayer().getUuid()));

        AppCommand msg = Commands.player("msg", "Send a player a private message.", "w", "tell")
                .usage("/msg <player> <message>", "a private message")
                .usage("/msg <player>", "talk to them in plain chat for five minutes")
                .usage("/r [message]", "answer whoever messaged you last");
        var target = ArgumentType.Word("player");
        target.setSuggestionCallback(PlayerNames.suggestAll());
        var text = ArgumentType.StringArray("message");
        msg.setDefaultExecutor((sender, ctx) -> Msg.fail(sender, "Invalid usage! '/msg <player> <message>'"));
        msg.addSyntax((sender, ctx) -> {
            if (sender instanceof Player me) send(me, ctx.get(target), null);
        }, target);
        msg.addSyntax((sender, ctx) -> {
            if (sender instanceof Player me) send(me, ctx.get(target), String.join(" ", ctx.get(text)));
        }, target, text);
        Commands.register(msg);

        AppCommand reply = Commands.player("r", "Answer whoever messaged you last.");
        reply.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player me) reply(me, null);
        });
        reply.addSyntax((sender, ctx) -> {
            if (sender instanceof Player me) reply(me, String.join(" ", ctx.get(text)));
        }, text);
        Commands.register(reply);
    }

    /** A plain chat line said in an open conversation goes as a message: whether it did. */
    public static boolean converse(@NotNull Player me, @NotNull String message) {
        Conversation open = conversations.get(me.getUuid());
        if (open == null) return false;
        if (open.until() < clock.getAsLong()) {
            conversations.remove(me.getUuid(), open);
            return false;
        }
        Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(open.with());
        if (to == null) {
            conversations.remove(me.getUuid());
            Msg.fail(me, "That player is not online!");
            return true;
        }
        deliver(me, to, message);
        return true;
    }

    /** Ends {@code player}'s conversation, if one is open. */
    public static void leave(@NotNull Player player) {
        conversations.remove(player.getUuid());
    }

    private static void reply(Player me, @Nullable String text) {
        Last last = lastFrom.get(me.getUuid());
        if (last == null || clock.getAsLong() - last.at() > WINDOW_MILLIS) {
            Msg.fail(me, "Nobody has messaged you in the past 5 minutes!");
            return;
        }
        Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(last.from());
        if (to == null) {
            Msg.fail(me, "That player is not online!");
            return;
        }
        reach(me, to, text);
    }

    private static void send(Player me, String name, @Nullable String text) {
        Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (to != null) {
            reach(me, to, text);
            return;
        }
        Seen.directory().byName(name).thenAccept(known -> {
            if (known != null) Msg.fail(me, "That player is not online!");
            else Msg.fail(me, "Can't find a player by the name of '" + name + "'");
        }).exceptionally(failed -> {
            LOGGER.warn("looking up {} for {} failed", name, me.getUsername(), failed);
            return null;
        });
    }

    // no text opens the conversation instead
    private static void reach(Player me, Player to, @Nullable String text) {
        if (text != null) {
            deliver(me, to, text);
            return;
        }
        String refused = refusal(me, to);
        if (refused != null) {
            Msg.fail(me, refused);
            return;
        }
        conversations.put(me.getUuid(), new Conversation(to.getUuid(), clock.getAsLong() + WINDOW_MILLIS));
        me.sendMessage(green("Opened a chat conversation with ").append(who(to.getUsername()))
                .append(green(" for the next 5 minutes. Use ")).append(Component.text("/chat a", NamedTextColor.AQUA))
                .append(green(" to leave")));
    }

    private static @Nullable String refusal(Player me, Player to) {
        if (me == to) return "You can't message yourself.";
        if (Blocks.blocked(me.getUuid(), to.getUuid())) {
            return "You blocked " + to.getUsername() + ". Use /block remove " + to.getUsername() + " first.";
        }
        if (Blocks.blocked(to.getUuid(), me.getUuid())) return "That player blocked you!";
        if (!PRIVACY.admits(me, to)) return to.getUsername() + " isn't accepting messages from you.";
        return null;
    }

    private static void deliver(Player me, Player to, String text) {
        String refused = refusal(me, to);
        if (refused != null) {
            conversations.remove(me.getUuid());
            Msg.fail(me, refused);
            return;
        }
        long now = clock.getAsLong();
        me.sendMessage(line("To ", to.getUsername(), text));
        to.sendMessage(line("From ", me.getUsername(), text));
        lastFrom.put(to.getUuid(), new Last(me.getUuid(), now));
        conversations.computeIfPresent(me.getUuid(), (id, open) -> new Conversation(open.with(), now + WINDOW_MILLIS));
    }

    // captured: "To Name: text", the name suggesting a reply on click
    private static Component line(String direction, String name, String text) {
        return Component.text(direction, NamedTextColor.LIGHT_PURPLE)
                .append(who(name).clickEvent(ClickEvent.suggestCommand("/msg " + name + " ")))
                .append(Component.text(": " + text, NamedTextColor.GRAY));
    }

    private static void forget(UUID player) {
        lastFrom.remove(player);
        conversations.remove(player);
    }
}
