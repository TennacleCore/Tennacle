package tennacle.games.bridge;

import tennacle.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A refused place, break or hit is named to the player; an arrow off a protected enemy vanishes. */
class RefusalsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(FakePlayer p, String line) {
        return chat(p).stream().anyMatch(l -> l.contains(line));
    }

    /** The verdict lands at the end of a tick the harness has to run itself. */
    private static void settle() {
        for (int i = 0; i < 2; i++) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
    }

    private static void seat(Game game, FakePlayer red, FakePlayer blue) {
        red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
        blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
        assertTrue(game.join(red.player));
        assertTrue(game.join(blue.player));
        await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
        BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
    }

    @Test
    void placeAndBreakRefused() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RefuseRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RefuseBlue");
        try {
            seat(game, red, blue);

            red.sent.clear();
            var above = new PlayerBlockPlaceEvent(red.player, game.instance(), Block.WHITE_TERRACOTTA, BlockFace.TOP,
                    new BlockVec(-3, 74, 1), new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(above);
            assertTrue(above.isCancelled(), "above the height plane");
            settle();
            assertTrue(said(red, "You can't place blocks here!"), chat(red).toString());

            red.sent.clear();
            var map = new PlayerBlockBreakEvent(red.player, game.instance(), Block.STONE, Block.AIR,
                    new BlockVec(0, 60, 0), BlockFace.TOP);
            EventDispatcher.call(map);
            assertTrue(map.isCancelled(), "the map, off the bridge");
            settle();
            assertTrue(said(red, "You can't break this block!"), chat(red).toString());

            red.sent.clear();
            var fine = new PlayerBlockPlaceEvent(red.player, game.instance(), Block.WHITE_TERRACOTTA, BlockFace.TOP,
                    new BlockVec(-3, 65, 1), new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(fine);
            assertFalse(fine.isCancelled(), "in the band");
            settle();
            assertFalse(said(red, "You can't"), "nothing to say: " + chat(red));
            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }

    @Test
    void protectedTargetAndArrow() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ShieldRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "ShieldBlue");
        try {
            seat(game, red, blue);
            Respawns respawns = game.module(Respawns.class);
            assertNotNull(respawns);
            double home = game.arena().marker(Bridge.RESPAWN, Bridge.teamOf(game, blue.player)).pos().x();
            EventDispatcher.call(new PlayerMoveEvent(blue.player, new Pos(0.5, 50, 0.5), true));
            BridgeRoundTest.tickUntil(game, "blue is back", () -> !respawns.isDead(blue.player)
                    && Math.abs(blue.player.getPosition().x() - home) < 0.01 && blue.player.getPosition().y() > 60);
            assertTrue(respawns.isProtected(blue.player), "behind protection");

            red.sent.clear();
            Mechanics.polyp.services().damage().apply(DamageSnapshot.of(blue.player, MeleeDamage.INSTANCE)
                    .withSource(red.player).withAmount(1f));
            settle();
            assertTrue(said(red, "This player has spawn protection!"), chat(red).toString());
            assertTrue(respawns.isProtected(blue.player), "the refused hit takes nothing off the window");

            // four blocks off, facing them: the arrow reaches a target it cannot hurt and dies there
            boolean east = home > 0;
            red.player.teleport(new Pos(east ? home - 4 : home + 4, 65, 0.5, east ? -90f : 90f, 0f)).join();
            var config = Mechanics.polyp.profiles().resolve(red.player, MechanicsKeys.PROJECTILES); // the harness installs no bow
            ProjectileEntity arrow = new ProjectileSystem(Mechanics.polyp, config)
                    .launch(ProjectileSnapshot.of(red.player, Arrow.INSTANCE).withConfig(config));
            assertNotNull(arrow);
            await("the arrow is in the world", () -> arrow.getInstance() != null);
            // no entity ticker in the harness: fly it ourselves, past blue at most
            for (int t = 1; t <= 40 && !arrow.isRemoved(); t++) {
                arrow.tick(t * 50L);
                if (east ? arrow.getPosition().x() > home + 1 : arrow.getPosition().x() < home - 1) break;
            }
            assertTrue(arrow.isRemoved(), "gone on the refusal, not through them: " + arrow.getPosition());
            assertTrue(respawns.isProtected(blue.player), "and they are still protected");
            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }
}
