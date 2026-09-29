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

    /** Food never drains but a hurt seat heals back, and a seat waits for whoever left it. */
    static final Ruleset BASE = Ruleset.named("bedfight")
            .rule(Roster.RECLAIM_TICKS, -1L) // a seat waits for the rest of the game
            .rule(Abandonment.EMPTY_TICKS, 1200L) // a minute with nobody in, then the game closes
            // hunger itself is inert (scale 0 = it never depletes), which is what leaves the regen running
            .path("hunger/exhaustionScale", 0)
            .path("hunger/naturalRegen", true)
            .path("vri/itemDrop", false)
            // only swings hurt: no bows in this kit, and a fall is the void plane's business
            .path("damage/enabledTypes", "only(minecraft:player_attack)");

    // minemen is mechanics alone: its bed fight capture is empty, so nothing else here would be more than a guess
    private static final Map<String, Ruleset> RULESETS = Map.of(
            "hypixel", Ruleset.named("hypixel")
                    .rule(Presets.KEY, "hypixel")
                    .rule(io.github.term4.grotto.modules.flow.Countdown.FROM, 5) // captured: "The game starts in 5 seconds!"
                    .rule(io.github.term4.grotto.modules.combat.Respawns.RESPAWN_TICKS, 100L)
                    .rule(io.github.term4.grotto.modules.combat.Respawns.REJOIN_TICKS, 200L) // bedwars: ten, not five
                    .path("damage/enabledTypes", "with(minecraft:fall)") // a hypixel bed fight bridges at its own risk
                    .over(BASE),
            "scrims", Presets.variant("scrims", BASE),
            "minemen", Presets.variant("minemen", BASE));

    /** The platform rulesets, each over {@link #BASE}; a platform naming none of them plays the base. */
    static Map<String, Ruleset> variants() {
        return RULESETS;
    }

    static void check(io.github.term4.polyp.MechanicsProfile global, io.github.term4.grotto.GameType type) {
        for (Ruleset ruleset : RULESETS.values()) ruleset.check(global, type);
    }
}
