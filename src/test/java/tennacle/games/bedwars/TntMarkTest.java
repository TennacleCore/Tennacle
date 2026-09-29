package tennacle.games.bedwars;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.items.TntMark;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captured (Hypixel, 2026-09-29): a red dust a tick over a TNT carrier's head, for everyone but the carrier. */
class TntMarkTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void othersSeeTheCarrier() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TntA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TntB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            b.player.teleport(a.player.getPosition().add(2, 0, 0)).join();
            await("in view", () -> a.player.getViewers().contains(b.player));
            a.player.setItemInMainHand(ItemStack.of(Material.TNT));
            a.sent.clear();
            b.sent.clear();
            tick();
            assertEquals(1, dust(b), "a teammate or an enemy sees the mark");
            assertEquals(0, dust(a), "the carrier never does");
            ParticlePacket mark = b.sent(ParticlePacket.class).stream().filter(p -> p.particle().key().equals(Particle.DUST.key())).findFirst().orElseThrow();
            assertEquals(a.player.getPosition().y() + a.player.getEyeHeight() + 1.0, mark.y(), 0.001, "a block over the eye");
            assertTrue(mark.longDistance(), "sent far, as captured");

            game.state().set(Scope.GAME, TntMark.MARK, false);
            b.sent.clear();
            tick();
            assertEquals(0, dust(b), "the rule off");
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    private static long dust(FakePlayer p) {
        return p.sent(ParticlePacket.class).stream().filter(packet -> packet.particle().key().equals(Particle.DUST.key())).count();
    }
}
