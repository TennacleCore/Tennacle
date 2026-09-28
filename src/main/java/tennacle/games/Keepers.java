package tennacle.games;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Figures standing at a map's points that a right click puts to work: shopkeepers. Scenery
 * ({@link WorldPolicy#INERT}), so no hit, shot, push or blast reaches them. Each faces its side's spawn and
 * turns to the nearest player it can see.
 */
public final class Keepers implements GameModule {

    /** What a right click on a keeper does. */
    @FunctionalInterface
    public interface Use {
        void use(@NotNull Game game, @NotNull Player player, @NotNull PointMarker at);
    }

    private record Post(MarkerKey<PointMarker> key, Supplier<? extends Entity> body, Use use) {}

    private record Standing(PointMarker at, Use use) {}

    private static final double SIGHT = 8; // vanilla's look-at-player range
    private static final String LOOK = "keepers:look";

    private final List<Post> posts = new ArrayList<>();
    private final Map<Entity, Standing> standing = new IdentityHashMap<>();
    private final Map<Player, Long> used = new ConcurrentHashMap<>();
    private Game game;

    private Keepers() {}

    public static @NotNull Keepers of() {
        return new Keepers();
    }

    /** A {@code body} at every {@code key} point, doing {@code use} on a right click. */
    public @NotNull Keepers at(@NotNull MarkerKey<PointMarker> key, @NotNull Supplier<? extends Entity> body, @NotNull Use use) {
        posts.add(new Post(key, body, use));
        return this;
    }

    /** The keepers standing, with the point each stands at. */
    public @NotNull Map<Entity, PointMarker> standing() {
        Map<Entity, PointMarker> out = new IdentityHashMap<>();
        standing.forEach((body, post) -> out.put(body, post.at()));
        return out;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        for (Post post : posts) {
            for (PointMarker at : game.arena().markers(post.key())) {
                Entity body = post.body().get();
                body.setTag(WorldPolicy.INERT, true);
                body.setNoGravity(true);
                if (body instanceof LivingEntity living) living.setInvulnerable(true);
                standing.put(body, new Standing(at, post.use()));
                game.shard().spawn(body, facing(at));
            }
        }
        // one press, one use: a modern client sends a packet a hand, a legacy one INTERACT and INTERACT_AT
        game.on(PlayerEntityInteractEvent.class, e -> {
            Standing post = standing.get(e.getTarget());
            if (post == null || e.getHand() != PlayerHand.MAIN) return;
            Player player = e.getPlayer();
            Long last = used.put(player, game.tick());
            if (last != null && last == game.tick()) return;
            post.use().use(game, player, post.at());
        });
        game.state().alarms().register(LOOK, (tick, payload) -> {
            look();
            game.state().alarms().arm(LOOK, tick + 1);
        });
        if (!standing.isEmpty()) game.state().alarms().arm(LOOK, game.tick() + 1);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        used.remove(player);
    }

    // toward the side's spawn when it has one
    private Pos facing(PointMarker at) {
        Pos pos = new Pos(at.point().x(), at.point().y(), at.point().z());
        PosMarker spawn = at.group().isEmpty() ? null : game.arena().marker(Markers.SPAWN, at.group());
        return spawn != null ? pos.withLookAt(spawn.pos()).withPitch(0) : pos;
    }

    private void look() {
        standing.keySet().forEach(body -> {
            Player nearest = nearest(body);
            if (nearest != null) body.lookAt(nearest);
        });
    }

    private @Nullable Player nearest(Entity body) {
        Player nearest = null;
        double best = SIGHT * SIGHT;
        for (Player player : game.members()) {
            if (!seen(player) || player.getInstance() != body.getInstance()) continue;
            double distance = player.getPosition().distanceSquared(body.getPosition());
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    // Hypixel v1.11: never toward an invisible player
    private boolean seen(Player player) {
        return player.getGameMode() != GameMode.SPECTATOR && !game.isGhost(player)
                && !player.isInvisible() && !player.hasEffect(PotionEffect.INVISIBILITY);
    }
}
