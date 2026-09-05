package tennacle.games.bridge;

import io.github.term4.grotto.Bodies;
import io.github.term4.grotto.modules.combat.Freeze;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.TimeLimit;
import tennacle.games.Match;
import tennacle.games.Presets;
import tennacle.games.Ruleset;

import java.util.List;
import java.util.Map;

/**
 * Bridge's rulesets: {@link #BASE} is what the mode IS on every server; each platform's is a preset over it,
 * the measured delta of that platform. A custom setup derives from one of these with {@code with(...)}.
 */
final class Rulesets {

    private Rulesets() {}

    /** What bridge IS on every server: arrows vanish off blocks, no hunger or natural regen, no tossing. */
    static final Ruleset BASE = Ruleset.named("bridge")
            .path("projectiles/minecraft:arrow/removeOnBlockHit", true)
            .path("projectiles/minecraft:arrow/selfHit", "pass_through") // no bow boost unless a platform asks
            // same team + FF off is the client lever that stops a 1.8 client hiding an arrow, so teammates already
            // have it from the game's own team; this trail is the cue off an ENEMY, at the cost of looking crit
            .path("visuals/legacyArrowVisibility/deflectParticles", true)
            .path("hunger/enabled", false)
            .path("hunger/naturalRegen", false)                       // healing is the apple only
            .path("vri/itemDrop", false)                              // hypixel parity: mined clay still drops
            // only swings and arrows hurt: a selection over the damage catalog, not a switch per type
            .path("damage/enabledTypes", "only(minecraft:player_attack, minecraft:thrown)")
            // the golden apple keeps its item, eat time and gate - only what it dishes out is swapped
            .path("consumables/minecraft:golden_apple/behavior", "heal-apple");

    // the crit META is never touched - a full-draw arrow shows the flag, a partial does not (its actual crit state).
    // hypixel and scrims drop the crit ROLL (critDamage false): the trail shows, the bonus does not; minemen rolls it.
    static final Ruleset VANILLA_RULES = Ruleset.named("minemen")
            .rule(Presets.KEY, "mmc18")
            .path("projectiles/minecraft:arrow/selfHit", "hit") // captured: bow boosting, minemen alone
            .rule(Freeze.ALLOWS, List.of(Freeze.DAMAGE)) // captured: nothing is thrown or fired from the cage
            .rule(Cages.BLOCKS, false)                  // captured: no cage at all
            .rule(Freeze.SEAT, Freeze.SEAT_RESPAWN)     // held where they respawn instead
            .rule(Freeze.BOUNDS, 0)                     // captured: pinned there, no room to move at all
            .rule(Bodies.CLEARS, List.of(Bodies.ARROWS)) // captured: a reset pulls the stuck arrows out
            .rule(TimeLimit.FROM, Match.LIVE);          // captured: the clock starts when the cages open, not on the count
    private static final Map<String, Ruleset> RULESETS = Map.of(
            "hypixel", Ruleset.named("hypixel")
                    .rule(Presets.KEY, "hypixel")
                    .path("projectiles/minecraft:arrow/critDamage", false)
                    .over(BASE),
            "scrims", Ruleset.named("scrims")
                    .rule(Presets.KEY, "scrims18")
                    .path("projectiles/minecraft:arrow/critDamage", false)
                    .path("projectiles/minecraft:arrow/stuckInBody", false) // captured: no body arrows on scrims
                    .rule(Countdown.FROM, 5) // captured: five beats on scrims, three elsewhere
                    .over(BASE),
            "minemen", VANILLA_RULES.over(BASE));

    /** The platform rulesets, each over {@link #BASE}; a platform naming none of them plays the base. */
    static Map<String, Ruleset> variants() {
        return RULESETS;
    }

    static void check(io.github.term4.polyp.MechanicsProfile global, io.github.term4.grotto.GameType type) {
        for (Ruleset ruleset : RULESETS.values()) ruleset.check(global, type);
    }
}
