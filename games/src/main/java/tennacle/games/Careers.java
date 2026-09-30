package tennacle.games;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import io.github.term4.polyp.platform.player.PlayerNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /stats [player]}: every mode's career line off the vault, in the order the modes signed up. A mode that
 * signs up a {@link Card} gets its line from it, and the menu's profile reads the same card.
 */
public final class Careers {

    /** One number on a card: its label, and how it reads off the card's values. */
    public record Row(@NotNull String label, @NotNull java.util.function.Function<int[], Object> value) {}

    /** A mode's career numbers: the keys read, then the rows made of them. */
    public record Card(@NotNull String title, @NotNull List<VaultKey<Integer>> keys, @NotNull List<Row> rows) {

        /** A row showing key {@code index} as it is. */
        public static @NotNull Row count(@NotNull String label, int index) {
            return new Row(label, values -> values[index]);
        }
    }

    private static final Map<String, Card> CARDS = new LinkedHashMap<>();

    /** One mode's line for a player, said to {@code to}. */
    @FunctionalInterface
    public interface Sheet {
        @NotNull CompletableFuture<Void> show(@NotNull CommandSender to, @NotNull UUID uuid, @NotNull String username);
    }

    private static final Map<String, Sheet> SHEETS = new LinkedHashMap<>();

    private Careers() {}

    public static synchronized void sheet(@NotNull String mode, @NotNull Sheet sheet) {
        SHEETS.put(mode, sheet);
    }

    /** Signs {@code mode} up with a card; its {@code /stats} line reads the card. */
    public static synchronized void card(@NotNull String mode, @NotNull Card card) {
        CARDS.put(mode, card);
        sheet(mode, (to, uuid, username) -> read(uuid, card).thenAccept(values -> {
            List<Object> parts = new java.util.ArrayList<>(List.of(Msg.v(username), " " + card.title() + ": "));
            for (int i = 0; i < card.rows().size(); i++) {
                Row row = card.rows().get(i);
                parts.add(Msg.v(row.value().apply(values)));
                parts.add(" " + row.label().toLowerCase(java.util.Locale.ROOT) + (i + 1 < card.rows().size() ? ", " : "."));
            }
            Msg.say(to, parts.toArray());
        }));
    }

    /** Every card, by mode, in the order the modes signed up. */
    public static synchronized @NotNull Map<String, Card> cards() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(CARDS));
    }

    /** {@code card}'s values for {@code uuid}. */
    public static @NotNull CompletableFuture<int[]> read(@NotNull UUID uuid, @NotNull Card card) {
        return read(Matches.grotto().vault(), uuid, card.keys());
    }

    /** {@code keys}' values for {@code uuid}, in order. */
    public static @NotNull CompletableFuture<int[]> read(@NotNull Vault vault, @NotNull UUID uuid, @NotNull List<VaultKey<Integer>> keys) {
        List<CompletableFuture<Integer>> reads = keys.stream().map(key -> vault.get(uuid, key)).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .thenApply(unused -> reads.stream().mapToInt(CompletableFuture::join).toArray());
    }

    public static void register() {
        AppCommand stats = Commands.player("stats", "Career numbers, mode by mode.")
                .usage("/stats [player]", "yours, or theirs")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggest());
        stats.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) show(p, p.getUsername(), p.getUuid());
        });
        stats.addSyntax((sender, ctx) -> {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null) {
                Msg.fail(sender, "No player named ", Msg.v(ctx.get(name)), ".");
                return;
            }
            show(sender, target.getUsername(), target.getUuid());
        }, name);
        Commands.register(stats);
    }

    private static void show(CommandSender to, String username, UUID uuid) {
        List<Sheet> sheets;
        synchronized (Careers.class) {
            sheets = List.copyOf(SHEETS.values());
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Sheet sheet : sheets) chain = chain.thenCompose(unused -> sheet.show(to, uuid, username));
        chain.exceptionally(failed -> { // an unread failure here is a command that answers nothing at all
            Msg.fail(to, "Could not read those stats.");
            return null;
        });
    }
}
