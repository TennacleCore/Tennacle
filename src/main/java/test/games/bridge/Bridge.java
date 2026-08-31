package test.games.bridge;

import test.Mechanics;
import test.games.Duels;
import test.games.HotArena;

import io.github.term4.barnacle.marker.MarkerKey;
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
 * Simple bridge: red side, blue side, a goal pit behind each - jump into the ENEMY's to score, first to
 * five wins. Falling resets you to your spawn. No cages, no bows, no block limits - just the game.
 * Gameplay lives in {@link Rules}, boards in {@link Boards}, chat and title text in {@link Banners},
 * end screens in {@link Ends}.
 */
public final class Bridge {

    /** Group = the DEFENDING team; scoring is entering the goal whose group is not yours. */
    public static final MarkerKey<RegionMarker> GOAL = MarkerKey.region("bridge:goal");

    public static final StateKey<Integer> GOALS = StateKey.of("bridge:goals", Codec.INT, 0, true);
    public static final StateKey<Integer> TARGET = StateKey.of("bridge:target", Codec.INT, 5);
    public static final StateKey<Integer> LIMIT = StateKey.of("bridge:limit-ticks", Codec.INT, 15 * 60 * 20);
    public static final StateKey<Integer> KILLS = StateKey.of("bridge:kills", Codec.INT, 0, true);

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
        HotArena arena = HotArena.of("bridge");
        GameType type = GameType.builder("bridge")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(Markers.SPAWN, 2)
                .expect(Markers.BOUNDS, 1)
                .modules(Duels.Countdown::new, () -> Kits.of(KIT), Rules::new,
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
