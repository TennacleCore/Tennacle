package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.bedwars.BedWars;
import tennacle.games.boxing.Boxing;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /stats} says every mode's career line, off the vault. */
class CareersTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        Careers.register();
    }

    @Test
    void everyModeHasALine() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CareerA");
        try {
            var vault = Matches.grotto().vault();
            vault.set(p.player.getUuid(), Boxing.CAREER_HITS, 40).join();
            vault.set(p.player.getUuid(), Boxing.CAREER_SPRINT, 10).join();
            vault.set(p.player.getUuid(), BedWars.CAREER_WINS, 3).join();
            vault.set(p.player.getUuid(), BedWars.CAREER_BEDS, 7).join();
            p.sent.clear();
            MinecraftServer.getCommandManager().execute(p.player, "stats");
            await("both lines", () -> chat(p).size() >= 2);
            List<String> lines = chat(p);
            assertTrue(lines.stream().anyMatch(l -> l.contains("Boxing: 40 hits, 25% w-tap")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("Bed Wars: 3 wins, 0 losses") && l.contains("7 beds broken")), lines.toString());
        } finally {
            p.player.remove();
        }
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    @Test
    void aCardReadsByName() {
        Careers.Card card = Careers.Card.of("Test", "test").count("Kills", "kills").ratio("K/D", "kills", "deaths")
                .hours("Time", "time").sum("Iron", "iron-a", "iron-b").hearts("Damage", "damage").build();
        org.junit.jupiter.api.Assertions.assertEquals(List.of("test:kills", "test:deaths", "test:time", "test:iron-a",
                "test:iron-b", "test:damage"), card.keys().stream().map(k -> k.id()).toList());
        int[] values = {1500, 600, 7380, 4, 6, 410};
        org.junit.jupiter.api.Assertions.assertEquals(List.of("1,500", "2.50", "2h 3m", "10", "20 hearts"),
                card.rows().stream().map(r -> r.value().apply(values)).toList());
        org.junit.jupiter.api.Assertions.assertEquals("3.00", card.rows().get(1).value().apply(new int[]{3, 0, 0, 0, 0, 0}),
                "nothing under it: the top alone");
    }
}
