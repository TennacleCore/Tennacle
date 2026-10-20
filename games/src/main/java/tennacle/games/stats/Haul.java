package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.api.event.container.ContainerChangeEvent;
import io.github.term4.polyp.api.event.container.ContainerOpenEvent;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Generators;
import tennacle.games.lives.Lives;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a mode's resources came from and went: generators, other players' drops, a kill's hand-over, chests (net per
 * visit), and what a death or a throw lost to the void. Installed after Spoils, so a death's hand-over has been taken.
 */
public final class Haul implements GameModule {

    private static final Tag<UUID> DROPPED_BY = Tag.Transient("tennacle:dropped-by");

    private record Visit(String key, Map<String, Integer> taken) {}

    private final Map<Material, String> resources;
    private final Map<UUID, Visit> visits = new ConcurrentHashMap<>();
    private Game game;
    private @Nullable Generators generators;

    private Haul(Map<Material, String> resources) {
        this.resources = Map.copyOf(resources);
    }

    /** {@code resources}: each counted item and its name in the stats ({@code iron-from-generators}). */
    public static @NotNull Haul of(@NotNull Map<Material, String> resources) {
        return new Haul(resources);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        generators = game.module(Generators.class);
        Lives lives = game.module(Lives.class);
        if (lives != null) lives.onDeath(death -> lost(death.victim()));
        game.on(ItemSpawnEvent.class, ItemSpawnEvent::player, e -> {
            boolean thrown = e.cause() == ItemSpawnEvent.Cause.PLAYER_DROP || e.cause() == ItemSpawnEvent.Cause.DEATH_DROP;
            if (thrown && e.player() != null) e.item().setTag(DROPPED_BY, e.player().getUuid());
        });
        // Polyp's pickup runs first and cancels a full inventory: this one landed
        game.on(PickupItemEvent.class, PickupItemEvent::getLivingEntity, e -> {
            if (!(e.getLivingEntity() instanceof Player picker)) return;
            String name = name(e.getItemStack());
            if (name == null) return;
            ItemEntity item = e.getItemEntity();
            UUID dropper = item.getTag(DROPPED_BY);
            if (generators != null && generators.made(item) != null) add(picker, name + "-from-generators", e.getItemStack().amount());
            else if (dropper != null && !dropper.equals(picker.getUuid())) add(picker, name + "-from-players", e.getItemStack().amount());
        });
        game.on(ContainerOpenEvent.class, e -> {
            if (!e.isCancelled()) visits.put(e.getPlayer().getUuid(), new Visit(e.key(), new HashMap<>()));
        });
        game.on(ContainerChangeEvent.class, e -> visitor(e.key()), this::changed);
        game.on(InventoryCloseEvent.class, e -> leave(e.getPlayer()));
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        leave(player);
    }

    /** A generator's share, handed straight into a teammate's inventory. */
    public static void generated(@NotNull Game game, @NotNull Player player, @NotNull ItemStack stack) {
        Haul haul = game.module(Haul.class);
        String name = haul != null ? haul.name(stack) : null;
        if (name != null) haul.add(player, name + "-from-generators", stack.amount());
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

    // a container one visitor has open; several at once can't be told apart
    private @Nullable Player visitor(String key) {
        Player only = null;
        for (Map.Entry<UUID, Visit> visit : visits.entrySet()) {
            if (!visit.getValue().key().equals(key)) continue;
            if (only != null) return null;
            only = game.firstMember(m -> m.getUuid().equals(visit.getKey()));
        }
        return only;
    }

    private void changed(ContainerChangeEvent e) {
        Player visitor = visitor(e.key());
        Visit visit = visitor != null ? visits.get(visitor.getUuid()) : null;
        if (visit == null) return;
        ItemStack before = e.before(), after = e.after();
        String was = name(before), now = name(after);
        if (was != null) visit.taken().merge(was, before.amount(), Integer::sum);
        if (now != null) visit.taken().merge(now, -after.amount(), Integer::sum);
    }

    private void leave(Player player) {
        Visit visit = visits.remove(player.getUuid());
        if (visit == null) return;
        visit.taken().forEach((name, net) -> {
            if (net > 0) add(player, name + "-from-chests", net);
        });
    }

    private @Nullable String name(ItemStack stack) {
        return stack.isAir() ? null : resources.get(stack.material());
    }

    private void add(Player player, String stat, long n) {
        Tally.add(game, player.getUuid(), stat, n);
    }
}
