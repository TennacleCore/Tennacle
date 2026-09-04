package tennacle.games;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.FakePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Captured minemen reads health in hearts and never lands off a half; the other looks read raw hp. */
class LookHitTest extends HeadlessServerTest {

    private static String line(Look look, float health, float impact) {
        FakePlayer p = FakePlayer.connect(instance, new Pos(8.5, 64, 8.5), "Hit" + look.hashCode());
        try {
            Component rendered = look.hit().render(p.player, health, impact);
            return PlainTextComponentSerializer.plainText().serialize(rendered);
        } finally {
            p.player.remove();
        }
    }

    @Test
    void minemenReadsHearts() {
        assertEquals("3.5❤", line(Look.MINEMEN, 7f, -7f).split(" ")[2], "seven hp is three and a half hearts");
        assertEquals("(-3.5❤)", line(Look.MINEMEN, 7f, -7f).split(" ")[3], "and so is the hit that took it");
    }

    @Test
    void minemenSnapsToTheHalf() {
        assertEquals("7❤", line(Look.MINEMEN, 14.4f, -1f).split(" ")[2], "14.4 hp rounds to 14, which is 7 hearts");
        assertEquals("3.5❤", line(Look.MINEMEN, 6.6f, -1f).split(" ")[2], "6.6 hp rounds to 7, which is 3.5");
    }
}
