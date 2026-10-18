package tennacle.games.bedwars;

import tennacle.games.KitLayouts;
import tennacle.games.TestWiring;
import tennacle.games.imports.KitImports;
import tennacle.games.shop.Favorites;
import tennacle.hypixel.HypixelApi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Layouts as the Hypixel API returned them for three players on 2026-10-18, trimmed to the fields read. */
class HypixelImportTest extends HeadlessServerTest {

    private static final String TERM4 = """
            {"stats": {
              "Bedwars": {
                "favorite_slots": "Melee,Shears,Utility,Blocks,Utility,Melee,Pickaxe,Utility,Axe",
                "favourites_2": "wool,stone_sword,ladder,fireball,golden_apple,ender_pearl,tnt,wood,bedbug,iron_boots,bow,arrow,jump_v_potion_(45_seconds),water_bucket,wooden_pickaxe,wooden_axe,iron_sword,stick_(knockback_i),dream_defender,magic_milk,shears"
              },
              "Duels": {
                "layout_bridge_duel_layout": {"0": "iron_sword", "1": "bow", "2": "diamond_pickaxe", "3": "stained_clay_2", "4": "stained_clay_1", "5": "golden_apple", "7": "glyph_menu", "8": "arrow"},
                "layout_bedwars_two_one_duels_rush_layout": {"0": "sword", "1": "shears", "2": "wood_axe", "3": "wool", "6": "wood_pickaxe"}
              }
            }}""";

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @AfterEach
    void off() {
        HypixelApi.source(null);
    }

    @Test
    void everyCapturedIdIsAWare() {
        List<String> ids = List.of("wool", "stone_sword", "ladder", "fireball", "golden_apple", "ender_pearl", "tnt", "wood",
                "bedbug", "iron_boots", "bow", "arrow", "jump_v_potion_(45_seconds)", "water_bucket", "wooden_pickaxe",
                "wooden_axe", "iron_sword", "stick_(knockback_i)", "dream_defender", "magic_milk", "shears", "chainmail_boots",
                "speed_ii_potion_(45_seconds)", "blast-proof_glass", "invisibility_potion_(30_seconds)", "end_stone", "bridge_egg");
        for (String id : ids) assertNotNull(Wares.fromHypixel(id), id);
        assertEquals("iron-armor", Wares.fromHypixel("iron_boots"));
        assertEquals("pickaxe", Wares.fromHypixel("wooden_pickaxe"));
        assertEquals("stick", Wares.fromHypixel("stick_(knockback_i)"));
        assertNull(Wares.fromHypixel("null"));
    }

    @Test
    void importsBedWarsAndKits() {
        JsonObject profile = JsonParser.parseString(TERM4).getAsJsonObject();
        HypixelApi.source(uuid -> CompletableFuture.completedFuture(profile));
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HpxImp");
        try {
            KitImports.run(p.player, "hypixel", null, null);
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();

            List<String> pins = Favorites.of(p.player, ItemShop.FRONT);
            assertNotNull(pins, "quick buy imported");
            assertEquals(21, pins.size());
            assertEquals(List.of("wool", "stone-sword", "ladder", "fireball"), pins.subList(0, 4));
            assertFalse(pins.contains(""), "every pin is one we sell");

            List<List<String>> hotbar = HotbarOrders.of(p.player).main();
            assertEquals(List.of("melee"), hotbar.get(0));
            assertEquals(List.of("utility"), hotbar.get(7));
            assertEquals(List.of("axe"), hotbar.get(8));

            Map<String, Integer> bridge = KitLayouts.of(p.player, "bridge");
            assertNotNull(bridge, "bridge imported");
            assertEquals(4, bridge.get("blocks.1"));
            assertEquals(3, bridge.get("blocks.2"));
            assertEquals(5, bridge.get("gapple"));
            assertEquals(8, bridge.get("arrow"));

            Map<String, Integer> bedfight = KitLayouts.of(p.player, "bedfight");
            assertNotNull(bedfight, "bed rush imported as bed fight");
            assertEquals(2, bedfight.get("axe"));
            assertEquals(6, bedfight.get("pickaxe"));
            assertEquals(3, bedfight.get("blocks"));
        } finally {
            p.player.remove();
        }
    }

    @Test
    void nothingSavedChangesNothing() {
        HypixelApi.source(uuid -> CompletableFuture.completedFuture(JsonParser.parseString("{\"stats\": {}}").getAsJsonObject()));
        FakePlayer p = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HpxNone");
        try {
            KitImports.run(p.player, "hypixel", "bedwars", null);
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            assertNull(Favorites.of(p.player, ItemShop.FRONT));
        } finally {
            p.player.remove();
        }
    }

    @Test
    void onePartAlone() {
        JsonObject profile = JsonParser.parseString(TERM4).getAsJsonObject();
        HypixelApi.source(uuid -> CompletableFuture.completedFuture(profile));
        FakePlayer p = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "HpxPart");
        try {
            List<List<String>> before = HotbarOrders.of(p.player).main();
            KitImports.run(p.player, "hypixel", "bedwars", "quickbuy");
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            assertNotNull(Favorites.of(p.player, ItemShop.FRONT), "the quick buy came in");
            assertEquals(before, HotbarOrders.of(p.player).main(), "the hotbar did not");
            assertNull(KitLayouts.of(p.player, "bridge"), "nor any other mode");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void kitImportCommandRuns() {
        JsonObject profile = JsonParser.parseString(TERM4).getAsJsonObject();
        HypixelApi.source(uuid -> CompletableFuture.completedFuture(profile));
        FakePlayer p = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "HpxCmd");
        try {
            MinecraftServer.getCommandManager().execute(p.player, "kit import bridge layout hypixel");
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            assertNotNull(KitLayouts.of(p.player, "bridge"), "the import branch, not /kit <mode>");
            assertNull(Favorites.of(p.player, ItemShop.FRONT));
        } finally {
            p.player.remove();
        }
    }
}
