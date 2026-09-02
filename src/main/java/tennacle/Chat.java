package tennacle;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Parties;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who hears whom. A member speaks to their game (spectators included); a spectator to the other spectators of
 * that game; anyone else to the players in the same room - the shard tree they stand in, else the instance.
 * {@code /p} reaches the party, {@code /msg} and {@code /r} one player.
 */
public final class Chat {

    private static final Map<UUID, UUID> lastWhisper = new ConcurrentHashMap<>();

    private Chat() {}

    public static void install() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerChatEvent.class, Chat::route);
        events.addListener(PlayerDisconnectEvent.class, e -> {
            lastWhisper.remove(e.getPlayer().getUuid());
            lastWhisper.values().removeIf(id -> id.equals(e.getPlayer().getUuid()));
        });

        Command party = new Command("p", "pc");
        var text = ArgumentType.StringArray("text");
        party.setDefaultExecutor((sender, ctx) -> Msg.tag(sender, "party", "usage: ", Msg.run("/p <message>")));
        party.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            List<Player> side = Parties.of(me);
            if (side.size() < 2) {
                Msg.err(me, "party", "you're not in a party");
                return;
            }
            Component line = Component.text("[party] ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(me.getUsername() + ": ", NamedTextColor.GRAY))
                    .append(Component.text(String.join(" ", ctx.get(text)), NamedTextColor.WHITE));
            for (Player member : side) member.sendMessage(line);
        }, text);
        MinecraftServer.getCommandManager().register(party);

        Command msg = new Command("msg", "tell", "w");
        var target = ArgumentType.Word("player");
        msg.setDefaultExecutor((sender, ctx) -> Msg.tag(sender, "msg", "usage: ", Msg.run("/msg <player> <message>")));
        msg.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            Player to = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(target));
            if (to == null || to == me) {
                Msg.err(me, "msg", "no player named ", Msg.v(ctx.get(target)));
                return;
            }
            whisper(me, to, String.join(" ", ctx.get(text)));
        }, target, text);
        MinecraftServer.getCommandManager().register(msg);

        Command reply = new Command("r");
        reply.setDefaultExecutor((sender, ctx) -> Msg.tag(sender, "msg", "usage: ", Msg.run("/r <message>")));
        reply.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            UUID last = lastWhisper.get(me.getUuid());
            Player to = last != null ? MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(last) : null;
            if (to == null) {
                Msg.err(me, "msg", "nobody to reply to");
                return;
            }
            whisper(me, to, String.join(" ", ctx.get(text)));
        }, text);
        MinecraftServer.getCommandManager().register(reply);
    }

    private static void whisper(Player from, Player to, String body) {
        from.sendMessage(Component.text("to " + to.getUsername() + ": ", NamedTextColor.GRAY)
                .append(Component.text(body, NamedTextColor.WHITE)));
        to.sendMessage(Component.text("from " + from.getUsername() + ": ", NamedTextColor.GRAY)
                .append(Component.text(body, NamedTextColor.WHITE)));
        lastWhisper.put(to.getUuid(), from.getUuid());
        lastWhisper.put(from.getUuid(), to.getUuid());
    }

    private static void route(PlayerChatEvent e) {
        Player me = e.getPlayer();
        String body = e.getRawMessage();
        Game game = gameOf(me);
        Game watched = Matches.grotto().spectating(me);
        List<Player> to = new ArrayList<>();
        Component line;
        if (game != null) {
            line = Component.text(me.getUsername(), Match.teamColor(game, me))
                    .append(Component.text(": " + body, NamedTextColor.WHITE));
            game.forEachAudience(to::add);
        } else if (watched != null) {
            line = Component.text("[spec] ", NamedTextColor.GRAY)
                    .append(Component.text(me.getUsername() + ": " + body, NamedTextColor.GRAY));
            to.addAll(watched.spectators());
        } else {
            line = Component.text(me.getUsername() + ": ", NamedTextColor.GRAY)
                    .append(Component.text(body, NamedTextColor.WHITE));
            Object room = roomOf(me);
            for (Player other : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (gameOf(other) == null && Matches.grotto().spectating(other) == null && roomOf(other) == room) to.add(other);
            }
        }
        e.setFormattedMessage(line);
        e.getRecipients().retainAll(to);
    }

    // ghosts are unbound: the tag can't route them, their seat still can
    private static @Nullable Game gameOf(Player player) {
        Game game = Game.of(player);
        if (game != null) return game;
        for (Game candidate : Matches.grotto().games()) {
            if (candidate.isGhost(player)) return candidate;
        }
        return null;
    }

    /** The shard tree's root, else the instance: the players who share it share a room. */
    private static Object roomOf(Player player) {
        Shard shard = Shard.of(player);
        if (shard == null) return player.getInstance();
        while (shard.parent() != null) shard = shard.parent();
        return shard;
    }
}
