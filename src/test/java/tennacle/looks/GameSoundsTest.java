package tennacle.looks;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Ruleset;
import tennacle.games.TestWiring;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.sound.SoundEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mapped cue plays only where the game's rule lists it and the player has not muted it. */
class GameSoundsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void theTeamVariantIsTheSameName() {
        assertEquals("enemy-down", GameSounds.name("canvas:enemy-down:1"));
        assertEquals("kill", GameSounds.name(SoundSet.KILL));
    }

    @Test
    void aLobbyCueHonorsTheMute() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(644.5, 65, 644.5), "SndC");
        try {
            SoundSet cues = SoundSet.of().cue(SoundSet.PARTY_INVITE,
                    Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f));
            a.sent.clear();
            GameSounds.lobby(a.player, cues, SoundSet.PARTY_INVITE);
            assertEquals(1, a.sent(SoundEffectPacket.class).size(), "an invite pings outside any game");
            MinecraftServer.getCommandManager().execute(a.player, "sounds party-invite off");
            a.sent.clear();
            GameSounds.lobby(a.player, cues, SoundSet.PARTY_INVITE);
            assertTrue(a.sent(SoundEffectPacket.class).isEmpty(), "muted");
        } finally {
            a.player.remove();
        }
    }

    @Test
    void theRuleAndTheMuteGate() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(640.5, 65, 640.5), "SndA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(642.5, 65, 642.5), "SndB");
        Game game = null;
        try {
            Mode bridge = Matches.mode("bridge");
            assertNotNull(bridge);
            game = Matches.start(bridge, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player));

            a.sent.clear();
            Deaths.cue(game, b.player, a.player); // the house maps the fall, not the credit: the killer dings too
            assertEquals(1, a.sent(SoundEffectPacket.class).size(), "an enemy fell: the ding");

            Matches.modify(game, Ruleset.named("teams").rule(GameSounds.CUES, List.of("countdown", "start")));
            a.sent.clear();
            Deaths.cue(game, b.player, a.player);
            assertTrue(a.sent(SoundEffectPacket.class).isEmpty(), "the rule dropped enemy-down");

            Matches.modify(game, Ruleset.named("back").rule(GameSounds.CUES, GameSounds.ALL));
            MinecraftServer.getCommandManager().execute(a.player, "sounds enemy-down off");
            a.sent.clear();
            Deaths.cue(game, b.player, a.player);
            assertTrue(a.sent(SoundEffectPacket.class).isEmpty(), "the player muted it");

            MinecraftServer.getCommandManager().execute(a.player, "sounds enemy-down on");
            a.sent.clear();
            Deaths.cue(game, b.player, a.player);
            assertEquals(1, a.sent(SoundEffectPacket.class).size(), "and unmuted it");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
