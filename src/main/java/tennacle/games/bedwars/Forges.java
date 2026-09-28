package tennacle.games.bedwars;

import tennacle.games.Generators;
import tennacle.games.Match;
import tennacle.games.Timeline;

import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each side's forge: iron spawners at its iron points, as many as the map's marker says for this mode, a
 * gold spawner at its forge point, and from Emerald Forge an emerald one there. A level written re-times them
 * and restarts them; the start boost runs the first 30 s at Golden Forge's pace with the first fill's cap. An
 * eliminated side's forge keeps going.
 */
final class Forges implements GameModule {

    /** The side's forge level: 0 none, then Iron, Golden, Emerald and Molten Forge. */
    static final StateKey<Integer> LEVEL = StateKey.of("ledger.bedwars.forge", Codec.INT, 0, true);

    // captured Solo/Doubles periods and caps by level; the four-team modes set their own baselines
    static final StateKey<List<Long>> IRON_PERIOD = StateKey.of("forge.iron.period", Codec.LONG.list(), List.of(51L, 36L, 21L, 21L, 21L));
    static final StateKey<List<Long>> GOLD_PERIOD = StateKey.of("forge.gold.period", Codec.LONG.list(), List.of(276L, 201L, 126L, 126L, 126L));
    /** From Emerald Forge; {@code 0} = no emeralds at that level. */
    static final StateKey<List<Long>> EMERALD_PERIOD = StateKey.of("forge.emerald.period", Codec.LONG.list(), List.of(0L, 0L, 0L, 1300L, 701L));
    static final StateKey<List<Integer>> IRON_CAP = StateKey.of("forge.iron.cap", Codec.INT.list(), List.of(48, 56, 64, 72, 80));
    static final StateKey<List<Integer>> GOLD_CAP = StateKey.of("forge.gold.cap", Codec.INT.list(), List.of(16, 18, 20, 22, 24));
    static final StateKey<List<Integer>> EMERALD_CAP = StateKey.of("forge.emerald.cap", Codec.INT.list(), List.of(0, 0, 0, 2, 3));
    /** Ticks of live play the start boost runs. */
    static final StateKey<Long> BOOST = StateKey.of("forge.boost", Codec.LONG, 600L);
    static final StateKey<Long> BOOST_IRON = StateKey.of("forge.boost.iron", Codec.LONG, 21L);
    static final StateKey<Long> BOOST_GOLD = StateKey.of("forge.boost.gold", Codec.LONG, 126L);
    /** The first fill's iron cap. */
    static final StateKey<Integer> BOOST_CAP = StateKey.of("forge.boost.cap", Codec.INT, 64);

    private static final String BOOST_ALARM = "bedwars:boost";
    private static final long FIRST = 10; // captured: the first iron and gold 4 to 12 ticks after the start
    private static final ItemStack IRON = ItemStack.of(Material.IRON_INGOT);
    private static final ItemStack GOLD = ItemStack.of(Material.GOLD_INGOT);
    private static final ItemStack EMERALD = ItemStack.of(Material.EMERALD);

    private record Forge(List<Generators.Generator> iron, Generators.Pool ironPool, Generators.Generator gold,
                         Generators.Pool goldPool, @Nullable Generators.Generator emerald, @Nullable Generators.Pool emeraldPool) {}

    private final String mode;
    private final Map<String, Forge> forges = new HashMap<>();
    private Game game;

    /** {@code mode} keys the iron marker's spawner count ({@code solo}, {@code doubles}, {@code 3v3v3v3}, {@code 4v4v4v4}). */
    Forges(@NotNull String mode) {
        this.mode = mode;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(IRON_PERIOD, GOLD_PERIOD, EMERALD_PERIOD, IRON_CAP, GOLD_CAP, EMERALD_CAP, BOOST, BOOST_IRON, BOOST_GOLD, BOOST_CAP);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Generators generators = game.module(Generators.class);
        Phases phases = game.module(Phases.class);
        if (generators == null || phases == null) throw new IllegalStateException("Forges needs Generators and Phases installed before it");
        for (Team team : game.roster().teams()) {
            PointMarker forge = game.arena().marker(BedWars.FORGE, team.id());
            if (forge == null) continue;
            Generators.Pool ironPool = generators.pool(0);
            List<Generators.Generator> iron = new ArrayList<>();
            for (PointMarker point : game.arena().markers(BedWars.FORGE_IRON)) {
                if (!point.group().equals(team.id()) || point.payload() == null) continue;
                for (int i = point.payload().getInt(mode); i > 0; i--) iron.add(generators.add(point.point(), IRON, 1, ironPool));
            }
            Generators.Pool goldPool = generators.pool(0);
            Generators.Generator gold = generators.add(forge.point(), GOLD, 1, goldPool);
            Generators.Pool emeraldPool = generators.pool(0);
            Generators.Generator emerald = generators.add(forge.point(), EMERALD, 1, emeraldPool);
            forges.put(team.id(), new Forge(iron, ironPool, gold, goldPool, emerald, emeraldPool));
        }
        for (StateKey<?> key : rules()) game.rules().watch(key, (scope, value) -> forges.keySet().forEach(this::retime));
        // captured: a level bought spawns at once, then runs at the new pace
        game.rules().watch(LEVEL, (scope, value) -> {
            if (!(scope instanceof Scope.Team team) || !forges.containsKey(team.id())) return;
            retime(team.id());
            restart(forges.get(team.id()));
        });
        game.state().alarms().register(BOOST_ALARM, (tick, payload) -> {
            // captured: the boost's end spawns on the spot, then the level's own pace
            for (String team : forges.keySet()) {
                retime(team);
                restart(forges.get(team));
            }
        });
        if (Phases.is(game, Match.LIVE)) start();
        else phases.onEnter(Match.LIVE, g -> start());
    }

    /** The side's forge level. */
    static int level(@NotNull Game game, @NotNull String team) {
        return game.state().get(Scope.team(team), LEVEL);
    }


    private void start() {
        for (String team : forges.keySet()) {
            retime(team);
            Forge forge = forges.get(team);
            for (Generators.Generator g : forge.iron()) g.start(FIRST);
            forge.gold().start(FIRST);
        }
        game.state().alarms().arm(BOOST_ALARM, game.tick() + game.rules().resolve(Scope.GAME, BOOST));
    }

    private boolean boosted() {
        return Timeline.elapsed(game) < game.rules().resolve(Scope.GAME, BOOST);
    }

    // the level's periods and caps, or the boost's while it runs; the next drop keeps its clock
    private void retime(String team) {
        Forge forge = forges.get(team);
        int level = level(game, team);
        boolean boosted = boosted();
        long iron = boosted ? game.rules().resolve(Scope.GAME, BOOST_IRON) : period(IRON_PERIOD, level);
        long gold = boosted ? game.rules().resolve(Scope.GAME, BOOST_GOLD) : period(GOLD_PERIOD, level);
        for (Generators.Generator g : forge.iron()) g.period(iron);
        forge.gold().period(gold);
        forge.ironPool().cap(boosted ? game.rules().resolve(Scope.GAME, BOOST_CAP) : cap(IRON_CAP, level));
        forge.goldPool().cap(cap(GOLD_CAP, level));
        if (forge.emerald() != null) {
            long emerald = period(EMERALD_PERIOD, level);
            forge.emerald().period(emerald);
            forge.emeraldPool().cap(cap(EMERALD_CAP, level));
            if (emerald <= 0) forge.emerald().stop();
        }
    }

    private void restart(Forge forge) {
        for (Generators.Generator g : forge.iron()) g.restart();
        forge.gold().restart();
        if (forge.emerald() != null && period(EMERALD_PERIOD, level(game, teamOf(forge))) > 0) forge.emerald().restart();
    }

    private String teamOf(Forge forge) {
        for (Map.Entry<String, Forge> entry : forges.entrySet()) if (entry.getValue() == forge) return entry.getKey();
        return "";
    }

    private long period(StateKey<List<Long>> key, int level) {
        List<Long> values = game.rules().resolve(Scope.GAME, key);
        return values.isEmpty() ? 0 : values.get(Math.min(level, values.size() - 1));
    }

    private int cap(StateKey<List<Integer>> key, int level) {
        List<Integer> values = game.rules().resolve(Scope.GAME, key);
        return values.isEmpty() ? 0 : values.get(Math.min(level, values.size() - 1));
    }
}
