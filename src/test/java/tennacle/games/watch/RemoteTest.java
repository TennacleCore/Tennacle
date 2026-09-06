package tennacle.games.watch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The speed heads walk the ladder a rung at a time and snap back onto it from any /rate speed. */
class RemoteTest {

    @Test
    void theHeadsWalkTheLadder() {
        assertEquals(0.5, Remote.slower(1));
        assertEquals(0.25, Remote.slower(0.5));
        assertEquals(0.25, Remote.slower(0.25), "the bottom rung halves no further");
        assertEquals(2.0, Remote.faster(1));
        assertEquals(8.0, Remote.faster(4));
        assertEquals(8.0, Remote.faster(8), "the top rung doubles no further");
    }

    @Test
    void offTheLadderTheySnapBack() {
        assertEquals(0.25, Remote.faster(0.03), "from a /rate speed under the ladder, up onto its first rung");
        assertEquals(0.03, Remote.slower(0.03), "and no rung under it");
        assertEquals(8.0, Remote.slower(10), "from the top, down onto the ladder");
        assertEquals(10.0, Remote.faster(10));
        assertEquals(1.0, Remote.faster(0.7));
        assertEquals(0.5, Remote.slower(0.7));
    }

    @Test
    void readingsRead() {
        assertEquals("8.0", Remote.rateText(8));
        assertEquals("0.5", Remote.rateText(0.5));
        assertEquals("0.25", Remote.rateText(0.25));
        assertEquals("0.01", Remote.rateText(0.01));
        assertEquals("01:28", Remote.stamp(88 * 20));
        assertEquals("5m", Remote.seekStep(300));
        assertEquals("30s", Remote.seekStep(30));
    }
}
