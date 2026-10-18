package tennacle;

import net.minestom.server.entity.Player;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;

/** A gap between a player's goes at something outside a game, kept on the player while they are online. */
public final class Cooldown {

    private final Tag<Long> last;
    private final long gapMillis;

    public Cooldown(@NotNull String id, long gapMillis) {
        this.last = Tag.Transient("tennacle:cooldown:" + id);
        this.gapMillis = gapMillis;
    }

    /** Whether {@code player} may go now, stamping the go when they may. */
    public boolean pass(@NotNull Player player) {
        long now = System.currentTimeMillis();
        Long before = player.getTag(last);
        if (before != null && now - before < gapMillis) return false;
        player.setTag(last, now);
        return true;
    }
}
