package tennacle.games.bridge;

import tennacle.Msg;

import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A goal bookmark reads as the side's wool, with the score, the scorer's hearts and the moment under it. */
class GoalMarkTest extends HeadlessServerTest {

    @Test
    void aGoalReadsInItsColor() {
        ListBinaryTag sides = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                .add(CompoundBinaryTag.builder().putString("team", "1").putInt("color", NamedTextColor.RED.value()).putInt("goals", 2).build())
                .add(CompoundBinaryTag.builder().putString("team", "2").putInt("color", NamedTextColor.BLUE.value()).putInt("goals", 4).build())
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder().putString("scorer", "Dogln").putString("team", "2")
                .putInt("color", NamedTextColor.BLUE.value()).putInt("goals", 4).putInt("target", 5)
                .putFloat("health", 24f).put("sides", sides).build();
        ItemStack item = Banners.mark(new Replay.Bookmark(87 * 20, "goal", data), Msg.SCRIMS);
        assertEquals(Material.BLUE_WOOL, item.material(), "the side's wool");
        String name = PlainTextComponentSerializer.plainText().serialize(item.get(DataComponents.CUSTOM_NAME));
        assertEquals("Dogln scored", name);
        List<Component> lore = item.get(DataComponents.LORE);
        List<String> lines = lore.stream().map(l -> PlainTextComponentSerializer.plainText().serialize(l)).toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("2 - 4"), "the score at that moment: " + lines);
        assertTrue(lines.get(1).contains("24"), "the scorer's hearts, absorption and all: " + lines);
        assertTrue(lines.get(2).contains("01:27"), "the moment: " + lines);
    }

    @Test
    void pastTenGoalsTheScoreReadsAsNumbers() {
        ListBinaryTag sides = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                .add(CompoundBinaryTag.builder().putString("team", "1").putInt("color", NamedTextColor.RED.value()).putInt("goals", 12).build())
                .add(CompoundBinaryTag.builder().putString("team", "2").putInt("color", NamedTextColor.BLUE.value()).putInt("goals", 7).build())
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder().putString("scorer", "Dogln").putString("team", "1")
                .putInt("color", NamedTextColor.RED.value()).putInt("goals", 12).putInt("target", 15)
                .putFloat("health", 20f).put("sides", sides).build();
        ItemStack item = Banners.mark(new Replay.Bookmark(20, "goal", data), Msg.SCRIMS);
        List<Component> lore = item.get(DataComponents.LORE);
        String score = PlainTextComponentSerializer.plainText().serialize(lore.getFirst());
        assertEquals("12 - 7", score, "no glyph meter has fifteen squares");
    }
}
