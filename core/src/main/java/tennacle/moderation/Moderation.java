package tennacle.moderation;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.Seen;
import tennacle.access.Access;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.commands.Completions;
import tennacle.storage.Directory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** {@code /kick}, {@code /mute}, {@code /unmute} and {@code /vanish}. */
public final class Moderation {

    public static final String KICK = "tennacle.moderate.kick";
    public static final String MUTE = "tennacle.moderate.mute";

    private static final Logger LOGGER = LoggerFactory.getLogger(Moderation.class);

    private Moderation() {}

    // before chat's listeners: a cancelled line skips them
    public static void install() {
        Mutes.install();
        Vanish.install();

        var who = ArgumentType.Word("player");
        who.setSuggestionCallback(PlayerNames.suggestAll());
        var reason = ArgumentType.StringArray("reason");

        AppCommand kick = Commands.player("kick", "Remove a player from the server.").node(KICK)
                .usage("/kick <player> [reason]", "they can join again right away");
        kick.addSyntax((sender, ctx) -> kick(sender, ctx.get(who), ""), who);
        kick.addSyntax((sender, ctx) -> kick(sender, ctx.get(who), String.join(" ", ctx.get(reason))), who, reason);
        Commands.register(kick);

        var length = ArgumentType.Word("duration");
        length.setSuggestionCallback(Completions.of(() -> java.util.Set.of("perm", "30m", "1h", "1d", "7d")));
        AppCommand mute = Commands.player("mute", "Stop a player from chatting.").node(MUTE)
                .usage("/mute <player> <duration> [reason]", "30m, 2h, 7d, 1w or perm; online or not");
        mute.addSyntax((sender, ctx) -> mute(sender, ctx.get(who), ctx.get(length), ""), who, length);
        mute.addSyntax((sender, ctx) -> mute(sender, ctx.get(who), ctx.get(length), String.join(" ", ctx.get(reason))),
                who, length, reason);
        Commands.register(mute);

        AppCommand unmute = Commands.player("unmute", "Lift a player's mute.").node(MUTE)
                .usage("/unmute <player>", "they can chat again");
        unmute.addSyntax((sender, ctx) -> unmute(sender, ctx.get(who)), who);
        Commands.register(unmute);

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
        Seen.find(target.getUsername()).thenCompose(entry -> outranks(sender, entry, "kick")).thenAccept(may -> {
            if (!may) return;
            // the reason alone: Velocity already prefixes "You were kicked from <server>:"
            target.kick(Component.text(reason.isEmpty() ? "No reason given." : reason, NamedTextColor.RED));
            Msg.tag(sender, "kick", "Kicked ", Msg.v(target.getUsername()), ".");
        }).exceptionally(failed -> failed(sender, "kick", name, failed));
    }

    private static void mute(CommandSender sender, String name, String typed, String reason) {
        long length = Mutes.parse(typed);
        if (length < 0) {
            Msg.err(sender, "mute", Msg.v(typed), " isn't a length. Try 30m, 2h, 7d, 1w or perm.");
            return;
        }
        String by = sender instanceof Player me ? me.getUsername() : "Console";
        found(sender, name, "mute", target -> outranks(sender, target, "mute").thenCompose(may -> {
            if (!may) return CompletableFuture.completedFuture(null);
            Mute mute = new Mute(length == Mute.FOREVER ? Mute.FOREVER : Mutes.clock.getAsLong() + length, reason, by);
            String lasting = mute.permanent() ? "permanently" : "for " + Mutes.span(length);
            return Mutes.set(target.id(), mute).thenRun(() -> {
                Msg.tag(sender, "mute", Msg.v(target.name()), " is muted " + lasting + ".");
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
                if (online != null) Msg.fail(online, "You are muted " + lasting + "." + Mutes.why(mute));
            });
        }));
    }

    private static void unmute(CommandSender sender, String name) {
        found(sender, name, "unmute", target -> Mutes.load(target.id()).thenCompose(mute -> {
            if (!mute.active(Mutes.clock.getAsLong())) {
                Msg.err(sender, "unmute", Msg.v(target.name()), " isn't muted.");
                return CompletableFuture.completedFuture(null);
            }
            return Mutes.set(target.id(), Mute.NONE).thenRun(() -> {
                Msg.tag(sender, "unmute", Msg.v(target.name()), " can chat again.");
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
                if (online != null) Msg.ok(online, "You are no longer muted.");
            });
        }));
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
