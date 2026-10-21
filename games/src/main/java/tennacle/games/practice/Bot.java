package tennacle.games.practice;

import io.github.term4.canvas.npc.PlayerBody;
import io.github.term4.polyp.mechanics.mobs.MobEntity;
import io.github.term4.polyp.mechanics.mobs.MobsSystem;
import io.github.term4.polyp.tracking.motion.TrackedVelocity;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.avatar.AvatarMeta;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * A player-shaped sparring partner on 1.8 player movement: W toward its target at the attribute speed (sprint
 * included), 0.026 air steering while sprinting. Its knockback reaches it over {@link Lag}, as a client's velocity
 * packet would.
 */
final class Bot extends MobEntity implements TrackedVelocity {

    static final String NAME = "Bot";

    interface Brain {
        void think(Bot bot);

        /** The bot's client just took a knockback: its next tick is tick 0. */
        void knocked(Bot bot);
    }

    private record Late(long at, Vec velocity) {}

    private final Brain brain;
    private final Lag lag;
    private final LongSupplier clock;
    private final ArrayDeque<Late> late = new ArrayDeque<>();
    // the melee knockback in the motion, which a server never tracks for a player (vanilla restores the pre-hit velocity)
    private Vec kick = Vec.ZERO;

    Bot(MobsSystem mobs, Brain brain, Lag lag, LongSupplier clock) {
        super(mobs, EntityType.PLAYER);
        this.brain = brain;
        this.lag = lag;
        this.clock = clock;
        ((AvatarMeta) getEntityMeta()).setDisplayedSkinParts((byte) 0x7F);
    }

    @Override
    protected void goals() {}

    @Override
    protected void livingTick() {
        long now = clock.getAsLong();
        while (!late.isEmpty() && late.peek().at() <= now) land(late.poll().velocity());
        super.livingTick();
    }

    @Override
    protected void actionState() {
        brain.think(this);
    }

    /** One tick of held W, or none. */
    void walk(boolean forward) {
        aiMoveSpeed(forward ? (float) getAttributeValue(Attribute.MOVEMENT_SPEED) : 0f);
        moveForward(forward ? 1.0f : 0.0f);
        jumpMovementFactor = isSprinting() ? 0.026f : 0.02f;
    }

    // EntityLivingBase.jump: the rise, and the sprint push the way it faces
    void jumpNow() {
        double up = 0.42;
        int boost = getEffectLevel(PotionEffect.JUMP_BOOST);
        if (boost >= 0) up += (boost + 1) * 0.1;
        Vec m = motion().withY(up);
        if (isSprinting()) {
            double yaw = Math.toRadians(yaw());
            m = m.add(-Math.sin(yaw) * 0.2, 0, Math.cos(yaw) * 0.2);
        }
        motion(m);
        kick = kick.withY(0);
    }

    // EntityPlayer.attackTargetEntityWithCurrentItem: a sprint or Knockback click on a player cuts the clicker's motion
    void cut() {
        motion(motion().mul(0.6, 1, 0.6));
        kick = kick.mul(0.6, 1, 0.6);
    }

    void stop() {
        late.clear();
        kick = Vec.ZERO;
        motion(Vec.ZERO);
    }

    @Override
    protected void travel(float strafe, float forward) {
        float friction = isOnGround() ? frictionBelow() * 0.91f : 0.91f;
        super.travel(strafe, forward);
        kick = new Vec(kick.x() * friction, collidedVertically ? 0 : kick.y() * 0.98, kick.z() * friction);
    }

    @Override
    public @NotNull Vec trackedVelocity() {
        return motion().sub(kick);
    }

    @Override
    public void setVelocity(@NotNull Vec velocity) {
        long at = lag.down(clock.getAsLong());
        if (at <= clock.getAsLong() && late.isEmpty()) land(velocity);
        else late.add(new Late(at, velocity));
    }

    private void land(Vec velocity) {
        Vec before = motion();
        super.setVelocity(velocity);
        kick = motion().sub(before);
        brain.knocked(this);
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
