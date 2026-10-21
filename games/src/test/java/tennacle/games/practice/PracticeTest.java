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
import io.github.term4.polyp.mechanics.attack.AttackSnapshot;
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
import io.github.term4.grotto.vault.MemoryVault;
import tennacle.PlayerPrefs;
import tennacle.boot.Mechanics;
import tennacle.games.Matches;
import tennacle.games.TestWiring;

import java.util.ArrayList;
import java.util.List;
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
        PlayerPrefs.vault(MemoryVault::new);
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
            assertTrue(Trainer.reachTo(bot, p.player.getPosition(), p.player.getBoundingBox()) <= 2.3, "from its reach");
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

    @Test
    void anAirChainBringsItDown() {
        chain("PracB", 0);
    }

    @Test
    void aLaggyChainStaysAirborne() {
        chain("PracE", 200);
    }

    private void chain(String name, int ping) {
        Game game = Matches.launch(Matches.mode("practice"), Matches.platformFor(null), arena());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        List<Double> rises = new ArrayList<>();
        var listener = EventListener.of(KnockbackAppliedEvent.class, e -> {
            if (e.target() instanceof Bot) rises.add(e.velocity().y() / 20);
        });
        MinecraftServer.getGlobalEventHandler().addListener(listener);
        try {
            p.player.setInstance(game.instance(), new Pos(-7.5, 65, 0.5)).join();
            assertTrue(game.join(p.player, game.roster().teams().getFirst().id()));
            Drill.set(p.player, Drill.DEFAULT.with(Drill.Knob.PING_MIN, ping).with(Drill.Knob.PING_MAX, ping));
            ticks(game, () -> bot(game) != null, "the bot is up");
            Bot bot = bot(game);
            bot.place(new Pos(-5.5, 65, 0.5));
            ticks(game, bot::isOnGround, "it stands");
            p.player.setSprinting(true);
            attacks.apply(new AttackSnapshot(p.player, bot, null));
            long until = game.tick() + 10;
            ticks(game, () -> game.tick() >= until, "its hurt time halves");
            assertTrue(!bot.isOnGround(), "still in the air");
            attacks.apply(new AttackSnapshot(p.player, bot, null));
            assertEquals(2, rises.size(), "two knockbacks");
            // the server tracks a jump arc from the takeoff, not the knockback's rise, and not a fall from rest
            assertTrue(rises.get(1) < rises.get(0) - 0.05, "lower than the first: " + rises);
            assertTrue(rises.get(1) > 0.2, "not driven down: " + rises);
        } finally {
            MinecraftServer.getGlobalEventHandler().removeListener(listener);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    @Test
    void itsReducesCutItsKnockback() {
        double plain = pushed(Drill.DEFAULT, false, "PracC");
        double reduced = pushed(Drill.DEFAULT.with(Drill.Knob.REDUCE, 1), false, "PracD");
        assertTrue(reduced < plain * 0.6, "reduced " + reduced + " against " + plain);
    }

    @Test
    void lagKeepsItsKnockback() {
        Drill fighting = Drill.DEFAULT.with(Drill.Knob.CONTINUOUS, 1);
        double prompt = pushed(fighting, true, "PracF");
        double laggy = pushed(fighting.with(Drill.Knob.PING_MIN, 150).with(Drill.Knob.PING_MAX, 150), true, "PracG");
        assertTrue(laggy > prompt * 0.7, "laggy " + laggy + " against " + prompt);
    }

    // how far one sprint hit sends the bot, standing or fighting
    private double pushed(Drill drill, boolean fighting, String name) {
        Game game = Matches.launch(Matches.mode("practice"), Matches.platformFor(null), arena());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        try {
            p.player.setInstance(game.instance(), new Pos(-7.5, 65, 0.5)).join();
            assertTrue(game.join(p.player, game.roster().teams().getFirst().id()));
            Drill.set(p.player, drill);
            ticks(game, () -> bot(game) != null, "the bot is up");
            Bot bot = bot(game);
            bot.place(new Pos(-5.5, 65, 0.5));
            ticks(game, bot::isOnGround, "it stands");
            if (fighting) {
                p.player.setHeldItemSlot((byte) 2);
                EventDispatcher.call(new PlayerUseItemEvent(p.player, PlayerHand.MAIN, p.player.getItemInMainHand(), 0));
            }
            double from = bot.getPosition().x();
            p.player.setSprinting(true);
            attacks.apply(new AttackSnapshot(p.player, bot, null));
            double[] far = {0};
            long until = game.tick() + 20;
            ticks(game, () -> {
                far[0] = Math.max(far[0], bot.getPosition().x() - from);
                return game.tick() >= until;
            }, "it lands");
            return far[0];
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    @Test
    void theServerBodyTrails() {
        Game game = Matches.launch(Matches.mode("practice"), Matches.platformFor(null), arena());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PracH");
        try {
            p.player.setInstance(game.instance(), new Pos(-7.5, 65, 0.5)).join();
            assertTrue(game.join(p.player, game.roster().teams().getFirst().id()));
            Drill.set(p.player, Drill.DEFAULT.with(Drill.Knob.PING_MIN, 200).with(Drill.Knob.PING_MAX, 200));
            ticks(game, () -> bot(game) != null, "the bot is up");
            Bot bot = bot(game);
            p.player.setHeldItemSlot((byte) 2);
            EventDispatcher.call(new PlayerUseItemEvent(p.player, PlayerHand.MAIN, p.player.getItemInMainHand(), 0));
            long until = game.tick() + 10;
            ticks(game, () -> game.tick() >= until, "it runs");
            double behind = bot.getPosition().x() - bot.body().x();
            assertTrue(behind > 0.2, "the server hears it two ticks late, " + behind + " blocks back");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    private static Bot bot(Game game) {
        for (Entity e : game.instance().getEntities()) if (e instanceof Bot b) return b;
        return null;
    }

    // the harness runs no world or entity ticks: the world age and the bot's are driven here
    private static void ticks(Game game, Supplier<Boolean> done, String what) {
        long deadline = System.currentTimeMillis() + 8000;
        long last = game.tick();
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            if (game.tick() > last) {
                last = game.tick();
                game.instance().tick(System.nanoTime() / 1_000_000); // the world age the hurt clock reads
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
