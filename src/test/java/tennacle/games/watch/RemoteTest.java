package tennacle.games.watch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The speed heads' arithmetic: halving floors at a quarter, doubling tops at eight, fixed steps stop at the rails. */
class RemoteTest {

    @Test
    void halvingStopsAtAQuarter() {
        assertEquals(0.5, Remote.slower(1, 0));
        assertEquals(0.25, Remote.slower(0.5, 0));
        assertEquals(0.25, Remote.slower(0.25, 0), "a quarter halves no further");
        assertEquals(0.15, Remote.slower(0.25, 0.1), 1.0e-9, "a fixed step still goes under");
        assertEquals(0.05, Remote.slower(0.15, 0.1), 1.0e-9, "down to the floor");
        assertEquals(0.05, Remote.slower(0.05, 0.05), 1.0e-9);
    }

    @Test
    void doublingStopsAtEight() {
        assertEquals(2.0, Remote.faster(1, 0));
        assertEquals(8.0, Remote.faster(4, 0));
        assertEquals(8.0, Remote.faster(8, 0), "eight doubles no further");
        assertEquals(9.0, Remote.faster(8, 1), "a fixed step still climbs");
        assertEquals(16.0, Remote.faster(15.5, 1), "to the ceiling");
    }

    @Test
    void readingsRead() {
        assertEquals("8.0", Remote.rateText(8));
        assertEquals("0.5", Remote.rateText(0.5));
        assertEquals("0.25", Remote.rateText(0.25));
        assertEquals("0.05", Remote.rateText(0.05));
        assertEquals("01:28", Remote.stamp(88 * 20));
        assertEquals("5m", Remote.seekStep(300));
        assertEquals("30s", Remote.seekStep(30));
        assertEquals("\u00d72", Remote.rateStep(0));
        assertEquals("0.5x", Remote.rateStep(0.5));
    }
}
