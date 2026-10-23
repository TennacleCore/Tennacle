package tennacle.games.bedwars;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.consume.ConsumeEvent;
import io.github.term4.polyp.mechanics.consumable.ConsumableConfigResolver.ConsumableContext;
import io.github.term4.polyp.mechanics.consumable.catalog.VanillaConsumables;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.TestWiring;
import tennacle.games.items.ItemGates;
import tennacle.games.items.Unlock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemGatesTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void lockedUntilABedBreaks() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture(false)));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GateA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GateB");
        try {
            for (FakePlayer p : List.of(a, b)) {
                p.player.setInstance(game.instance(), ROOM).join();
                assertTrue(Matches.enter(game, List.of(p.player)));
            }
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            rule(game, "invisibility", "beds 1");
            rule(game, "wool", "own bed");
            rule(game, "tnt", "disabled");
            ItemGates gates = ItemGates.of(game);
            assertNotNull(gates);

            ItemStack invis = Wares.icon(Wares.of("invisibility"), game, a.player);
            assertEquals("invisibility", Wares.pickOf(invis));
            assertTrue(drink(a.player, invis).isCancelled(), "a drink finishing before any bed breaks is refused");
            assertTrue(chat(a).contains("You can't use Invisibility Potion (30 seconds) until a bed breaks!"), chat(a).toString());

            String bTeam = Match.teamOf(game, b.player);
            BlockVec bBed = game.arena().marker(BedWars.BED, bTeam).boxes().getFirst().min();
            BlockVec against = bBed.sub(1, 0, 0);
            a.player.teleport(new Pos(6.5, 65, 0.5)).join();
            a.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL, 8));
            assertTrue(place(a.player, against).isCancelled(), "wool waits for its own bed");

            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.BLUE_BED, Block.AIR, bBed, BlockFace.TOP));
            assertFalse(drink(a.player, invis).isCancelled(), "once a bed is gone, it goes through");
            assertTrue(place(a.player, against).isCancelled(), "a's own bed still stands");
            b.player.teleport(new Pos(6.5, 65, 0.5)).join();
            b.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL, 8));
            assertFalse(place(b.player, against).isCancelled(), "b's bed is gone: b may build");

            assertTrue(gates.disabled(a.player, "tnt"), "a disabled ware");
            assertEquals(null, gates.when(a.player, "tnt"), "no unlock to tell of");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static void rule(Game game, String ware, String unlock) {
        assertNotNull(Wares.of(ware), ware);
        StateKey<Unlock> key = (StateKey<Unlock>) game.type().supportedRules().get("items." + ware);
        assertNotNull(key, "items." + ware);
        game.state().set(Scope.GAME, key, Unlock.parse(unlock));
    }

    private static ConsumeEvent drink(Player player, ItemStack item) {
        var services = Polyp.getInstance().services();
        ConsumeEvent drink = new ConsumeEvent(new ConsumableContext(player, item, PlayerHand.MAIN, VanillaConsumables.POTION, services), services);
        EventDispatcher.call(drink);
        return drink;
    }

    private static PlayerBlockPlaceEvent place(Player player, BlockVec at) {
        var event = new PlayerBlockPlaceEvent(player, player.getInstance(), Block.WHITE_WOOL, BlockFace.TOP, at,
                new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
        EventDispatcher.call(event);
        return event;
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(m -> PlainTextComponentSerializer.plainText().serialize(m.message())).toList();
    }
}
