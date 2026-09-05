package tennacle.commands;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.bridge.Cages;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.client.play.ClientTabCompletePacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.packet.server.play.TabCompletePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /rules <package>} narrows the listing the way {@code /rules paths <path>} walks the mechanics tree. */
class RulesListingTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> lines(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static List<String> complete(FakePlayer p, String text) {
        p.sent.clear();
        MinecraftServer.getPacketListenerManager().processClientPacket(new ClientTabCompletePacket(3, text), p.player.getPlayerConnection());
        return p.sent(TabCompletePacket.class).stream().flatMap(t -> t.matches().stream()).map(m -> m.match()).toList();
    }

    private static Game start(FakePlayer a, FakePlayer b) throws InterruptedException {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.start(bridge, Matches.platformFor(bridge, "scrims"), List.of(List.of(a.player), List.of(b.player)));
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        assertNotNull(Game.of(a.player));
        return game;
    }

    @Test
    void aPackageNarrowsTheListing() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(300.5, 65, 300.5), "RuleListA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(302.5, 65, 302.5), "RuleListB");
        Game game = null;
        try {
            game = start(a, b);

            List<String> flow = lines(a, "rules flow");
            assertTrue(flow.stream().anyMatch(l -> l.contains("flow.time.limit")), "the package lists its own");
            assertTrue(flow.stream().noneMatch(l -> l.contains("combat.")), "and nothing else: " + flow);
            assertTrue(flow.stream().anyMatch(l -> l.contains("narrow:") && l.contains("flow.countdown")
                    && l.contains("flow.time")), "with the way further in: " + flow);

            assertTrue(lines(a, "rules flow.time").stream().noneMatch(l -> l.contains("flow.countdown.from")),
                    "a deeper package narrows again");
            assertTrue(lines(a, "rules view.health.display").stream().anyMatch(l -> l.contains("view.health.display =")),
                    "a whole id with no value reads it back");
            assertTrue(lines(a, "rules nosuchpackage").stream().anyMatch(l -> l.contains("no rule, team or player")),
                    "and a name that is none of the three says so");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theWordsComplete() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(304.5, 65, 304.5), "RuleTabA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(306.5, 65, 306.5), "RuleTabB");
        Game game = null;
        try {
            game = start(a, b);
            String bool = Cages.BLOCKS.id();

            assertEquals(List.of("space"), complete(a, "/rules spa"), "a segment at a time");
            List<String> under = complete(a, "/rules space.");
            assertTrue(under.contains("space.build") && under.stream().allMatch(w -> w.startsWith("space.")), "the next segment down: " + under);
            assertTrue(complete(a, "/rules space.build.").contains("space.build.breakable-outside"), "then the ids themselves");
            List<String> first = complete(a, "/rules ");
            assertTrue(first.contains("paths") && first.contains("@RuleTabA") && first.contains("flow")
                    && first.stream().anyMatch(w -> w.startsWith("@") && !w.equals("@RuleTabA") && !w.equals("@RuleTabB")),
                    "paths, sides, seats and packages: " + first);
            assertEquals(List.of("true", "false"), complete(a, "/rules " + bool + " "), "a boolean's two values");
            assertEquals(List.of("space"), complete(a, "/rules @RuleTabA spa"), "an id after a seat");
            assertEquals(List.of("true", "false"), complete(a, "/rules @RuleTabA " + bool + " "), "and then its value");
            assertTrue(complete(a, "/rules paths att").contains("attack"), "the mechanics tree");
            List<String> knobs = complete(a, "/rules paths attack/");
            assertTrue(knobs.contains("attack/fullHitScale"), "one level down, the separator kept: " + knobs);
            assertTrue(complete(a, "/rules paths attack.full").contains("attack.fullHitScale"), "either separator");
            assertTrue(complete(a, "/rules paths attack full").contains("fullHitScale"), "or a space");
            assertEquals(List.of("true", "false"), complete(a, "/rules projectiles/minecraft:arrow/critDamage "), "a knob's boolean");
            assertTrue(complete(a, "/rules projectiles/minecraft:arrow/selfHit ").contains("pass_through"), "an enum knob's constants");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
