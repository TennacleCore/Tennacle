package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.items.Creatures;

import io.github.term4.grotto.Game;

/** Sudden Death's dragons: a wave over every standing side's spawn, and the world hears it. */
final class Dragons {

    private Dragons() {}

    static void suddenDeath(Game game) {
        wave(game, false);
    }

    static void moreDragons(Game game) {
        wave(game, true);
    }

    private static void wave(Game game, boolean more) {
        Creatures creatures = game.module(Creatures.class);
        if (creatures == null) return;
        if (more) Match.say(game, Lines.moreDragons(game));
        creatures.wave((team, dragon) -> Match.say(game, Lines.suddenDeathDragon(game, team)));
        Match.each(game, p -> p.playSound(Lines.DRAGON_GROWL));
    }
}
