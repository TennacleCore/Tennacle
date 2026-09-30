package tennacle.looks;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code clock.colors} stops: a base color, then each mark as the time remaining falls past it. */
class ClockColorTest {

    private static final List<String> MATCH = List.of("green", "10:00 yellow", "5:00 red");

    @Test
    void marksTakeOverInTurn() {
        assertEquals(NamedTextColor.GREEN, Frames.timeColor(MATCH, 15 * 60 * 20));
        assertEquals(NamedTextColor.YELLOW, Frames.timeColor(MATCH, 10 * 60 * 20), "at the mark, not past it");
        assertEquals(NamedTextColor.YELLOW, Frames.timeColor(MATCH, 6 * 60 * 20));
        assertEquals(NamedTextColor.RED, Frames.timeColor(MATCH, 5 * 60 * 20));
        assertEquals(NamedTextColor.RED, Frames.timeColor(MATCH, 0));
    }

    @Test
    void oneColorNeverChanges() {
        List<String> flat = List.of("light_purple");
        assertEquals(NamedTextColor.LIGHT_PURPLE, Frames.timeColor(flat, 30 * 60 * 20));
        assertEquals(NamedTextColor.LIGHT_PURPLE, Frames.timeColor(flat, 0));
    }

    @Test
    void plainSecondsAndBadStops() {
        assertEquals(NamedTextColor.RED, Frames.timeColor(List.of("green", "30 red"), 30 * 20), "a mark in seconds");
        List<String> broken = List.of("green", "soon yellow", "5:00 mauve");
        assertEquals(NamedTextColor.GREEN, Frames.timeColor(broken, 60), "an unreadable stop leaves the color alone");
    }
}
