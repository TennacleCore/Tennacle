package tennacle.games.bedwars;

import tennacle.games.Generators;
import tennacle.games.Match;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The middle's diamond and emerald generators: each its own cap, its tier's period, the tier the timeline
 * raises. A tier-up restarts them, as captured: one drop on the spot with the line.
 */
final class Mid implements GameModule {

    /** A kind of generator: its markers, item, rules and the tier it stands at. */
    enum Kind {
        DIAMOND(BedWars.DIAMOND, Material.DIAMOND, "generators.diamond", List.of(601L, 451L, 226L), 4, 10L, "ledger.bedwars.diamond-tier"),
        // captured: the first emerald about 30 s in, then its period
        EMERALD(BedWars.EMERALD, Material.EMERALD, "generators.emerald", List.of(1301L, 1001L, 701L), 2, 610L, "ledger.bedwars.emerald-tier");

        final MarkerKey<PointMarker> marker;
        final ItemStack stack;
        /** Ticks between drops at tiers I, II and III. */
        final StateKey<List<Long>> period;
        /** The most lying at one generator. */
        final StateKey<Integer> cap;
        /** Ticks from the start to the first drop. */
        final StateKey<Long> first;
        final StateKey<Integer> tier;

        Kind(MarkerKey<PointMarker> marker, Material material, String rule, List<Long> periods, int cap, long first, String tierId) {
            this.marker = marker;
            this.stack = ItemStack.of(material);
            this.period = StateKey.of(rule + ".period", Codec.LONG.list(), periods);
            this.cap = StateKey.of(rule + ".cap", Codec.INT, cap);
            this.first = StateKey.of(rule + ".first", Codec.LONG, first);
            this.tier = StateKey.of(tierId, Codec.INT, 1, true);
        }
    }

    private final List<List<Generators.Generator>> generators = new ArrayList<>();
    private final List<List<Generators.Pool>> pools = new ArrayList<>();
    private Game game;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        Set<StateKey<?>> out = new java.util.LinkedHashSet<>();
        for (Kind kind : Kind.values()) {
            out.add(kind.period);
            out.add(kind.cap);
            out.add(kind.first);
        }
        return out;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Generators shared = game.module(Generators.class);
        Phases phases = game.module(Phases.class);
        if (shared == null || phases == null) throw new IllegalStateException("Mid needs Generators and Phases installed before it");
        for (Kind kind : Kind.values()) {
            generators.add(new ArrayList<>());
            pools.add(new ArrayList<>());
            for (PointMarker point : game.arena().markers(kind.marker)) {
                Generators.Pool pool = shared.pool(game.rules().resolve(Scope.GAME, kind.cap));
                pools.get(kind.ordinal()).add(pool);
                generators.get(kind.ordinal()).add(shared.add(point.point(), kind.stack, period(kind), pool));
            }
            game.rules().watch(kind.period, (scope, value) -> retime(kind));
            game.rules().watch(kind.cap, (scope, value) -> retime(kind));
        }
        if (Phases.is(game, Match.LIVE)) start();
        else phases.onEnter(Match.LIVE, g -> start());
    }

    /** The kind's tier, 1 to 3. */
    static int tier(@NotNull Game game, @NotNull Kind kind) {
        return game.state().get(Scope.GAME, kind.tier);
    }

    /** Raises {@code kind} to {@code tier}: the new period, and a drop on the spot. */
    public void tier(@NotNull Kind kind, int tier) {
        game.state().set(Scope.GAME, kind.tier, Math.max(1, Math.min(3, tier)));
        retime(kind);
        for (Generators.Generator g : generators.get(kind.ordinal())) g.restart();
    }

    private void start() {
        for (Kind kind : Kind.values()) {
            retime(kind);
            long first = game.rules().resolve(Scope.GAME, kind.first);
            for (Generators.Generator g : generators.get(kind.ordinal())) g.start(first);
        }
    }

    private void retime(Kind kind) {
        long period = period(kind);
        int cap = game.rules().resolve(Scope.GAME, kind.cap);
        for (Generators.Generator g : generators.get(kind.ordinal())) g.period(period);
        for (Generators.Pool pool : pools.get(kind.ordinal())) pool.cap(cap);
    }

    private long period(Kind kind) {
        List<Long> periods = game.rules().resolve(Scope.GAME, kind.period);
        int tier = tier(game, kind);
        return periods.isEmpty() ? 600 : periods.get(Math.min(tier, periods.size()) - 1);
    }
}
