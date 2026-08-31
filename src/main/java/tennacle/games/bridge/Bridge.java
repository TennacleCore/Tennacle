package tennacle.games.bridge;

import tennacle.Mechanics;
import tennacle.games.Duels;
import tennacle.games.MapPool;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.List;
import java.util.Map;

/**
 * Bridge: red side, blue side, a goal pit behind each - jump into the ENEMY's to score, first to five wins.
 * Rounds start caged ({@link Cages}); building is gated to the map's build slices between the bridge's
 * underside and the height band; dying to the void respawns you behind brief protection ({@link Rules}).
 * Boards in {@link Boards}, chat and title text in {@link Banners}, end screens in {@link Ends}.
 */
public final class Bridge {

    /** Group = the DEFENDING team; scoring is entering the goal whose group is not yours. */
    public static final MarkerKey<RegionMarker> GOAL = MarkerKey.region("bridge:goal");
    /** The caged round-start spot per team (rounds begin and re-cage here); slots seat off it too. */
    public static final MarkerKey<PosMarker> CAGE = MarkerKey.pos("bridge:cage");
    /** Post-death spawn per team. */
    public static final MarkerKey<PosMarker> RESPAWN = MarkerKey.pos("bridge:respawn");
    /** Horizontal build slices (multi-box for non-square shapes); Y never gates here - see the band. */
    public static final MarkerKey<RegionMarker> BUILD = MarkerKey.region("bridge:build");
    /** Highest placeable Y - the starter ships y=100 (texture packs key off it); flat build boxes band under it. */
    public static final MarkerKey<LimitMarker> HEIGHT = MarkerKey.limit("bridge:height");
    /** The kill plane - the starter ships structure bottom - 10. */
    public static final MarkerKey<LimitMarker> VOID = MarkerKey.limit("bridge:void");
    // the Bloom-authored bridge geometry: the build band derives from these (underside .. top + height - 1)
    public static final MarkerKey<RegionMarker> SECTION = MarkerKey.region("section");
    public static final MarkerKey<RegionMarker> CENTER = MarkerKey.region("center");

    public static final StateKey<Integer> GOALS = StateKey.of("bridge:goals", Codec.INT, 0, true);
    public static final StateKey<Integer> TARGET = StateKey.of("bridge:target", Codec.INT, 5);
    public static final StateKey<Integer> LIMIT = StateKey.of("bridge:limit-ticks", Codec.INT, 15 * 60 * 20);
    public static final StateKey<Integer> KILLS = StateKey.of("bridge:kills", Codec.INT, 0, true);
    /** Knockback immunity after a respawn; forfeited by swinging or touching blocks. */
    public static final StateKey<Integer> PROTECT = StateKey.of("bridge:protect-ticks", Codec.INT, 40);

    // explicit per-team blocks - the team NAME never dictates the material
    private static final Map<String, Material> BLOCKS = Map.of(
            "red", Material.RED_TERRACOTTA, "blue", Material.BLUE_TERRACOTTA);

    private static final Kit KIT = Kit.of("bridge")
            .item(0, (g, seat) -> ItemStack.of(blocks(seat.team()), 64))
            .item(1, (g, seat) -> ItemStack.of(blocks(seat.team()), 64))
            .dyedArmor(EquipmentSlot.HELMET, Material.LEATHER_HELMET)
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Material.LEATHER_LEGGINGS)
            .dyedArmor(EquipmentSlot.BOOTS, Material.LEATHER_BOOTS);

    private Bridge() {}

    public static void install() {
        MapPool arena = MapPool.of("bridge");
        GameType type = GameType.builder("bridge")
                .slots(SlotLayout.groups(CAGE, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(CAGE, 2)
                .expect(Markers.BOUNDS, 1)
                .expect(GOAL, 2)
                .expect(RESPAWN, 2)
                .expect(BUILD, 1)
                .expect(HEIGHT, 1)
                .expect(VOID, 1)
                .expect(SECTION, 2)
                .expect(CENTER, 1)
                .modules(Duels.Countdown::new, () -> Kits.of(KIT), Cages::new, Rules::new,
                        () -> Duels.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Duels.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
                .build();
        Duels.kind(new Duels.Kind("bridge", type, arena,
                game -> Mechanics.polyp.profiles().setWorld(game.shard(),
                        io.github.term4.polyp.MechanicsProfile.builder()
                                .set(io.github.term4.polyp.MechanicsKeys.DAMAGE, Duels.meleeOnlyOver(null))
                                .build()),
                Duels.SCRIMS,
                false, // no death animation or spectate: everyone stays where they stand
                List.of(Component.text("Cross the bridge to score goals.", NamedTextColor.YELLOW),
                        Component.text("Knock off your opponent to gain a clear path.", NamedTextColor.YELLOW),
                        Component.empty(),
                        Component.text("First player to score 5 goals wins!", NamedTextColor.YELLOW)),
                GOALS)); // a SCORING kind: hypixel ends on "<TEAM> WINS!" over the final score
    }

    static String teamOf(Game game, Player player) {
        var seat = game.roster().slotOf(player);
        return seat != null ? seat.team() : "";
    }

    private static Material blocks(String team) {
        return BLOCKS.getOrDefault(team, Material.WHITE_TERRACOTTA);
    }
}
