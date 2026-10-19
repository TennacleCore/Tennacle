package tennacle.social.messages;

import java.util.concurrent.CompletableFuture;
import net.minestom.server.timer.TaskSchedule;
import tennacle.access.Access;
import tennacle.net.Topics;
import tennacle.net.Network;
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
import tennacle.moderation.Vanish;
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

    /** A message to another server, waiting on its answer. */
    private record Pending(Player from, String text) {}

    static volatile long answerMillis = 3_000;
    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final Map<UUID, Last> lastFrom = new ConcurrentHashMap<>();
    private static final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();

    private Messages() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> forget(e.getPlayer().getUuid()));
        Network.bus().subscribe(Topics.WHISPER, Messages::heard);
        Network.bus().subscribe(Topics.WHISPERED, Messages::answered);

        AppCommand msg = Commands.player("msg", "Send a player a private message.", "w", "tell").speaks()
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

        AppCommand reply = Commands.player("r", "Answer whoever messaged you last.").speaks();
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
        if (to == null) remote(me, open.with(), message);
        else deliver(me, to, message);
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
        if (to == null) remote(me, last.from(), text);
        else reach(me, to, text);
    }

    private static void send(Player me, String name, @Nullable String text) {
        Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (to != null && !Vanish.hides(me, to)) {
            reach(me, to, text);
            return;
        }
        Seen.directory().byName(name).thenAccept(known -> {
            if (known != null) remote(me, known.id(), text);
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
        open(me, to.getUuid(), to.getUsername());
    }

    private static @Nullable String refusal(Player me, Player to) {
        if (me == to) return null; // the user, 2026-10-18: a note to yourself goes through
        String blocked = Blocks.refusal(me, to, "That player blocked you!");
        if (blocked != null) return blocked;
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

    // on another server: the checks run here, against what they stored, and their server only delivers
    private static void remote(Player me, UUID to, @Nullable String text) {
        boolean staff = Access.allows(me, Vanish.SEE);
        Network.presence().where(to).thenCompose(here -> {
            if (here == null || here.hidden() && !staff) {
                notOnline(me);
                return CompletableFuture.completedFuture(null);
            }
            if (Blocks.blocked(me.getUuid(), to)) {
                refuse(me, "You blocked " + here.name() + ". Use /block remove " + here.name() + " first.");
                return CompletableFuture.completedFuture(null);
            }
            return Blocks.load(to, me.getUuid()).thenCombine(PRIVACY.admits(me, to), (blocked, admits) -> {
                if (blocked) refuse(me, "That player blocked you!");
                else if (!admits) refuse(me, here.name() + " isn't accepting messages from you.");
                else if (text == null) open(me, to, here.name());
                else whisper(me, to, text, staff);
                return null;
            });
        }).exceptionally(failed -> {
            LOGGER.warn("a message from {} to {} was not sent", me.getUsername(), to, failed);
            Msg.fail(me, "That message didn't send. Try again in a moment.");
            return null;
        });
    }

    private static void whisper(Player me, UUID to, String text, boolean staff) {
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(me, text));
        Network.bus().publish(Topics.WHISPER, new Topics.Whisper(id, me.getUuid(), me.getUsername(), staff, Network.server(), to, text));
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (pending.remove(id) != null) notOnline(me);
        }).delay(TaskSchedule.millis(answerMillis)).schedule();
    }

    // on the server the message was for
    private static void heard(Topics.Whisper e) {
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(e.to());
            if (to == null) return; // its own server answers, or nobody and the sender times out
            if (Vanish.vanished(to) && !e.fromStaff()) {
                Network.bus().publish(Topics.WHISPERED, new Topics.Whispered(e.id(), Network.server(), to.getUsername(), "That player is not online!"));
                return;
            }
            to.sendMessage(line("From ", e.fromName(), e.text()));
            lastFrom.put(to.getUuid(), new Last(e.from(), clock.getAsLong()));
            Network.bus().publish(Topics.WHISPERED, new Topics.Whispered(e.id(), Network.server(), to.getUsername(), null));
        });
    }

    // back on the sender's
    private static void answered(Topics.Whispered e) {
        Pending sent = pending.remove(e.id());
        if (sent == null) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            Player me = sent.from();
            if (!me.isOnline()) return;
            if (e.refused() != null) {
                refuse(me, e.refused());
                return;
            }
            me.sendMessage(line("To ", e.toName(), sent.text()));
            long now = clock.getAsLong();
            conversations.computeIfPresent(me.getUuid(), (id, open) -> new Conversation(open.with(), now + WINDOW_MILLIS));
        });
    }

    private static void open(Player me, UUID to, String name) {
        conversations.put(me.getUuid(), new Conversation(to, clock.getAsLong() + WINDOW_MILLIS));
        me.sendMessage(green("Opened a chat conversation with ").append(who(name))
                .append(green(" for the next 5 minutes. Use ")).append(Component.text("/chat a", NamedTextColor.AQUA))
                .append(green(" to leave")));
    }

    private static void refuse(Player me, String why) {
        conversations.remove(me.getUuid());
        Msg.fail(me, why);
    }

    private static void notOnline(Player me) {
        refuse(me, "That player is not online!");
    }

    private static void forget(UUID player) {
        lastFrom.remove(player);
        conversations.remove(player);
    }
}
