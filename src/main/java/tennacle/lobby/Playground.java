package tennacle.lobby;

import io.github.term4.polyp.platform.player.Wardrobe;
import tennacle.Mechanics;

import io.github.term4.polyp.tracking.ClientVersion;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The lobby world and what an arrival wears there. The mechanics test pads (damage types, water-flow lanes) are
 * built only where the dev commands exist, and the test rig's kit is {@code /arsenal} - a lobby hands out nothing.
 */
public final class Playground {

    private static final Logger LOGGER = LoggerFactory.getLogger(Playground.class);

    private Playground() {}

    public static InstanceContainer createWorld(boolean testPads) {
        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setExplosionSupplier(Mechanics.explosions.supplier()); // route instance.explode(...) through the system
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.OBSIDIAN));
        world.setChunkSupplier(LightingChunk::new);
        if (testPads) buildTestPads(world);
        return world;
    }

    // damage-type pads (water/fire/cactus) + the water flow lanes, from the polyp example server
    private static void buildTestPads(InstanceContainer world) {
        world.loadChunk(1, 0).thenRun(() -> {
            for (int x = 18; x <= 20; x++)
                for (int z = 0; z <= 2; z++)
                    for (int y = 40; y <= 46; y++)
                        world.setBlock(x, y, z, Block.WATER);
            world.setBlock(25, 40, 0, Block.FIRE);
            world.setBlock(25, 40, 1, Block.FIRE);
            world.setBlock(29, 39, 0, Block.SAND);
            world.setBlock(29, 40, 0, Block.CACTUS);
            world.setBlock(29, 41, 0, Block.CACTUS);
        });
        // enclosed lanes with a hand-set `level` gradient (Minestom does not spread fluids): a hit taken standing
        // in a lane folds the flow term AWAY from the wool marker - compare to vanilla 1.8
        world.loadChunk(0, 0).thenRun(() -> {
            buildFlowLane(world,  2, 40,  4,  1,  0, 7, Block.RED_WOOL, true);    // EAST  (+X)
            buildFlowLane(world,  8, 40,  7, -1,  0, 7, Block.BLUE_WOOL, true);   // WEST  (-X)
            buildFlowLane(world, 12, 40,  4,  0,  1, 7, Block.YELLOW_WOOL, true); // SOUTH (+Z)
            buildFlowLane(world, 14, 40, 10,  0, -1, 7, Block.LIME_WOOL, true);   // NORTH (-Z)
            buildFlowLane(world,  2, 40, 12,  1,  0, 7, Block.ORANGE_WOOL, false); // 1-deep: items slide
        });
    }

    /**
     * A 1-wide enclosed water channel with a manual {@code level} gradient (marker end = level 0) so the current
     * flows toward the far end. {@code deep} = a stream over a source pool: the gradient in the TOP layer only -
     * vanilla water under water is always source (flow decay 0), and a bottom-layer gradient pushes bottom-resting
     * items in a way vanilla can't produce (the 1.8 client's own item sim rubber-bands against it).
     */
    private static void buildFlowLane(InstanceContainer inst, int sx, int baseY, int sz,
                                      int dirX, int dirZ, int length, Block marker, boolean deep) {
        int pdx = dirZ, pdz = -dirX; // perpendicular = side-wall offset
        for (int i = -1; i <= length; i++) { // -1 and length = solid end caps
            int cx = sx + dirX * i, cz = sz + dirZ * i;
            boolean cap = i < 0 || i >= length;
            Block grad = cap ? Block.STONE : Block.WATER.withProperty("level", Integer.toString(Math.min(i, 7)));
            inst.setBlock(cx, baseY - 1, cz, Block.STONE);                                 // floor
            inst.setBlock(cx, baseY, cz, deep ? (cap ? Block.STONE : Block.WATER) : grad); // feet
            inst.setBlock(cx, baseY + 1, cz, deep ? grad : Block.AIR);                     // body
            inst.setBlock(cx, baseY + 2, cz, Block.AIR);   // headroom
            for (int s = -1; s <= 1; s += 2) {             // side walls (solid -> no spurious side flow)
                int wx = cx + pdx * s, wz = cz + pdz * s;
                inst.setBlock(wx, baseY - 1, wz, Block.STONE);
                inst.setBlock(wx, baseY, wz, Block.STONE);
                inst.setBlock(wx, baseY + 1, wz, Block.STONE);
            }
        }
        inst.setBlock(sx, baseY + 2, sz, marker); // flow points AWAY from this marker
    }

    public static void installListeners(InstanceContainer world) {
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            e.setSpawningInstance(world);
            e.getPlayer().setRespawnPoint(new Pos(0.5, 42, 0.5));
            logProtocol(e.getPlayer());
        });
        // the lobby dresses every arrival - a login, a game's exit, a rejoin - at spawn, never in config: a player
        // becomes a viewer of its own inventory only after config, and Minestom pushes an item's attribute
        // modifiers (ARMOR...) only for the inventory's viewers
        Wardrobe.install();
        Wardrobe.outfit(world, Playground::outfit);
    }

    /** The lobby body: adventure, nothing worn, nothing carried - a game outfits its own. */
    static void outfit(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.setHelmet(ItemStack.AIR);
        p.setChestplate(ItemStack.AIR);
        p.setLeggings(ItemStack.AIR);
        p.setBoots(ItemStack.AIR);
    }

    // polls until the Via/proxy handshake reveals the protocol (-1 until then), a few tries only
    private static void logProtocol(Player player) {
        if (!Mechanics.polyp.viaProxyDetails) return;
        final int[] runs = {0};
        MinecraftServer.getSchedulerManager().scheduleTask(() -> {
            if (!player.isOnline()) return TaskSchedule.stop();
            int protocol = Mechanics.polyp.clientInfo().getProtocol(player);
            if (protocol == ClientVersion.UNKNOWN_PROTOCOL) {
                return (++runs[0] >= 3) ? TaskSchedule.stop() : TaskSchedule.tick(20);
            }
            LOGGER.info("{} protocol {}", player.getUsername(), protocol);
            return TaskSchedule.stop();
        }, TaskSchedule.tick(20));
    }
}
