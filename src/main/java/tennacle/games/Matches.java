package tennacle.games;

import tennacle.games.matchmaking.Queues;
import tennacle.games.watch.Spectators;
import tennacle.games.watch.Theaters;
import tennacle.games.watch.Replays;
import tennacle.looks.Look;
import tennacle.looks.GameSounds;
import tennacle.storage.MatchLog;
import io.github.term4.polyp.platform.player.Wardrobe;
import tennacle.Mechanics;
import tennacle.Msg;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.barnacle.Arena;
import io.github.term4.canvas.tab.TabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Grotto;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Choice;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.explosion.BlockBreaking;
import io.github.term4.polyp.mechanics.explosion.ExplosionConfig;
import io.github.term4.polyp.MechanicsProfile;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * The one launch for any seating: a mode, a platform (its ruleset and look), the sides. The look name is
 * stamped and the ruleset folded into the game's world profile BEFORE modules install; the type's body policy
 * takes each player from there. A live {@link #modify} layers a further ruleset over the running game. Modes,
 * looks and platforms register at boot; the lobby route is the app's only exit.
 */
public final class Matches {

    private static final Logger LOGGER = LoggerFactory.getLogger(Matches.class);

    /** The active look's name, stamped at launch. */
    public static final StateKey<String> LOOK = StateKey.of("ledger.match.look", Codec.STRING, "");
    /** The platform's name, stamped at launch - what a re-fold resolves the ruleset from. */
    public static final StateKey<String> PLATFORM = StateKey.of("ledger.match.platform", Codec.STRING, "");
    /** The ruleset the platform picked, stamped by the fold: the mode's base when the platform names none of its variants. */
    public static final StateKey<String> RULES = StateKey.of("ledger.match.rules", Codec.STRING, "");

    private static final Map<String, Mode> modes = new LinkedHashMap<>();
    private static final Map<String, Look> looks = new LinkedHashMap<>(Map.of(
            "tennacle", Look.TENNACLE, "minemen", Look.MINEMEN, "scrims", Look.SCRIMS, "hypixel", Look.HYPIXEL));
    private static final Map<String, Platform> platforms = new LinkedHashMap<>(Map.of(
            "minemen", Platform.of("minemen"), "scrims", Platform.of("scrims"), "hypixel", Platform.of("hypixel")));
    // live modifiers by game id - pruned as games end
    private static final Map<String, Ruleset> modifiers = new ConcurrentHashMap<>();
    private static Grotto grotto;
    private static InstanceContainer lobby;
    private static Consumer<Player> lobbyRoute = player -> {};

    private Matches() {}

    public static void install(@NotNull InstanceContainer lobby, @NotNull Pos lobbySpawn, @NotNull ShardManager shards) {
        Matches.lobby = lobby;
        grotto = Grotto.install(shards); // shared manager: /shard tree shows the game trees
        lobbyRoute = player -> {
            Spectators.exit(player); // a game's end routes its spectators here; restore the body and the HUD
            if (player.getInstance() == lobby) { // Minestom rejects a same-instance setInstance: no spawn event either
                player.teleport(lobbySpawn, Vec.ZERO);
                Wardrobe.arrive(player, lobby);
            } else {
                player.setVelocity(Vec.ZERO); // setInstance would carry the fall they left with
                player.setInstance(lobby, lobbySpawn); // the lobby dresses the arrival
            }
            TabList.refresh(player); // the chrome is the lobby's again
        };
        grotto.exit(lobbyRoute);
        grotto.onGameEnd((game, outcome) -> modifiers.remove(game.id())); // an override dies with its game
    }

    public static @NotNull Grotto grotto() {
        return grotto;
    }

    /** The lobby world, once installed. */
    public static @Nullable InstanceContainer lobby() {
        return lobby;
    }

    /**
     * A void plane crossed, for the modes that watch one: a seat is the mode's own to answer for, and a watcher
     * who flew off the map comes back to the play instead of falling forever.
     */
    public static void fell(@NotNull Player p, @NotNull java.util.function.BiConsumer<Game, Player> seated) {
        Game game = Game.of(p);
        if (game != null) {
            seated.accept(game, p);
            return;
        }
        Game watched = grotto.spectating(p);
        if (watched != null) Spectators.recall(watched, p);
    }

    /** The exit route - for commands that send players home. */
    public static void toLobby(@NotNull Player player) {
        lobbyRoute.accept(player);
    }

    public static void register(@NotNull Mode mode) {
        modes.put(mode.name(), mode);
    }

    public static void register(@NotNull Platform platform) {
        platforms.put(platform.name(), platform);
    }

    public static @Nullable Mode mode(@NotNull String name) {
        return modes.get(name);
    }

    public static @NotNull Set<String> modes() {
        return modes.keySet();
    }

    public static @Nullable Platform platform(@NotNull String name) {
        return platforms.get(name);
    }

    public static @NotNull Set<String> platforms() {
        return platforms.keySet();
    }

    /** The registered mode behind a running game (by its GameType id), or null for a foreign game. */
    public static @Nullable Mode modeOf(@NotNull Game game) {
        return modes.get(game.type().id());
    }

    public static @NotNull Look lookOf(@NotNull Game game) {
        Look look = looks.get(game.state().get(Scope.GAME, LOOK));
        return look != null ? look : Look.TENNACLE;
    }

    /** A registered look by name; the house look for one nobody registered. */
    public static @NotNull Look look(@NotNull String name) {
        return looks.getOrDefault(name, Look.TENNACLE);
    }

    /** The preset the match was asked for, else {@code default}. */
    public static @NotNull String platformNameOf(@NotNull Game game) {
        String name = game.state().get(Scope.GAME, PLATFORM);
        return name.isEmpty() ? Platform.DEFAULT.name() : name;
    }

    /** The ruleset the fold picked for the match. */
    public static @NotNull String rulesNameOf(@NotNull Game game) {
        return game.state().get(Scope.GAME, RULES);
    }

    /** The Polyp preset the match's mechanics start from, else the mode's own profile. */
    public static @NotNull String mechanicsNameOf(@NotNull Game game) {
        String name = game.rules().resolve(Scope.GAME, Presets.KEY);
        return name.isEmpty() ? "the mode's own" : name;
    }

    /** {@link #lookOf} as its registry name - what a per-look module pick switches on. */
    public static @NotNull String lookNameOf(@NotNull Game game) {
        String name = game.state().get(Scope.GAME, LOOK);
        return looks.containsKey(name) ? name : "tennacle";
    }

    /** The per-look module choice - picked at install off the stamped look, every variant declared. */
    public static @NotNull GameModule byLook(@NotNull Supplier<GameModule> hypixel, @NotNull Supplier<GameModule> minemen,
                                             @NotNull Supplier<GameModule> scrims, @NotNull Supplier<GameModule> tennacle) {
        return Choice.of(g -> switch (lookNameOf(g)) {
            case "hypixel" -> hypixel;
            case "scrims" -> scrims;
            case "minemen" -> minemen;
            default -> tennacle;
        }, hypixel, minemen, scrims, tennacle);
    }

    /** End screens carrying the game's look - what every mode's {@code Ends} starts from. */
    public static @NotNull Summary summary() {
        return Summary.of().theme(g -> lookOf(g).theme()).clock(Match::playTime).kept(Replays::said);
    }

    public static @NotNull Results results() {
        return Results.of().theme(g -> lookOf(g).theme())
                .tally("Kills", (game, p) -> {
                    StateKey<Integer> key = seatStat(game, "Kills");
                    return key == null ? 0 : game.state().get(Scope.slot(p.slot().index()), key);
                })
                .rematch("/rematch")
                .kept(Replays::said);
    }

    // the mode's own kill counter, by the label it shows everywhere else; a mode that counts none has no tally
    private static @Nullable StateKey<Integer> seatStat(Game game, String label) {
        Mode mode = modeOf(game);
        if (mode == null) return null;
        for (Mode.SeatStat stat : mode.tabStats()) {
            if (stat.label().equals(label)) return stat.key();
        }
        return null;
    }

    /** Plays the look's sound for {@code cue} to everyone in the game; a look without one stays silent. */
    public static void cue(@NotNull Game game, @NotNull String cue) {
        Match.each(game, p -> GameSounds.play(game, p, cue));
    }

    /**
     * Launches {@code mode} under {@code platform} on {@code arena} with nobody seated: the look stamped, the
     * platform's ruleset folded into the world profile and seeded, all before the modules install. What
     * {@link #start} does before seating; the round harnesses seat their own players.
     */
    public static @NotNull Game launch(@NotNull Mode mode, @NotNull Platform platform, @NotNull Arena arena) {
        return launch(mode, platform, arena, Options.NONE);
    }

    /** {@link #launch} with a request's {@link Options}: its rules as the first live modifier. */
    public static @NotNull Game launch(@NotNull Mode mode, @NotNull Platform platform, @NotNull Arena arena,
                                       @NotNull Options options) {
        return grotto.launch(mode.type(), arena, null, g -> {
            // a mode that names its own look keeps it under every preset: without captures there is nothing
            // for a platform's look to be faithful TO, and only its mechanics are worth copying
            g.state().set(Scope.GAME, LOOK, mode.look() != null ? mode.look() : platform.look());
            g.state().set(Scope.GAME, PLATFORM, platform.name());
            if (!options.rules().isEmpty()) modifiers.put(g.id(), options.rules());
            fold(g);
            Replays.record(g);
            for (Consumer<Game> hook : options.atLaunch()) hook.accept(g);
        });
    }

    /** Folds the game's ruleset (and any live modifiers) into its world profile and seeds it. */
    public static void fold(@NotNull Game game) {
        Mode mode = modeOf(game);
        if (mode == null) throw new IllegalStateException("not a registered mode: " + game.type().id());
        Platform platform = platformFor(game.state().get(Scope.GAME, PLATFORM));
        Ruleset rules = mode.ruleset(platform.ruleset());
        game.state().set(Scope.GAME, RULES, rules.name());
        if (platform.mechanics() != null) rules = Ruleset.named(platform.name()).rule(Presets.KEY, platform.mechanics()).over(rules);
        Ruleset live = modifiers.get(game.id());
        if (live != null) rules = live.over(rules);
        rules.seed(game); // first: the preset the profile starts from is itself a rule - read after seeding, or a
                          // change to it lands one fold late (the value you set applies on the NEXT change)
        MechanicsProfile.Builder profile = Presets.builderFor(game, mode); // mechanics:preset, else the mode's
        // a match's terrain is the arena's. Presets fitted for a block-breaking game (hypixel, mmc18) carry
        // blast rays, and inside a match those grief the map - a mode that wants breakable ground registers a
        // named BlockBreaking (as bridge does for heal-apple) and names it on explosion/blockBreaking below
        profile.mutate(MechanicsKeys.EXPLOSION, ex -> ex == null ? null
                : ExplosionConfig.builder(ex).blockBreaking((BlockBreaking) null).build());
        rules.apply(profile, Mechanics.polyp.profiles().global(), game);
        Mechanics.polyp.profiles().setWorld(game.shard(), profile.build());
    }

    /**
     * Layers {@code ruleset} over the running game and re-folds: the world profile swaps (polyp pushes the
     * per-player config on the change) and the rules re-seed. Checked first - a bad entry changes nothing.
     */
    public static void modify(@NotNull Game game, @NotNull Ruleset ruleset) {
        if (game.status() != Game.Status.OPEN) throw new IllegalStateException("the game is over");
        ruleset.check(Mechanics.polyp.profiles().global(), game.type());
        Ruleset previous = modifiers.get(game.id());
        modifiers.merge(game.id(), ruleset, (older, newer) -> newer.over(older));
        try {
            fold(game);
        } catch (RuntimeException e) {
            // the fold rejects what check cannot see (a preset name, a path the live profile refuses). Without
            // this the bad entry stays in the modifier and every LATER change re-folds it and fails too
            if (previous != null) modifiers.put(game.id(), previous);
            else modifiers.remove(game.id());
            try {
                fold(game);
            } catch (RuntimeException restore) {
                LOGGER.error("the rollback re-fold failed for game {}", game.id(), restore);
            }
            throw e;
        }
    }

    /**
     * Drops the live entry for {@code id} at {@code scope} and re-folds; the ledger's cell there goes with it, so
     * a seat freed of its override reads its team's value again. {@code false}: nothing was set there.
     */
    /** Whether a live entry for {@code id} stands at {@code scope} - what {@link #unset} would drop. */
    public static boolean overrides(@NotNull Game game, @NotNull Scope scope, @NotNull String id) {
        Ruleset live = modifiers.get(game.id());
        return live != null && live.names(scope, id);
    }

    public static boolean unset(@NotNull Game game, @NotNull Scope scope, @NotNull String id) {
        if (game.status() != Game.Status.OPEN) throw new IllegalStateException("the game is over");
        Ruleset live = modifiers.get(game.id());
        if (live == null || !live.names(scope, id)) return false;
        Ruleset less = live.without(scope, id);
        if (less.isEmpty()) modifiers.remove(game.id());
        else modifiers.put(game.id(), less);
        StateKey<?> key = game.type().supportedRules().get(id);
        if (key != null) game.state().unset(scope, key);
        fold(game);
        return true;
    }

    /**
     * {@link #launch} on the mode's own map, then every side seated on its own team - the type's body policy
     * stashes, freshens and kits them. A side the map cannot seat aborts the launch and throws.
     */
    public static @NotNull Game start(@NotNull Mode mode, @NotNull Platform platform,
                                      @NotNull List<? extends Collection<Player>> sides) {
        return start(mode, platform, sides, Options.NONE);
    }

    /** {@link #start} on the map the options name, else the pool's pick. */
    public static @NotNull Game start(@NotNull Mode mode, @NotNull Platform platform,
                                      @NotNull List<? extends Collection<Player>> sides, @NotNull Options options) {
        return seat(mode, platform, sides, options, pick(mode, options));
    }

    /**
     * {@link #start}, with a cold map built off the tick thread first: the pool's pick is instantiated on a
     * virtual thread when nothing has loaded it yet, and the seating lands on the next tick after. The future
     * fails as {@link #start} throws. A launch is the only thing that loads a map, so a boot loads none and an
     * idle one leaves when the engine's sweep says so.
     */
    public static @NotNull CompletableFuture<Game> begin(@NotNull Mode mode, @NotNull Platform platform,
                                                         @NotNull List<? extends Collection<Player>> sides,
                                                         @NotNull Options options) {
        Arena arena;
        try {
            arena = pick(mode, options);
        } catch (RuntimeException e) { // the map may not be authored yet
            return CompletableFuture.failedFuture(e);
        }
        if (arena.instantiated() != null) {
            try {
                return CompletableFuture.completedFuture(seat(mode, platform, sides, options, arena));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<Game> out = new CompletableFuture<>();
        for (Collection<Player> side : sides) {
            for (Player p : side) Msg.say(p, "Preparing ", Msg.v(arena.meta().name()), "...");
        }
        Thread.ofVirtual().name("tennacle-map-" + mode.name()).start(() -> {
            try {
                arena.instantiate();
            } catch (RuntimeException e) {
                out.completeExceptionally(e);
                return;
            }
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                try {
                    out.complete(seat(mode, platform, sides, options, arena));
                } catch (RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        });
        return out;
    }

    // the map the options name, else the pool's pick - chosen once, so a warm-up and its seating agree
    private static Arena pick(Mode mode, Options options) {
        Arena arena = options.map() != null ? mode.arena().named(options.map()) : mode.arena().get();
        if (arena == null) throw new IllegalStateException("no map named " + options.map() + " for " + mode.name());
        return arena;
    }

    private static Game seat(Mode mode, Platform platform, List<? extends Collection<Player>> sides, Options options,
                             Arena arena) {
        for (Collection<Player> side : sides) { // before the launch, so a refusal orphans nothing
            for (Player player : side) {
                // engaged, not seated: the shard tag lands at the end of the transfer, and a player mid-flight
                // into another game would otherwise be seated twice
                if (grotto.engaged(player)) throw new IllegalStateException(player.getUsername() + " is in a game");
            }
        }
        // a watcher seated out of a theater leaves it first: its dressing would ride into the game
        for (Collection<Player> side : sides) for (Player player : side) Theaters.leave(player);
        Game game = launch(mode, platform, arena, options);
        List<Team> teams = game.roster().teams();
        if (sides.size() > teams.size()) {
            game.end(Outcome.aborted());
            throw new IllegalStateException(mode.name() + " seats " + teams.size() + " sides, not " + sides.size());
        }
        try {
            for (int i = 0; i < sides.size(); i++) {
                for (Player player : sides.get(i)) {
                    // a seat held elsewhere keeps holding: this game over, /rejoin takes them back to it
                    Queues.dequeue(player); // a seat here ends every wait, whichever door they came through
                    Spectators.exit(player); // a watcher who queued: their body and HUD come back before the seat
                    KitLayouts.cancel(player); // an open kit edit would write its stash over the match inventory
                    if (!game.join(player, teams.get(i).id())) {
                        throw new IllegalStateException(mode.name() + " has no seat left on " + teams.get(i).id()
                                + " for " + player.getUsername());
                    }
                }
            }
        } catch (RuntimeException e) {
            // anything thrown here (a hook, a listener, a transfer) leaves a registered game nobody sits in:
            // the abandonment watchdog only reaps games that seated someone
            game.end(Outcome.aborted());
            throw e;
        }
        MatchLog.started(game, mode.name(), platform.name(), sides);
        return game;
    }

    /** How many a side of {@code mode} seats on its map - what a queue pairs by. */
    public static int seatsPerSide(@NotNull Mode mode) {
        Map<String, Integer> perTeam = new HashMap<>();
        for (Slot slot : mode.type().slots().resolve(mode.arena().get())) perTeam.merge(slot.team(), 1, Integer::sum);
        return perTeam.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    /** The platform a request named; {@link Platform#DEFAULT} when it named none. */
    public static @NotNull Platform platformFor(@Nullable String name) {
        Platform named = name != null ? platforms.get(name) : null;
        return named != null ? named : Platform.DEFAULT;
    }
}
