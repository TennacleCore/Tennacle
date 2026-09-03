package tennacle.games;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Death cues by relation to the dead: {@link SoundSet#KILL} to the killer, {@link SoundSet#DIED} to the dead,
 * {@link SoundSet#ALLY_DOWN} to their teammates, {@link SoundSet#ENEMY_DOWN} to every other team. A look maps
 * the cues it wants; an unmapped cue stays silent.
 */
public final class Deaths {

    private Deaths() {}

    public static void cue(@NotNull Game game, @NotNull Player victim, @Nullable Player killer) {
        cue(game, victim, killer, Matches.lookOf(game).sounds());
    }

    public static void cue(@NotNull Game game, @NotNull Player victim, @Nullable Player killer, @NotNull SoundSet sounds) {
        String team = Match.teamOf(game, victim);
        sounds.play(victim, SoundSet.DIED);
        if (killer != null && killer != victim) sounds.play(killer, SoundSet.KILL);
        for (Player member : game.members()) {
            if (member == victim || member == killer) continue;
            sounds.play(member, Objects.equals(Match.teamOf(game, member), team) ? SoundSet.ALLY_DOWN : SoundSet.ENEMY_DOWN);
        }
    }
}
