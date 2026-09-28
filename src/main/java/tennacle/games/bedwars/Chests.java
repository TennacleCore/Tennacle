package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.Generators;
import tennacle.games.Match;
import tennacle.games.beds.Lives;

import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.container.ContainerInsertEvent;
import io.github.term4.polyp.api.event.container.ContainerOpenEvent;
import io.github.term4.polyp.mechanics.containers.ContainerKey;
import io.github.term4.polyp.mechanics.containers.ContainerSystem;
import io.github.term4.polyp.mechanics.containers.ContainersConfigResolver.ResolvedContainer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A base's chests (captured): the team chest, shut to every other side while its own stands, and each player's ender
 * chest. Polyp's containers hold both; this adds the lock, a punch putting the held stack in, the side's upgrades
 * reaching what it stored, and a final death's ender chest spilled at the side's forge.
 */
final class Chests implements GameModule {

    /** Each store and the seat whose stamps it takes: the side's first for a team chest, the owner's for an ender chest. */
    private final Map<String, Slot> stores = new ConcurrentHashMap<>();
    private final Map<String, String> enders = new ConcurrentHashMap<>();
    private Game game;
    private ContainerSystem containers;
    private LastStanding standing;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        containers = Mechanics.containers;
        standing = game.module(LastStanding.class);
        Lives lives = game.module(Lives.class);
        if (containers == null || standing == null || lives == null) {
            throw new IllegalStateException("Chests needs the container system, and LastStanding and Lives installed before it");
        }
        game.on(ContainerOpenEvent.class, e -> {
            Team owner = locked(e.getPlayer(), e.pos(), e.key());
            if (owner == null) return;
            e.setCancelled(true);
            e.getPlayer().sendMessage(Lines.chestLocked(game, owner));
        });
        game.on(ContainerInsertEvent.class, e -> {
            if (Gear.kept(e.item())) e.setCancelled(true);
        });
        game.on(PlayerStartDiggingEvent.class, e -> punch(e.getPlayer(), e.getBlockPosition()));
        for (StateKey<?> key : BedWars.KIT.stampReads()) game.rules().watch(key, (scope, value) -> restamp(scope));
        lives.onDeath(death -> {
            if (death.last()) spill(death.victim(), death.seat().team());
        });
    }

    // another side's team chest while that side stands; files the store under its seat either way
    private @Nullable Team locked(Player player, Point pos, String key) {
        Slot seat = game.roster().slotOf(player);
        if (personal(player, pos)) {
            if (seat != null) {
                stores.put(key, seat);
                enders.put(player.getUsername(), key);
            }
            return null;
        }
        String owner = ownerOf(pos);
        if (owner == null) return null;
        List<Slot> seats = game.roster().seatsOf(owner);
        if (!seats.isEmpty()) stores.put(key, seats.getFirst());
        boolean theirs = seat != null && seat.team().equals(owner);
        return theirs || !standing.stands(owner) ? null : game.roster().team(owner);
    }

    private boolean personal(Player player, Point pos) {
        ResolvedContainer kind = containers.kindAt(game.shard(), pos, game.shard().getBlock(pos), player);
        return kind != null && kind.key() == ContainerKey.VIEWER;
    }

    private @Nullable String ownerOf(Point pos) {
        for (Team team : game.roster().teams()) {
            RegionMarker base = game.arena().marker(BedWars.BASE, team.id());
            if (base != null && base.contains(pos)) return team.id();
        }
        return null;
    }

    // captured: the held stack goes in, every stack like it on a sneak; a sword or bow keeps the last of its kind back
    private void punch(Player player, Point pos) {
        ItemStack held = player.getItemInMainHand();
        if (held.isAir() || game.roster().slotOf(player) == null || game.isGhost(player)) return;
        ResolvedContainer kind = containers.kindAt(game.shard(), pos, game.shard().getBlock(pos), player);
        String key = kind != null ? containers.keyAt(game.shard(), pos, player) : null;
        if (key == null) return;
        Team owner = locked(player, pos, key);
        if (owner != null) {
            player.sendMessage(Lines.depositLocked(game, owner));
            return;
        }
        if (Gear.kept(held)) {
            player.sendMessage(Lines.cantDeposit(game));
            return;
        }
        PlayerInventory inv = player.getInventory();
        boolean weapon = Gear.sword(held) || held.material() == Material.BOW;
        if (weapon && count(inv, held.material()) < 2) {
            player.sendMessage(Lines.needTwo(game));
            return;
        }
        int size = kind.rows() * 9;
        int moved = move(inv, player.getHeldSlot(), key, size);
        if (player.isSneaking() && !weapon) {
            for (int slot = 0; slot < PlayerInventory.INNER_INVENTORY_SIZE; slot++) {
                if (inv.getItemStack(slot).isSimilar(held)) moved += move(inv, slot, key, size);
            }
        }
        if (moved == 0) {
            player.sendMessage(Lines.noSpace(game));
            return;
        }
        int total = 0;
        ItemStack[] stored = containers.contents(game.shard(), key);
        if (stored != null) {
            for (ItemStack in : stored) if (in != null && in.isSimilar(held)) total += in.amount();
        }
        player.sendMessage(Lines.deposited(game, held, moved, kind.key() != ContainerKey.VIEWER, total));
        Pos feet = player.getPosition();
        game.forEachAudience(viewer -> viewer.playSound(Lines.DEPOSIT, feet.x(), feet.y(), feet.z()));
    }

    private int move(PlayerInventory inv, int slot, String key, int size) {
        ItemStack in = inv.getItemStack(slot);
        if (in.isAir()) return 0;
        ItemStack left = containers.insert(game.shard(), key, size, in);
        inv.setItemStack(slot, left);
        return in.amount() - left.amount();
    }

    private static int count(PlayerInventory inv, Material material) {
        int n = 0;
        for (int slot = 0; slot < PlayerInventory.INNER_INVENTORY_SIZE; slot++) {
            ItemStack in = inv.getItemStack(slot);
            if (in.material() == material) n += in.amount();
        }
        return n;
    }

    // captured: an upgrade reaches the swords already stored, in the team chest and every member's ender chest
    private void restamp(Scope scope) {
        stores.forEach((key, seat) -> {
            if (!covers(scope, seat)) return;
            ItemStack[] slots = containers.contents(game.shard(), key);
            if (slots == null) return;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == null) continue;
                ItemStack stamped = BedWars.KIT.stamped(game, seat, slots[i]);
                if (!stamped.equals(slots[i])) containers.set(game.shard(), key, i, slots.length, stamped);
            }
        });
    }

    private static boolean covers(Scope scope, Slot seat) {
        return switch (scope) {
            case Scope.Game ignored -> true;
            case Scope.Team team -> team.id().equals(seat.team());
            case Scope.Slot slot -> slot.index() == seat.index();
            case Scope.Ent ignored -> false;
        };
    }

    // captured: just before the final-kill line, and told to everyone
    private void spill(Player player, String team) {
        String key = enders.remove(player.getUsername());
        PointMarker forge = game.arena().marker(BedWars.FORGE, team);
        ItemStack[] slots = key != null ? containers.contents(game.shard(), key) : null;
        if (slots == null || forge == null) return;
        boolean any = false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            containers.set(game.shard(), key, i, slots.length, ItemStack.AIR);
            Generators.drop(game, forge.point(), slots[i]);
            any = true;
        }
        if (any) Match.say(game, Lines.enderDropped(game, player.getUsername()));
    }
}
