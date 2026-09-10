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
 * <p>The two halves arrive a millisecond or two apart but land a tick apart about as often as not - the entity
 * ticks between the packets - so the tick alone cannot tell them apart. The gap can: the client's own
 * {@code rightClickDelayTimer} is four ticks, so a second press is never nearer than that, and a tail never
 * further than one.
 */
final class Press {

    private static final Tag<Long> LAST = Tag.Transient("tennacle:right-click");
    private static final long TAIL = 1;

    private Press() {}

    /** {@code false} = the tail of a press already taken. */
    static boolean once(@NotNull Player who) {
        long now = who.getAliveTicks();
        Long last = who.getTag(LAST);
        if (last != null && now - last <= TAIL) return false; // a tail leaves the head's tick standing
        who.setTag(LAST, now);
        return true;
    }
}
