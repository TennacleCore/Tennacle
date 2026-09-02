package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.barnacle.Arena;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Grotto;
import io.github.term4.grotto.modules.Choice;
import io.github.term4.grotto.modules.Results;
import io.github.term4.grotto.modules.Summary;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one launch for any seating: a mode, a ruleset, a look, the players. The look name is stamped and the
 * ruleset folded into the game's world profile BEFORE modules install; the type's body policy takes each
 * player from there. Modes, looks and platforms register at boot; the lobby route is the app's only exit.
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
    private static Grotto grotto;
    private static Consumer<Player> lobbyRoute = player -> {};

    private Matches() {}

    public static void install(@NotNull InstanceContainer lobby, @NotNull Pos lobbySpawn, @NotNull ShardManager shards) {
        grotto = Grotto.install(shards); // shared manager: /shard tree shows the game trees
        lobbyRoute = player -> player.setInstance(lobby, lobbySpawn); // the body is the type's; the route is ours
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

    /** Folds the game's ruleset into its world profile and seeds it - the launch does this; a test re-folds. */
    public static void fold(@NotNull Game game) {
        Mode mode = modeOf(game);
        if (mode == null) throw new IllegalStateException("not a registered mode: " + game.type().id());
        Platform platform = platformFor(mode, game.state().get(Scope.GAME, PLATFORM));
        Ruleset rules = mode.ruleset(platform.ruleset());
        MechanicsProfile.Builder profile = mode.profile().get();
        rules.apply(profile, Mechanics.polyp.profiles().global(), game);
        Mechanics.polyp.profiles().setWorld(game.shard(), profile.build());
        rules.seed(game);
    }

    /** {@link #launch}, then every player seated - the type's body policy stashes, freshens and kits them. */
    public static @NotNull Game start(@NotNull Mode mode, @NotNull Platform platform, @NotNull Collection<Player> seating) {
        Game game = launch(mode, platform, mode.arena().get());
        for (Player player : seating) game.join(player);
        return game;
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
