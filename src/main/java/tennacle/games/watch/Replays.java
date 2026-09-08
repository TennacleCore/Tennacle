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
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.barnacle.Arena;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.presets.Versus;
import io.github.term4.canvas.theme.Titles;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.platform.SharedTeam;
import io.github.term4.polyp.platform.compatibility.SpectatorHud;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.Marker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.modules.flow.Phases;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every game is recorded: the log spools to disk as it grows and lands as {@code <mode>/<game id>.shard} under
 * the replay directory when the game ends, naming its mode, map, look and lineup. What the game said and showed to everyone
 * in it rides its own channels ({@link Match#say}, {@link Match#title}), and its sidebar as the first seat saw it
 * is kept on change, so a watcher reads the kill lines, sees the titles and the board as the players did. Everyone
 * who held a seat then hears it is there to watch, wherever they are by then. A watch raises a theater over the
 * map's own world and puts the viewer in it as a ghost, {@link Dressing dressed} the way a game's spectator is,
 * holding the {@link Remote}; a party watches together behind its leader, who holds it for them. Every watch
 * raises a theater of its own - two people who start the same replay apart never share a remote. A replay that
 * runs out holds its last frame until the watchers leave; the last viewer out closes the theater. A game the
 * countdown never outlived keeps nothing - no file, no spool. The file is
 * what a later store takes; the spool in {@code <mode>/spool/} is gone once the file is kept. A game a stop cuts
 * short still lands ({@link #flush} waits for the file); one a crash cuts leaves its spool, and the next boot
 * files what the spool holds under the mode ({@link #install}).
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
    private static final int SPOOL_TICKS = 1200;

    private record Theater(String game, Shard stage, Playback playback, Arena arena, Dressing dressing,
                          Set<Player> watchers, double floor) {}

    private static volatile @Nullable Path dir;
    private static volatile @Nullable ShardManager shards;
    private static boolean installed;
    private static final Map<String, Recording> live = new ConcurrentHashMap<>();
    private static final Map<Recording, Game> recorded = new ConcurrentHashMap<>();
    private static final Set<Theater> theaters = ConcurrentHashMap.newKeySet();
    private static final Map<Player, Theater> watching = new ConcurrentHashMap<>();
    private static final AtomicInteger staged = new AtomicInteger(); // one shard name per theater, however many of a game
    private static final Set<CompletableFuture<?>> landing = ConcurrentHashMap.newKeySet();

    private Replays() {}

    public static void install(@NotNull Path replays, @NotNull ShardManager shardManager) {
        dir = replays;
        shards = shardManager;
        sweep(replays);
        if (installed) return; // channels, sources and listeners are server-wide: once
        installed = true;
        Replay.register(LINES);
        Replay.register(TITLES);
        Replay.register(ROWS);
        Replay.dispatchers(RowsSampler::new);
        Remote.install();
        // ENDING, not end: the teardown that follows clears the game's block overlay, and every cleared cell
        // would land in the file as one last write - the map's own colors back over the side that wore them
        Matches.grotto().onGameEnding(Replays::ended);
        Sessions.register(viewer -> watching.containsKey(viewer) ? () -> {
            leave(viewer);
            Matches.toLobby(viewer);
        } : null);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> leave(e.getPlayer()));
        // no game watches the theater's void line, so the replay does: back to where the watcher came in
        MinecraftServer.getGlobalEventHandler().addListener(PlayerMoveEvent.class, e -> {
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

    /**
     * {@code <mode>/<game id>.shard} under the replay directory, once installed: the first mode folder holding
     * it, else where a run before the mode folders kept it (may not exist).
     */
    public static @Nullable Path fileOf(@NotNull String gameId) {
        Path at = dir;
        if (at == null) return null;
        String name = gameId + ".shard";
        try (Stream<Path> entries = Files.list(at)) {
            for (Path folder : entries.filter(Files::isDirectory).toList()) {
                Path file = folder.resolve(name);
                if (Files.exists(file)) return file;
            }
        } catch (IOException ignored) {
            // no replay directory yet: nothing recorded
        }
        return at.resolve(name);
    }

    /** Called at launch; a no-op until {@link #install}. */
    public static void record(@NotNull Game game) {
        Path at = dir;
        if (at == null || !game.rules().resolve(Scope.GAME, RECORD)) return;
        Mode mode = Matches.modeOf(game);
        Path spool = at.resolve(folderOf(mode != null ? mode.name() : null)).resolve("spool").resolve(game.id() + ".spool");
        try {
            // the header carries what the end would write, so a crash leaves a spool the next boot can file
            Recording recording = Replay.record(game.shard()).spool(spool, SPOOL_TICKS, data -> stamp(data, game, mode));
            live.put(game.id(), recording);
            recorded.put(recording, game);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("game {} is not being recorded", game.id(), e);
        }
    }

    /** Waits for the files of games that just ended to land; {@code false} = one was still in flight. */
    public static boolean flush(long timeout, @NotNull TimeUnit unit) {
        try {
            CompletableFuture.allOf(landing.toArray(CompletableFuture[]::new)).get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static String folderOf(@Nullable String mode) {
        return mode == null || mode.isBlank() ? "games" : mode;
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

    // what an earlier run left behind: the spool of a game a crash cut short becomes its file, and a file from
    // before the mode folders moves under its mode
    private static void sweep(Path at) {
        if (!Files.isDirectory(at)) return;
        List<Path> spools = new ArrayList<>();
        List<Path> loose = new ArrayList<>();
        try (Stream<Path> entries = Files.list(at)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && name.endsWith(".shard")) loose.add(entry);
                else if (Files.isDirectory(entry)) spools.addAll(spoolsIn(name.equals("spool") ? entry : entry.resolve("spool")));
            }
        } catch (IOException e) {
            LOGGER.warn("the replay directory {} was not swept", at, e);
            return;
        }
        for (Path spool : spools) salvage(at, spool);
        for (Path file : loose) file(at, file);
        try {
            Files.deleteIfExists(at.resolve("spool")); // the folder from before the mode folders, once emptied
        } catch (IOException ignored) {
            // still holds something: stays
        }
    }

    private static List<Path> spoolsIn(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(f -> f.getFileName().toString().endsWith(".spool")).toList();
        } catch (IOException e) {
            LOGGER.warn("{} was not swept", folder, e);
            return List.of();
        }
    }

    // a spool that fails to salvage goes too: it would fail the same way every boot
    private static void salvage(Path at, Path spool) {
        String id = spool.getFileName().toString().replaceFirst("\\.spool$", "");
        try {
            byte[] bytes = Replay.salvage(spool);
            if (bytes == null) {
                LOGGER.warn("the spool of game {} held nothing to keep; dropped", id);
            } else {
                Path file = at.resolve(folderOf(ShardFile.userData(bytes).get(MODE))).resolve(id + ".shard");
                Atomic.write(file, bytes);
                LOGGER.info("filed the replay of game {} from its spool, cut where the last run stopped", id);
            }
            Files.deleteIfExists(spool);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("the spool of game {} could not be salvaged; dropped", id, e);
            try {
                Files.deleteIfExists(spool);
            } catch (IOException ignored) {
                // it stays; the next boot says so again
            }
        }
    }

    private static void file(Path at, Path file) {
        try {
            UserData.View data = ShardFile.userData(Files.readAllBytes(file));
            if (data.get(Replay.OPS) == null) return; // a plain shard save someone dropped here: not ours to move
            Path to = at.resolve(folderOf(data.get(MODE))).resolve(file.getFileName().toString());
            Files.createDirectories(to.getParent());
            Files.move(file, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("{} stays where it is", file, e);
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

    /** What the running recording has bookmarked so far; empty where nothing is recording it. */
    public static @NotNull java.util.List<io.github.term4.archipelago.replay.Replay.Bookmark> marksOf(@NotNull Game game) {
        Recording recording = live.get(game.id());
        return recording == null ? java.util.List.of() : recording.bookmarks();
    }

    private static int paced(int ticks, double rate) {
        return (int) Math.max(ticks > 0 ? 1 : 0, Math.round(ticks / rate));
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

    private static void ended(Game game, Outcome outcome) {
        Recording recording = live.remove(game.id());
        Path at = dir;
        if (recording == null || at == null) return;
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
        Path file = at.resolve(folderOf(mode != null ? mode.name() : null)).resolve(game.id() + ".shard");
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
        landing.add(landed);
        landed.whenComplete((v, e) -> landing.remove(landed));
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
        if (id.equals(watching(viewer))) { // already in it: opening a second theater of the same game helps nobody
            Msg.say(viewer, "You're already watching that replay.");
            return;
        }
        if (!Parties.requireLeader(viewer)) return;
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
        // a theater of their own, over the map's one instance: another party's playback is another shard, and
        // the two never see each other's blocks, ghosts or remote
        Instance world = arena.instantiate();
        Shard stage = new Shard(world, ExecutionDomain.create()); // its own clock, as a game has
        manager.register(stage, "replay-" + id.substring(0, 8) + "-" + staged.incrementAndGet());
        Playback playback;
        try {
            playback = Replay.play(stage, bytes);
        } catch (IllegalArgumentException e) {
            manager.destroy(stage);
            Msg.fail(viewer, "That replay could not be opened: " + e.getMessage());
            return;
        }
        playback.holdAtEnd(true); // the last frame stays up until the watchers leave; the last one out closes the theater
        String lookName = data.get(LOOK);
        String code = data.get(CODE);
        List<String> lineup = data.get(LINEUP);
        List<String> health = data.get(HEALTH);
        Dressing dressing = new Dressing(stage, playback, id, code != null ? code : "", playedOn(data, file), mode,
                lookName != null ? lookName : "tennacle", lineup != null ? lineup : List.of(),
                health != null ? health : List.of());
        Theater theater = new Theater(id, stage, playback, arena, dressing, ConcurrentHashMap.newKeySet(),
                floor(arena));
        theaters.add(theater);
        for (Player member : Parties.of(viewer)) { // the leader first: the remote is theirs
            if (member != viewer && Game.of(member) != null) {
                Msg.say(viewer, Msg.v(member.getUsername()), " is in a game and stays behind.");
                continue;
            }
            admit(member, theater, member == viewer);
            Msg.ok(member, "Watching the replay of game ", Msg.v(id), ". ", Msg.run("/l"), " to leave.");
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
        String stamped = data.get(PLAYED);
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
