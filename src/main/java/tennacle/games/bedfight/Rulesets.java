package tennacle.games.bedfight;

import tennacle.games.Ruleset;
import tennacle.games.Presets;

import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.roster.Roster;

import java.util.Map;

/**
 * Bed fight's rulesets: {@link #BASE} is what the mode IS on every server; each platform's is a preset over it.
 */
final class Rulesets {

    private Rulesets() {}

    /** No hunger or natural regen, and a seat waits for whoever left it. */
    static final Ruleset BASE = Ruleset.named("bedfight")
            .rule(Roster.RECLAIM_TICKS, -1L) // a seat waits for the rest of the game
            .rule(Abandonment.EMPTY_TICKS, 1200L) // a minute with nobody in, then the game closes
            .path("hunger/enabled", false)
            .path("hunger/naturalRegen", false)
            .path("vri/itemDrop", false)
            // only swings hurt: no bows in this kit, and a fall is the void plane's business
            .path("damage/enabledTypes", "only(minecraft:player_attack)");

    // minemen is mechanics alone: its bed fight capture is empty, so nothing else here would be more than a guess
    private static final Map<String, Ruleset> RULESETS = Map.of(
            "hypixel", Ruleset.named("hypixel")
                    .rule(Presets.KEY, "hypixel")
                    .rule(io.github.term4.grotto.modules.flow.Countdown.FROM, 5) // captured: "The game starts in 5 seconds!"
                    .over(BASE),
            "scrims", Ruleset.named("scrims").rule(Presets.KEY, "scrims18").over(BASE),
            "minemen", Ruleset.named("minemen").rule(Presets.KEY, "mmc18").over(BASE));

    /** The platform rulesets, each over {@link #BASE}; a platform naming none of them plays the base. */
    static Map<String, Ruleset> variants() {
        return RULESETS;
    }

    static void check(io.github.term4.polyp.MechanicsProfile global, io.github.term4.grotto.GameType type) {
        for (Ruleset ruleset : RULESETS.values()) ruleset.check(global, type);
    }
}
