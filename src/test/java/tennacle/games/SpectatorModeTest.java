package tennacle.games;

import net.minestom.server.network.packet.client.play.ClientInputPacket;
import net.minestom.server.network.packet.client.play.ClientSpectatorActionPacket;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.platform.compatibility.SpectatorHud;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.network.packet.server.play.CameraPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A spectator's two bodies through the commands: the ghost they enter as, the client's spectator mode, and back. */
class SpectatorModeTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void gameModeCommandsSwitchASpectatorsBodyAndRefuseTheRest() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ModeA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "ModeB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "ModeWatch");
        FakePlayer x = FakePlayer.connect(instance, new Pos(6.5, 65, 6.5), "ModeOutsider");
        Game game = null;
        try {
            Polyp.getInstance().clientInfo().setProtocol(w.player, 47); // 1.8
            w.player.setGameMode(GameMode.SURVIVAL);
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(sumo, null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            MinecraftServer.getCommandManager().execute(w.player, "spectate ModeA");
            assertSame(game, Matches.grotto().spectating(w.player));
            deadline = System.currentTimeMillis() + 4000;
            while (w.player.getDisplayName() == null && System.currentTimeMillis() < deadline) { // the landing lists them
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player), "enters as the ghost");
            assertEquals(GameMode.ADVENTURE, w.player.getGameMode());
            assertTrue(SpectatorHud.hidden(w.player));
            assertEquals(TextDecoration.State.FALSE, w.player.getDisplayName().decoration(TextDecoration.ITALIC),
                    "grey and upright in tab, the scrims row");

            MinecraftServer.getCommandManager().execute(w.player, "gmc");
            assertEquals(GameMode.ADVENTURE, w.player.getGameMode(), "creative is refused while spectating");
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player));

            MinecraftServer.getCommandManager().execute(w.player, "gm spectator");
            assertEquals(SpectatorMode.Mode.TRUE, SpectatorMode.mode(w.player), "/gm spectator is the true body");
            assertEquals(GameMode.SPECTATOR, w.player.getGameMode());
            assertFalse(SpectatorHud.hidden(w.player));

            // the camera: the click arrives as the spectate-entity packet (a 1.8 spectator's attack is rewritten
            // into it by ViaBackwards); a member rides, an outsider is not a target
            var connection = w.player.getPlayerConnection();
            w.sent.clear();
            MinecraftServer.getPacketListenerManager().processClientPacket(new ClientSpectatorActionPacket(x.player.getEntityId()), connection);
            assertNull(SpectatorMode.camera(w.player), "only the watched game's players are cameras");
            MinecraftServer.getPacketListenerManager().processClientPacket(new ClientSpectatorActionPacket(a.player.getEntityId()), connection);
            assertSame(a.player, SpectatorMode.camera(w.player), "the packet rode the member");
            assertEquals(a.player.getEntityId(), w.sent(CameraPacket.class).getLast().cameraId());

            // sneaking, as the input packet carries it, brings the camera home on the tick
            w.sent.clear();
            MinecraftServer.getPacketListenerManager().processClientPacket(new ClientInputPacket(false, false, false, false, false, true, false), connection);
            MinecraftServer.getSchedulerManager().processTick();
            assertNull(SpectatorMode.camera(w.player), "sneak dismounts");
            assertEquals(w.player.getEntityId(), w.sent(CameraPacket.class).getLast().cameraId());
            MinecraftServer.getPacketListenerManager().processClientPacket(new ClientInputPacket(false, false, false, false, false, false, false), connection);

            // a click that still arrives as an attack rides too
            MinecraftServer.getGlobalEventHandler().call(new EntityAttackEvent(w.player, a.player));
            assertSame(a.player, SpectatorMode.camera(w.player));

            MinecraftServer.getCommandManager().execute(w.player, "spec mode ghost");
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player));
            assertNull(SpectatorMode.camera(w.player), "the camera comes home with the body change");
            assertEquals(GameMode.ADVENTURE, w.player.getGameMode());
            assertTrue(SpectatorHud.hidden(w.player));

            MinecraftServer.getCommandManager().execute(w.player, "spec mode true");
            assertEquals(GameMode.SPECTATOR, w.player.getGameMode());
            assertTrue(Spectators.exit(w.player));
            assertNull(SpectatorMode.mode(w.player));
            assertEquals(GameMode.SURVIVAL, w.player.getGameMode(), "the body walked in with comes back");
            assertFalse(SpectatorHud.hidden(w.player));
            MinecraftServer.getCommandManager().execute(w.player, "gmc");
            assertEquals(GameMode.CREATIVE, w.player.getGameMode(), "not spectating: the command sets it as asked");
        } finally {
            Spectators.exit(w.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            w.player.remove();
            x.player.remove();
        }
    }

    @Test
    void aSevenClientStaysAGhost() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SevenA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "SevenB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "SevenWatch");
        Game game = null;
        try {
            Polyp.getInstance().clientInfo().setProtocol(w.player, 5); // 1.7.10
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(sumo, null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            MinecraftServer.getCommandManager().execute(w.player, "spectate SevenA");
            assertSame(game, Matches.grotto().spectating(w.player));
            MinecraftServer.getCommandManager().execute(w.player, "spec mode true");
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player), "1.7 has no spectator mode");
            assertEquals(GameMode.ADVENTURE, w.player.getGameMode());
        } finally {
            Spectators.exit(w.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            w.player.remove();
        }
    }
}
