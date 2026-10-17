package tennacle.social.friends;

import io.github.term4.grotto.vault.VaultKey;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static tennacle.social.Lines.green;
import static tennacle.social.Lines.who;
import static tennacle.social.Lines.yellow;

/** Who is friends with whom, kept on both players' accounts, and the join and leave notices between them. */
public final class Friends {

    /** How many friends a player can have. */
    public static final int MAX = 200;

    /** {@code name}: theirs when the list was last written; {@code since}: epoch millis. */
    public record Friend(@NotNull UUID id, @NotNull String name, long since, boolean best) {
        static final Codec<Friend> CODEC = StructCodec.struct(
                "id", Codec.UUID, Friend::id,
                "name", Codec.STRING, Friend::name,
                "since", Codec.LONG, Friend::since,
                "best", Codec.BOOLEAN, Friend::best,
                Friend::new);
    }

    /** Whose joins and leaves a player hears. */
    public enum Notices { ALL, BEST, OFF }

    // written only through update: a set from a stale cache would drop the other side's write
    static final PlayerPrefs.Entry<List<Friend>, List<Friend>> LIST = PlayerPrefs.register(
            VaultKey.of("social:friends", Friend.CODEC.list(), List.of()));
    static final PlayerPrefs.Entry<String, Notices> NOTICES = PlayerPrefs.register(
            VaultKey.of("social:friend-notices", Codec.STRING, Notices.ALL.name()), Friends::parse, Notices::name);

    private Friends() {}

    public static void install() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) announce(e.getPlayer(), "joined.");
        });
        events.addListener(PlayerDisconnectEvent.class, e -> announce(e.getPlayer(), "left."));
        Requests.install();
        FriendCommands.register();
    }

    /** Whether the two are friends, as far as either one's loaded list says. */
    public static boolean are(@NotNull UUID a, @NotNull UUID b) {
        List<Friend> list = LIST.of(a);
        if (list != null) return list.stream().anyMatch(friend -> friend.id().equals(b));
        list = LIST.of(b);
        return list != null && list.stream().anyMatch(friend -> friend.id().equals(a));
    }

    public static @NotNull CompletableFuture<List<Friend>> of(@NotNull UUID player) {
        return LIST.load(player);
    }

    static @NotNull CompletableFuture<Void> befriend(UUID a, String aName, UUID b, String bName, long now) {
        return CompletableFuture.allOf(
                LIST.update(a, list -> with(list, new Friend(b, bName, now, false))),
                LIST.update(b, list -> with(list, new Friend(a, aName, now, false))));
    }

    static @NotNull CompletableFuture<Void> unfriend(UUID a, UUID b) {
        return CompletableFuture.allOf(LIST.update(a, without(b)), LIST.update(b, without(a)));
    }

    static @NotNull CompletableFuture<List<Friend>> best(UUID owner, UUID friend, boolean best) {
        return LIST.update(owner, list -> list.stream()
                .map(f -> f.id().equals(friend) ? new Friend(f.id(), f.name(), f.since(), best) : f).toList());
    }

    static Notices notices(Player player) {
        Notices held = NOTICES.of(player.getUuid());
        return held != null ? held : Notices.ALL;
    }

    private static List<Friend> with(List<Friend> list, Friend friend) {
        List<Friend> out = new ArrayList<>(list);
        out.removeIf(f -> f.id().equals(friend.id()));
        out.add(friend);
        return out;
    }

    private static UnaryOperator<List<Friend>> without(UUID id) {
        return list -> list.stream().filter(f -> !f.id().equals(id)).toList();
    }

    private static Notices parse(String name) {
        try {
            return Notices.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unreadable) {
            return Notices.ALL;
        }
    }

    // captured: "Friend > Name joined." to every online friend who listens
    static void announce(Player player, String what) {
        Component line = green("Friend > ").append(who(player.getUsername() + " ")).append(yellow(what));
        of(player.getUuid()).thenAccept(list -> {
            for (Friend friend : list) {
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(friend.id());
                if (online == null || online == player) continue;
                Notices hears = notices(online);
                if (hears == Notices.OFF) continue;
                if (hears == Notices.BEST && !bestOf(online.getUuid(), player.getUuid())) continue;
                online.sendMessage(line);
            }
        });
    }

    private static boolean bestOf(UUID owner, UUID friend) {
        List<Friend> list = LIST.of(owner);
        return list != null && list.stream().anyMatch(f -> f.id().equals(friend) && f.best());
    }
}
