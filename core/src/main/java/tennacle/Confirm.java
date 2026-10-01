package tennacle;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** An action that asks for itself twice: the second ask within the window goes ahead. */
public final class Confirm {

    /** How long a first ask stands. */
    public static final long WINDOW_MILLIS = 10_000;

    private record Ask(String key, long until) {}

    private static final Map<UUID, Ask> asked = new ConcurrentHashMap<>();
    static volatile LongSupplier clock = System::currentTimeMillis;

    private Confirm() {}

    /** Whether {@code player} asked for {@code key} within the window before now; else this ask is the first. */
    public static boolean again(@NotNull Player player, @NotNull String key) {
        long now = clock.getAsLong();
        asked.values().removeIf(ask -> ask.until() < now);
        Ask last = asked.get(player.getUuid());
        if (last != null && last.key().equals(key)) {
            asked.remove(player.getUuid());
            return true;
        }
        asked.put(player.getUuid(), new Ask(key, now + WINDOW_MILLIS));
        return false;
    }
}
