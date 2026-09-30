package tennacle.games;

import io.github.term4.polyp.api.event.projectile.PearlTeleportEvent;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.canvas.hologram.Hologram;
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
import tennacle.games.map.Building;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Figures standing at a map's points that a right click puts to work: shopkeepers. Scenery
 * ({@link WorldPolicy#INERT}), so no hit, shot, push or blast reaches them. Each faces its side's spawn and
 * turns to the nearest player it can see, at any distance (the user, 2026-09-28: no range, all the way round).
 */
public final class Keepers implements GameModule, Building.Reserves {

    /** What a right click on a keeper does. */
    @FunctionalInterface
    public interface Use {
        void use(@NotNull Game game, @NotNull Player player, @NotNull PointMarker at);
    }

    private record Post(MarkerKey<PointMarker> key, Supplier<? extends Entity> body, Use use,
                        Function<Game, List<Hologram.Line>> labels) {}

    private record Standing(PointMarker at, Use use) {}

    private static final String LOOK = "keepers:look";
    /** Blocks around a keeper, across and up, that a pearl may not land in; {@code 0} = none. */
    public static final StateKey<Double> SPACE = StateKey.of("keeper.space", Codec.DOUBLE, 1.5);
    /** A keeper turns to an invisible player too, giving them away; off (Hypixel), it never does. */
    public static final StateKey<Boolean> LOOK_INVISIBLE = StateKey.of("keeper.look.invisible", Codec.BOOLEAN, false);
    /** Blocks out around a keeper's feet that nobody builds in (captured: 1, a 3 x 3 column); {@code -1} = none. */
    public static final StateKey<Integer> RESERVE = StateKey.of("keeper.reserve", Codec.INT, 1);
    /** How far below and above a keeper's feet that column runs (captured: 5). */
    public static final StateKey<Integer> RESERVE_HEIGHT = StateKey.of("keeper.reserve.height", Codec.INT, 5);

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
        return at(key, body, use, game -> List.of());
    }

    /** {@link #at} with lines over it, each rising from the point it stands at. */
    public @NotNull Keepers at(@NotNull MarkerKey<PointMarker> key, @NotNull Supplier<? extends Entity> body, @NotNull Use use,
                               @NotNull Function<Game, List<Hologram.Line>> labels) {
        posts.add(new Post(key, body, use, labels));
        return this;
    }

    /** The keepers standing, with the point each stands at. */
    public @NotNull Map<Entity, PointMarker> standing() {
        Map<Entity, PointMarker> out = new IdentityHashMap<>();
        standing.forEach((body, post) -> out.put(body, post.at()));
        return out;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(SPACE, LOOK_INVISIBLE, RESERVE, RESERVE_HEIGHT);
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
                Hologram labels = Hologram.at(facing(at), Match.scenery(game));
                post.labels().apply(game).forEach(labels::line);
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
            game.state().alarms().arm(LOOK, tick + 1);
            look();
        });
        if (!standing.isEmpty()) game.state().alarms().arm(LOOK, game.tick() + 1);
        // captured: a pearl cannot land in a shopkeeper's space; the user: it is refused and handed back, no use spent
        game.on(PearlTeleportEvent.class, PearlTeleportEvent::shooter, e -> {
            if (!(e.shooter() instanceof Player thrower) || game.roster().slotOf(thrower) == null || !inSpace(e.target())) return;
            e.setCancelled(true);
            ItemStack pearl = ItemStack.of(Material.ENDER_PEARL);
            Kits kits = game.module(Kits.class);
            if (kits == null || !kits.give(thrower, null, pearl)) thrower.getInventory().addItemStack(pearl);
        });
    }

    /** Whether {@code at} lies within {@link #SPACE} of a keeper, across, or up from its feet. */
    public boolean inSpace(@NotNull Point at) {
        double space = game.rules().resolve(Scope.GAME, SPACE);
        if (space <= 0) return false;
        for (Standing post : standing.values()) {
            Point feet = post.at().point();
            if (Math.abs(at.x() - feet.x()) <= space && Math.abs(at.z() - feet.z()) <= space
                    && at.y() >= feet.y() - space && at.y() <= feet.y() + 2 + space) return true;
        }
        return false;
    }

    @Override
    public boolean reserves(@NotNull Point pos) {
        int reach = game != null ? game.rules().resolve(Scope.GAME, RESERVE) : -1;
        if (reach < 0) return false;
        int height = game.rules().resolve(Scope.GAME, RESERVE_HEIGHT);
        for (Standing post : standing.values()) {
            Point feet = post.at().point();
            if (Math.abs(pos.blockX() - feet.blockX()) <= reach && Math.abs(pos.blockZ() - feet.blockZ()) <= reach
                    && Math.abs(pos.blockY() - feet.blockY()) <= height) return true;
        }
        return false;
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
        double best = Double.MAX_VALUE;
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

    // Hypixel v1.11: never toward an invisible player, unless the rule says
    private boolean seen(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || game.isGhost(player)) return false;
        return game.rules().resolve(Scope.GAME, LOOK_INVISIBLE)
                || !player.isInvisible() && !player.hasEffect(PotionEffect.INVISIBILITY);
    }
}
