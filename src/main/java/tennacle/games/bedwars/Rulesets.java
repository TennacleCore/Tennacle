package tennacle.games.bedwars;

import tennacle.games.Presets;
import tennacle.games.Ruleset;

import io.github.term4.grotto.GameType;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.polyp.MechanicsProfile;

import java.util.Map;

/** Bed Wars' one ruleset for now: Hypixel's mechanics, a seat held for the game, nothing dropped at death. */
final class Rulesets {

    private Rulesets() {}

    static final Ruleset BASE = Ruleset.named("bedwars")
            .rule(Presets.KEY, "hypixel")
            .rule(Roster.RECLAIM_TICKS, -1L)
            .rule(Abandonment.EMPTY_TICKS, 1200L)
            .path("vri/itemDrop", false)
            // the user's rule: a refused placement or break sounds to nobody but its own client
            .path("fx/polyp:block_place_refused", "to(predicted-for(source), place-sound)")
            .path("fx/polyp:block_break_refused", "none");

    static Map<String, Ruleset> variants() {
        return Map.of();
    }

    static void check(MechanicsProfile global, GameType type) {
        BASE.check(global, type);
    }
}
