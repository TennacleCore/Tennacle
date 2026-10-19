package tennacle.moderation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Every ban, mute and kick a player has had, lifted ones included. A database table in production. */
public interface Punishments {

    enum Kind { BAN, MUTE, KICK }

    long FOREVER = Long.MAX_VALUE;

    /** Times in epoch millis; a kick's {@code until} is its {@code at}, so it is never in force. {@code by} null = the console. */
    record Punishment(long id, @NotNull UUID player, @NotNull Kind kind, @NotNull String reason, @Nullable UUID by,
                      @NotNull String byName, long at, long until, @Nullable UUID liftedBy, @NotNull String liftedByName,
                      long liftedAt) {

        public boolean active(long now) {
            return liftedAt == 0 && until > now;
        }

        public boolean permanent() {
            return until == FOREVER;
        }
    }

    @NotNull CompletableFuture<Punishment> add(@NotNull UUID player, @NotNull Kind kind, @NotNull String reason,
                                               @Nullable UUID by, @NotNull String byName, long at, long until);

    /** The one in force, latest first when several overlap. */
    @NotNull CompletableFuture<@Nullable Punishment> active(@NotNull UUID player, @NotNull Kind kind, long now);

    /** Lifts every one of {@code kind} in force; the latest of them, or null when none was. */
    @NotNull CompletableFuture<@Nullable Punishment> lift(@NotNull UUID player, @NotNull Kind kind, @Nullable UUID by,
                                                          @NotNull String byName, long now);

    /** Newest first. */
    @NotNull CompletableFuture<List<Punishment>> history(@NotNull UUID player);
}
