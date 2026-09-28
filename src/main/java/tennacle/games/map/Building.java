package tennacle.games.map;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.view.Refusals;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import org.jetbrains.annotations.NotNull;
import tennacle.commands.RulesCompletions;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Where a seat may build and what it may break. Bridge and bed fight declare the same six rule ids and the same
 * place/break shape; only the geometry differs, so the rules, the vocabulary and the placed set live here and
 * each mode supplies its own {@link Space}.
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

    /** Every rule this carries, for a type's declaration. */
    public static final Set<StateKey<?>> RULES = Set.of(BUILD, HEIGHT, FLOOR, OUTSIDE, BREAK, BREAK_OUTSIDE);

    static {
        RulesCompletions.words(BREAK.id(), List.of(PLACED, MAP));
        RulesCompletions.words(BREAK_OUTSIDE.id(), List.of(PLACED, MAP));
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

    private final it.unimi.dsi.fastutil.longs.LongSet placed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private Building() {}

    /**
     * Wires the place and break handlers for {@code game}. {@code touched} runs after either lands, for a mode
     * that ends spawn protection on it. The returned handle answers whether a member placed a block.
     */
    public static @NotNull Building install(@NotNull Game game, @NotNull Space space,
                                            @NotNull Consumer<Player> touched) {
        Building building = new Building();
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            if (!game.rules().resolve(player, BUILD)) {
                refuse(e, REASON_BUILD);
                return;
            }
            if (space.reserved(pos)) {
                refuse(e, REASON_RESERVED);
                return;
            }
            boolean allowed = space.buildable(player, pos)
                    || (game.rules().resolve(player, OUTSIDE) && space.inBand(player, pos)); // off the region, still in the band
            if (!allowed) {
                refuse(e, space.belowFloor(player, pos) ? REASON_FLOOR : space.aboveHeight(player, pos) ? REASON_HEIGHT : REASON_OUTSIDE);
                return;
            }
            building.placed.add(key(pos));
            touched.accept(player);
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean own = building.placed.contains(key(pos));
            List<String> may = game.rules().resolve(player, space.guarded(pos) ? BREAK : BREAK_OUTSIDE);
            if (!game.rules().resolve(player, BUILD)) {
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
        return building;
    }

    // Refusals says so, in the mode's words for the reason
    private static void refuse(net.minestom.server.event.trait.CancellableEvent event, String reason) {
        event.setCancelled(true);
        Refusals.because(event, reason);
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
