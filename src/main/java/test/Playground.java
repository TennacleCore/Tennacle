package test;

import io.github.term4.polyp.entity.PrimedTnt;
import io.github.term4.polyp.tracking.ClientVersion;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;
import net.minestom.server.timer.TaskSchedule;

/**
 * The world and the player's kit: flat map with the damage/flow test pads, full combat inventory + armor on
 * join, the preset-driven TNT item.
 */
final class Playground {

    private Playground() {}

    static InstanceContainer createWorld() {
        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setExplosionSupplier(Mechanics.explosions.supplier()); // route instance.explode(...) through the system
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.OBSIDIAN));
        world.setChunkSupplier(LightingChunk::new);
        buildTestPads(world);
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

    static void installListeners(InstanceContainer world) {
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            e.setSpawningInstance(world);
            e.getPlayer().setRespawnPoint(new Pos(0.5, 42, 0.5));
            logProtocol(e.getPlayer());
            giveKit(e.getPlayer());
        });
        // armor on FIRST SPAWN, not config: a player becomes a viewer of its own inventory in UNSAFE_init (after
        // config), and Minestom pushes an item's attribute modifiers (ARMOR...) only for the inventory's viewers
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) return;
            Player p = e.getPlayer();
            p.setGameMode(GameMode.SURVIVAL);
            p.setHelmet(ItemStack.of(Material.DIAMOND_HELMET));
            p.setChestplate(ItemStack.of(Material.DIAMOND_CHESTPLATE));
            p.setLeggings(ItemStack.of(Material.DIAMOND_LEGGINGS));
            p.setBoots(ItemStack.of(Material.DIAMOND_BOOTS));
            // deferred: at spawn the Via handshake may not have revealed the protocol yet, and the welcome
            // format (gradient vs flat) depends on it
            Msg.onceProtocolKnown(p, 60, () -> Msg.usage(p,
                    "Tennacle - the Archipelago + Polyp playground", new String[][]{
                    {"/shard", "isolated game worlds over this map"},
                    {"/replay", "record + replay a game"},
                    {"/boom", "shard-scoped explosion at your feet"},
                    {"/gm <mode>", "gamemode (also /gmc /gms)"},
                    {"/lag /compat /shorts /enchant /effect /sword /explode", "dev tools"},
            }));
        });
        // TNT test item: placing TNT spawns a primed-TNT entity instead of a block, from the active PRESET - so its
        // wire/bounce/fuse always match the profile (a mismatch made a Hypixel-profile run behave like MineMen)
        events.addListener(PlayerBlockPlaceEvent.class, event -> {
            if (!event.getBlock().compare(Block.TNT)) return;
            event.setCancelled(true);
            Player p = event.getPlayer();
            if (p.getInstance() == null || Mechanics.explosions == null) return;
            PrimedTnt.spawn(Mechanics.explosions, MechanicsWorld.of(p), event.getBlockPosition(), Mechanics.presetFor(p).tnt);
            if (p.getGameMode() != GameMode.CREATIVE) { // cancelling the place keeps the item, so consume one like vanilla TNT
                ItemStack held = p.getItemInHand(event.getHand());
                p.setItemInHand(event.getHand(), held.withAmount(held.amount() - 1));
            }
        });
    }

    private static void giveKit(Player player) {
        var inv = player.getInventory();
        inv.addItemStack(ItemStack.of(Material.STONE, 64));
        inv.addItemStack(ItemStack.of(Material.RED_WOOL, 64));
        inv.addItemStack(ItemStack.of(Material.LADDER, 64));
        inv.addItemStack(ItemStack.of(Material.IRON_PICKAXE));
        inv.addItemStack(ItemStack.of(Material.DIAMOND_SWORD));
        inv.addItemStack(ItemStack.of(Material.BOW));
        inv.addItemStack(ItemStack.of(Material.ARROW, 64));
        inv.addItemStack(ItemStack.of(Material.SNOWBALL, 64));
        inv.addItemStack(ItemStack.of(Material.EGG, 16));
        inv.addItemStack(ItemStack.of(Material.ENDER_PEARL, 16));
        inv.addItemStack(ItemStack.of(Material.FISHING_ROD));
        inv.addItemStack(ItemStack.of(Material.FIRE_CHARGE, 64)); // right-click to throw a fireball
        inv.addItemStack(ItemStack.of(Material.TNT, 64)); // place to spawn a primed TNT
        inv.addItemStack(ItemStack.of(Material.GOLDEN_APPLE, 16));
        inv.addItemStack(ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE, 16));
        inv.addItemStack(ItemStack.of(Material.MILK_BUCKET));
        // a drinkable Speed potion (custom effect, so it works without extending VanillaPotions)
        inv.addItemStack(ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(new CustomPotionEffect(PotionEffect.SPEED, 0, 1200, false, true, true))));
        // registry-id potions (amount 1: potions are unstackable, an overstacked addItemStack silently no-ops)
        inv.addItemStack(ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.HEALING)));
        inv.addItemStack(ItemStack.of(Material.SPLASH_POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.SWIFTNESS)));
        inv.addItemStack(ItemStack.of(Material.SPLASH_POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.HARMING)));
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
            System.out.println(player.getUsername() + " protocol " + protocol);
            return TaskSchedule.stop();
        }, TaskSchedule.tick(20));
    }
}
