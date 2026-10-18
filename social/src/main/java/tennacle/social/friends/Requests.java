package tennacle.social.friends;

import io.github.term4.grotto.vault.VaultKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Arrivals;
import tennacle.PlayerPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static tennacle.social.Lines.accent;
import static tennacle.social.Lines.frame;
import static tennacle.social.Lines.good;
import static tennacle.social.Lines.prose;


/** Friend requests waiting on whoever they were sent to, kept on that player's account. */
final class Requests {

    static final long LIFE_MILLIS = 5 * 60_000;
    static volatile LongSupplier clock = System::currentTimeMillis;

    /** {@code started}: when the five minutes began, 0 until the receiver was online for them. */
    record Request(@NotNull UUID from, @NotNull String name, long sent, long started) {
        static final Codec<Request> CODEC = StructCodec.struct(
                "from", Codec.UUID, Request::from,
                "name", Codec.STRING, Request::name,
                "sent", Codec.LONG, Request::sent,
                "started", Codec.LONG, Request::started,
                Request::new);

        boolean live(long now) {
            return started == 0 || now - started < LIFE_MILLIS;
        }
    }

    static final PlayerPrefs.Entry<List<Request>, List<Request>> LIST = PlayerPrefs.register(
            VaultKey.of("social:friend-inbox", Request.CODEC.list(), List.of()));

    private Requests() {}

    static void install() {
        Arrivals.add(Arrivals.WELCOME + 10, Requests::arrived);
    }

    /** The live requests waiting on {@code to}, oldest first. */
    static CompletableFuture<List<Request>> waiting(UUID to) {
        long now = clock.getAsLong();
        return LIST.load(to).thenApply(list -> list.stream().filter(r -> r.live(now)).toList());
    }

    /** Files a request from {@code from} with {@code to}; false when one from them already stands. */
    static CompletableFuture<Boolean> send(UUID from, String name, UUID to, boolean online) {
        long now = clock.getAsLong();
        AtomicBoolean filed = new AtomicBoolean();
        return LIST.update(to, list -> {
            List<Request> out = new ArrayList<>(list.stream().filter(r -> r.live(now)).toList());
            if (out.stream().anyMatch(r -> r.from().equals(from))) return out;
            out.add(new Request(from, name, now, online ? now : 0));
            filed.set(true);
            return out;
        }).thenApply(list -> filed.get());
    }

    /** Takes the live request from {@code from} off {@code to}'s list; null when none stands. */
    static CompletableFuture<@Nullable Request> take(UUID to, UUID from) {
        long now = clock.getAsLong();
        AtomicReference<Request> taken = new AtomicReference<>();
        return LIST.update(to, list -> {
            List<Request> out = new ArrayList<>();
            for (Request r : list) {
                if (!r.live(now)) continue;
                if (r.from().equals(from)) taken.set(r);
                else out.add(r);
            }
            return out;
        }).thenApply(list -> taken.get());
    }

    /** Starts the clocks that waited for them, then tells them what is waiting. */
    static void arrived(Player player) {
        long now = clock.getAsLong();
        LIST.update(player.getUuid(), list -> list.stream()
                .filter(r -> r.live(now))
                .map(r -> r.started() == 0 ? new Request(r.from(), r.name(), r.sent(), now) : r)
                .toList()).thenAccept(list -> {
            if (list.isEmpty()) return;
            // captured: the count reads "requests" even for one
            Component notice = good("You have " + list.size() + " pending friend requests.\n")
                    .append(prose("Use ")).append(accent("/f requests")).append(prose(" to see them!"))
                    .clickEvent(ClickEvent.runCommand("/f requests"))
                    .hoverEvent(HoverEvent.showText(prose("Click to see ")
                            .append(accent("requests")).append(prose("!"))));
            frame(player, notice);
        });
    }
}
