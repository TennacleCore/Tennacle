package tennacle.games.practice;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.api.event.knockback.KnockbackAppliedEvent;
import io.github.term4.polyp.mechanics.attack.AttackSystem;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.boot.Mechanics;
import tennacle.games.Matches;
import tennacle.games.TestWiring;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeTest extends HeadlessServerTest {

    private static AttackSystem attacks;

    // the harness installs no attack system; this one detects nothing, so only the bot's swings go through it
    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        attacks = AttackSystem.install(Mechanics.polyp, (node, sink) -> {});
    }

    @AfterAll
    static void unwire() {
        Mechanics.polyp.uninstall(attacks.node());
    }

    @Test
    void theBotHitsOnce() {
        Game game = Matches.launch(Matches.mode("practice"), Matches.platformFor(null), arena());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PracA");
        AtomicInteger knocks = new AtomicInteger();
        var listener = EventListener.of(KnockbackAppliedEvent.class, e -> {
            if (e.target() == p.player && e.snapshot().source() instanceof Bot) knocks.incrementAndGet();
        });
        MinecraftServer.getGlobalEventHandler().addListener(listener);
        try {
            p.player.setInstance(game.instance(), new Pos(-7.5, 65, 0.5)).join();
            assertTrue(game.join(p.player, game.roster().teams().getFirst().id()));
            ticks(game, () -> bot(game) != null, "the bot is up");
            Bot bot = bot(game);
            assertNotNull(bot);
            p.player.setHeldItemSlot((byte) 2);
            EventDispatcher.call(new PlayerUseItemEvent(p.player, PlayerHand.MAIN, p.player.getItemInMainHand(), 0));
            ticks(game, () -> knocks.get() > 0, "the bot ran in and hit");
            assertTrue(Trainer.reachTo(bot, p.player) <= 2.3, "from its reach");
            assertEquals(20f, p.player.getHealth(), "and dealt nothing");
            long until = game.tick() + 30;
            ticks(game, () -> game.tick() >= until, "a while on");
            assertEquals(1, knocks.get(), "one hit, then it stands");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeListener(listener);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    private static Bot bot(Game game) {
        for (Entity e : game.instance().getEntities()) if (e instanceof Bot b) return b;
        return null;
    }

    // the harness runs no entity ticks: the bot's are driven here
    private static void ticks(Game game, Supplier<Boolean> done, String what) {
        long deadline = System.currentTimeMillis() + 8000;
        long last = game.tick();
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            if (game.tick() > last) {
                last = game.tick();
                Bot bot = bot(game);
                if (bot != null) bot.tick(System.nanoTime() / 1_000_000);
            }
            Thread.onSpinWait();
        }
        assertTrue(done.get(), what);
    }

    private static Arena arena() {
        Session s = Session.create("practice-fixture", new ShardManager(), x -> {});
        try {
            for (int x = -12; x <= 12; x++) {
                for (int z = -6; z <= 6; z++) s.instance().setBlock(x, 64, z, Block.STONE);
            }
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(-7.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(7.5, 65, 0.5, 90, 0)));
            s.spawn(new Pos(0.5, 70, 0.5));
            return Arena.read(s.save());
        } finally {
            s.close();
        }
    }
}
