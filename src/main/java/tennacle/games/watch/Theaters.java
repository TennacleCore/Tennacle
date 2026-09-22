package tennacle.games.watch;

import tennacle.looks.Look;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.GameIds;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.matchmaking.Parties;
import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.barnacle.Arena;
import io.github.term4.grotto.Game;
import io.github.term4.polyp.platform.SharedTeam;
import io.github.term4.polyp.platform.compatibility.SpectatorHud;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.Marker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.Markers;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watching a game back. A watch raises a theater over the map's own world and puts the viewer in it as a ghost,
 * {@link Dressing dressed} the way a game's spectator is, holding the {@link Remote}; a party watches together
 * behind its leader, who holds it for them. Every watch raises a theater of its own - two people who start the
 * same replay apart never share a remote. A replay that runs out holds its last frame until the watchers leave;
 * the last viewer out closes the theater.
 */
public final class Theaters {

    private static final Logger LOGGER = LoggerFactory.getLogger(Theaters.class);

    private record Theater(String game, Shard stage, Playback playback, Arena arena, Dressing dressing,
                          Set<Player> watchers, double floor) {}

    private static volatile @Nullable ShardManager shards;

    private static final Set<Theater> theaters = ConcurrentHashMap.newKeySet();

    private static final Map<Player, Theater> watching = new ConcurrentHashMap<>();

    private static final AtomicInteger staged = new AtomicInteger(); // one shard name per theater, however many of a game

    private Theaters() {}

    static void install(@NotNull ShardManager shardManager) {
        shards = shardManager;
        Remote.install();
        Sessions.register(viewer -> watching.containsKey(viewer) ? () -> {
            leave(viewer);
            Matches.toLobby(viewer);
        } : null);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> leave(e.getPlayer()));
        // no game watches the theater's void line, so the replay does: back to where the watcher came in
        MinecraftServer.getGlobalEventHandler().addListener(PlayerMoveEvent.class, e -> {
            if (watching.isEmpty()) return; // every move packet on the server reaches here, and usually nobody watches
            Theater theater = watching.get(e.getPlayer());
            if (theater == null || e.getNewPosition().y() >= theater.floor()) return;
            e.getPlayer().teleport(landing(theater.arena()));
        });
        // watchers are observers of the stage, members of nothing: the tab lists a theater's watchers to each other
        ShardTabList.rule((viewer, subject) -> {
            Theater theirs = watching.get(subject);
            if (theirs == null) return null;
            return watching.get(viewer) == theirs;
        });
        // the stop fires inside the theater's own tick: the teardown waits for the next
        MinecraftServer.getGlobalEventHandler().addListener(PlaybackStopEvent.class,
                e -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> over(e.playback())));
    }

    /** The game {@code viewer} is watching a replay of, or null. */
    public static @Nullable String watching(@NotNull Player viewer) {
        Theater theater = watching.get(viewer);
        return theater != null ? theater.game() : null;
    }

    /** A replay as a viewer knows it: the game's mode, map, and the date and board code its boards printed, never the file's id. */
    public record Watched(@NotNull String game, @Nullable Mode mode, @NotNull Arena arena, @NotNull String look,
                          @NotNull LocalDate played, @NotNull String code) {}

    /** What {@code viewer} is watching, or null. */
    public static @Nullable Watched watched(@NotNull Player viewer) {
        Theater theater = watching.get(viewer);
        if (theater == null) return null;
        Dressing dressing = theater.dressing();
        return new Watched(theater.game(), dressing.mode(), theater.arena(), dressing.lookName(), dressing.played(), dressing.code());
    }

    /** The game whose replay plays on {@code stage} (a theater's root), or null. */
    public static @Nullable String replayOf(@NotNull Shard stage) {
        for (Theater theater : theaters) {
            if (theater.stage() == stage) return theater.game();
        }
        return null;
    }

    /** The playback {@code viewer} is watching, or null. */
    public static @Nullable Playback playbackOf(@NotNull Player viewer) {
        Theater theater = watching.get(viewer);
        return theater != null ? theater.playback() : null;
    }

    /** The look of the game {@code viewer} is watching a replay of, or null. */
    public static @Nullable Look lookOf(@NotNull Player viewer) {
        Theater theater = watching.get(viewer);
        return theater != null ? theater.dressing().look() : null;
    }

    /**
     * Plays the replay of game {@code idOrCode} for {@code viewer}: a theater over the map's world, a ghost in it
     * holding the remote. A party comes along behind its leader; a member on their own is refused like a duel.
     */
    public static void watch(@NotNull Player viewer, @NotNull String idOrCode) {
        watch(viewer, idOrCode, null);
    }

    /** {@link #watch(Player, String)} staged over {@code map} instead of the one the replay names. */
    public static void watch(@NotNull Player viewer, @NotNull String idOrCode, @Nullable Arena map) {
        ShardManager manager = shards;
        String id = idOf(idOrCode);
        Path file = id == null || manager == null ? null : Tapes.fileOf(id);
        if (file == null || !Files.exists(file)) {
            Msg.fail(viewer, "No replay of a game ", Msg.v(idOrCode), ".");
            return;
        }
        if (Game.of(viewer) != null) {
            Msg.fail(viewer, "You're in a game. ", Msg.run("/l"), " first.");
            return;
        }
        if (id.equals(watching(viewer))) { // already in it: opening a second theater of the same game helps nobody
            Msg.say(viewer, "You're already watching that replay.");
            return;
        }
        if (!Parties.requireLeader(viewer)) return;
        // the read, the decode and a cold map load are all off the tick thread, and Replay.play relays into the
        // stage's own live domain - which Archipelago refuses from another clock's thread
        Thread.ofVirtual().name("tennacle-replay-" + id.substring(0, 8)).start(() -> {
            byte[] loaded;
            try {
                loaded = Files.readAllBytes(file);
            } catch (IOException e) {
                MinecraftServer.getSchedulerManager().scheduleNextTick(() ->
                        Msg.fail(viewer, "That replay could not be read."));
                return;
            }
            // the stage, its playback and the admits all on the server tick: a domain player's command runs on
            // THEIR shard's clock, and Replay.play relays into the stage's own live clock from there
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> open(viewer, id, map, loaded, file));
        });
    }

    private static void open(Player viewer, String id, @Nullable Arena map, byte[] bytes, Path file) {
        ShardManager manager = shards;
        UserData.View data = ShardFile.userData(bytes);
        String modeName = data.get(Replays.MODE);
        Mode mode = modeName != null ? Matches.mode(modeName) : null;
        Arena arena = map;
        if (arena == null) {
            String named = data.get(Replays.MAP);
            arena = mode != null && named != null ? mode.arena().named(named) : null;
        }
        if (arena == null) {
            Msg.fail(viewer, "That replay's map is not on this server.");
            return;
        }
        // a theater of their own, over the map's one instance: another party's playback is another shard, and
        // the two never see each other's blocks, ghosts or remote
        Instance world = arena.instantiate();
        Shard stage = new Shard(world, ExecutionDomain.create()); // its own clock, as a game has
        manager.register(stage, "replay-" + id.substring(0, 8) + "-" + staged.incrementAndGet());
        Playback playback;
        try {
            playback = Replay.play(stage, bytes);
        } catch (RuntimeException e) {
            manager.destroy(stage);
            Msg.fail(viewer, "That replay could not be opened: " + e.getMessage());
            return;
        }
        playback.holdAtEnd(true); // the last frame stays up until the watchers leave; the last one out closes the theater
        String lookName = data.get(Replays.LOOK);
        String code = data.get(Replays.CODE);
        List<String> lineup = data.get(Replays.LINEUP);
        List<String> health = data.get(Replays.HEALTH);
        Dressing dressing = new Dressing(stage, playback, id, code != null ? code : "", playedOn(data, file), mode,
                lookName != null ? lookName : "tennacle", lineup != null ? lineup : List.of(),
                health != null ? health : List.of());
        playback.ghostNames(dressing::listName); // before the first move op raises a ghost
        Theater theater = new Theater(id, stage, playback, arena, dressing, ConcurrentHashMap.newKeySet(),
                floor(arena));
        theaters.add(theater);
        try {
            for (Player member : Parties.of(viewer)) { // the leader first: the remote is theirs
                if (member != viewer && Game.of(member) != null) {
                    Msg.say(viewer, Msg.v(member.getUsername()), " is in a game and stays behind.");
                    continue;
                }
                admit(member, theater, member == viewer);
                Msg.ok(member, "Watching the replay of game ", Msg.v(id), ". ", Msg.run("/l"), " to leave.");
            }
        } catch (RuntimeException e) {
            // a stage with its own clock thread would tick on forever with nobody in it
            if (theaters.remove(theater)) close(theater);
            throw e;
        }
        if (theater.watchers().isEmpty() && theaters.remove(theater)) close(theater); // nobody made it in
    }

    /**
     * Puts {@code viewer} in the theater {@code host} watches, without the remote - the one who opened it keeps
     * it. A party comes along behind its leader, as {@link #watch} takes one. {@code false} = {@code host}
     * watches nothing.
     */
    public static boolean join(@NotNull Player viewer, @NotNull Player host) {
        Theater theater = watching.get(host);
        if (theater == null) return false;
        if (watching.get(viewer) == theater) {
            Msg.say(viewer, "You're already watching that replay.");
            return true;
        }
        if (Game.of(viewer) != null) {
            Msg.fail(viewer, "You're in a game. ", Msg.run("/l"), " first.");
            return true;
        }
        if (!Parties.requireLeader(viewer)) return true;
        for (Player member : Parties.of(viewer)) {
            if (member != viewer && Game.of(member) != null) {
                Msg.say(viewer, Msg.v(member.getUsername()), " is in a game and stays behind.");
                continue;
            }
            admit(member, theater, false);
            Msg.ok(member, "Watching the replay with ", Msg.v(host.getUsername()), ". ", Msg.run("/l"), " to leave.");
        }
        return true;
    }

    // the stamped day; a file from before the stamp is dated by the day it was written, which ended the game
    private static LocalDate playedOn(UserData.View data, Path file) {
        String stamped = data.get(Replays.PLAYED);
        if (stamped != null) {
            try {
                return LocalDate.parse(stamped);
            } catch (java.time.format.DateTimeParseException ignored) {
            }
        }
        try {
            return Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (IOException e) {
            return LocalDate.now();
        }
    }

    private static void admit(Player viewer, Theater theater, boolean controller) {
        leave(viewer); // a view elsewhere ends first
        Spectators.exit(viewer);
        SpectatorMode.enter(viewer, SpectatorMode.Mode.GHOST);
        SpectatorHud.show(viewer); // the ghost body drops a 1.8 screen's bars, and the timeline rides the experience bar
        Spectators.freshen(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, true);
        theater.watchers().add(viewer);
        watching.put(viewer, theater);
        // the row before the body: a 1.8 client drops the spawn of a player it holds no tab entry for, and the
        // transfer below spawns the theater's other watchers the moment this one lands. Dressing's own resync
        // decorates the row afterwards - it is the ADD that has to come first
        ShardTabList.resync(viewer);
        Pos landing = landing(theater.arena());
        viewer.setInstance(theater.stage().instance(), landing).thenRun(() -> {
            if (watching.get(viewer) != theater) return;
            // the stage's members are the replay's ghosts; a watcher is bound to nothing, so co-watchers are
            // base entities to each other and invisible until the view says otherwise
            ShardView.set(viewer, ShardView.of(theater.stage())
                    .withBaseEntities(other -> watching.get(other) == theater));
            theater.dressing().enter(viewer, controller);
        });
    }

    // the lowest plane the map draws - a game's void line, where it has one; else far enough under the arrival
    // that only a watcher off the map crosses it
    private static double floor(Arena arena) {
        Double lowest = null;
        for (Marker marker : arena.markers()) {
            if (marker instanceof LimitMarker plane && plane.y() != null && (lowest == null || plane.y() < lowest)) {
                lowest = plane.y();
            }
        }
        return lowest != null ? lowest : landing(arena).y() - 64;
    }

    // where a dead seat waits, as the game puts it (the dead marker, else the spectator's spot), leveled: a marker
    // authored looking up or down would tilt the arrival
    static @NotNull Pos landing(@NotNull Arena arena) {
        List<PosMarker> dead = arena.markers(Markers.DEAD);
        Pos at = !dead.isEmpty() ? dead.getFirst().pos() : Game.spectatorLanding(arena);
        return (at != null ? at : new Pos(0.5, 64, 0.5)).withPitch(0);
    }

    /** Whether {@code viewer} is booked into a theater they are no longer standing in. */
    static boolean strayed(@NotNull Player viewer) {
        Theater theater = watching.get(viewer);
        return theater != null && viewer.getInstance() != theater.stage().instance();
    }

    /**
     * Takes {@code viewer} out of a replay, and the party they lead out behind them, home; the last one out closes
     * the theater. {@code false} = was not watching.
     */
    public static boolean leave(@NotNull Player viewer) {
        Theater theater = watching.remove(viewer);
        if (theater == null) return false;
        undress(viewer, theater);
        theater.watchers().remove(viewer);
        if (Parties.leads(viewer)) {
            for (Player member : Parties.of(viewer)) {
                if (member != viewer && watching.get(member) == theater && leave(member)) Matches.toLobby(member);
            }
        }
        if (theater.watchers().isEmpty() && theaters.remove(theater)) close(theater);
        return true;
    }

    private static void undress(Player viewer, Theater theater) {
        theater.dressing().exit(viewer);
        ShardView.set(viewer, null);
        SpectatorMode.exit(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, false);
    }

    // the playback was halted under its watchers (a dev's /replay halt): everyone watching goes home
    private static void over(Playback playback) {
        for (Theater theater : theaters) {
            if (theater.playback() != playback || !theaters.remove(theater)) continue;
            for (Player viewer : List.copyOf(theater.watchers())) {
                watching.remove(viewer);
                undress(viewer, theater);
                Msg.say(viewer, "The replay ended.");
                Matches.toLobby(viewer);
            }
            theater.watchers().clear();
            close(theater);
            return;
        }
    }

    private static void close(Theater theater) {
        theater.dressing().close();
        if (theater.playback().active()) theater.playback().halt();
        ShardManager manager = shards;
        if (manager != null) manager.destroy(theater.stage());
    }

    // the id itself, or a board code re-derived for the day it was shown; a game past midnight had yesterday's
    private static @Nullable String idOf(String idOrCode) {
        try {
            return UUID.fromString(idOrCode).toString();
        } catch (IllegalArgumentException notAnId) {
            for (LocalDate day : List.of(LocalDate.now(), LocalDate.now().minusDays(1))) {
                try {
                    String id = GameIds.derive(day, idOrCode);
                    if (id != null) return id;
                } catch (IllegalArgumentException notACode) {
                    return null;
                }
            }
            return null;
        }
    }
}
