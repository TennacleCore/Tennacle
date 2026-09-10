package tennacle.games.watch;

import net.minestom.server.entity.Player;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;

/**
 * One right-click, however many packets it becomes. A 1.8 client aimed at a block, holding an item that does
 * nothing, sends the placement AND the use-item behind it: {@code PlayerControllerMP.onPlayerRightClick} returns
 * the item's own {@code onItemUse}, false for a feather or a compass, so {@code Minecraft.rightClickMouse} still
 * runs its {@code sendUseItem} fallback and queues a second placement at (-1,-1,-1). Both are main hand, so the
 * hand tells them apart from nothing. Aimed at open air the first half never happens, which is why the same click
 * behaves depending on where it points.
 *
 * <p>The client's own right-click delay is four ticks, so the two halves of one press always land in the same tick
 * and two presses never can.
 */
final class Press {

    private static final Tag<Long> LAST = Tag.Transient("tennacle:right-click");

    private Press() {}

    /** {@code false} = the tail of a press already taken this tick. */
    static boolean once(@NotNull Player who) {
        long now = who.getAliveTicks();
        Long last = who.getTag(LAST);
        if (last != null && last == now) return false;
        who.setTag(LAST, now);
        return true;
    }
}
