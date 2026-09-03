package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.barnacle.Arena;
import io.github.term4.canvas.tab.TabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Grotto;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.Choice;
import io.github.term4.grotto.modules.Results;
import io.github.term4.grotto.modules.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one launch for any seating: a mode, a platform (its ruleset and look), the sides. The look name is
 * stamped and the ruleset folded into the game's world profile BEFORE modules install; the type's body policy
 * takes each player from there. A live {@link #modify} layers a further ruleset over the running game. Modes,
 * looks and platforms register at boot; the lobby route is the app's only exit.
 */
public final class Matches {

    /** The active look's name, stamped at launch. */
    public static final StateKey<String> LOOK = StateKey.of("match:look", Codec.STRING, "");
    /** The platform's name, stamped at launch - what a re-fold resolves the ruleset from. */
    public static final StateKey<String> PLATFORM = StateKey.of("match:platform", Codec.STRING, "");

    private static final Map<String, Mode> modes = new LinkedHashMap<>();
    private static final Map<String, Look> looks = new LinkedHashMap<>(Map.of(
            "minemen", Look.MINEMEN, "scrims", Look.SCRIMS, "hypixel", Look.HYPIXEL));
    private static final Map<String, Platform> platforms = new LinkedHashMap<>(Map.of(
            "minemen", Platform.of("minemen"), "scrims", Platform.of("scrims"), "hypixel", Platform.of("hypixel")));
    // live modifiers by game id - pruned as games end
    private static final Map<String, Ruleset> modifiers = new ConcurrentHashMap<>();
    private static Grotto grotto;
    private static Consumer<Player> lobbyRoute = player -> {};

    private Matches() {}

    public static void install(@NotNull InstanceContainer lobby, @NotNull Pos lobbySpawn, @NotNull ShardManager shards) {
        grotto = Grotto.install(shards); // shared manager: /shard tree shows the game trees
        lobbyRoute = player -> {
            Spectators.exit(player); // a game's end routes its spectators here; restore the body and the HUD
            if (player.getInstance() == lobby) player.teleport(lobbySpawn); // Minestom rejects a same-instance setInstance
            else player.setInstance(lobby, lobbySpawn); // the body is the type's; the route is ours
            TabList.refresh(player); // the chrome is the lobby's again
        };
        grotto.exit(lobbyRoute);
    }

    public static @NotNull Grotto grotto() {
        return grotto;
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
        return look != null ? look : Look.MINEMEN;
    }

    /** {@link #lookOf} as its registry name - what a per-look module pick switches on. */
    public static @NotNull String lookNameOf(@NotNull Game game) {
        String name = game.state().get(Scope.GAME, LOOK);
        return looks.containsKey(name) ? name : "minemen";
    }

    /** The per-look module choice - picked at install off the stamped look, every variant declared. */
    public static @NotNull GameModule byLook(@NotNull Supplier<GameModule> hypixel, @NotNull Supplier<GameModule> minemen,
                                             @NotNull Supplier<GameModule> scrims) {
        return Choice.of(g -> switch (lookNameOf(g)) {
            case "hypixel" -> hypixel;
            case "scrims" -> scrims;
            default -> minemen;
        }, hypixel, minemen, scrims);
    }

    /** End screens carrying the game's look - what every mode's {@code Ends} starts from. */
    public static @NotNull Summary summary() {
        return Summary.of().theme(g -> lookOf(g).theme());
    }

    public static @NotNull Results results() {
        return Results.of().theme(g -> lookOf(g).theme());
    }

    /** Plays the look's sound for {@code cue} to everyone in the game; a look without one stays silent. */
    public static void cue(@NotNull Game game, @NotNull String cue) {
        var sound = lookOf(game).sounds().get(cue);
        if (sound != null) Match.each(game, p -> p.playSound(sound));
    }

    /**
     * Launches {@code mode} under {@code platform} on {@code arena} with nobody seated: the look stamped, the
     * platform's ruleset folded into the world profile and seeded, all before the modules install. What
     * {@link #start} does before seating; the round harnesses seat their own players.
     */
    public static @NotNull Game launch(@NotNull Mode mode, @NotNull Platform platform, @NotNull Arena arena) {
        return grotto.launch(mode.type(), arena, null, g -> {
            g.state().set(Scope.GAME, LOOK, platform.look());
            g.state().set(Scope.GAME, PLATFORM, platform.name());
            fold(g);
        });
    }

    /** Folds the game's ruleset (and any live modifiers) into its world profile and seeds it. */
    public static void fold(@NotNull Game game) {
        Mode mode = modeOf(game);
        if (mode == null) throw new IllegalStateException("not a registered mode: " + game.type().id());
        Platform platform = platformFor(mode, game.state().get(Scope.GAME, PLATFORM));
        Ruleset rules = mode.ruleset(platform.ruleset());
        Ruleset live = modifiers.get(game.id());
        if (live != null) rules = live.over(rules);
        MechanicsProfile.Builder profile = Presets.builderFor(game, mode); // mechanics:preset, else the mode's
        rules.apply(profile, Mechanics.polyp.profiles().global(), game);
        Mechanics.polyp.profiles().setWorld(game.shard(), profile.build());
        rules.seed(game);
    }

    /**
     * Layers {@code ruleset} over the running game and re-folds: the world profile swaps (polyp pushes the
     * per-player config on the change) and the rules re-seed. Checked first - a bad entry changes nothing.
     */
    public static void modify(@NotNull Game game, @NotNull Ruleset ruleset) {
        if (game.status() != Game.Status.OPEN) throw new IllegalStateException("the game is over");
        ruleset.check(Mechanics.polyp.profiles().global(), game.type());
        modifiers.keySet().removeIf(id -> grotto.games().stream().noneMatch(g -> g.id().equals(id) && g.status() == Game.Status.OPEN));
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
                System.err.println("[rules] rollback re-fold failed for " + game.id() + ": " + restore);
            }
            throw e;
        }
    }

    /**
     * {@link #launch} on the mode's own map, then every side seated on its own team - the type's body policy
     * stashes, freshens and kits them. A side the map cannot seat aborts the launch and throws.
     */
    public static @NotNull Game start(@NotNull Mode mode, @NotNull Platform platform,
                                      @NotNull List<? extends Collection<Player>> sides) {
        for (Collection<Player> side : sides) { // before the launch, so a refusal orphans nothing
            for (Player player : side) {
                Game now = Game.of(player);
                if (now != null && now.status() == Game.Status.OPEN) {
                    throw new IllegalStateException(player.getUsername() + " is in a game");
                }
            }
        }
        Game game = launch(mode, platform, mode.arena().get());
        List<Team> teams = game.roster().teams();
        if (sides.size() > teams.size()) {
            game.end(Outcome.aborted());
            throw new IllegalStateException(mode.name() + " seats " + teams.size() + " sides, not " + sides.size());
        }
        for (int i = 0; i < sides.size(); i++) {
            for (Player player : sides.get(i)) {
                Queues.dequeue(player); // a seat here ends every wait, whichever door they came through
                Spectators.exit(player); // a watcher who queued: their body and HUD come back before the seat
                if (!game.join(player, teams.get(i).id())) {
                    game.end(Outcome.aborted());
                    throw new IllegalStateException(mode.name() + " has no seat left on " + teams.get(i).id()
                            + " for " + player.getUsername());
                }
            }
        }
        return game;
    }

    /** How many a side of {@code mode} seats on its map - what a queue pairs by. */
    public static int seatsPerSide(@NotNull Mode mode) {
        Map<String, Integer> perTeam = new HashMap<>();
        for (Slot slot : mode.type().slots().resolve(mode.arena().get())) perTeam.merge(slot.team(), 1, Integer::sum);
        return perTeam.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    /** The platform a request named, the mode's default when it named none. */
    public static @NotNull Platform platformFor(@NotNull Mode mode, @Nullable String name) {
        Platform named = name != null ? platforms.get(name) : null;
        if (named != null) return named;
        Platform fallback = platforms.get(mode.defaultPlatform());
        return fallback != null ? fallback : Platform.of("minemen");
    }

    static @NotNull List<String> lookNames() {
        return List.copyOf(looks.keySet());
    }
}
