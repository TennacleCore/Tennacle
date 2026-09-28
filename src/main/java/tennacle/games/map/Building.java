package tennacle.games.map;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.explosion.ExplosionEvent;
import io.github.term4.polyp.api.event.explosion.TntPrimeEvent;
import io.github.term4.polyp.api.event.fluid.FluidSpreadEvent;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.commands.RulesCompletions;
import tennacle.games.Match;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Where a seat may build, what it may break, and where a fluid may run. Bridge and bed fight declare the same rule
 * ids and the same place/break shape; only the geometry differs, so the rules, the vocabulary and the placed set
 * live here and each mode supplies its own {@link Space}.
 */
public final class Building {

    /** Building at all: off, and a seat neither places nor breaks. */
    public static final StateKey<Boolean> BUILD = StateKey.of("space.build", Codec.BOOLEAN, true);
    /** Absolute max build Y; {@code -1} keeps the mode's height marker. */
    public static final StateKey<Integer> HEIGHT = StateKey.of("space.build.height", Codec.INT, -1);
    /** Lowest placeable Y; {@code -1} keeps the mode's floor marker. */
    public static final StateKey<Integer> FLOOR = StateKey.of("space.build.floor", Codec.INT, -1);
    /** May place outside the mode's build region - still under the height and above the floor. */
    public static final StateKey<Boolean> OUTSIDE = StateKey.of("space.build.outside", Codec.BOOLEAN, false);

    /** A block a member put there. */
    public static final String PLACED = "placed";
    /** The map's own. */
    public static final String MAP = "map";

    /** What may be broken inside the mode's guarded regions; a kind nobody names stays standing. */
    public static final StateKey<List<String>> BREAK = StateKey.of("space.break", Codec.STRING.list(), List.of(PLACED, MAP));
    /** What may be broken outside one - the rest of the map. */
    public static final StateKey<List<String>> BREAK_OUTSIDE =
            StateKey.of("space.break.outside", Codec.STRING.list(), List.of(PLACED));
    /** A fluid may run into a reserved cell; off, it stops at the edge and stands still there (a base box). */
    public static final StateKey<Boolean> FLOW_RESERVED = StateKey.of("space.flow.reserved", Codec.BOOLEAN, false);
    /** What a blast takes, wherever it goes off; a kind nobody names stands. A mode that names it keeps its preset's blast terrain. */
    public static final StateKey<List<String>> BLAST = StateKey.of("space.blast", Codec.STRING.list(), List.of(PLACED));
    /** A blast may take a reserved cell (a base box); off, those stand whatever else goes. */
    public static final StateKey<Boolean> BLAST_RESERVED = StateKey.of("space.blast.reserved", Codec.BOOLEAN, false);

    /** Every rule this carries, for a type's declaration. */
    public static final Set<StateKey<?>> RULES = Set.of(BUILD, HEIGHT, FLOOR, OUTSIDE, BREAK, BREAK_OUTSIDE, FLOW_RESERVED,
            BLAST, BLAST_RESERVED);

    static {
        RulesCompletions.words(BREAK.id(), List.of(PLACED, MAP));
        RulesCompletions.words(BREAK_OUTSIDE.id(), List.of(PLACED, MAP));
        RulesCompletions.words(BLAST.id(), List.of(PLACED, MAP));
    }

    /** The mode's geometry: everything the shared handlers cannot know. */
    public interface Space {
        /** Inside the mode's own build region, at a height that region allows. */
        boolean buildable(@NotNull Player placer, @NotNull Point pos);

        /** Between the floor and the ceiling, wherever it is - what {@link #OUTSIDE} lets a seat use. */
        boolean inBand(@NotNull Player placer, @NotNull Point pos);

        /** A guarded region, where {@link #BREAK} applies instead of {@link #BREAK_OUTSIDE}. */
        boolean guarded(@NotNull Point pos);

        /** A position no seat may place on whatever the rules say (a spawn, a cage). */
        default boolean reserved(@NotNull Point pos) {
            return false;
        }

        /** Under the floor, for the line that says so; a space with no floor of its own never is. */
        default boolean belowFloor(@NotNull Player placer, @NotNull Point pos) {
            return false;
        }

        /** Over the height, likewise. */
        default boolean aboveHeight(@NotNull Player placer, @NotNull Point pos) {
            return false;
        }
    }

    /** The reasons a refusal carries ({@link Refusals#because}), for a mode's line to pick its copy by. */
    public static final String REASON_BUILD = "build", REASON_RESERVED = "reserved", REASON_FLOOR = "floor",
            REASON_HEIGHT = "height", REASON_OUTSIDE = "outside", REASON_MAP = "map", REASON_PLACED = "placed";

    private static final Map<Game, Building> BY_GAME = Collections.synchronizedMap(new WeakHashMap<>());

    private final it.unimi.dsi.fastutil.longs.LongSet placed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final List<Predicate<Point>> stills = new java.util.ArrayList<>();
    private final Game game;
    private final Space space;

    /** The building rules a mode installed for {@code game}, or null before its rules module ran. */
    public static @Nullable Building of(@NotNull Game game) {
        return BY_GAME.get(game);
    }

    private Building(Game game, Space space) {
        this.game = game;
        this.space = space;
    }

    /**
     * Wires the place and break handlers for {@code game}. {@code touched} runs after either lands, for a mode
     * that ends spawn protection on it. The returned handle answers whether a member placed a block.
     */
    public static @NotNull Building install(@NotNull Game game, @NotNull Space space,
                                            @NotNull Consumer<Player> touched) {
        Building building = new Building(game, space);
        BY_GAME.put(game, building);
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            String reason = building.refusal(player, e.getBlockPosition());
            if (reason != null) {
                refuse(e, reason);
                return;
            }
            building.placed.add(key(e.getBlockPosition()));
            touched.accept(player);
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean own = building.placed.contains(key(pos));
            List<String> may = game.rules().resolve(player, space.guarded(pos) ? BREAK : BREAK_OUTSIDE);
            if (!Match.live(game) || !game.rules().resolve(player, BUILD)) {
                refuse(e, REASON_BUILD);
                return;
            }
            if (!own && space.reserved(pos)) {
                refuse(e, REASON_RESERVED);
                return;
            }
            if (!may.contains(own ? PLACED : MAP)) {
                refuse(e, own ? REASON_PLACED : REASON_MAP);
                return;
            }
            building.placed.remove(key(pos)); // after the refusal: a block that stays is still the one who placed it
            touched.accept(player);
        });
        game.onWorld(FluidSpreadEvent.class, FluidSpreadEvent::world, e -> {
            if (building.stilled(e.to())) e.setCancelled(true);
        });
        // Polyp primes a placed TNT inside the placement, before this handler refuses the block: the same refusal
        // first, and the refused placement then says why
        game.onWorld(TntPrimeEvent.class, TntPrimeEvent::world, e -> {
            if (e.cause() != TntPrimeEvent.Cause.PLACEMENT || e.blockPos() == null) return;
            if (!(e.igniter() instanceof Player player) || game.roster().slotOf(player) == null) return;
            if (building.refusal(player, e.blockPos()) != null) e.setCancelled(true);
        });
        game.onWorld(ExplosionEvent.class, ExplosionEvent::world, e -> {
            Player igniter = e.source() instanceof Player p && game.roster().slotOf(p) != null ? p : null;
            List<String> may = igniter != null ? game.rules().resolve(igniter, BLAST) : game.rules().resolve(Scope.GAME, BLAST);
            boolean reserved = igniter != null ? game.rules().resolve(igniter, BLAST_RESERVED) : game.rules().resolve(Scope.GAME, BLAST_RESERVED);
            e.blocks().removeIf(pos -> !reserved && space.reserved(pos) || !may.contains(building.placed(pos) ? PLACED : MAP));
            for (Point pos : e.blocks()) building.placed.remove(key(pos));
        });
        return building;
    }

    // a cell no fluid may run into: a reserved one under the rule, or one a module holds
    private boolean stilled(Point pos) {
        if (!game.rules().resolve(Scope.GAME, FLOW_RESERVED) && space.reserved(pos)) return true;
        for (Predicate<Point> still : stills) {
            if (still.test(pos)) return true;
        }
        return false;
    }

    /** Keeps every fluid out of {@code area} until the returned runnable is called (a sponge's cube). */
    public @NotNull Runnable still(@NotNull Predicate<Point> area) {
        stills.add(area);
        return () -> stills.remove(area);
    }

    /** Takes the block at {@code pos} away, a member's mark with it (a sponge that is done, the water it drank). */
    public void vanish(@NotNull Point pos) {
        game.shard().setBlock(pos, Block.AIR);
        placed.remove(key(pos));
    }

    // Refusals says so, in the mode's words for the reason
    private static void refuse(net.minestom.server.event.trait.CancellableEvent event, String reason) {
        event.setCancelled(true);
        Refusals.because(event, reason);
    }

    /** Why {@code placer} may not place at {@code pos} (a {@code REASON_}), or null. */
    public @Nullable String refusal(@NotNull Player placer, @NotNull Point pos) {
        if (!Match.live(game) || !game.rules().resolve(placer, BUILD)) return REASON_BUILD;
        if (space.reserved(pos)) return REASON_RESERVED;
        boolean allowed = space.buildable(placer, pos)
                || (game.rules().resolve(placer, OUTSIDE) && space.inBand(placer, pos)); // off the region, still in the band
        if (allowed) return null;
        return space.belowFloor(placer, pos) ? REASON_FLOOR : space.aboveHeight(placer, pos) ? REASON_HEIGHT : REASON_OUTSIDE;
    }

    /** Puts {@code block} into the air at {@code pos} as {@code placer}'s own placement, under the same rules as a hand's. */
    public boolean place(@NotNull Player placer, @NotNull Point pos, @NotNull Block block) {
        if (refusal(placer, pos) != null || !game.shard().getBlock(pos).air()) return false;
        game.shard().setBlock(pos, block);
        placed.add(key(pos));
        return true;
    }

    public @NotNull Space space() {
        return space;
    }

    /** Whether a member put the block at {@code pos} there. */
    public boolean placed(@NotNull Point pos) {
        return placed.contains(key(pos));
    }

    /** Forgets every placement - a round reset, where the map goes back to its own blocks. */
    public void clear() {
        placed.clear();
    }

    /** A {@link Space} from three lambdas, for a mode with nothing reserved. */
    public static @NotNull Space space(@NotNull BiPredicate<Player, Point> buildable,
                                       @NotNull BiPredicate<Player, Point> inBand,
                                       @NotNull Predicate<Point> guarded) {
        return new Space() {
            @Override public boolean buildable(@NotNull Player placer, @NotNull Point pos) { return buildable.test(placer, pos); }
            @Override public boolean inBand(@NotNull Player placer, @NotNull Point pos) { return inBand.test(placer, pos); }
            @Override public boolean guarded(@NotNull Point pos) { return guarded.test(pos); }
        };
    }

    // vanilla's BlockPos.asLong packing: one primitive per placed block, no key object per place or break
    private static long key(Point pos) {
        return ((long) pos.blockX() & 0x3FFFFFFL) << 38 | ((long) pos.blockZ() & 0x3FFFFFFL) << 12 | (pos.blockY() & 0xFFFL);
    }
}
