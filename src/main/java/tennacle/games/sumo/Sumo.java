package tennacle.games.sumo;

import tennacle.Mechanics;
import tennacle.games.Ruleset;
import tennacle.games.Countdown;
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
 * The Hypixel profile rides the game's shard (world scope beats the server global).
 * Gameplay lives in {@link Rules}, boards in {@link Boards}, end screens in {@link Ends}.
 */
public final class Sumo {

    public static final StateKey<Integer> SWINGS = StateKey.of("sumo:swings", Codec.INT, 0, true);
    public static final StateKey<Integer> LANDED = StateKey.of("sumo:landed", Codec.INT, 0, true);
    /** Captured: hypixel sumo counts down from 2:00. */
    public static final StateKey<Integer> LIMIT = StateKey.of("sumo:limit-ticks", Codec.INT, 2 * 60 * 20);

    /** Only swings hurt - a selection over the damage catalog, as a path. */
    static final Ruleset RULES = Ruleset.named("sumo").path("damage/enabledTypes", "only(minecraft:player_attack)");

    private Sumo() {}

    public static void install() {
        HotArena arena = HotArena.of("sumo");
        GameType type = GameType.builder("sumo")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(Markers.SPAWN, 2)
                .expect(Markers.BOUNDS, 1)
                .modules(Countdown::new, Rules::new,
                        () -> Duels.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Duels.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
                .build();
        RULES.check(Mechanics.polyp.profiles().global());
        Duels.kind(new Duels.Kind("sumo", type, arena,
                game -> {
                    // hypixel mechanics + compat are what sumo IS; the ruleset folds over that base
                    MechanicsProfile.Builder profile = Preset.HYPIXEL.profile().toBuilder()
                            .set(MechanicsKeys.COMPAT, Preset.HYPIXEL.compat());
                    RULES.apply(profile, Mechanics.polyp.profiles().global(), game);
                    Mechanics.polyp.profiles().setWorld(game.shard(), profile.build());
                    RULES.seed(game);
                },
                Duels.HYPIXEL, true,
                List.of(Component.text("Eliminate your opponents!", NamedTextColor.YELLOW, TextDecoration.BOLD))));
    }
}
