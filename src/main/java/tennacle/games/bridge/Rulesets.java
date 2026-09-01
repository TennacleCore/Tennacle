package tennacle.games.bridge;

import tennacle.games.Ruleset;

import java.util.Map;

/**
 * Bridge's rulesets: {@link #BASE} is what the mode IS on every server; each look is a preset over it, the
 * measured delta of one platform. A custom setup derives from one of these with {@code with(...)}.
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

    // measured 2026-08-31: hypixel and scrims drop the bow crit roll (scrims keeps the trail); minemen is vanilla
    static final Ruleset VANILLA_RULES = Ruleset.named("minemen");
    private static final Map<String, Ruleset> RULESETS = Map.of(
            "hypixel", Ruleset.named("hypixel")
                    .path("projectiles/minecraft:arrow/critDamage", false)
                    .path("projectiles/minecraft:arrow/critParticles", false),
            "scrims", Ruleset.named("scrims")
                    .path("projectiles/minecraft:arrow/critDamage", false)
                    .path("projectiles/minecraft:arrow/critParticles", true),
            "minemen", VANILLA_RULES);

    /** The look's ruleset; an unknown name plays vanilla (minemen). */
    static Ruleset forLook(String look) {
        return RULESETS.getOrDefault(look, VANILLA_RULES);
    }
}
