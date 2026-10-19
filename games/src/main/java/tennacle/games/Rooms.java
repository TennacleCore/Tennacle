package tennacle.games;

import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import net.minestom.server.command.builder.arguments.ArgumentType;
import tennacle.social.channels.Channels;
import tennacle.social.messages.Messages;
import tennacle.games.watch.Theaters;
import tennacle.games.watch.Replays;
import tennacle.looks.Look;
import io.github.term4.canvas.tab.TabList;
import net.kyori.adventure.text.Component;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.canvas.chat.Chat;
import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/**
 * The rooms: a member speaks to their game (spectators included), a spectator to the other spectators of
 * that game, anyone else to the players in the same shard tree, else the same instance - unless they opened a
 * conversation or chose a channel. The routing itself is Canvas's {@link Chat}.
 */
public final class Rooms {

    private static final AtomicBoolean repainting = new AtomicBoolean();
    private static volatile BiPredicate<Player, Player> hears = (speaker, reader) -> true;

    private Rooms() {}

    /** Whether a line said outside any game reaches {@code reader}; the speaker always hears their own. */
    public static void hears(@NotNull BiPredicate<Player, Player> rule) {
        hears = rule;
    }

    public static void install() {
        Chat.install(Rooms::of);
        AppCommand all = Commands.player("ac", "Talk in the main chat, whatever channel you're in.")
                .usage("/ac <message>", "game, team or lobby chat, past /chat and an open conversation");
        var text = ArgumentType.StringArray("message");
        all.setDefaultExecutor((sender, ctx) -> Msg.fail(sender, "Invalid usage! '/ac <message>'"));
        all.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            Chat.Room room = normal(me, String.join(" ", ctx.get(text)));
            for (Player reader : room.audience()) reader.sendMessage(room.line());
        }, text);
        Commands.register(all);
        TabList.header(Rooms::tabHeader);
        TabList.footer(Rooms::tabFooter);
        // the lobby chrome counts who is online, so the count changing is what repaints it - otherwise every
        // other viewer's header keeps the number it was drawn with
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) repaint();
        });
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> repaint());
    }

    /** Next tick, and once: the leaver still counts in this one, and a queue emptying is one count change. */
    private static void repaint() {
        if (!repainting.compareAndSet(false, true)) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            repainting.set(false);
            TabList.refreshAll();
        });
    }

    /** The viewer's game's look - the replayed game's for a watcher - else the server chrome in the house look. */
    private static Look lookFor(Player viewer) {
        Look replayed = Theaters.lookOf(viewer);
        if (replayed != null) return replayed;
        Game game = gameOf(viewer);
        if (game == null) game = Matches.grotto().spectating(viewer);
        return game != null ? Matches.lookOf(game) : Look.TENNACLE;
    }

    static Component tabHeader(Player viewer) {
        return lookFor(viewer).tab().header().apply(viewer);
    }

    static Component tabFooter(Player viewer) {
        return lookFor(viewer).tab().footer().apply(viewer);
    }

    /** The room {@code me}'s line goes to, as the chat policy decides it. */
    public static Chat.@Nullable Room room(@NotNull Player me, @NotNull String message) {
        return of(me, message);
    }

    private static Chat.@Nullable Room of(@NotNull Player me, @NotNull String message) {
        if (Messages.converse(me, message)) return new Chat.Room(List.of(), Component.empty()); // went as a message
        Chat.Room chosen = Channels.room(me, message); // /chat party: the party hears everything, wherever they are
        if (chosen != null) return chosen;
        return normal(me, message);
    }

    /** Where {@code me}'s line goes with no channel chosen and no conversation open: {@code /ac}'s room. */
    public static Chat.@NotNull Room normal(@NotNull Player me, @NotNull String message) {
        Game game = gameOf(me);
        if (game != null) {
            if (TeamChat.benched(game, me)) return TeamChat.spectatorRoom(game, me, message);
            Chat.Room team = TeamChat.room(game, me, message);
            if (team != null) return team; // a team's own words: not the tape's
            List<Player> to = new ArrayList<>();
            game.forEachAudience(to::add);
            Component line = Matches.lookOf(game).chat().render(game, me, message);
            Replays.said(game, line);
            return new Chat.Room(to, line);
        }
        Game watched = Matches.grotto().spectating(me);
        if (watched != null) return TeamChat.spectatorRoom(watched, me, message);
        Object room = roomOf(me);
        List<Player> to = new ArrayList<>();
        for (Player other : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (gameOf(other) != null || Matches.grotto().spectating(other) != null || roomOf(other) != room) continue;
            if (other != me && !hears.test(me, other)) continue;
            to.add(other);
        }
        return new Chat.Room(to, Chat.plain(me, message));
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
