package tennacle.games.practice;

import io.github.term4.canvas.npc.PlayerBody;
import io.github.term4.polyp.mechanics.mobs.MobEntity;
import io.github.term4.polyp.mechanics.mobs.MobsSystem;
import io.github.term4.polyp.tracking.motion.MotionTracker;
import io.github.term4.polyp.tracking.motion.TrackedVelocity;
import net.minestom.server.coordinate.Pos;
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
 * packet would; its position goes out as a 1.8 tracker relays a player's, every other tick.
 *
 * <p>The velocity a hit on it reads is what a 1.8 server tracks for a player, not its motion: the server moves a
 * player with no input and restores the pre-hit velocity after melee knockback, so only the takeoff counts. The first
 * rising move off the ground calls {@code jump()} server-side (NetHandlerPlayServer.processPlayer): 0.42 up, and the
 * sprint push if sprinting, then gravity and friction, with no re-seed until it lands; the server's own floor probe
 * lands that arc first when it would collide ({@link MotionTracker#bodyMotY}).
 */
final class Bot extends MobEntity implements TrackedVelocity {

    static final String NAME = "Bot";

    // EntityTracker.trackEntity: a player relays every 2 ticks
    private static final int RELAY_TICKS = 2;

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
    private boolean launched, impulse;
    private double trackedY;
    private Vec trackedH = Vec.ZERO;

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
        Vec m = motion().withY(rise());
        if (isSprinting()) m = m.add(push());
        motion(m);
    }

    // EntityPlayer.attackTargetEntityWithCurrentItem: a sprint or Knockback click on a player cuts the clicker's motion
    void cut() {
        motion(motion().mul(0.6, 1, 0.6));
    }

    void stop() {
        late.clear();
        launched = false;
        trackedY = 0;
        trackedH = Vec.ZERO;
        motion(Vec.ZERO);
    }

    @Override
    protected void travel(float strafe, float forward) {
        boolean ground = isOnGround();
        double from = getPosition().y();
        float friction = ground ? frictionBelow() * 0.91f : 0.91f;
        super.travel(strafe, forward);
        double dy = getPosition().y() - from;
        trackedH = new Vec(small(trackedH.x() * friction), 0, small(trackedH.z() * friction));
        trackedY = MotionTracker.bodyMotY(this, trackedY, isOnGround());
        if (isOnGround()) launched = false;
        else if (!launched && dy > 0) {
            launched = true;
            trackedY = rise();
            if (isSprinting()) trackedH = trackedH.add(push());
        }
    }

    @Override
    public @NotNull Vec trackedVelocity() {
        return new Vec(trackedH.x(), trackedY, trackedH.z());
    }

    @Override
    public void setVelocity(@NotNull Vec velocity) {
        long at = lag.down(clock.getAsLong());
        if (at <= clock.getAsLong() && late.isEmpty()) land(velocity);
        else late.add(new Late(at, velocity));
    }

    private void land(Vec velocity) {
        super.setVelocity(velocity);
        impulse = true;
        brain.knocked(this);
    }

    // off the beat for a knockback, as EntityLiving's isAirBorne makes the tracker look
    @Override
    public void refreshPosition(@NotNull Pos newPosition, boolean ignoreView, boolean sendPackets) {
        boolean send = sendPackets && (impulse || getAliveTicks() % RELAY_TICKS == 0);
        if (send) impulse = false;
        super.refreshPosition(newPosition, ignoreView, send);
    }

    private double rise() {
        int boost = getEffectLevel(PotionEffect.JUMP_BOOST);
        return 0.42 + (boost >= 0 ? (boost + 1) * 0.1 : 0);
    }

    private Vec push() {
        double yaw = Math.toRadians(yaw());
        return new Vec(-Math.sin(yaw) * 0.2, 0, Math.cos(yaw) * 0.2);
    }

    private static double small(double v) {
        return Math.abs(v) < 0.005 ? 0 : v;
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
