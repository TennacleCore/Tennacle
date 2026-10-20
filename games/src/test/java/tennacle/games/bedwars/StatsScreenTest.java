package tennacle.games.bedwars;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.click.Click;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Careers;
import tennacle.games.TestWiring;
import tennacle.storage.StatStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsScreenTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        Careers.install(new StatStore() {
            @Override
            public @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat) {
                return CompletableFuture.completedFuture(0L);
            }

            @Override
            public @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn) {
                return CompletableFuture.completedFuture(0L);
            }

            @Override
            public @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public @NotNull CompletableFuture<Map<String, Long>> all(@NotNull UUID player) {
                return CompletableFuture.completedFuture(Map.of("bedwars:wins", 5L, "bedwars-solo:wins", 2L,
                        "bedwars:diamond-from-own-diamond-generators", 7L, "bedwars:bought-reinforced-armor-2", 3L));
            }
        });
    }

    @Test
    void categoriesReadTheStats() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "StatScreen");
        UUID id = p.player.getUuid();
        try {
            Screens.open(p.player, StatsScreen.open(id, "StatScreen"));
            Screens.repaint(p.player);
            assertTrue(lore(p, 20).contains("Wins | Losses: 5 | 0"), "all modes: " + lore(p, 20));
            assertTrue(lore(p, 31).contains("Own Generators | Other: 7 | 0"), "diamonds: " + lore(p, 31));
            assertTrue(lore(p, 22).contains("Melee: 0 | 0"), "kills | finals: " + lore(p, 22));
            assertTrue(!lore(p, 42).isEmpty(), "the last category has its slot");
            EventDispatcher.call(new InventoryPreClickEvent(Screens.inventory(p.player), p.player, new Click.Left(39)));
            assertTrue(lore(p, 21).stream().anyMatch(l -> l.endsWith(": 3")), "purchases, an upgrade tier by name: " + lore(p, 21));
        } finally {
            p.player.remove();
        }
    }

    @Test
    void soloShowsItsOwn() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "StatSolo");
        try {
            Screens.open(p.player, StatsScreen.open(p.player.getUuid(), "StatSolo"));
            EventDispatcher.call(new InventoryPreClickEvent(Screens.inventory(p.player), p.player, new Click.Left(3)));
            assertTrue(lore(p, 20).contains("Wins | Losses: 2 | 0"), "solo alone: " + lore(p, 20));
        } finally {
            p.player.remove();
        }
    }

    private static List<String> lore(FakePlayer p, int slot) {
        List<Component> lines = Screens.inventory(p.player).getItemStack(slot).get(DataComponents.LORE);
        return lines == null ? List.of() : lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
