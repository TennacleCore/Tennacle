package tennacle;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one leave pipeline: anything a player can be INSIDE - a game seat, a Bloom editing session, a future
 * replay or spectate - registers a membership here, and {@code /l} runs the matching departure instead of
 * every command learning about every world. New session kinds plug in at install.
 */
public final class Sessions {

    /** One kind of "being somewhere": non-null = this player is yours, and running it performs the leave. */
    @FunctionalInterface
    public interface Kind {
        @Nullable Runnable departure(@NotNull Player player);
    }

    private static final List<Kind> kinds = new CopyOnWriteArrayList<>();

    private Sessions() {}

    public static void register(@NotNull Kind kind) {
        kinds.add(kind);
    }

    /** Runs the first matching kind's departure; {@code false} = the player was inside nothing. */
    public static boolean depart(@NotNull Player player) {
        for (Kind kind : kinds) {
            Runnable leave = kind.departure(player);
            if (leave != null) {
                leave.run();
                return true;
            }
        }
        return false;
    }
}
