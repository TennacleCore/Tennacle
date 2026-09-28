package tennacle.games.bedwars;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keepers' and generators' lines, where the captures put them, and the block turning over a generator. */
class DisplaysTest extends HeadlessServerTest {

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
    void keepersAndGeneratorsWearTheirLines() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DisplayA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DisplayB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);

            Point shop = game.arena().marker(BedWars.SHOP, "1").point();
            assertEquals(List.of("ITEM SHOP", "RIGHT CLICK"), names(game, shop));
            assertEquals(shop.y() + 2.63125 - 0.5, stands(game, shop).getFirst().getPosition().y(), 1e-9,
                    "the text where Hypixel's full-size stand draws it");
            assertEquals(List.of("SOLO", "UPGRADES", "RIGHT CLICK"), names(game, game.arena().marker(BedWars.UPGRADES, "1").point()));

            Point diamond = game.arena().markers(BedWars.DIAMOND).getFirst().point();
            List<String> lines = names(game, diamond);
            assertEquals("Tier I", lines.get(0));
            assertEquals("Diamond", lines.get(1));
            assertTrue(lines.get(2).startsWith("Spawns in "), lines.toString());
            String before = lines.get(2);
            long until = game.tick() + 40;
            BedWarsRoundTest.tickUntil(game, "two seconds", () -> game.tick() >= until);
            assertNotEquals(before, names(game, diamond).get(2), "the count moves");

            LivingEntity block = (LivingEntity) game.instance().getEntities().stream()
                    .filter(e -> e instanceof LivingEntity living && living.getHelmet().material() == Material.DIAMOND_BLOCK)
                    .findFirst().orElseThrow();
            float yaw = block.getPosition().yaw();
            long later = game.tick() + 5;
            BedWarsRoundTest.tickUntil(game, "a turn", () -> game.tick() >= later);
            assertNotEquals(yaw, block.getPosition().yaw(), 1e-3, "the block turns");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    // the named stands over a point, top down
    private static List<Entity> stands(Game game, Point at) {
        return game.instance().getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.ARMOR_STAND && e.getCustomName() != null)
                .filter(e -> Math.abs(e.getPosition().x() - at.x()) < 1e-6 && Math.abs(e.getPosition().z() - at.z()) < 1e-6)
                .sorted(Comparator.comparingDouble((Entity e) -> e.getPosition().y()).reversed())
                .toList();
    }

    private static List<String> names(Game game, Point at) {
        return stands(game, at).stream().map(e -> PlainTextComponentSerializer.plainText().serialize(e.getCustomName())).toList();
    }
}
