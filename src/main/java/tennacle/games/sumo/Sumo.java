package tennacle.games.sumo;

import tennacle.Mechanics;
import tennacle.games.Duels;
import tennacle.games.HotArena;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presentation;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Body;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.modules.Countdown;
import io.github.term4.grotto.modules.Freeze;
import io.github.term4.grotto.modules.KillCredit;
import io.github.term4.grotto.modules.LastStanding;
import io.github.term4.grotto.modules.Phases;
import io.github.term4.grotto.modules.TimeLimit;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.presets.Preset;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.GameMode;

import java.util.List;
import java.util.Map;

/**
 * Sumo: Hypixel-preset knockback, zero damage (hits shove but never hurt) - first out of the ring loses.
 * The Hypixel profile rides the game's shard (world scope beats the server global).
 * Gameplay lives in {@link Rules}, boards in {@link Boards}, end screens in {@link Ends}.
 */
public final class Sumo {

    public static final StateKey<Integer> SWINGS = StateKey.of("sumo:swings", Codec.INT, 0, true);
    public static final StateKey<Integer> LANDED = StateKey.of("sumo:landed", Codec.INT, 0, true);

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
                .body(Body.fresh().gamemode(GameMode.ADVENTURE))
                .rule(TimeLimit.LIMIT, 2L * 60 * 20) // captured: hypixel sumo counts down from 2:00
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of(Match.COUNT_FROM).grace(Match.START_GRACE_TICKS).voice(Presentation::voice),
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        () -> TimeLimit.of().from(Match.LIVE).onExpire(TimeLimit.draw()),
                        KillCredit::new, Rules::new, Presentation::new,
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Matches.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
                .build();
        RULES.check(Mechanics.polyp.profiles().global());
        // hypixel mechanics + compat are what sumo IS; the ruleset folds over that base
        Duels.mode(new Mode("sumo", type, arena,
                () -> Preset.HYPIXEL.profile().toBuilder().set(MechanicsKeys.COMPAT, Preset.HYPIXEL.compat()),
                RULES, Map.of(), "hypixel",
                List.of(Component.text("Eliminate your opponents!", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                true, null));
    }
}
