package tennacle.games.watch;

import tennacle.looks.Look;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.GameIds;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.Match;
import tennacle.games.matchmaking.Parties;
import tennacle.storage.Atomic;

import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.barnacle.Arena;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.presets.Versus;
import io.github.term4.canvas.theme.Titles;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.platform.SharedTeam;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every game is recorded: the log spools to disk as it grows and lands as {@code <game id>.shard} in the replay
 * directory when the game ends, naming its mode, map, look and lineup. What the game said and showed to everyone
 * in it rides its own channels ({@link Match#say}, {@link Match#title}), and its sidebar as the first seat saw it
 * is kept on change, so a watcher reads the kill lines, sees the titles and the board as the players did. Everyone
 * who held a seat then hears it is there to watch, wherever they are by then. A watch raises a theater over the
 * map's own world and puts the viewer in it as a ghost, {@link Dressing dressed} the way a game's spectator is,
 * holding the {@link Remote}; a party watches together behind its leader, who holds it for them. The last viewer
 * out closes the theater, and a replay that runs out sends everyone home. The file is what a later store takes;
 * the spool beside it is gone once the file is kept.
 */
public final class Replays {

    public static final DataKey<String> MODE = new DataKey<>("tennacle:mode", Codec.STRING);
    public static final DataKey<String> MAP = new DataKey<>("tennacle:map", Codec.STRING);
    public static final DataKey<String> CODE = new DataKey<>("tennacle:code", Codec.STRING);
    /** The look the game played under: the theater's tab chrome and health column follow it. */
    public static final DataKey<String> LOOK = new DataKey<>("tennacle:look", Codec.STRING);
    /** Everyone who held a seat, as {@code name|team|color}: names and nameplates keep the side's color. */
    public static final DataKey<List<String>> LINEUP = new DataKey<>("tennacle:lineup", Codec.STRING.list());
    /** The health surfaces the game showed ({@link HealthDisplay#NAME}, {@link HealthDisplay#TAB}). */
    public static final DataKey<List<String>> HEALTH = new DataKey<>("tennacle:health", Codec.STRING.list());

    /** A line everyone in the game read. */
    public record Line(@NotNull Component text) {}

    /** A title everyone in the game saw, its times in client ticks. */
    public record Shown(@NotNull Component title, @NotNull Component subtitle, int fadeIn, int stay, int fadeOut) {}

    /** The game's sidebar as its first seat saw it, whole. */
    public record Rows(@NotNull Component title, @NotNull List<Component> lines) {}

    // the game's own channels; what one player alone gets (a dead screen, command feedback, a party) never lands here
    static final Recordable<Line> LINES = Recordable.of("tennacle:line", 1, 10,
            StructCodec.struct("t", Codec.COMPONENT, Line::text, Line::new),
            () -> (pb, op) -> pb.shard().forEachWatcher(w -> w.sendMessage(op.op().text())));
    // a title is a one-shot: shown on the crossing either way, its times stretched to the rate so a beat
    // still hands over to the next without a gap at half speed
    static final Recordable<Shown> TITLES = Recordable.of("tennacle:title", 1, 10, StructCodec.struct(
                    "t", Codec.COMPONENT, Shown::title, "s", Codec.COMPONENT, Shown::subtitle,
                    "i", Codec.INT, Shown::fadeIn, "d", Codec.INT, Shown::stay, "o", Codec.INT, Shown::fadeOut,
                    Shown::new),
            () -> new Applier<Shown>() {
                @Override
                public void apply(@NotNull Playback pb, @NotNull Recorded<Shown> op) {
                    Shown shown = op.op();
                    double rate = Math.max(1.0e-9, pb.rate());
                    Title title = Title.title(shown.title(), shown.subtitle(),
                            Titles.times(paced(shown.fadeIn(), rate), paced(shown.stay(), rate), paced(shown.fadeOut(), rate)));
                    pb.shard().forEachWatcher(w -> w.showTitle(title));
                }

                @Override
                public void unapply(@NotNull Playback pb, @NotNull Recorded<Shown> op) {
                    apply(pb, op);
                }
            });
    // the sidebar is state: crossing an op backward lands on the one before it, a seek on the latest, a
    // keyframe carries it whole
    static final Recordable<Rows> ROWS = Recordable.of("tennacle:rows", 1, 10,
            StructCodec.struct("t", Codec.COMPONENT, Rows::title, "l", Codec.COMPONENT.list(), Rows::lines, Rows::new),
            () -> new Applier<Rows>() {
                @Override
                public void apply(@NotNull Playback pb, @NotNull Recorded<Rows> op) {
                    Dressing dressing = Dressing.of(pb);
                    if (dressing != null) dressing.rows(op.op());
                }

                @Override
                public void unapply(@NotNull Playback pb, @NotNull Recorded<Rows> op) {
                    int at = pb.cursor(Replays.ROWS); // the cursor sits on the op being crossed
                    List<Recorded<Rows>> ops = pb.ops(Replays.ROWS);
                    if (at > 0 && at <= ops.size()) apply(pb, ops.get(at - 1));
                }
            })
            .folding((s, op) -> s.latest(Replays.ROWS, "rows", op))
            .keyframing();

    private static final Logger LOGGER = LoggerFactory.getLogger(Replays.class);
    private static final int SPOOL_TICKS = 1200;

    private record Theater(Shard stage, Playback playback, Arena arena, Dressing dressing, Set<Player> watchers) {}

    private static volatile @Nullable Path dir;
    private static volatile @Nullable ShardManager shards;
    private static boolean installed;
    private static final Map<String, Recording> live = new ConcurrentHashMap<>();
    private static final Map<Recording, Game> recorded = new ConcurrentHashMap<>();
    private static final Map<String, Theater> theaters = new ConcurrentHashMap<>();
    private static final Map<Player, String> watching = new ConcurrentHashMap<>();

    private Replays() {}

    public static void install(@NotNull Path replays, @NotNull ShardManager shardManager) {
        dir = replays;
        shards = shardManager;
        if (installed) return; // channels, sources and listeners are server-wide: once
        installed = true;
        Replay.register(LINES);
        Replay.register(TITLES);
        Replay.register(ROWS);
        Replay.dispatchers(RowsSampler::new);
        Remote.install();
        Matches.grotto().onGameEnd(Replays::ended);
        Sessions.register(viewer -> watching.containsKey(viewer) ? () -> {
            leave(viewer);
            Matches.toLobby(viewer);
        } : null);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> leave(e.getPlayer()));
        // the stop fires inside the theater's own tick: the teardown waits for the next
        MinecraftServer.getGlobalEventHandler().addListener(PlaybackStopEvent.class,
                e -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> over(e.playback())));
    }

    /** {@code <game id>.shard} under the replay directory, once installed. */
    public static @Nullable Path fileOf(@NotNull String gameId) {
        Path at = dir;
        return at == null ? null : at.resolve(gameId + ".shard");
    }

    /** Called at launch; a no-op until {@link #install}. */
    public static void record(@NotNull Game game) {
        Path at = dir;
        if (at == null) return;
        try {
            Recording recording = Replay.record(game.shard())
                    .spool(at.resolve("spool").resolve(game.id() + ".spool"), SPOOL_TICKS);
            live.put(game.id(), recording);
            recorded.put(recording, game);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("game {} is not being recorded", game.id(), e);
        }
    }

    /** Whether {@code game} is being recorded. */
    public static boolean recording(@NotNull Game game) {
        return live.containsKey(game.id());
    }

    /** Keeps a line everyone in {@code game} read; nothing for a game not being recorded. */
    public static void said(@NotNull Game game, @NotNull Component line) {
        Recording recording = live.get(game.id());
        if (recording != null) recording.emit(LINES, new Line(line));
    }

    /** Keeps a title everyone in {@code game} saw. */
    public static void shown(@NotNull Game game, @NotNull Title title) {
        Recording recording = live.get(game.id());
        if (recording == null) return;
        Title.Times times = title.times() != null ? title.times() : Title.DEFAULT_TIMES;
        recording.emit(TITLES, new Shown(title.title(), title.subtitle(),
                ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut())));
    }

    private static int ticks(Duration duration) {
        return (int) (duration.toMillis() / 50);
    }

    /** Bookmarks the moment in {@code game}'s replay - a goal, say - with what a watcher's list should read of it. */
    public static void mark(@NotNull Game game, @NotNull String name, @NotNull CompoundBinaryTag data) {
        Recording recording = live.get(game.id());
        if (recording != null) recording.bookmark(name, data);
    }

    private static int paced(int ticks, double rate) {
        return (int) Math.max(ticks > 0 ? 1 : 0, Math.round(ticks / rate));
    }

    /** The game {@code viewer} is watching a replay of, or null. */
    public static @Nullable String watching(@NotNull Player viewer) {
        return watching.get(viewer);
    }

    /** The playback {@code viewer} is watching, or null. */
    public static @Nullable Playback playbackOf(@NotNull Player viewer) {
        String id = watching.get(viewer);
        Theater theater = id != null ? theaters.get(id) : null;
        return theater != null ? theater.playback() : null;
    }

    /** The look of the game {@code viewer} is watching a replay of, or null. */
    public static @Nullable Look lookOf(@NotNull Player viewer) {
        String id = watching.get(viewer);
        Theater theater = id != null ? theaters.get(id) : null;
        return theater != null ? theater.dressing().look() : null;
    }

    private static void ended(Game game, Outcome outcome) {
        Recording recording = live.remove(game.id());
        Path at = dir;
        if (recording == null || at == null) return;
        recorded.remove(recording);
        // read now: the ledger and roster go with the game right after this
        Mode mode = Matches.modeOf(game);
        String map = mode != null ? mode.arena().nameOf(game.arena()) : null; // the pool's name, what a watch looks up
        String mapName = game.arena().meta().name();
        String look = Matches.lookNameOf(game);
        List<String> lineup = lineup(game);
        List<String> health = health(game);
        long ticks = Match.playTime(game);
        List<UUID> seated = game.roster().participants().stream().map(Roster.Participant::uuid).distinct().toList();
        Component line = recordedLine(game.id(), mapName, ticks);
        recording.stopAsync(data -> {
            if (mode != null) data.put(MODE, mode.name());
            if (map != null) data.put(MAP, map);
            data.put(CODE, game.code());
            data.put(LOOK, look);
            data.put(LINEUP, lineup);
            data.put(HEALTH, health);
        }).thenAccept(bytes -> {
            if (bytes == null) return; // the encode failed and logged
            try {
                Files.createDirectories(at);
                Atomic.write(at.resolve(game.id() + ".shard"), bytes);
                recording.discard();
            } catch (IOException e) {
                LOGGER.warn("the replay of game {} was not saved", game.id(), e);
                return;
            }
            for (UUID uuid : seated) {
                Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
                if (player != null) player.sendMessage(line);
            }
        });
    }

    // everyone who held a seat, once, in the seat's color: a rejoin is the same name again
    private static List<String> lineup(Game game) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Roster.Participant p : game.roster().participants()) {
            out.put(p.username(), p.username() + "|" + p.slot().team() + "|"
                    + game.roster().team(p.slot().team()).color().value());
        }
        return new ArrayList<>(out.values());
    }

    private static List<String> health(Game game) {
        if (game.module(HealthDisplay.class) == null) return List.of();
        List<String> surfaces = game.rules().resolve(Scope.GAME, HealthDisplay.SURFACES);
        List<String> out = new ArrayList<>();
        if (surfaces.contains(HealthDisplay.NAME)) out.add(HealthDisplay.NAME);
        if (surfaces.contains(HealthDisplay.TAB) && Matches.lookOf(game).tabHealth()) out.add(HealthDisplay.TAB);
        return out;
    }

    /** Scrims' line: green, a click copies the watch command, the hover names the map and the length. */
    static @NotNull Component recordedLine(@NotNull String gameId, @NotNull String map, long ticks) {
        Component hover = Msg.THEME.prose("Click to watch replay of game on ").append(Msg.v(map))
                .append(Msg.THEME.prose(" for " + clock(ticks)));
        return Versus.recordedLine(Msg.THEME, "/replay watch " + gameId, hover);
    }

    public static @NotNull String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + "%02d".formatted(seconds % 60);
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
        Path file = id == null || manager == null ? null : fileOf(id);
        if (file == null || !Files.exists(file)) {
            Msg.fail(viewer, "No replay of a game ", Msg.v(idOrCode), ".");
            return;
        }
        if (Game.of(viewer) != null) {
            Msg.fail(viewer, "You're in a game. ", Msg.run("/l"), " first.");
            return;
        }
        if (!Parties.requireLeader(viewer)) return;
        Theater theater = theaters.get(id);
        if (theater == null) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                Msg.fail(viewer, "That replay could not be read.");
                return;
            }
            UserData.View data = ShardFile.userData(bytes);
            String modeName = data.get(MODE);
            Mode mode = modeName != null ? Matches.mode(modeName) : null;
            Arena arena = map;
            if (arena == null) {
                String named = data.get(MAP);
                arena = mode != null && named != null ? mode.arena().named(named) : null;
            }
            if (arena == null) {
                Msg.fail(viewer, "That replay's map is not on this server.");
                return;
            }
            Instance world = arena.instantiate();
            Shard stage = new Shard(world);
            manager.register(stage, "replay-" + id.substring(0, 8));
            Playback playback;
            try {
                playback = Replay.play(stage, bytes);
            } catch (IllegalArgumentException e) {
                manager.destroy(stage);
                Msg.fail(viewer, "That replay could not be opened: " + e.getMessage());
                return;
            }
            String lookName = data.get(LOOK);
            String code = data.get(CODE);
            List<String> lineup = data.get(LINEUP);
            List<String> health = data.get(HEALTH);
            Dressing dressing = new Dressing(stage, playback, id, code != null ? code : "", mode,
                    lookName != null ? lookName : "tennacle", lineup != null ? lineup : List.of(),
                    health != null ? health : List.of());
            theater = new Theater(stage, playback, arena, dressing, ConcurrentHashMap.newKeySet());
            theaters.put(id, theater);
        }
        for (Player member : Parties.of(viewer)) { // the leader first: the remote is theirs
            if (member != viewer && Game.of(member) != null) {
                Msg.say(viewer, Msg.v(member.getUsername()), " is in a game and stays behind.");
                continue;
            }
            admit(member, theater, id, member == viewer);
        }
    }

    private static void admit(Player viewer, Theater theater, String id, boolean controller) {
        leave(viewer); // a view elsewhere ends first
        Spectators.exit(viewer);
        SpectatorMode.enter(viewer, SpectatorMode.Mode.GHOST);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, true);
        theater.watchers().add(viewer);
        watching.put(viewer, id);
        Pos spawn = theater.arena().meta().spawn();
        Pos landing = spawn != null ? spawn : new Pos(0.5, 64, 0.5);
        viewer.setInstance(theater.stage().instance(), landing).thenRun(() -> {
            if (!id.equals(watching.get(viewer))) return;
            ShardView.set(viewer, ShardView.of(theater.stage()));
            theater.dressing().enter(viewer, controller);
        });
        Msg.ok(viewer, "Watching the replay of game ", Msg.v(id), ". ", Msg.run("/l"), " to leave.");
    }

    /**
     * Takes {@code viewer} out of a replay, and the party they lead out behind them, home; the last one out closes
     * the theater. {@code false} = was not watching.
     */
    public static boolean leave(@NotNull Player viewer) {
        String id = watching.remove(viewer);
        if (id == null) return false;
        Theater theater = theaters.get(id);
        undress(viewer, theater);
        if (theater != null) {
            theater.watchers().remove(viewer);
            if (Parties.leads(viewer)) {
                for (Player member : Parties.of(viewer)) {
                    if (member != viewer && id.equals(watching.get(member)) && leave(member)) Matches.toLobby(member);
                }
            }
            if (theater.watchers().isEmpty() && theaters.remove(id) != null) close(theater);
        }
        return true;
    }

    private static void undress(Player viewer, @Nullable Theater theater) {
        if (theater != null) theater.dressing().exit(viewer);
        ShardView.set(viewer, null);
        SpectatorMode.exit(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, false);
    }

    // the playback ran out: everyone watching goes home
    private static void over(Playback playback) {
        for (Map.Entry<String, Theater> entry : theaters.entrySet()) {
            Theater theater = entry.getValue();
            if (theater.playback() != playback || !theaters.remove(entry.getKey(), theater)) continue;
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

    // the game's sidebar as its first seat sees it, kept on change; a keyframe re-emits it whole
    private static final class RowsSampler implements Dispatcher {
        private @Nullable Boards.Rendered last;

        @Override
        public void sample(@NotNull Recording recording) {
            Game game = recorded.get(recording);
            Player seat = game != null ? Match.reference(game) : null;
            Boards.Rendered now = seat != null ? Boards.rendered(seat) : null;
            if (now == null || now.equals(last)) return;
            last = now;
            recording.emit(ROWS, new Rows(now.title(), now.lines()));
        }

        @Override
        public void reset(@NotNull Recording recording) {
            last = null;
        }
    }
}
