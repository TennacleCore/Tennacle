package tennacle.moderation;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.vault.MemoryVault;
import io.github.term4.polyp.world.WorldPolicy;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.PlayerPrefs;
import tennacle.access.Access;
import tennacle.access.Role;
import tennacle.games.TestWiring;
import tennacle.net.Network;
import tennacle.net.Topics;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        MemoryVault vault = new MemoryVault();
        PlayerPrefs.vault(() -> vault);
        Access.install();
        TestWiring.ensure(instance);
    }

    private static List<String> run(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return plain(p);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void lengthsReadAndParse() {
        assertTrue(Lengths.parse("30m") == 1_800_000L && Lengths.parse("1W") == 604_800_000L);
        assertTrue(Lengths.parse("0h") < 0 && Lengths.parse("5y") < 0 && Lengths.parse("h") < 0);
        assertTrue(Lengths.parse("perm") == Punishments.FOREVER && Lengths.parse("Permanent") == Punishments.FOREVER);
        assertTrue(Lengths.span(7_200_000L).equals("2 hours") && Lengths.span(5_400_000L).equals("2 hours"));
        assertTrue(Lengths.span(1_000L).equals("1 second") && Lengths.span(0).equals("1 second"));
        assertTrue(Lengths.span(3_599_900L).equals("1 hour") && Lengths.span(604_799_000L).equals("1 week"), "just under reads the whole unit");
    }

    @Test
    void aMuteSilencesChatAndMessages() {
        FakePlayer mod = FakePlayer.connect(instance, new Pos(900.5, 65, 0.5), "MuteMod");
        FakePlayer a = FakePlayer.connect(instance, new Pos(902.5, 65, 0.5), "MuteA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(904.5, 65, 0.5), "MuteB");
        try {
            Access.role(mod.player.getUuid(), Role.MOD).join();
            assertTrue(said(run(mod, "mute MuteA 5y"), "isn't a length"));
            a.sent.clear();
            assertTrue(said(run(mod, "mute MuteA 2h spam"), "MuteA is muted for 2 hours."));
            assertTrue(said(plain(a), "You are muted for 2 hours. Reason: spam"), plain(a).toString());

            a.sent.clear();
            PlayerChatEvent chat = new PlayerChatEvent(a.player, List.of(a.player, b.player), "hi");
            EventDispatcher.call(chat);
            assertTrue(chat.isCancelled());
            assertTrue(said(plain(a), "You are muted for another 2 hours. Reason: spam"), plain(a).toString());

            b.sent.clear();
            run(a, "msg MuteB hi");
            assertFalse(said(plain(b), "From MuteA"), "a message is speech too");
            assertTrue(said(run(mod, "mute MuteMod 1h"), "You can't mute MuteMod"), "staff at the same level");

            long now = System.currentTimeMillis();
            Moderation.clock = () -> now + 3 * 3_600_000L;
            try {
                PlayerChatEvent later = new PlayerChatEvent(a.player, List.of(a.player), "back");
                EventDispatcher.call(later);
                assertFalse(later.isCancelled(), "it runs out");
            } finally {
                Moderation.clock = System::currentTimeMillis;
            }

            assertTrue(said(run(mod, "unmute MuteA"), "MuteA can chat again."));
            assertTrue(said(run(mod, "unmute MuteA"), "MuteA isn't muted."));

            a.sent.clear();
            assertTrue(said(run(mod, "mute MuteA perm"), "MuteA is muted permanently."));
            assertTrue(said(plain(a), "You are muted permanently."), plain(a).toString());
            Moderation.clock = () -> now + 520 * 604_800_000L;
            try {
                PlayerChatEvent years = new PlayerChatEvent(a.player, List.of(a.player), "still?");
                EventDispatcher.call(years);
                assertTrue(years.isCancelled(), "it never runs out");
            } finally {
                Moderation.clock = System::currentTimeMillis;
            }
            assertTrue(said(run(mod, "unmute MuteA"), "MuteA can chat again."));
            run(a, "msg MuteB hi");
            assertTrue(said(plain(b), "From MuteA: hi"), plain(b).toString());
        } finally {
            mod.player.remove();
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void bansTurnAwayAndList() {
        FakePlayer mod = FakePlayer.connect(instance, new Pos(940.5, 65, 0.5), "BanMod");
        FakePlayer a = FakePlayer.connect(instance, new Pos(942.5, 65, 0.5), "BanA");
        long now = System.currentTimeMillis();
        Moderation.clock = () -> now;
        try {
            Access.role(mod.player.getUuid(), Role.MOD).join();
            assertTrue(said(run(mod, "history BanA"), "BanA has a clean record."));
            List<String> banned = run(mod, "ban BanA 7d cheating");
            assertTrue(said(banned, "BanA is banned for 1 week."), banned.toString());
            var refused = Bans.refusal(a.player.getUuid());
            assertTrue(refused != null && PlainTextComponentSerializer.plainText().serialize(refused)
                    .equals("You are banned for another 1 week.\n\nReason: cheating"), String.valueOf(refused));
            assertTrue(said(run(mod, "unban BanA"), "BanA can join again."));
            assertTrue(Bans.refusal(a.player.getUuid()) == null, "lifted");
            assertTrue(said(run(mod, "unban BanA"), "BanA isn't banned."));

            List<String> history = run(mod, "history BanA");
            assertTrue(said(history, "BanA has 1 on record"), history.toString());
            assertTrue(said(history, ", for 1 week, by BanMod. Reason: cheating. Lifted by BanMod."), history.toString());
        } finally {
            Moderation.clock = System::currentTimeMillis;
            mod.player.remove();
            a.player.remove();
        }
    }

    @Test
    void anotherServersMuteLands() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(960.5, 65, 0.5), "FarMuteA");
        try {
            long now = System.currentTimeMillis();
            Moderation.store().add(a.player.getUuid(), Punishments.Kind.MUTE, "spam", null, "Panel", now, now + 3_600_000).join();
            a.sent.clear();
            Network.bus().publish(Topics.PUNISHED, new Topics.Punished("api", a.player.getUuid(), "MUTE"));
            tickUntil("the mute lands", () -> said(plain(a), "You are muted for 1 hour. Reason: spam"));
            PlayerChatEvent chat = new PlayerChatEvent(a.player, List.of(a.player), "hi");
            EventDispatcher.call(chat);
            assertTrue(chat.isCancelled());

            Moderation.store().lift(a.player.getUuid(), Punishments.Kind.MUTE, null, "Panel", System.currentTimeMillis()).join();
            Network.bus().publish(Topics.PUNISHED, new Topics.Punished("api", a.player.getUuid(), "MUTE"));
            tickUntil("the lift lands", () -> said(plain(a), "You are no longer muted."));
        } finally {
            a.player.remove();
        }
    }

    private static void tickUntil(String what, java.util.function.BooleanSupplier done) {
        long until = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("never: " + what);
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
    }

    @Test
    void vanishHidesFromPlayersOnly() {
        FakePlayer mod = FakePlayer.connect(instance, new Pos(920.5, 65, 0.5), "VanMod");
        FakePlayer other = FakePlayer.connect(instance, new Pos(922.5, 65, 0.5), "VanMod2");
        FakePlayer a = FakePlayer.connect(instance, new Pos(924.5, 65, 0.5), "VanA");
        try {
            Access.role(mod.player.getUuid(), Role.MOD).join();
            Access.role(other.player.getUuid(), Role.MOD).join();
            assertTrue(WorldPolicy.canSee(a.player, mod.player) && ShardTabList.listed(a.player, mod.player));
            assertTrue(said(run(mod, "vanish"), "You are hidden from players."));
            assertFalse(WorldPolicy.canSee(a.player, mod.player), "out of sight");
            assertFalse(ShardTabList.listed(a.player, mod.player), "off the tab list");
            assertTrue(WorldPolicy.canSee(other.player, mod.player) && ShardTabList.listed(other.player, mod.player), "staff see through it");
            mod.sent.clear();
            List<String> answer = run(a, "msg VanMod hi");
            assertTrue(said(answer, "not online") || said(answer, "Can't find a player"), answer.toString());
            assertFalse(said(plain(mod), "From VanA"));
            assertTrue(said(run(mod, "vanish"), "You are visible again."));
            assertTrue(WorldPolicy.canSee(a.player, mod.player) && ShardTabList.listed(a.player, mod.player));
        } finally {
            mod.player.remove();
            other.player.remove();
            a.player.remove();
        }
    }
}
