package tennacle.dev;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArenaIconsTest {

    @Test
    void theRareBlockWins() {
        // stone under every island, snow on this one alone
        Map<String, Double> shares = Map.of("minecraft:stone", 0.7, "minecraft:snow_block", 0.2, "minecraft:spruce_log", 0.01);
        Map<String, Integer> present = Map.of("minecraft:stone", 60, "minecraft:snow_block", 2, "minecraft:spruce_log", 1);
        assertEquals("minecraft:snow_block", ArenaIcons.signature(shares, present, 60));
        assertNull(ArenaIcons.signature(Map.of("minecraft:spruce_log", 0.01), present, 60), "a detail is not a map's look");
    }
}
