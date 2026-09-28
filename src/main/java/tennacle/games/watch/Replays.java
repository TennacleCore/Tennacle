package tennacle.games.watch;

import tennacle.looks.Frames;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.Msg;
import tennacle.games.Match;
import tennacle.storage.Atomic;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Dispatcher;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.presets.Versus;
import io.github.term4.canvas.theme.Titles;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.modules.flow.Phases;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Every game is recorded: the log spools to disk as it grows and lands as a file when the game ends, naming its
 * mode, map, look and lineup ({@link Tapes}). What the game said and showed to everyone in it rides its own
 * channels ({@link Match#say}, {@link Match#title}), and its sidebar as the first seat saw it is kept on change,
 * so a watcher reads the kill lines, sees the titles and the board as the players did. Everyone who held a seat
 * then hears it is there to watch, wherever they are by then - the watching itself is {@link Theaters}. A game
 * the countdown never outlived keeps nothing: no file, no spool.
 */
public final class Replays {

    public static final DataKey<String> MODE = new DataKey<>("tennacle:mode", Codec.STRING);
    public static final DataKey<String> MAP = new DataKey<>("tennacle:map", Codec.STRING);
    public static final DataKey<String> CODE = new DataKey<>("tennacle:code", Codec.STRING);
    /** The day the game was launched, as its boards printed it: a code names a game only beside its date. */
    public static final DataKey<String> PLAYED = new DataKey<>("tennacle:played", Codec.STRING);
    /** Whether the game is recorded at all, read once at launch: a ruleset turns replays off for a whole
     *  platform, and {@code /rules} before a launch turns them off for the next one. */
    public static final StateKey<Boolean> RECORD = StateKey.of("replay.record", Codec.BOOLEAN, true);
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

    private static boolean installed;
    private static final Map<String, Recording> live = new ConcurrentHashMap<>();
    private static final Map<Recording, Game> recorded = new ConcurrentHashMap<>();

    private Replays() {}

    public static void install(@NotNull Path replays, @NotNull ShardManager shardManager) {
        Tapes.install(replays);
        if (installed) return; // channels, sources and listeners are server-wide: once
        installed = true;
        Replay.register(LINES);
        Replay.register(TITLES);
        Replay.register(ROWS);
        Replay.dispatchers(RowsSampler::new);
        // ENDING, not end: the teardown that follows clears the game's block overlay, and every cleared cell
        // would land in the file as one last write - the map's own colors back over the side that wore them
        Matches.grotto().onGameEnding(Replays::ended);
        Theaters.install(shardManager);
    }

    /** Called at launch; a no-op until {@link #install}. */
    /** Drops a recording started for a launch that then failed: the maps and the spool go with it. */
    public static void discard(@NotNull Game game) {
        Recording recording = live.remove(game.id());
        if (recording == null) return;
        recorded.remove(recording);
        try {
            recording.abandon();
        } catch (IOException e) {
            LOGGER.warn("the spool of the failed launch {} was not dropped", game.id(), e);
        }
    }

    public static void record(@NotNull Game game) {
        Mode mode = Matches.modeOf(game);
        Path spool = Tapes.spoolOf(game.id(), mode != null ? mode.name() : null);
        if (spool == null || !game.rules().resolve(Scope.GAME, RECORD)) return;
        try {
            // the header carries what the end would write, so a crash leaves a spool the next boot can file
            Recording recording = Replay.record(game.shard()).cueQuorum(() -> game.members().size())
                    .spool(spool, Tapes.SPOOL_TICKS, data -> stamp(data, game, mode));
            live.put(game.id(), recording);
            recorded.put(recording, game);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("game {} is not being recorded", game.id(), e);
        }
    }

    // what the file names of the game, read on the game's clock while the roster stands
    private static void stamp(UserData.Writer data, Game game, @Nullable Mode mode) {
        String map = mode != null ? mode.arena().nameOf(game.arena()) : null; // the pool's name, what a watch looks up
        if (mode != null) data.put(MODE, mode.name());
        if (map != null) data.put(MAP, map);
        data.put(CODE, game.code());
        data.put(PLAYED, launchedOn(game).toString());
        data.put(LOOK, Matches.lookNameOf(game));
        data.put(LINEUP, lineup(game));
        data.put(HEALTH, health(game));
    }

    // the board's date at the launch, not at the end: a game across midnight is filed under the day it began
    private static LocalDate launchedOn(Game game) {
        return Instant.now().minusSeconds(game.tick() / 20).atZone(ZoneId.systemDefault()).toLocalDate();
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

    /** What the running recording has bookmarked so far; empty where nothing is recording it. */
    public static @NotNull java.util.List<io.github.term4.archipelago.replay.Replay.Bookmark> marksOf(@NotNull Game game) {
        Recording recording = live.get(game.id());
        return recording == null ? java.util.List.of() : recording.bookmarks();
    }

    private static int paced(int ticks, double rate) {
        return (int) Math.max(ticks > 0 ? 1 : 0, Math.round(ticks / rate));
    }

    private static void ended(Game game, Outcome outcome) {
        Recording recording = live.remove(game.id());
        if (recording == null) return;
        recorded.remove(recording);
        if (!played(game)) {
            try {
                recording.abandon();
            } catch (IOException e) {
                LOGGER.warn("the spool of game {} was not dropped", game.id(), e);
            }
            return;
        }
        // read now: the ledger and roster go with the game right after this
        Mode mode = Matches.modeOf(game);
        String mapName = game.arena().meta().name();
        long ticks = Match.playTime(game);
        List<UUID> seated = game.roster().participants().stream().map(Roster.Participant::uuid).distinct().toList();
        Component line = recordedLine(game.id(), mapName, ticks);
        Path file = Tapes.fileFor(game.id(), mode != null ? mode.name() : null);
        if (file == null) return;
        CompletableFuture<Void> landed = recording.stopAsync(data -> stamp(data, game, mode)).thenAccept(bytes -> {
            if (bytes == null) return; // the encode failed and logged
            try {
                Atomic.write(file, bytes);
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
        Tapes.landing(landed);
    }

    // a tick of live play, which a game aborted at the countdown's end never had (the phase stamps its entry
    // before the seating check runs); a mode with no phases says nothing either way, so its game keeps its file
    private static boolean played(Game game) {
        if (Phases.phase(game).isEmpty()) return true;
        long live = Phases.enteredAt(game, Match.LIVE);
        return live >= 0 && game.tick() > live;
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
    /** A recorded length as a watcher reads it, {@code m:ss}. */
    public static @NotNull String clock(long ticks) {
        return Frames.shortClock(ticks);
    }

    static @NotNull Component recordedLine(@NotNull String gameId, @NotNull String map, long ticks) {
        Component hover = Msg.THEME.prose("Click to watch replay of game on ").append(Msg.v(map))
                .append(Msg.THEME.prose(" for " + clock(ticks)));
        return Versus.recordedLine(Msg.THEME, "/replay watch " + gameId, hover);
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
