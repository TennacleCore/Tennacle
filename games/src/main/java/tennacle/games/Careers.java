package tennacle.games;

import tennacle.storage.StatKeys;

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

        /** A card read off {@code family}'s stats by name ({@code bedwars} + {@code kills} = {@code bedwars:kills}). */
        public static @NotNull Builder of(@NotNull String title, @NotNull String family) {
            return new Builder(title, family);
        }
    }

    public static final class Builder {
        private final String title;
        private final String family;
        private final List<String> stats = new java.util.ArrayList<>();
        private final List<Row> rows = new java.util.ArrayList<>();

        private Builder(String title, String family) {
            this.title = title;
            this.family = family;
        }

        public @NotNull Builder count(@NotNull String label, @NotNull String stat) {
            int i = index(stat);
            rows.add(new Row(label, values -> String.format(java.util.Locale.ROOT, "%,d", values[i])));
            return this;
        }

        /** Each stat added up: iron collected from every source. */
        public @NotNull Builder sum(@NotNull String label, @NotNull String... stats) {
            int[] at = java.util.Arrays.stream(stats).mapToInt(this::index).toArray();
            rows.add(new Row(label, values -> String.format(java.util.Locale.ROOT, "%,d",
                    java.util.Arrays.stream(at).mapToLong(i -> values[i]).sum())));
            return this;
        }

        /** {@code over / under}, as Hypixel shows K/D: two places, and the top alone while nothing is under it. */
        public @NotNull Builder ratio(@NotNull String label, @NotNull String over, @NotNull String under) {
            int a = index(over), b = index(under);
            rows.add(new Row(label, values -> String.format(java.util.Locale.ROOT, "%.2f",
                    values[b] == 0 ? (double) values[a] : (double) values[a] / values[b])));
            return this;
        }

        public @NotNull Builder hours(@NotNull String label, @NotNull String seconds) {
            int i = index(seconds);
            rows.add(new Row(label, values -> (values[i] / 3600) + "h " + (values[i] / 60 % 60) + "m"));
            return this;
        }

        /** A stat kept in tenths of a health point, read as hearts. */
        public @NotNull Builder hearts(@NotNull String label, @NotNull String tenths) {
            int i = index(tenths);
            rows.add(new Row(label, values -> String.format(java.util.Locale.ROOT, "%,d hearts", values[i] / 20)));
            return this;
        }

        public @NotNull Card build() {
            List<VaultKey<Integer>> keys = stats.stream()
                    .map(stat -> VaultKey.of(family + ":" + stat, net.minestom.server.codec.Codec.INT, 0)).toList();
            return new Card(title, keys, List.copyOf(rows));
        }

        private int index(String stat) {
            int i = stats.indexOf(stat);
            if (i >= 0) return i;
            stats.add(stat);
            return stats.size() - 1;
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
        card(mode, card, card);
    }

    /** The menu's {@code card}, and a shorter {@code line} for {@code /stats} in chat. */
    public static synchronized void card(@NotNull String mode, @NotNull Card card, @NotNull Card line) {
        CARDS.put(mode, card);
        card.keys().forEach(StatKeys::register);
        line.keys().forEach(StatKeys::register);
        sheet(mode, (to, uuid, username) -> read(uuid, line).thenAccept(values -> {
            List<Object> parts = new java.util.ArrayList<>(List.of(Msg.v(username), " " + line.title() + ": "));
            for (int i = 0; i < line.rows().size(); i++) {
                Row row = line.rows().get(i);
                parts.add(Msg.v(row.value().apply(values)));
                parts.add(" " + row.label().toLowerCase(java.util.Locale.ROOT) + (i + 1 < line.rows().size() ? ", " : "."));
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
