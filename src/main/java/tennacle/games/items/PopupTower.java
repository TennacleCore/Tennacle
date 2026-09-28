package tennacle.games.items;

import tennacle.boot.Mechanics;
import tennacle.games.TeamBlocks;
import tennacle.games.map.Building;
import tennacle.games.map.Structures;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.mechanics.knockback.KnockbackSystem;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.ServerFlag;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The Compact Pop-up Tower (captured 2026-09-24): the chest never lands; from the next tick the blueprint goes up two
 * cells a tick, floor first, doorway toward the placer, a ladder up the middle. A cell that is taken keeps its slot,
 * a player a cell lands in is thrown back along their own look. Any mode with building rules may list it.
 */
public final class PopupTower implements GameModule {

    public static final StateKey<Integer> PER_TICK = StateKey.of("tower.per-tick", Codec.INT, 2);

    static final Sound BLOCK = Sound.sound(SoundEvent.ENTITY_CHICKEN_EGG, Sound.Source.MASTER, 0.8f, 50 / 63f);

    private static final int[][] WALL = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {0, -2}, {0, 2}, {1, -1}, {1, 0}, {1, 1}};
    private static final int[][] CRENELS = {{-3, -2}, {-3, 0}, {-3, 2}, {2, -2}, {2, 0}, {2, 2}, {-2, -3}, {-2, 3}, {1, -3}, {1, 3}};
    private static final String[] FACING = {"south", "west", "north", "east"};
    private static final int[][] DIR = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    private Game game;
    private Structures structures;
    private Building building;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(PER_TICK);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        structures = game.module(Structures.class);
        building = Building.of(game);
        if (structures == null || building == null) throw new IllegalStateException("PopupTower needs the mode's building rules and Structures installed before it");
        // after Building's own handler: a placement it refused never gets here, one it allowed is ours
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (!e.getBlock().compare(Block.TRAPPED_CHEST) || game.roster().slotOf(player) == null || game.isGhost(player)) return;
            e.setCancelled(true);
            spend(player, e.getHand());
            build(player, e.getBlockPosition());
        });
    }

    private static void spend(Player player, PlayerHand hand) {
        ItemStack held = hand == PlayerHand.MAIN ? player.getItemInMainHand() : player.getItemInOffHand();
        ItemStack left = held.amount() > 1 ? held.withAmount(held.amount() - 1) : ItemStack.AIR;
        if (hand == PlayerHand.MAIN) player.setItemInMainHand(left);
        else player.setItemInOffHand(left);
    }

    /** Lays the blueprint at {@code p}, faced from where {@code placer} looks. */
    public void build(Player placer, Point p) {
        Slot seat = game.roster().slotOf(placer);
        if (seat == null) return;
        Block wool = TeamBlocks.wool(game, seat);
        int facing = facing(placer.getPosition().yaw());
        List<Structures.Cell> cells = blueprint(p, facing, wool);
        boolean alongX = DIR[facing][0] != 0;
        int perTick = game.rules().resolve(placer, PER_TICK);
        structures.add(Structures.lay(cells, perTick, cell -> {
            if (!building.place(placer, cell.pos(), cell.block())) return false;
            Point at = cell.pos();
            game.forEachAudience(v -> v.playSound(BLOCK, at.blockX() + (alongX ? 0 : 0.5),
                    at.blockY(), at.blockZ() + (alongX ? 0.5 : 0)));
            shove(at);
            return true;
        }));
    }

    /** 1.8 EntityLivingBase.getHorizontalFacing: the look rounded to a side. */
    static int facing(float yaw) {
        return (int) Math.floor(yaw * 4.0f / 360.0f + 0.5) & 3;
    }

    /** The cells floor first, each layer from the lowest world x up. */
    static List<Structures.Cell> blueprint(Point p, int facing, Block wool) {
        int dx = DIR[facing][0], dz = DIR[facing][1];
        int rx = -dz, rz = dx;
        Block ladder = Block.LADDER.withProperty("facing", FACING[(facing + 2) & 3]);
        List<Structures.Cell> out = new ArrayList<>();
        for (int h = -1; h <= 6; h++) {
            List<int[]> layer = new ArrayList<>();
            if (h <= 3) {
                for (int[] wall : WALL) layer.add(wall);
                if (h >= 2) layer.add(new int[] {-2, 0});
                layer.add(new int[] {0, 0});
            } else if (h == 4) {
                for (int r = -2; r <= 2; r += 2) for (int f = -3; f <= 2; f++) layer.add(new int[] {f, r});
                for (int r = -1; r <= 1; r += 2) for (int f = -2; f <= 1; f++) layer.add(new int[] {f, r});
                for (int r = -3; r <= 3; r += 6) {
                    layer.add(new int[] {-2, r});
                    layer.add(new int[] {1, r});
                }
            } else if (h == 5) {
                for (int f = -3; f <= 2; f += 5) for (int r = -2; r <= 2; r++) layer.add(new int[] {f, r});
                for (int r = -3; r <= 3; r += 6) for (int f = -2; f <= 1; f++) layer.add(new int[] {f, r});
            } else {
                for (int[] crenel : CRENELS) layer.add(crenel);
            }
            List<Structures.Cell> placed = new ArrayList<>();
            for (int[] fr : layer) {
                int f = fr[0], r = fr[1];
                BlockVec at = new BlockVec(p.blockX() + f * dx + r * rx, p.blockY() + h, p.blockZ() + f * dz + r * rz);
                boolean column = f == 0 && r == 0;
                placed.add(new Structures.Cell(at, column && h >= 0 ? ladder : wool));
            }
            placed.sort(Comparator.comparingInt((Structures.Cell c) -> c.pos().blockX()).thenComparingInt(c -> c.pos().blockZ()));
            out.addAll(placed);
        }
        return out;
    }

    // captured: -0.5 along the player's own look, plus 0.2 up, sent again for every cell that lands in them
    private void shove(Point cell) {
        KnockbackSystem knockback = Mechanics.polyp != null ? Mechanics.polyp.services().knockback() : null;
        if (knockback == null) return;
        for (Player member : game.members()) {
            if (game.isGhost(member) || !member.getBoundingBox().intersectBox(member.getPosition().sub(cell), FULL)) continue;
            Pos look = member.getPosition();
            Vec push = look.direction().mul(-0.5).add(0, 0.2, 0);
            knockback.deliver(member, push.mul(ServerFlag.SERVER_TICKS_PER_SECOND));
        }
    }

    private static final net.minestom.server.collision.BoundingBox FULL = new net.minestom.server.collision.BoundingBox(1, 1, 1, new Vec(0, 0, 0));
}
