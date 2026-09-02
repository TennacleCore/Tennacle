package tennacle.games.bridge;

import tennacle.games.Ruleset;

import java.util.Map;

/**
 * Bridge's rulesets: {@link #BASE} is what the mode IS on every server; each platform's is a preset over it,
 * the measured delta of that platform. A custom setup derives from one of these with {@code with(...)}.
 */
final class Rulesets {

    private Rulesets() {}

    /** What bridge IS on every server: arrows vanish and never stick, no hunger or natural regen, no tossing. */
    static final Ruleset BASE = Ruleset.named("bridge")
            .path("projectiles/minecraft:arrow/removeOnBlockHit", true)
            .path("projectiles/minecraft:arrow/stuckInBody", false)   // no cosmetic body arrows
            .path("hunger/enabled", false)
            .path("hunger/naturalRegen", false)                       // healing is the apple only
            .path("vri/itemDrop", false)                              // hypixel parity: mined clay still drops
            // only swings and arrows hurt: a selection over the damage catalog, not a switch per type
            .path("damage/enabledTypes", "only(minecraft:player_attack, minecraft:thrown)")
            // the golden apple keeps its item, eat time and gate - only what it dishes out is swapped
            .path("consumables/minecraft:golden_apple/behavior", "heal-apple");

    // an arrow is critical iff it was drawn full - no ruleset touches the crit meta or the roll, so the flag it
    // carries and the bonus it deals always match (a partial draw is neither)
    static final Ruleset VANILLA_RULES = Ruleset.named("minemen");
    private static final Map<String, Ruleset> RULESETS = Map.of(
            "hypixel", Ruleset.named("hypixel").over(BASE),
            "scrims", Ruleset.named("scrims")
                    .rule(Cages.SECONDS, 5) // captured: five on scrims, three elsewhere
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
