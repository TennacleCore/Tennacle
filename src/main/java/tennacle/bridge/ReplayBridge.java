package tennacle.bridge;

import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.projectile.ProjectileHitEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.util.Directions;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.EventDispatcher;
import io.github.term4.archipelago.replay.core.CoreChannels;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

/**
 * Template glue between Polyp and archipelago-replay - everything mechanics-flavored a replay needs,
 * contributed purely via {@link Replay} registration (an application using both libraries copies this class).
 * Playback is puppet-only; the save/load revivers ({@code polyp:item}/{@code polyp:projectile}) are registered
 * separately for shard saves.
 */
public final class ReplayBridge {

    private static boolean installed;

    private ReplayBridge() {}

    public static void install() {
        CoreChannels.register();
        if (installed) return;
        installed = true;

        // the twin wire is generation-split (legacy viewers also get per-tick anchors and pins)
        Replay.viewerProtocol(p -> Polyp.getInstance().clientInfo().getProtocol(p));

        // launches recorded at end of THIS tick (the stamp exists, nothing can hit before its first move tick) -
        // a per-tick sampler alone misses projectiles that hit and remove themselves within one tick. The ASYNC
        // world spawn may not have landed by then (no instance/position yet), so the spawn pos comes from the event.
        Replay.dispatchers(() -> new EventDispatcher() {
            @Override
            protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
                node.addListener(ProjectileLaunchEvent.class, e -> {
                    ProjectileEntity projectile = e.projectile();
                    Entity launcher = projectile.getShooter();
                    if (launcher == null || !rec.covers(launcher)) return;
                    String shooter = launcher instanceof Player p ? p.getUsername() : "";
                    MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                        // a point-blank impact removes the projectile within its launch tick - still footage
                        // (the removal sweep kills the twin next tick); only a CANCELLED launch never existed
                        if (rec.active() && !e.isCancelled()) {
                            // the launch path spawns at spawnPos withView(velocity) - record the same rotation
                            Vec vel = e.velocity();
                            rec.spawn(projectile, e.spawnPos().withView(Directions.yaw(vel), Directions.pitch(vel)),
                                    shooter);
                        }
                    });
                });

                // impact-time removal: the per-tick sweep is a tick late - the twin sails through the mannequin
                // (or past the wall) before vanishing. Arrows that stick aren't removed and keep their track.
                node.addListener(ProjectileHitEvent.class, e -> {
                    ProjectileEntity projectile = e.projectile();
                    Point hit = e.hitPoint();
                    MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                        if (!rec.active() || !projectile.isRemoved()) return;
                        Pos last = projectile.getPosition();
                        rec.remove(projectile, new Pos(hit.x(), hit.y(), hit.z(), last.yaw(), last.pitch()), "");
                    });
                });
            }
        });
    }
}
