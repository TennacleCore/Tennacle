package tennacle.moderation;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.PlayerPrefs;
import tennacle.Seen;
import tennacle.access.Access;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.commands.Completions;
import tennacle.moderation.Punishments.Kind;
import tennacle.moderation.Punishments.Punishment;
import tennacle.storage.Directory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** {@code /kick}, {@code /mute}, {@code /ban}, their lifts, {@code /history} and {@code /vanish}. */
public final class Moderation {

    public static final String KICK = "tennacle.moderate.kick";
    public static final String MUTE = "tennacle.moderate.mute";
    public static final String BAN = "tennacle.moderate.ban";
    public static final String HISTORY = "tennacle.moderate.history";

    private static final Logger LOGGER = LoggerFactory.getLogger(Moderation.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private static volatile Punishments store = new VaultPunishments(PlayerPrefs::vault);
    static volatile LongSupplier clock = System::currentTimeMillis;

    private Moderation() {}

    public static void store(@NotNull Punishments punishments) {
        store = punishments;
    }

    static Punishments store() {
        return store;
    }

    static long now() {
        return clock.getAsLong();
    }

    // before chat's listeners: a cancelled line skips them
    public static void install() {
        Mutes.install();
        Bans.install();
        Vanish.install();

        var who = ArgumentType.Word("player");
        who.setSuggestionCallback(PlayerNames.suggestAll());
        var length = ArgumentType.Word("duration");
        length.setSuggestionCallback(Completions.of(() -> Set.of("perm", "30m", "1h", "1d", "7d")));
        var reason = ArgumentType.StringArray("reason");

        AppCommand kick = Commands.player("kick", "Remove a player from the server.").node(KICK)
                .usage("/kick <player> [reason]", "they can join again right away");
        kick.addSyntax((sender, ctx) -> kick(sender, ctx.get(who), ""), who);
        kick.addSyntax((sender, ctx) -> kick(sender, ctx.get(who), String.join(" ", ctx.get(reason))), who, reason);
        Commands.register(kick);

        for (Kind kind : new Kind[]{Kind.MUTE, Kind.BAN}) {
            boolean mute = kind == Kind.MUTE;
            AppCommand add = Commands.player(tag(kind), mute ? "Stop a player from chatting." : "Keep a player off the server.")
                    .node(mute ? MUTE : BAN)
                    .usage("/" + tag(kind) + " <player> <duration> [reason]", "30m, 2h, 7d, 1w or perm; online or not");
            add.addSyntax((sender, ctx) -> punish(sender, kind, ctx.get(who), ctx.get(length), ""), who, length);
            add.addSyntax((sender, ctx) -> punish(sender, kind, ctx.get(who), ctx.get(length), String.join(" ", ctx.get(reason))),
                    who, length, reason);
            Commands.register(add);

            AppCommand lift = Commands.player("un" + tag(kind), mute ? "Lift a player's mute." : "Lift a player's ban.")
                    .node(mute ? MUTE : BAN)
                    .usage("/un" + tag(kind) + " <player>", mute ? "they can chat again" : "they can join again");
            lift.addSyntax((sender, ctx) -> lift(sender, kind, ctx.get(who)), who);
            Commands.register(lift);
        }

        AppCommand history = Commands.player("history", "A player's bans, mutes and kicks.").node(HISTORY)
                .usage("/history <player>", "newest first, lifted ones too");
        history.addSyntax((sender, ctx) -> history(sender, ctx.get(who)), who);
        Commands.register(history);

        AppCommand vanish = Commands.player("vanish", "Hide from players: out of sight and off the tab list.", "v")
                .node(Vanish.SEE);
        vanish.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Vanish.toggle(me)) Msg.tag(me, "vanish", "You are hidden from players. Staff still see you.");
            else Msg.tag(me, "vanish", "You are visible again.");
        });
        Commands.register(vanish);
    }

    private static void kick(CommandSender sender, String name, String reason) {
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (target == null || sender instanceof Player me && Vanish.hides(me, target)) {
            Msg.err(sender, "kick", Msg.v(name), " isn't online.");
            return;
        }
        Seen.find(target.getUsername()).thenCompose(entry -> outranks(sender, entry, "kick")).thenCompose(may -> {
            if (!may) return CompletableFuture.completedFuture(null);
            long now = now();
            return store.add(target.getUuid(), Kind.KICK, reason, id(sender), name(sender), now, now).thenRun(() -> {
                // the reason alone: Velocity's prefix is configured away
                target.kick(Component.text(reason.isEmpty() ? "No reason given." : reason, NamedTextColor.RED));
                Msg.tag(sender, "kick", "Kicked ", Msg.v(target.getUsername()), ".");
            });
        }).exceptionally(failed -> failed(sender, "kick", name, failed));
    }

    private static void punish(CommandSender sender, Kind kind, String name, String typed, String reason) {
        String tag = tag(kind);
        long length = Lengths.parse(typed);
        if (length < 0) {
            Msg.err(sender, tag, Msg.v(typed), " isn't a length. Try 30m, 2h, 7d, 1w or perm.");
            return;
        }
        found(sender, name, tag, target -> outranks(sender, target, tag).thenCompose(may -> {
            if (!may) return CompletableFuture.completedFuture(null);
            long now = now();
            long until = length == Punishments.FOREVER ? Punishments.FOREVER : now + length;
            return store.add(target.id(), kind, reason, id(sender), name(sender), now, until).thenAccept(added -> {
                String lasting = Lengths.lasting(added, now);
                Msg.tag(sender, tag, Msg.v(target.name()), " is " + done(kind) + " " + lasting + ".");
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
                if (online == null) return;
                if (kind == Kind.MUTE) {
                    Mutes.held(target.id(), added);
                    Msg.fail(online, "You are muted " + lasting + "." + Lengths.why(added));
                } else {
                    online.kick(Bans.screen(added, now));
                }
            });
        }));
    }

    private static void lift(CommandSender sender, Kind kind, String name) {
        String tag = "un" + tag(kind);
        found(sender, name, tag, target -> store.lift(target.id(), kind, id(sender), name(sender), now()).thenAccept(lifted -> {
            if (lifted == null) {
                Msg.err(sender, tag, Msg.v(target.name()), " isn't " + done(kind) + ".");
                return;
            }
            Msg.tag(sender, tag, Msg.v(target.name()), kind == Kind.MUTE ? " can chat again." : " can join again.");
            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
            if (online != null && kind == Kind.MUTE) {
                Mutes.held(target.id(), null);
                Msg.ok(online, "You are no longer muted.");
            }
        }));
    }

    private static void history(CommandSender sender, String name) {
        found(sender, name, "history", target -> store.history(target.id()).thenAccept(list -> {
            if (list.isEmpty()) {
                Msg.tag(sender, "history", Msg.v(target.name()), " has a clean record.");
                return;
            }
            Msg.tag(sender, "history", Msg.v(target.name()), " has " + list.size() + " on record, newest first:");
            long now = now();
            for (Punishment p : list) Msg.say(sender, line(p, now));
        }));
    }

    static String line(Punishment p, long now) {
        String kind = p.kind().name().charAt(0) + p.kind().name().substring(1).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(kind).append(", ")
                .append(DAY.format(Instant.ofEpochMilli(p.at()).atZone(ZoneId.systemDefault())));
        if (p.kind() != Kind.KICK) out.append(", ").append(p.permanent() ? "permanent" : "for " + Lengths.span(p.until() - p.at()));
        out.append(", by ").append(p.byName()).append('.');
        if (!p.reason().isEmpty()) out.append(" Reason: ").append(p.reason()).append('.');
        if (p.liftedAt() != 0) out.append(" Lifted by ").append(p.liftedByName()).append('.');
        else if (p.active(now)) out.append(" In force.");
        return out.toString();
    }

    private static String tag(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static String done(Kind kind) {
        return kind == Kind.MUTE ? "muted" : "banned";
    }

    private static UUID id(CommandSender sender) {
        return sender instanceof Player me ? me.getUuid() : null;
    }

    private static String name(CommandSender sender) {
        return sender instanceof Player me ? me.getUsername() : "Console";
    }

    // staff act only on those below them; an owner and the console on anyone
    private static CompletableFuture<Boolean> outranks(CommandSender sender, Directory.Entry target, String tag) {
        if (!(sender instanceof Player me) || Access.owner(me)) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> above = Access.owner(target.id(), target.name())
                ? CompletableFuture.completedFuture(false)
                : Access.grants(me.getUuid()).thenCombine(Access.grants(target.id()),
                        (mine, theirs) -> mine.role().compareTo(theirs.role()) > 0);
        return above.thenApply(may -> {
            if (!may) Msg.err(sender, tag, "You can't " + tag + " ", Msg.v(target.name()), ". They're staff at your level or above.");
            return may;
        });
    }

    private static void found(CommandSender sender, String name, String tag, Function<Directory.Entry, CompletableFuture<?>> then) {
        Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.err(sender, tag, "No player named ", Msg.v(name), ".");
                return CompletableFuture.completedFuture(null);
            }
            return then.apply(target);
        }).exceptionally(failed -> failed(sender, tag, name, failed));
    }

    private static <T> T failed(CommandSender sender, String tag, String name, Throwable failed) {
        LOGGER.warn("/{} {} failed", tag, name, failed);
        Msg.err(sender, tag, "That didn't save. Try again in a moment.");
        return null;
    }
}
