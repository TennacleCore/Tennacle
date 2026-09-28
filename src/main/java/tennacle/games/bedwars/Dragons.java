package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.items.Creatures;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** Sudden Death's dragons: every standing side gets its own over its spawn, and the world hears it. */
final class Dragons implements GameModule {

    static final StateKey<Integer> PER_TEAM = StateKey.of("creatures.dragons.per-team", Codec.INT, 1);
    /** Over the side's spawn. */
    static final StateKey<Double> HEIGHT = StateKey.of("creatures.dragons.height", Codec.DOUBLE, 15.0);

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(PER_TEAM, HEIGHT);
    }

    @Override
    public void install(@NotNull Game game) {
        if (game.module(Creatures.class) == null || game.module(LastStanding.class) == null) {
            throw new IllegalStateException("Dragons needs Creatures and LastStanding installed before it");
        }
    }

    static void suddenDeath(Game game) {
        wave(game, false);
    }

    static void moreDragons(Game game) {
        wave(game, true);
    }

    private static void wave(Game game, boolean more) {
        Creatures creatures = game.module(Creatures.class);
        LastStanding standing = game.module(LastStanding.class);
        if (creatures == null || standing == null) return;
        if (more) Match.say(game, Lines.moreDragons(game));
        int perTeam = game.rules().resolve(Scope.GAME, PER_TEAM);
        double height = game.rules().resolve(Scope.GAME, HEIGHT);
        for (Team team : game.roster().teams()) {
            if (standing.standing(team.id()) == 0) continue;
            Pos spawn = null;
            for (Slot seat : game.roster().seatsOf(team.id())) {
                if (seat.spawn() != null) {
                    spawn = seat.spawn();
                    break;
                }
            }
            if (spawn == null) continue;
            for (int i = 0; i < perTeam; i++) {
                creatures.dragon(team, spawn.add(0, height, 0));
                if (!more) Match.say(game, Lines.suddenDeathDragon(game, team));
            }
        }
        Match.each(game, p -> p.playSound(Lines.DRAGON_GROWL));
    }
}
