package tennacle.games.watch;

import tennacle.games.Matches;
import tennacle.games.watch.Spectators;
import tennacle.games.Mode;
import net.minestom.server.network.packet.server.play.TeamsPacket;
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
import tennacle.server.ServerWiring;

/** A spectator's two bodies through the commands: the ghost they enter as, the client's spectator mode, and back. */
class SpectatorModeTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        ServerWiring.ensure(instance);
    }

    /** One click, one swap: a client whose main hand changed nothing retries the whole click on the off hand,
     *  and a feather that answered both would land back where it started. */
    @Test
    void theFeatherSwapsOncePerClick() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(20.5, 65, 20.5), "FeatherA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(22.5, 65, 22.5), "FeatherB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(24.5, 65, 24.5), "FeatherWatch");
        Game game = null;
        try {
            Polyp.getInstance().clientInfo().setProtocol(w.player, 47);
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
            settle(() -> Game.of(a.player) != null && Game.of(b.player) != null);
            MinecraftServer.getCommandManager().execute(w.player, "spectate FeatherA");
            settle(() -> Matches.grotto().spectating(w.player) != null);
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player), "enters as the ghost");

            w.player.setHeldItemSlot((byte) Spectators.FEATHER_SLOT);
            click(w.player);
            assertEquals(SpectatorMode.Mode.TRUE, SpectatorMode.mode(w.player), "one click, one swap");
            tick(w.player);
            click(w.player);
            assertEquals(SpectatorMode.Mode.GHOST, SpectatorMode.mode(w.player), "and back, not there and back");
        } finally {
            Spectators.exit(w.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(io.github.term4.grotto.Outcome.aborted());
            a.player.remove();
            b.player.remove();
            w.player.remove();
        }
    }

    /**
     * One right-click on a block a 1.8 client could not use, as the client sends it: the placement, then the
     * use-item behind it, both on the main hand. {@code PlayerControllerMP.onPlayerRightClick} returns the
     * item's own {@code onItemUse}, false for a feather, so {@code Minecraft.rightClickMouse} still runs its
     * {@code sendUseItem} fallback.
     */
    private static void click(net.minestom.server.entity.Player viewer) {
        net.minestom.server.event.EventDispatcher.call(
                new net.minestom.server.event.player.PlayerBlockInteractEvent(viewer,
                        net.minestom.server.entity.PlayerHand.MAIN, viewer.getInstance(),
                        net.minestom.server.instance.block.Block.STONE,
                        new net.minestom.server.coordinate.BlockVec(24, 64, 24),
                        new net.minestom.server.coordinate.Vec(0.5, 1, 0.5),
                        net.minestom.server.instance.block.BlockFace.TOP));
        step(viewer); // the entity ticks between the two packets as often as not - measured on the live server
        net.minestom.server.event.EventDispatcher.call(
                new net.minestom.server.event.player.PlayerUseItemEvent(viewer,
                        net.minestom.server.entity.PlayerHand.MAIN, viewer.getItemInMainHand(), 0));
    }

    private static void step(net.minestom.server.entity.Player viewer) {
        viewer.tick(System.nanoTime() / 1_000_000L);
    }

    // the harness ticks the shard, not a watcher bound to nothing. Four, the client's own right-click delay:
    // nearer than that and a press reads as the tail of the one before it
    private static void tick(net.minestom.server.entity.Player viewer) {
        long was = viewer.getAliveTicks();
        for (int i = 0; i < 4; i++) step(viewer);
        assertTrue(viewer.getAliveTicks() >= was + 4, "the watcher ticked");
    }

    private static void settle(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
    }

    @Test
    void gamemodeSwitchesSpectatorBody() throws InterruptedException {
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
            game = Matches.start(sumo, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
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
                    "gray and upright in tab, the scrims row");
            // no nameplate over a spectator, and a 1.8 client hangs the below-name health off that same gate
            java.util.Set<String> nameless = w.sent(TeamsPacket.class).stream()
                    .filter(t -> t.action() instanceof TeamsPacket.CreateTeamAction create
                            && create.settings().nameTagVisibility() == TeamsPacket.NameTagVisibility.NEVER)
                    .map(TeamsPacket::teamName).collect(java.util.stream.Collectors.toSet());
            assertFalse(nameless.isEmpty(), "the spectators' team hides nameplates");
            assertTrue(w.sent(TeamsPacket.class).stream().anyMatch(t -> nameless.contains(t.teamName())
                            && t.action() instanceof TeamsPacket.AddEntitiesToTeamAction add
                            && add.entities().contains("ModeWatch")),
                    "and the spectator is on it: no name over their head, and no health under it");

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

    /** Vanilla's law over the game's sight rules: a true spectator is unseen by ghosts, a head to true spectators, gone while riding. */
    @Test
    void trueSpectatorSeenBySpectators() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LawA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "LawB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "LawWatchW");
        FakePlayer x = FakePlayer.connect(instance, new Pos(6.5, 65, 6.5), "LawWatchX");
        Game game = null;
        try {
            Polyp.getInstance().clientInfo().setProtocol(w.player, 47);
            Polyp.getInstance().clientInfo().setProtocol(x.player, 47);
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            Spectators.enter(game, w.player, a.player);
            Spectators.enter(game, x.player, a.player);
            assertEquals(net.minestom.server.item.Material.COMPASS, w.player.getInventory().getItemStack(0).material(),
                    "a spectator holds the players' compass");
            assertEquals(2, Spectators.alive(game).size(), "both seats stand");
            assertEquals(a.player.getPosition(), Lineup.aimOf(w.player).get(), "the compass starts on whom they came to watch");
            Spectators.exit(x.player);
            Spectators.enter(game, x.player, w.player); // watching a watcher: their aim carries through
            assertEquals(a.player.getPosition(), Lineup.aimOf(x.player).get(), "a spectator of a spectator inherits the aim");
            Spectators.exit(x.player);
            Spectators.enter(game, x.player, a.player);
            deadline = System.currentTimeMillis() + 4000;
            while ((!x.player.isViewer(w.player) || !w.player.isViewer(x.player)) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertTrue(x.player.isViewer(w.player) && w.player.isViewer(x.player), "two ghosts see each other");

            assertTrue(Spectators.mode(x.player, SpectatorMode.Mode.TRUE));
            assertFalse(x.player.isViewer(w.player), "a ghost's eyes are not spectator eyes: the true spectator vanishes to them");
            assertTrue(w.player.isViewer(x.player), "the true spectator still sees the ghost");
            assertFalse(x.player.isViewer(a.player), "and a member never sees a spectator");

            assertTrue(Spectators.mode(w.player, SpectatorMode.Mode.TRUE));
            assertTrue(x.player.isViewer(w.player), "spectator eyes see the spectator's body - the translucent head");
            assertTrue(SpectatorMode.camera(x.player, a.player));
            assertFalse(x.player.isViewer(w.player), "none while the camera rides");
            SpectatorMode.cameraHome(x.player);
            assertTrue(x.player.isViewer(w.player), "back when it comes home");

            assertTrue(Spectators.mode(x.player, SpectatorMode.Mode.GHOST));
            assertTrue(x.player.isViewer(w.player), "a ghost again: the game's own rule lists them to spectators");
        } finally {
            Spectators.exit(w.player);
            Spectators.exit(x.player);
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
            game = Matches.start(sumo, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
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
