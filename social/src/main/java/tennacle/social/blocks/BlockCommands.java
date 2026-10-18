package tennacle.social.blocks;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.Seen;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.social.friends.Friends;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static tennacle.social.Lines.good;
import static tennacle.social.Lines.prose;
import static tennacle.social.Lines.value;
import static tennacle.social.Lines.who;


/** {@code /block} ({@code /ignore}) add, remove and list; Hypixel's lines where captured. */
final class BlockCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockCommands.class);
    private static final int ROWS = 10;

    private BlockCommands() {}

    static void register() {
        AppCommand block = Commands.player("block", "Stop a player reaching you, and you them.", "ignore")
                .usage("/block <player>", "block a player")
                .usage("/block remove <player>", "unblock a player")
                .usage("/block list [page]", "everyone you blocked");
        var sub = ArgumentType.Word("sub").from("add", "remove", "list");
        var arg = ArgumentType.Word("player");
        arg.setSuggestionCallback(PlayerNames.suggestAll());
        block.setDefaultExecutor((sender, ctx) -> Commands.usage(sender, "block"));
        block.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (ctx.get(sub).equals("list")) list(p, 1);
            else Msg.fail(p, "Invalid usage! '/block " + ctx.get(sub) + " <player>'");
        }, sub);
        block.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            switch (ctx.get(sub)) {
                case "add" -> add(p, ctx.get(arg));
                case "remove" -> remove(p, ctx.get(arg));
                default -> list(p, page(ctx.get(arg)));
            }
        }, sub, arg);
        block.addSyntax((sender, ctx) -> { // "/block <player>" reads as a subcommand word: anything else is the add
            if (sender instanceof Player p) add(p, ctx.get(arg));
        }, arg);
        Commands.register(block);
    }

    private static void add(Player me, String name) {
        guard(me, Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.fail(me, "No player found with name " + name + "!");
                return done();
            }
            if (target.id().equals(me.getUuid())) {
                Msg.fail(me, "You cannot block yourself.");
                return done();
            }
            AtomicBoolean added = new AtomicBoolean();
            return Blocks.LIST.update(me.getUuid(), list -> {
                if (list.stream().anyMatch(b -> b.id().equals(target.id()))) return list;
                added.set(true);
                List<Blocks.Blocked> more = new ArrayList<>(list);
                more.add(new Blocks.Blocked(target.id(), target.name()));
                return more;
            }).thenCompose(list -> {
                if (!added.get()) {
                    Msg.fail(me, "You've already blocked " + target.name() + "!");
                    return done();
                }
                return Friends.sever(me.getUuid(), target.id()).thenRun(() -> me.sendMessage(good("Blocked " + target.name() + ".")));
            });
        }));
    }

    private static void remove(Player me, String name) {
        AtomicBoolean removed = new AtomicBoolean();
        String[] shown = {name};
        guard(me, Seen.find(name).thenCompose(target -> Blocks.LIST.update(me.getUuid(), list -> {
            List<Blocks.Blocked> left = new ArrayList<>();
            for (Blocks.Blocked blocked : list) {
                boolean match = target != null ? blocked.id().equals(target.id()) : blocked.name().equalsIgnoreCase(name);
                if (match) {
                    removed.set(true);
                    shown[0] = blocked.name();
                } else {
                    left.add(blocked);
                }
            }
            return left;
        })).thenRun(() -> {
            if (removed.get()) me.sendMessage(good("Unblocked " + shown[0] + "."));
            else Msg.fail(me, name + " isn't blocked!");
        }));
    }

    private static void list(Player me, int page) {
        guard(me, Blocks.LIST.load(me.getUuid()).thenAccept(list -> {
            if (list.isEmpty()) {
                Msg.fail(me, "You haven't blocked anyone.");
                return;
            }
            List<Blocks.Blocked> sorted = list.stream()
                    .sorted(Comparator.comparing(blocked -> blocked.name().toLowerCase(Locale.ROOT))).toList();
            int pages = (sorted.size() + ROWS - 1) / ROWS;
            int at = Math.clamp(page, 1, pages);
            // captured: a numbered list, ten to a page, the numbers running on across pages
            me.sendMessage(prose("------ Blocked Players (Page " + at + " of " + pages + ") ------"));
            for (int i = (at - 1) * ROWS; i < Math.min(sorted.size(), at * ROWS); i++) {
                me.sendMessage(value((i + 1) + ". ").append(who(sorted.get(i).name())));
            }
        }));
    }

    private static int page(String arg) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    private static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    private static void guard(Player me, CompletableFuture<?> work) {
        work.exceptionally(failed -> {
            LOGGER.warn("a block command for {} failed", me.getUsername(), failed);
            Msg.fail(me, "Something went wrong. Try again in a moment.");
            return null;
        });
    }
}
