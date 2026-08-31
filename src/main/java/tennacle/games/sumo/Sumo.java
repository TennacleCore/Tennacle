package tennacle.games.sumo;

import tennacle.Mechanics;
import tennacle.games.Duels;
import tennacle.games.HotArena;

import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.presets.Preset;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;

import java.util.List;

/**
 * Sumo: Hypixel-preset knockback, zero damage (hits shove but never hurt) - first out of the ring loses.
 * The Hypixel profile rides the game's shard (world scope beats the server's MMC18 global).
 * Gameplay lives in {@link Rules}, boards in {@link Boards}, end screens in {@link Ends}.
 */
public final class Sumo {

    public static final StateKey<Integer> SWINGS = StateKey.of("sumo:swings", Codec.INT, 0, true);
    public static final StateKey<Integer> LANDED = StateKey.of("sumo:landed", Codec.INT, 0, true);
    /** Captured: hypixel sumo counts down from 2:00. */
    public static final StateKey<Integer> LIMIT = StateKey.of("sumo:limit-ticks", Codec.INT, 2 * 60 * 20);

    private Sumo() {}

    public static void install() {
        HotArena arena = HotArena.of("sumo");
        GameType type = GameType.builder("sumo")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(Markers.SPAWN, 2)
                .expect(Markers.BOUNDS, 1)
                .modules(Duels.Countdown::new, Rules::new,
                        () -> Duels.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Duels.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
                .build();
        // world scope beats whatever the server runs globally: Hypixel mechanics AND its compat layer (the
        // self-overlap placement refusal), minus every non-melee damage type
        MechanicsProfile hypixel = Preset.HYPIXEL.profile();
        MechanicsProfile profile = hypixel.toBuilder()
                .set(MechanicsKeys.DAMAGE, Duels.meleeOnlyOver(hypixel.get(MechanicsKeys.DAMAGE)))
                .set(MechanicsKeys.COMPAT, Duels.compatOf(Preset.HYPIXEL))
                .build();
        Duels.kind(new Duels.Kind("sumo", type, arena,
                game -> Mechanics.polyp.profiles().setWorld(game.shard(), profile),
                Duels.HYPIXEL, true,
                List.of(Component.text("Eliminate your opponents!", NamedTextColor.YELLOW, TextDecoration.BOLD))));
    }
}
