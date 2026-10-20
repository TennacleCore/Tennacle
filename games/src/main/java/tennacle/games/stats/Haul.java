package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
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
 * Where a mode's resources came from and went: generators, other players' drops, a kill's hand-over, chests, and what
 * a death or a throw lost to the void. Installed after Spoils, so a death's hand-over has been taken.
 *
 * <p>A chest visit is read off the visitor's own inventory, opened to closed, less what reached or left them some other
 * way meanwhile: a shared chest's contents can't say whose click moved them.
 */
public final class Haul implements GameModule {

    private static final Tag<UUID> DROPPED_BY = Tag.Transient("tennacle:dropped-by");

    private record Visit(Map<String, Integer> held, Map<String, Integer> elsewhere) {}

    /** Whose generator a pickup came from, in the mode's own terms: a base's forge, a side's diamonds. */
    @FunctionalInterface
    public interface Origins {
        void picked(@NotNull Game game, @NotNull Player picker, @NotNull Generators.Generator generator,
                    @NotNull String resource, int amount);
    }

    private final Map<Material, String> resources;
    private @Nullable Origins origins;
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

    public @NotNull Haul origins(@NotNull Origins origins) {
        this.origins = origins;
        return this;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        generators = game.module(Generators.class);
        Lives lives = game.module(Lives.class);
        if (lives != null) lives.onDeath(death -> lost(death.victim()));
        game.on(ItemSpawnEvent.class, ItemSpawnEvent::player, e -> {
            boolean thrown = e.cause() == ItemSpawnEvent.Cause.PLAYER_DROP || e.cause() == ItemSpawnEvent.Cause.DEATH_DROP;
            if (!thrown || e.player() == null) return;
            e.item().setTag(DROPPED_BY, e.player().getUuid());
            String name = name(e.item().getItemStack());
            if (name != null) elsewhere(e.player(), name, -e.item().getItemStack().amount());
        });
        // Polyp's pickup runs first and cancels a full inventory: this one landed
        game.on(PickupItemEvent.class, PickupItemEvent::getLivingEntity, e -> {
            if (!(e.getLivingEntity() instanceof Player picker)) return;
            String name = name(e.getItemStack());
            if (name == null) return;
            ItemEntity item = e.getItemEntity();
            UUID dropper = item.getTag(DROPPED_BY);
            int amount = e.getItemStack().amount();
            elsewhere(picker, name, amount);
            Generators.Generator made = generators != null ? generators.made(item) : null;
            if (made != null) generated(picker, made, name, amount);
            else if (dropper != null && !dropper.equals(picker.getUuid())) add(picker, name + "-from-players", amount);
        });
        game.on(ContainerOpenEvent.class, e -> {
            if (!e.isCancelled()) visits.put(e.getPlayer().getUuid(), new Visit(held(e.getPlayer()), new ConcurrentHashMap<>()));
        });
        game.on(InventoryCloseEvent.class, e -> leave(e.getPlayer()));
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        leave(player);
    }

    /** A generator's share, handed straight into a teammate's inventory. */
    public static void generated(@NotNull Game game, @NotNull Player player, @NotNull Generators.Generator generator,
                                 @NotNull ItemStack stack) {
        Haul haul = game.module(Haul.class);
        String name = haul != null ? haul.name(stack) : null;
        if (name == null) return;
        haul.elsewhere(player, name, stack.amount());
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
        haul.elsewhere(killer, name, stack.amount());
        haul.elsewhere(victim, name, -stack.amount());
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

    private Map<String, Integer> held(Player player) {
        Map<String, Integer> held = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) count(held, inv.getItemStack(slot));
        count(held, inv.getCursorItem());
        return held;
    }

    private void count(Map<String, Integer> held, ItemStack stack) {
        String name = name(stack);
        if (name != null) held.merge(name, stack.amount(), Integer::sum);
    }

    // a change to their inventory during a visit that no chest made
    private void elsewhere(Player player, String name, int amount) {
        Visit visit = visits.get(player.getUuid());
        if (visit != null) visit.elsewhere().merge(name, amount, Integer::sum);
    }

    private void leave(Player player) {
        Visit visit = visits.remove(player.getUuid());
        if (visit == null) return;
        Map<String, Integer> now = held(player);
        for (String name : resources.values()) {
            int net = now.getOrDefault(name, 0) - visit.held().getOrDefault(name, 0) - visit.elsewhere().getOrDefault(name, 0);
            if (net > 0) add(player, name + "-from-chests", net);
            else if (net < 0) add(player, name + "-into-chests", -net);
        }
    }

    private @Nullable String name(ItemStack stack) {
        return stack.isAir() ? null : resources.get(stack.material());
    }

    private void add(Player player, String stat, long n) {
        Tally.add(game, player.getUuid(), stat, n);
    }
}
