package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.npc.Npc;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NpcClickTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
        if (Npcs.standing().isEmpty()) Npcs.install();
    }

    @Test
    void aClickOpensTheGamesMenu() {
        Map<Npc, Npcs.Spec> standing = Npcs.standing();
        assertFalse(standing.isEmpty(), "an NPC per kind of game");
        var first = standing.entrySet().iterator().next();
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 3.5), "NpcClick");
        try {
            EventDispatcher.call(new PlayerEntityInteractEvent(p.player, first.getKey().entity(), PlayerHand.MAIN, Vec.ZERO));
            assertEquals(first.getValue().name(),
                    PlainTextComponentSerializer.plainText().serialize(Screens.inventory(p.player).getTitle()));
        } finally {
            p.player.remove();
        }
    }
}
