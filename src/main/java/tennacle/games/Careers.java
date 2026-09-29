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

/** {@code /stats [player]}: every mode's career line off the vault, in the order the modes signed up. */
public final class Careers {

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
