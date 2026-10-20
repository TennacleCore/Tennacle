package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.polyp.api.event.explosion.ExplosionEvent;
import net.minestom.server.MinecraftServer;
import io.github.term4.polyp.api.event.container.ContainerChangeEvent;
import io.github.term4.polyp.api.event.container.ContainerOpenEvent;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Generators;
import tennacle.games.lives.Lives;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a mode's resources came from and went: generators, other players' drops, a kill's hand-over, chests, and what
 * a death or a throw lost to the void. Installed after Spoils, so a death's hand-over has been taken.
 *
 * <p>A chest counts by the chest's own slots, each change the visitor's whose click made it that tick: a click fires its
 * pre-click event and changes the slots in the same tick, on a 1.8 client's path too. A visit ends at its close, the
 * next chest opened, a death, a leave or the game's end, whichever comes first, and counts net.
 */
public final class Haul implements GameModule, Tally.Closes {

    private static final Tag<UUID> DROPPED_BY = Tag.Transient("tennacle:dropped-by");
    private static final Tag<Boolean> PICKED = Tag.Transient("tennacle:picked");

    private record Visit(String key, @Nullable Chest chest, Map<String, Integer> net) {}

    /** Which chest a visit was, for the split ({@code iron-from-team-chest}); {@code team} its side when another's. */
    public record Chest(@NotNull String name, @Nullable String team) {}

    /** Whose generator a pickup came from, in the mode's own terms: a base's forge, a side's diamonds. */
    @FunctionalInterface
    public interface Origins {
        void picked(@NotNull Game game, @NotNull Player picker, @NotNull Generators.Generator generator,
                    @NotNull String resource, int amount);

        /** {@code by}'s blast took items lying at {@code generator}. */
        default void destroyed(@NotNull Game game, @NotNull Player by, @NotNull Generators.Generator generator,
                               @NotNull String resource, int amount) {}

        /** The chest {@code visitor} opened at {@code pos}; null leaves it in the totals alone. */
        default @Nullable Chest chest(@NotNull Game game, @NotNull Player visitor, @NotNull Point pos) {
            return null;
        }
    }

    private final Map<Material, String> resources;
    private @Nullable Origins origins;
    private final Map<UUID, Visit> visits = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clicked = new ConcurrentHashMap<>();
    private Game game;
    private @Nullable Generators generators;

    private Haul(Map<Material, String> resources) {
        this.resources = Map.copyOf(resources);
    }

    /** {@code resources}: each counted item and its name in the stats ({@code iron-from-generators}). */
    public static @NotNull Haul of(@NotNull Map<Material, String> resources) {
        return new Haul(resources);
    }

    public @NotNull Haul origins(@NotNull Origins origins) {
        this.origins = origins;
        return this;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        generators = game.module(Generators.class);
        Lives lives = game.module(Lives.class);
        if (lives != null) lives.onDeath(death -> {
            lost(death.victim());
            leave(death.victim().getUuid());
        });
        game.on(ItemSpawnEvent.class, ItemSpawnEvent::player, e -> {
            boolean thrown = e.cause() == ItemSpawnEvent.Cause.PLAYER_DROP || e.cause() == ItemSpawnEvent.Cause.DEATH_DROP;
            if (!thrown || e.player() == null) return;
            e.item().setTag(DROPPED_BY, e.player().getUuid());
        });
        // Polyp's pickup runs first and cancels a full inventory: this one landed
        game.on(PickupItemEvent.class, PickupItemEvent::getLivingEntity, e -> {
            if (!(e.getLivingEntity() instanceof Player picker)) return;
            String name = name(e.getItemStack());
            if (name == null) return;
            ItemEntity item = e.getItemEntity();
            item.setTag(PICKED, true);
            UUID dropper = item.getTag(DROPPED_BY);
            int amount = e.getItemStack().amount();
            Generators.Generator made = generators != null ? generators.made(item) : null;
            if (made != null) generated(picker, made, name, amount);
            else if (dropper != null && !dropper.equals(picker.getUuid())) add(picker, name + "-from-players", amount);
        });
        game.on(ContainerOpenEvent.class, e -> {
            if (e.isCancelled()) return;
            UUID id = e.getPlayer().getUuid();
            Visit open = visits.get(id);
            if (open != null && open.key().equals(e.key())) return; // a 1.8 right-click opens twice: one visit
            leave(id);
            Chest chest = origins != null ? origins.chest(game, e.getPlayer(), e.pos()) : null;
            visits.put(id, new Visit(e.key(), chest, new ConcurrentHashMap<>()));
        });
        game.on(InventoryPreClickEvent.class, e -> clicked.put(e.getPlayer().getUuid(), game.tick()));
        game.onWorld(ContainerChangeEvent.class, ContainerChangeEvent::world, this::changed);
        game.on(InventoryCloseEvent.class, e -> leave(e.getPlayer().getUuid()));
        // a blast takes items once their health runs out, and says nothing: what it hit and is gone by the tick's end
        game.onWorld(ExplosionEvent.class, ExplosionEvent::world, e -> {
            if (e.isCancelled()) return;
            Player by = e.source() instanceof Player p ? p : FriendlyFire.attributed(e.source());
            List<ItemEntity> hit = new ArrayList<>();
            for (ExplosionEvent.Target target : e.targets()) {
                if (target.entity() instanceof ItemEntity item && name(item.getItemStack()) != null) hit.add(item);
            }
            if (!hit.isEmpty()) MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                for (ItemEntity item : hit) {
                    if (item.isRemoved() && !Boolean.TRUE.equals(item.getTag(PICKED))) destroyed(by, item);
                }
            });
        });
    }

    private void destroyed(@Nullable Player by, ItemEntity item) {
        String name = name(item.getItemStack());
        int amount = item.getItemStack().amount();
        UUID dropper = item.getTag(DROPPED_BY);
        if (dropper != null) Tally.add(game, dropper, name + "-lost-to-explosions", amount);
        if (by == null || game.roster().slotOf(by) == null) return;
        add(by, name + "-destroyed", amount);
        Generators.Generator made = generators != null ? generators.made(item) : null;
        if (made != null && origins != null) origins.destroyed(game, by, made, name, amount);
    }

    @Override
    public void close() {
        List.copyOf(visits.keySet()).forEach(this::leave);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        leave(player.getUuid());
    }

    /** A generator's share, handed straight into a teammate's inventory. */
    public static void generated(@NotNull Game game, @NotNull Player player, @NotNull Generators.Generator generator,
                                 @NotNull ItemStack stack) {
        Haul haul = game.module(Haul.class);
        String name = haul != null ? haul.name(stack) : null;
        if (name == null) return;
        haul.generated(player, generator, name, stack.amount());
    }

    private void generated(Player player, Generators.Generator generator, String name, int amount) {
        add(player, name + "-from-generators", amount);
        if (origins != null) origins.picked(game, player, generator, name, amount);
    }

    /** A kill's hand-over: the killer's stolen, the victim's lost to players. */
    public static void handed(@NotNull Game game, @NotNull Player victim, @NotNull Player killer, @NotNull ItemStack stack) {
        Haul haul = game.module(Haul.class);
        String name = haul != null ? haul.name(stack) : null;
        if (name == null) return;
        haul.add(killer, name + "-stolen", stack.amount());
        haul.add(victim, name + "-lost-to-players", stack.amount());
    }

    /** An item gone off the map: a player's throw is that player's loss to the void. */
    public static void offMap(@NotNull Game game, @NotNull Entity entity) {
        Haul haul = game.module(Haul.class);
        if (haul == null || !(entity instanceof ItemEntity item)) return;
        UUID dropper = item.getTag(DROPPED_BY);
        String name = haul.name(item.getItemStack());
        if (dropper != null && name != null) Tally.add(game, dropper, name + "-lost-to-void", item.getItemStack().amount());
    }

    // what the hand-over left them still carried
    private void lost(Player victim) {
        PlayerInventory inv = victim.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) lostStack(victim, inv.getItemStack(slot));
        lostStack(victim, inv.getCursorItem());
    }

    private void lostStack(Player victim, ItemStack stack) {
        String name = name(stack);
        if (name != null) add(victim, name + "-lost-to-void", stack.amount());
    }

    // the one visitor of this chest who clicked this tick; two at once can't be told apart
    private void changed(ContainerChangeEvent e) {
        UUID by = null;
        for (Map.Entry<UUID, Visit> visit : visits.entrySet()) {
            if (!visit.getValue().key().equals(e.key()) || !Long.valueOf(game.tick()).equals(clicked.get(visit.getKey()))) continue;
            if (by != null) return;
            by = visit.getKey();
        }
        if (by == null) return;
        Map<String, Integer> net = visits.get(by).net();
        String was = name(e.before()), now = name(e.after());
        if (was != null) net.merge(was, e.before().amount(), Integer::sum);
        if (now != null) net.merge(now, -e.after().amount(), Integer::sum);
    }

    private void leave(UUID player) {
        Visit visit = visits.remove(player);
        if (visit == null) return;
        Chest chest = visit.chest();
        visit.net().forEach((name, net) -> {
            if (net == 0) return;
            String way = net > 0 ? "-from-" : "-into-";
            long amount = Math.abs(net);
            Tally.add(game, player, name + way + "chests", amount);
            if (chest == null) return;
            Tally.add(game, player, name + way + chest.name(), amount);
            if (chest.team() != null) Tally.pair(game, player, chest.team(), name + way + "their-chest", amount);
        });
    }

    private @Nullable String name(ItemStack stack) {
        return stack.isAir() ? null : resources.get(stack.material());
    }

    private void add(Player player, String stat, long n) {
        Tally.add(game, player.getUuid(), stat, n);
    }
}
