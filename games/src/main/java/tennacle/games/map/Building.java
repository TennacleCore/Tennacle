package tennacle.games.map;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.explosion.ExplosionEvent;
import io.github.term4.polyp.api.event.explosion.TntPrimeEvent;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import io.github.term4.polyp.api.event.fluid.FluidSpreadEvent;
import net.kyori.adventure.key.Key;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.commands.RulesCompletions;
import tennacle.boot.Mechanics;
import tennacle.games.Match;

import java.util.List;
import java.util.Set;
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
    /** May drop an item below the floor; off, the drop is refused (Hypixel) and the inventory itself stays usable. */
    public static final StateKey<Boolean> FLOOR_DROPS = StateKey.of("space.floor.drops", Codec.BOOLEAN, true);

    /** A block a member put there. */
    public static final String PLACED = "placed";
    /** The map's own. */
    public static final String MAP = "map";
    /** The map's grass and its kin, which a placement replaces anyway; comes up empty-handed. Never a flower or a snow layer. */
    public static final String GRASS = "grass";
    private static final Set<Key> GRASSES = Set.of(Block.SHORT_GRASS.key(), Block.TALL_GRASS.key(), Block.FERN.key(),
            Block.LARGE_FERN.key(), Block.DEAD_BUSH.key(), Block.BUSH.key(), Block.SHORT_DRY_GRASS.key(), Block.TALL_DRY_GRASS.key());

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

    /** A hand's placements and breaks, at the seat; a structure's blocks and a blast's are neither. */
    public static final StateKey<Integer> PLACED_COUNT = StateKey.of("ledger.build.placed", Codec.INT, 0, true);
    public static final StateKey<Integer> BROKEN_COUNT = StateKey.of("ledger.build.broken", Codec.INT, 0, true);

    /** Every rule this carries, for a type's declaration. */
    public static final Set<StateKey<?>> RULES = Set.of(BUILD, HEIGHT, FLOOR, OUTSIDE, FLOOR_DROPS, BREAK, BREAK_OUTSIDE, FLOW_RESERVED,
            BLAST, BLAST_RESERVED);

    static {
        RulesCompletions.words(BREAK.id(), List.of(PLACED, MAP, GRASS));
        RulesCompletions.words(BREAK_OUTSIDE.id(), List.of(PLACED, MAP, GRASS));
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

    /** The floor and the ceiling a seat builds between: the map's, or the seat's own {@link #FLOOR}/{@link #HEIGHT}. */
    public static final class Band {
        private final Game game;
        private final int floorY;
        private final int ceilingY;

        public Band(@NotNull Game game, int floorY, int ceilingY) {
            this.game = game;
            this.floorY = floorY;
            this.ceilingY = ceilingY;
        }

        /** Between the mode's two limit planes. */
        public static @NotNull Band of(@NotNull Game game, @NotNull MarkerKey<LimitMarker> floor, @NotNull MarkerKey<LimitMarker> height) {
            return new Band(game, (int) Math.ceil(plane(game, floor)), (int) Math.floor(plane(game, height)));
        }

        /** The floor under the game's own rules, where no placer is asked. */
        public int floor() {
            int rule = game.rules().resolve(Scope.GAME, FLOOR);
            return rule >= 0 ? rule : floorY;
        }

        public int ceiling() {
            int rule = game.rules().resolve(Scope.GAME, HEIGHT);
            return rule >= 0 ? rule : ceilingY;
        }

        public int floor(@NotNull Player placer) {
            int rule = game.rules().resolve(placer, FLOOR);
            return rule >= 0 ? rule : floorY;
        }

        public int ceiling(@NotNull Player placer) {
            int rule = game.rules().resolve(placer, HEIGHT);
            return rule >= 0 ? rule : ceilingY;
        }

        public boolean contains(@NotNull Player placer, @NotNull Point pos) {
            return pos.blockY() >= floor(placer) && pos.blockY() <= ceiling(placer);
        }

        public boolean below(@NotNull Player placer, @NotNull Point pos) {
            return pos.blockY() < floor(placer);
        }

        public boolean above(@NotNull Player placer, @NotNull Point pos) {
            return pos.blockY() > ceiling(placer);
        }

        /** Inside one of {@code region}'s boxes: a flat (1-tall) box, what the wand draws over a deck, is an X/Z slice through the band; a taller one is its own volume. */
        public boolean covers(@NotNull RegionMarker region, @NotNull Player placer, @NotNull Point pos) {
            for (RegionMarker.Cuboid box : region.boxes()) {
                if (pos.blockX() < box.min().blockX() || pos.blockX() > box.max().blockX()
                        || pos.blockZ() < box.min().blockZ() || pos.blockZ() > box.max().blockZ()) continue;
                if (box.min().blockY() == box.max().blockY() ? contains(placer, pos)
                        : pos.blockY() >= box.min().blockY() && pos.blockY() <= box.max().blockY()) return true;
            }
            return false;
        }
    }

    /** A limit marker's pinned y. */
    public static double plane(@NotNull Game game, @NotNull MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    /** A module that keeps cells clear wherever it is installed, beside the {@link Space}'s own. */
    public interface Reserves {
        boolean reserves(@NotNull Point pos);

        /** A reserved cell a fluid stops at too, while {@link #FLOW_RESERVED} is off. */
        default boolean stillsFluid(@NotNull Point pos) {
            return reserves(pos);
        }
    }

    /** The reasons a refusal carries ({@link Refusals#because}), for a mode's line to pick its copy by. */
    public static final String REASON_BUILD = "build", REASON_RESERVED = "reserved", REASON_FLOOR = "floor",
            REASON_HEIGHT = "height", REASON_OUTSIDE = "outside", REASON_MAP = "map", REASON_PLACED = "placed";


    private final it.unimi.dsi.fastutil.longs.LongSet placed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    // cells whose break comes up empty-handed, by the tick of the break: Polyp drops at that tick's end
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap bare = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private long bareAge = -1;
    // each still area with the fluids it turned away, woken when it lifts
    private final java.util.Map<Predicate<Point>, Set<net.minestom.server.coordinate.BlockVec>> stills = new java.util.LinkedHashMap<>();
    private final List<Predicate<Point>> fixed = new java.util.ArrayList<>();
    private final Game game;
    private final Space space;

    /** The module that installed a game's building rules, which is how {@link #of} finds them. */
    public interface Owner {
        @Nullable Building building();
    }

    /** The building rules a mode installed for {@code game}, or null before its rules module ran. */
    public static @Nullable Building of(@NotNull Game game) {
        for (Owner owner : game.modules(Owner.class)) {
            Building building = owner.building();
            if (building != null) return building;
        }
        return null;
    }

    private Building(Game game, Space space) {
        this.game = game;
        this.space = space;
        bare.defaultReturnValue(-1);
    }

    /**
     * Wires the place and break handlers for {@code game}. {@code touched} runs after either lands, for a mode
     * that ends spawn protection on it. The returned handle answers whether a member placed a block.
     */
    public static @NotNull Building install(@NotNull Game game, @NotNull Space space,
                                            @NotNull Consumer<Player> touched) {
        Building building = new Building(game, space);
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            String reason = building.refusal(player, e.getBlockPosition());
            if (reason != null) {
                refuse(e, reason);
                return;
            }
            building.placed.add(key(e.getBlockPosition()));
            Match.addSeat(game, player, PLACED_COUNT);
            touched.accept(player);
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            if (building.fixed(pos)) { // no line and nothing drops: the block simply stays (a sponge at work)
                e.setCancelled(true);
                Refusals.handled(e);
                return;
            }
            boolean own = building.placed.contains(key(pos));
            List<String> may = game.rules().resolve(player, space.guarded(pos) ? BREAK : BREAK_OUTSIDE);
            if (!Match.live(game) || !game.rules().resolve(player, BUILD)) {
                refuse(e, REASON_BUILD);
                return;
            }
            if (!own && building.reserved(pos)) {
                refuse(e, REASON_RESERVED);
                return;
            }
            String kind = own ? PLACED : GRASSES.contains(e.getBlock().key()) ? GRASS : MAP;
            if (!may.contains(kind) && !(kind.equals(GRASS) && may.contains(MAP))) {
                refuse(e, own ? REASON_PLACED : REASON_MAP);
                return;
            }
            building.placed.remove(key(pos)); // after the refusal: a block that stays is still the one who placed it
            if (kind.equals(GRASS)) building.bare(pos);
            Match.addSeat(game, player, BROKEN_COUNT);
            touched.accept(player);
        });
        game.onWorld(ItemSpawnEvent.class, ItemSpawnEvent::world, e -> {
            if (e.cause() != ItemSpawnEvent.Cause.BLOCK_DROP) return;
            if (building.bare.get(key(e.position())) == e.world().worldAge()) e.setCancelled(true);
        });
        game.onWorld(FluidSpreadEvent.class, FluidSpreadEvent::world, e -> {
            if (building.stilled(e.to(), e.from())) e.setCancelled(true);
        });
        game.on(ItemDropEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null || game.rules().resolve(player, FLOOR_DROPS)) return;
            if (space.belowFloor(player, player.getPosition())) e.setCancelled(true);
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
            e.blocks().removeIf(pos -> !reserved && building.reserved(pos) || !may.contains(building.placed(pos) ? PLACED : MAP));
            for (Point pos : e.blocks()) building.placed.remove(key(pos));
        });
        return building;
    }

    private void bare(Point pos) {
        long now = game.shard().worldAge();
        if (now != bareAge) {
            bare.clear();
            bareAge = now;
        }
        bare.put(key(pos), now);
    }

    // asked per check: a reserving module may install after the mode's rules
    private boolean reserved(Point pos) {
        if (space.reserved(pos)) return true;
        for (Reserves module : game.modules(Reserves.class)) {
            if (module.reserves(pos)) return true;
        }
        return false;
    }

    // a cell no fluid may run into: a reserved one under the rule, or one a module holds
    private boolean stilled(Point pos, net.minestom.server.coordinate.BlockVec from) {
        if (!game.rules().resolve(Scope.GAME, FLOW_RESERVED) && reservedFromFluids(pos)) return true;
        for (var still : stills.entrySet()) {
            if (!still.getKey().test(pos)) continue;
            still.getValue().add(from);
            return true;
        }
        return false;
    }

    private boolean reservedFromFluids(Point pos) {
        if (space.reserved(pos)) return true;
        for (Reserves module : game.modules(Reserves.class)) {
            if (module.stillsFluid(pos)) return true;
        }
        return false;
    }

    /** Keeps every fluid out of {@code area} until the returned runnable is called (a sponge's cube). */
    public @NotNull Runnable still(@NotNull Predicate<Point> area) {
        stills.put(area, new java.util.HashSet<>());
        return () -> {
            Set<net.minestom.server.coordinate.BlockVec> refused = stills.remove(area);
            // a refused fluid sleeps until a neighbor changes, and lifting the area changes no block
            if (refused == null || Mechanics.fluids == null) return;
            for (var from : refused) Mechanics.fluids.placed(game.shard(), from);
        };
    }

    /** Keeps the blocks in {@code area} from every pick until the returned release runs. */
    public @NotNull Runnable fix(@NotNull Predicate<Point> area) {
        fixed.add(area);
        return () -> fixed.remove(area);
    }

    private boolean fixed(Point pos) {
        for (Predicate<Point> area : fixed) {
            if (area.test(pos)) return true;
        }
        return false;
    }

    /** Takes the block at {@code pos} away, a member's mark with it, and tells its neighbors (a sponge that is done, the water it drank). */
    public void vanish(@NotNull Point pos) {
        game.shard().setBlock(pos, Block.AIR);
        placed.remove(key(pos));
        game.shard().applyPhysics(pos);
    }

    // Refusals says so, in the mode's words for the reason
    private static void refuse(net.minestom.server.event.trait.CancellableEvent event, String reason) {
        event.setCancelled(true);
        Refusals.because(event, reason);
    }

    /** Why {@code placer} may not place at {@code pos} (a {@code REASON_}), or null. */
    public @Nullable String refusal(@NotNull Player placer, @NotNull Point pos) {
        if (!Match.live(game) || !game.rules().resolve(placer, BUILD)) return REASON_BUILD;
        if (reserved(pos)) return REASON_RESERVED;
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

    /** A {@link Space} over {@code band}, for a mode with nothing reserved. */
    public static @NotNull Space space(@NotNull Band band, @NotNull BiPredicate<Player, Point> buildable,
                                       @NotNull Predicate<Point> guarded) {
        return space(band, buildable, guarded, pos -> false);
    }

    /** A {@link Space} over {@code band}: the floor and height answers are the band's, the rest the mode's. */
    public static @NotNull Space space(@NotNull Band band, @NotNull BiPredicate<Player, Point> buildable,
                                       @NotNull Predicate<Point> guarded, @NotNull Predicate<Point> reserved) {
        return new Space() {
            @Override public boolean buildable(@NotNull Player placer, @NotNull Point pos) { return buildable.test(placer, pos); }
            @Override public boolean inBand(@NotNull Player placer, @NotNull Point pos) { return band.contains(placer, pos); }
            @Override public boolean guarded(@NotNull Point pos) { return guarded.test(pos); }
            @Override public boolean reserved(@NotNull Point pos) { return reserved.test(pos); }
            @Override public boolean belowFloor(@NotNull Player placer, @NotNull Point pos) { return band.below(placer, pos); }
            @Override public boolean aboveHeight(@NotNull Player placer, @NotNull Point pos) { return band.above(placer, pos); }
        };
    }

    // vanilla's BlockPos.asLong packing: one primitive per placed block, no key object per place or break
    private static long key(Point pos) {
        return ((long) pos.blockX() & 0x3FFFFFFL) << 38 | ((long) pos.blockZ() & 0x3FFFFFFL) << 12 | (pos.blockY() & 0xFFFL);
    }
}
