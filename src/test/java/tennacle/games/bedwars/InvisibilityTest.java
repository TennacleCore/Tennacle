package tennacle.games.bedwars;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.fall.FallDamage;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.junit.jupiter.api.BeforeAll;
import io.github.term4.polyp.Polyp;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.PotionType;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import io.github.term4.polyp.mechanics.consumable.ConsumableSystem;
import io.github.term4.polyp.mechanics.hunger.HungerSystem;
import io.github.term4.polyp.presets.vanilla18.Consumables;
import org.junit.jupiter.api.Test;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.beds.Lives;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An invisible player to an enemy's eyes and to the hit that breaks it; the dead dressed for their wait. */
class InvisibilityTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        // the harness boots no consumables; a drink needs them
        if (Mechanics.polyp.module(HungerSystem.class) == null) HungerSystem.install(Mechanics.polyp);
        if (Mechanics.polyp.module(ConsumableSystem.class) == null) ConsumableSystem.install(Mechanics.polyp, Consumables.config());
    }

    private interface Body {
        void run(Game game, FakePlayer a, FakePlayer b);
    }

    @Test
    void anEnemySeesNoArmorAndNoNameUntilTheHit() {
        round("InvA", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            a.addEffect(new Potion(PotionEffect.INVISIBILITY, 1, 600, Potion.ICON_FLAG));
            List<EntityEquipmentPacket> worn = fb.sent(EntityEquipmentPacket.class).stream()
                    .filter(p -> p.entityId() == a.getEntityId()).toList();
            assertFalse(worn.isEmpty(), "the armor goes out again in the tick of the drink");
            assertTrue(worn.getLast().equipments().get(EquipmentSlot.HELMET).isAir(), "an enemy sees no helmet: " + worn.getLast().equipments());
            assertTrue(fb.sent(TeamsPacket.class).stream().anyMatch(t -> t.teamName().endsWith("-")
                    && t.action() instanceof TeamsPacket.AddEntitiesToTeamAction add && add.entities().contains(a.getUsername())),
                    "the name sits on the nameless twin team");
            var services = Mechanics.polyp.services();
            game.module(Respawns.class).unprotect(a);
            services.damage().apply(DamageSnapshot.of(a, FallDamage.INSTANCE).withAmount(1f));
            assertTrue(a.hasEffect(PotionEffect.INVISIBILITY), "a fall keeps it");
            fb.sent.clear();
            services.damage().apply(DamageSnapshot.of(a, MeleeDamage.INSTANCE).withSource(b).withAmount(1f));
            assertTrue(a.hasEffect(PotionEffect.INVISIBILITY), "a hit the hurt window eats dealt nothing");
            services.damage().apply(DamageSnapshot.of(a, MeleeDamage.INSTANCE).withSource(b).withAmount(3f));
            assertFalse(a.hasEffect(PotionEffect.INVISIBILITY), "an enemy's hit that lands breaks it");
            assertTrue(chat(fa).contains("You took damage and lost your invisibility!"), chat(fa).toString());
            assertTrue(fb.sent(TeamsPacket.class).stream().anyMatch(t -> !t.teamName().endsWith("-")
                    && t.action() instanceof TeamsPacket.AddEntitiesToTeamAction add && add.entities().contains(a.getUsername())),
                    "and the name comes back");
        });
    }

    @Test
    void theDeadWearInvisibilityAndNightVision() {
        round("InvD", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Respawns respawns = game.module(Respawns.class);
            game.module(Lives.class).fell(a);
            assertTrue(respawns.isDead(a), "a dead wait, the bed standing");
            assertTrue(a.hasEffect(PotionEffect.INVISIBILITY) && a.hasEffect(PotionEffect.NIGHT_VISION), "dressed for the wait");
            game.module(Lives.class).fell(b);
            fb.sent.clear();
            b.sendPacket(a.getMetadataPacket());
            EntityMetaDataPacket seen = fb.sent(EntityMetaDataPacket.class).stream().filter(m -> m.entityId() == a.getEntityId()).toList().getLast();
            Metadata.Entry<?> flags = seen.entries().get(0);
            assertTrue(flags != null && flags.value() instanceof Byte f && (f & 0x20) == 0, "a fellow dead sees the body: " + seen.entries());
            BedWarsRoundTest.tickUntil(game, "the respawn", () -> !respawns.isDead(a));
            assertFalse(a.hasEffect(PotionEffect.INVISIBILITY), "the respawn's body sheds it");
        });
    }

    @Test
    void aDrunkHandEmptiesForTheEnemy() {
        round("DrinkA", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Polyp.getInstance().clientInfo().setProtocol(a, 47);
            Polyp.getInstance().clientInfo().setProtocol(b, 47);
            ItemStack potion = ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                    new PotionContents(PotionType.SWIFTNESS));
            a.getInventory().setItemStack(a.getHeldSlot(), potion);
            assertTrue(a.getViewers().contains(b), "in view");
            fb.sent.clear();
            EventDispatcher.call(new PlayerFinishItemUseEvent(a, PlayerHand.MAIN, potion, 32L));
            assertTrue(a.getItemInMainHand().isAir(), "the bottle goes with the drink");
            List<EntityEquipmentPacket> hands = fb.sent(EntityEquipmentPacket.class).stream()
                    .filter(p -> p.entityId() == a.getEntityId() && p.equipments().containsKey(EquipmentSlot.MAIN_HAND)).toList();
            assertFalse(hands.isEmpty(), "the enemy is told about the hand");
            assertTrue(hands.getLast().equipments().get(EquipmentSlot.MAIN_HAND).isAir(), "and sees it empty");
        });
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name + "F");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            assertEquals("1", Match.teamOf(game, a.player), "the fixture's first side");
            a.sent.clear();
            b.sent.clear();
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
