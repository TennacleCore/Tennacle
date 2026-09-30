package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.canvas.theme.Role;
import io.github.term4.canvas.theme.Tint;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cage variant can carry its own block per team, so a map that hands team 1 purple still gets the ore cage
 * that variant was built with.
 */
class CageFillTest extends HeadlessServerTest {

    private static DataMarker fill(String... pairs) {
        var payload = CompoundBinaryTag.builder();
        for (int i = 0; i < pairs.length; i += 2) payload.putString(pairs[i], pairs[i + 1]);
        return new DataMarker("cage.fill", "ores", "", payload.build(), 1);
    }

    @Test
    void aVariantsOwnBlocksBeatTheTeamsTint() {
        Map<String, Role.Fill> fills = Cages.fills(fill("1", "redstone_ore", "2", "lapis_ore"));
        assertEquals(Material.REDSTONE_ORE, fills.get("1").block());
        assertEquals(Material.LAPIS_ORE, fills.get("2").block());

        Role.Fill purple = new Role.Fill(null, "wool", Tint.parse("light_purple"));
        assertEquals(Material.REDSTONE_ORE, Role.Fill.merge(fills.get("1"), purple).block(),
                "the map's team look never reaches a cage that named its own block");
    }

    @Test
    void aFamilyOnItsOwnStillTakesTheTeamsHue() {
        Role.Fill merged = Role.Fill.merge(Cages.fills(fill("1", "stained_glass")).get("1"),
                new Role.Fill(null, "wool", Tint.parse("light_purple")));
        assertNull(merged.block());
        assertEquals("stained_glass", merged.family(), "the cage says which family");
        assertEquals(Tint.parse("light_purple"), merged.tint(), "the map says which hue");
    }

    @Test
    void unparsableFillIsDropped() {
        Map<String, Role.Fill> fills = Cages.fills(fill("1", "not_a_block", "2", "lapis_ore"));
        assertTrue(fills.get("1") == null, "the bad one is gone");
        assertEquals(Material.LAPIS_ORE, fills.get("2").block(), "the rest of the set stands");
        assertTrue(Cages.fills(null).isEmpty(), "no marker at all is the ordinary case");
    }
}
