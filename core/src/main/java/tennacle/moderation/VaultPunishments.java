package tennacle.moderation;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The stand-in: each player's history as one list under their account. Ids count per player. */
public final class VaultPunishments implements Punishments {

    private static final UUID CONSOLE = new UUID(0, 0);

    private static final Codec<Punishment> CODEC = StructCodec.struct(
            "id", Codec.LONG, Punishment::id,
            "player", Codec.UUID_COERCED, Punishment::player,
            "kind", Codec.Enum(Kind.class), Punishment::kind,
            "reason", Codec.STRING, Punishment::reason,
            "by", Codec.UUID_COERCED, p -> p.by() == null ? CONSOLE : p.by(),
            "byName", Codec.STRING, Punishment::byName,
            "at", Codec.LONG, Punishment::at,
            "until", Codec.LONG, Punishment::until,
            "liftedBy", Codec.UUID_COERCED, p -> p.liftedBy() == null ? CONSOLE : p.liftedBy(),
            "liftedByName", Codec.STRING, Punishment::liftedByName,
            "liftedAt", Codec.LONG, Punishment::liftedAt,
            (id, player, kind, reason, by, byName, at, until, liftedBy, liftedByName, liftedAt) -> new Punishment(id,
                    player, kind, reason, CONSOLE.equals(by) ? null : by, byName, at, until,
                    CONSOLE.equals(liftedBy) ? null : liftedBy, liftedByName, liftedAt));

    private static final VaultKey<List<Punishment>> HISTORY = VaultKey.of("moderation:history", CODEC.list(), List.of());

    private final Supplier<Vault> vault;

    public VaultPunishments(@NotNull Supplier<Vault> vault) {
        this.vault = vault;
    }

    @Override
    public @NotNull CompletableFuture<Punishment> add(@NotNull UUID player, @NotNull Kind kind, @NotNull String reason,
                                                      @Nullable UUID by, @NotNull String byName, long at, long until) {
        Punishment[] added = new Punishment[1];
        return vault.get().update(player, HISTORY, list -> {
            long id = list.stream().mapToLong(Punishment::id).max().orElse(0) + 1;
            added[0] = new Punishment(id, player, kind, reason, by, byName, at, until, null, "", 0);
            List<Punishment> out = new ArrayList<>(list);
            out.add(added[0]);
            return out;
        }).thenApply(stored -> added[0]);
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Punishment> active(@NotNull UUID player, @NotNull Kind kind, long now) {
        return vault.get().get(player, HISTORY).thenApply(list -> latest(list, kind, now));
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Punishment> lift(@NotNull UUID player, @NotNull Kind kind,
                                                                 @Nullable UUID by, @NotNull String byName, long now) {
        Punishment[] lifted = new Punishment[1];
        return vault.get().update(player, HISTORY, list -> {
            lifted[0] = latest(list, kind, now);
            if (lifted[0] == null) return list;
            return list.stream().map(p -> p.kind() == kind && p.active(now)
                    ? new Punishment(p.id(), p.player(), p.kind(), p.reason(), p.by(), p.byName(), p.at(), p.until(),
                    by, byName, now)
                    : p).toList();
        }).thenApply(stored -> lifted[0]);
    }

    @Override
    public @NotNull CompletableFuture<List<Punishment>> history(@NotNull UUID player) {
        return vault.get().get(player, HISTORY).thenApply(list ->
                list.stream().sorted(Comparator.comparingLong(Punishment::at).thenComparingLong(Punishment::id).reversed()).toList());
    }

    private static @Nullable Punishment latest(List<Punishment> list, Kind kind, long now) {
        return list.stream().filter(p -> p.kind() == kind && p.active(now))
                .max(Comparator.comparingLong(Punishment::at).thenComparingLong(Punishment::id)).orElse(null);
    }
}
