package tennacle.games.practice;

import io.github.term4.canvas.npc.PlayerBody;
import io.github.term4.polyp.mechanics.mobs.MobEntity;
import io.github.term4.polyp.mechanics.mobs.MobsSystem;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.avatar.AvatarMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * A player-shaped sparring partner on 1.8 player movement: W toward its target at the attribute speed (sprint
 * included), 0.026 air steering while sprinting. Its knockback lands {@link #latency} ticks late, as a client's
 * velocity packet would.
 */
final class Bot extends MobEntity {

    static final String NAME = "Bot";

    private record Late(long at, Vec velocity) {}

    private final Consumer<Bot> brain;
    private final ArrayDeque<Late> late = new ArrayDeque<>();
    private long ticks;
    int latency;

    Bot(MobsSystem mobs, Consumer<Bot> brain) {
        super(mobs, EntityType.PLAYER);
        this.brain = brain;
        ((AvatarMeta) getEntityMeta()).setDisplayedSkinParts((byte) 0x7F);
    }

    @Override
    protected void goals() {}

    @Override
    protected void livingTick() {
        ticks++;
        while (!late.isEmpty() && late.peek().at() <= ticks) super.setVelocity(late.poll().velocity());
        super.livingTick();
    }

    @Override
    protected void actionState() {
        brain.accept(this);
    }

    /** One tick of held W, or none. */
    void walk(boolean forward) {
        aiMoveSpeed(forward ? (float) getAttributeValue(Attribute.MOVEMENT_SPEED) : 0f);
        moveForward(forward ? 1.0f : 0.0f);
        jumpMovementFactor = isSprinting() ? 0.026f : 0.02f;
    }

    void stop() {
        late.clear();
        motion(Vec.ZERO);
    }

    @Override
    public void setVelocity(@NotNull Vec velocity) {
        if (latency <= 0) super.setVelocity(velocity);
        else late.add(new Late(ticks + latency, velocity));
    }

    @Override
    public void updateNewViewer(@NotNull Player viewer) {
        PlayerBody.show(viewer, getUuid(), NAME, null, true);
        super.updateNewViewer(viewer);
    }

    @Override
    public void updateOldViewer(@NotNull Player viewer) {
        super.updateOldViewer(viewer);
        PlayerBody.hide(viewer, getUuid());
    }
}
