package tennacle.looks;

import tennacle.games.Match;
import tennacle.games.Matches;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Death cues by relation to the dead: {@link SoundSet#KILL} to the killer, {@link SoundSet#DIED} to the dead,
 * {@link SoundSet#ALLY_DOWN} to their teammates, {@link SoundSet#ENEMY_DOWN} to every other team - each first as
 * its {@code <cue>:<the dead's team>} variant, then plain, so a many-team look can tell whose side fell. The killer
 * is one of the dead's enemies too: a look with no credit cue (scrims, the house) still dings them on the fall. The
 * look maps a cue or leaves it silent; {@link GameSounds} decides whether a mapped one reaches the ear.
 */
public final class Deaths {

    private Deaths() {}

    public static void cue(@NotNull Game game, @NotNull Player victim, @Nullable Player killer) {
        cue(game, victim, killer, Matches.lookOf(game).sounds());
    }

    public static void cue(@NotNull Game game, @NotNull Player victim, @Nullable Player killer, @NotNull SoundSet sounds) {
        String team = Match.teamOf(game, victim);
        GameSounds.first(game, sounds, victim, SoundSet.DIED + ":" + team, SoundSet.DIED);
        for (Player member : game.members()) {
            if (member == victim) continue;
            String fell = Objects.equals(Match.teamOf(game, member), team) ? SoundSet.ALLY_DOWN : SoundSet.ENEMY_DOWN;
            if (member == killer) {
                GameSounds.first(game, sounds, member, SoundSet.KILL + ":" + team, SoundSet.KILL, fell + ":" + team, fell);
            } else {
                GameSounds.first(game, sounds, member, fell + ":" + team, fell);
            }
        }
    }
}
