package tennacle.social.friends;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Confirm;
import tennacle.Msg;
import tennacle.Seen;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.social.Audience;
import tennacle.social.Choice;
import tennacle.social.blocks.Blocks;
import tennacle.storage.Directory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static tennacle.social.Lines.accent;
import static tennacle.social.Lines.bad;
import static tennacle.social.Lines.dim;
import static tennacle.social.Lines.frame;
import static tennacle.social.Lines.good;
import static tennacle.social.Lines.prose;
import static tennacle.social.Lines.rule;
import static tennacle.social.Lines.who;


/** {@code /friend} ({@code /f}, {@code /friends}) and {@code /fl}, worded as Hypixel's are. */
public final class FriendCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(FriendCommands.class);

    /** Who may send a player a friend request. */
    public static final Choice REQUESTS = Choice.of("social:friend-requests", Audience.EVERYONE, Audience.PARTY, Audience.NOBODY);

    private static final String[][] HELP = {
            {"accept", "<player>", "Accept a friend request"},
            {"add", "<player>", "Add a player as a friend"},
            {"best", "<player>", "Toggle a player as best friend"},
            {"deny", "<player>", "Decline a friend request"},
            {"help", "", "Prints all available friend commands."},
            {"list", "<page/best>", "List your friends"},
            {"notifications", "", "Toggle friend join/leave notifications"},
            {"remove", "<player>", "Remove a player from your friends"},
            {"removeall", "", "Remove all your friends (excluding best friends)"},
            {"requests", "<page>", "View friend requests"}};

    private static volatile @Nullable BiConsumer<Player, Player> view;

    private FriendCommands() {}

    /** What clicking an online friend in the list opens; unset, the rows only hover. */
    public static void views(@Nullable BiConsumer<Player, Player> open) {
        view = open;
    }

    static void register() {
        AppCommand friend = Commands.player("friend", "Add friends and see where they are.", "f", "friends")
                .usage("/friend <player>", "send a player a friend request")
                .usage("/friend list [page|best]", "your friends, eight to a page")
                .usage("/friend help", "every friend command")
                .featured();
        var sub = ArgumentType.Word("sub").from("add", "accept", "deny", "remove", "removeall", "best", "list",
                "requests", "notifications", "help", "view");
        var arg = ArgumentType.Word("player");
        arg.setSuggestionCallback(PlayerNames.suggestAll());
        friend.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) help(p);
        });
        friend.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(sub), null);
        }, sub);
        friend.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(sub), ctx.get(arg));
        }, sub, arg);
        friend.addSyntax((sender, ctx) -> { // "/f <player>" reads as a subcommand word: anything else is the add
            if (sender instanceof Player p) add(p, ctx.get(arg));
        }, arg);
        Commands.register(friend);

        AppCommand fl = Commands.player("fl", "Your friends, eight to a page.");
        var page = ArgumentType.Word("page");
        fl.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) list(p, null);
        });
        fl.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) list(p, ctx.get(page));
        }, page);
        Commands.register(fl);
    }

    private static void run(Player me, String sub, @Nullable String arg) {
        switch (sub) {
            case "list" -> list(me, arg);
            case "requests" -> requests(me, arg);
            case "notifications" -> notifications(me);
            case "removeall" -> removeAll(me);
            case "help" -> help(me);
            default -> {
                if (arg == null) {
                    Msg.fail(me, "Invalid usage! '/friend " + sub + " <player>'");
                    return;
                }
                switch (sub) {
                    case "add" -> add(me, arg);
                    case "accept" -> accept(me, arg);
                    case "deny" -> deny(me, arg);
                    case "remove" -> remove(me, arg);
                    case "best" -> best(me, arg);
                    case "view" -> view(me, arg);
                    default -> help(me);
                }
            }
        }
    }

    private static void add(Player me, String name) {
        guard(me, Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.fail(me, "No player found with name " + name + "!");
                return done();
            }
            if (target.id().equals(me.getUuid())) {
                Msg.fail(me, "You can't add yourself as a friend.");
                return done();
            }
            UUID them = target.id();
            var mine = Friends.of(me.getUuid());
            var theirs = Friends.of(them);
            var iBlocked = Blocks.load(me.getUuid(), them);
            var theyBlocked = Blocks.load(them, me.getUuid());
            var asked = Requests.waiting(me.getUuid());
            var privacy = REQUESTS.load(them);
            return CompletableFuture.allOf(mine, theirs, iBlocked, theyBlocked, asked, privacy).thenCompose(loaded -> {
                String theirName = target.name();
                if (mine.join().stream().anyMatch(f -> f.id().equals(them))) {
                    Msg.fail(me, "You're already friends with " + theirName + "!");
                    return done();
                }
                if (iBlocked.join()) {
                    Msg.fail(me, "You blocked " + theirName + ". Use /block remove " + theirName + " first.");
                    return done();
                }
                if (theyBlocked.join()) {
                    Msg.fail(me, "You cannot add that player since they have blocked you.");
                    return done();
                }
                if (asked.join().stream().anyMatch(r -> r.from().equals(them))) return accepted(me, target);
                if (mine.join().size() >= Friends.MAX) {
                    Msg.fail(me, "You can only have " + Friends.MAX + " friends.");
                    return done();
                }
                if (theirs.join().size() >= Friends.MAX) {
                    Msg.fail(me, theirName + "'s friends list is full!");
                    return done();
                }
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(them);
                if (!admits(privacy.join(), me, online)) {
                    Msg.fail(me, theirName + " isn't accepting friend requests.");
                    return done();
                }
                return Requests.send(me.getUuid(), me.getUsername(), them, online != null).thenAccept(filed -> {
                    if (!filed) {
                        frame(me, bad("Please wait before sending this person another request!"));
                        return;
                    }
                    if (online == null) { // after Hypixel's offline guild invite
                        frame(me, prose("You sent an offline friend request to " + theirName
                                + "! They will have 5 minutes to accept once they come online!"));
                        return;
                    }
                    frame(me, prose("You sent a friend request to " + theirName + "! They have 5 minutes to accept it!"));
                    frame(online, prose("Friend request from ").append(who(me.getUsername())), buttons(me.getUsername()));
                });
            });
        }));
    }

    private static boolean admits(Audience audience, Player from, @Nullable Player to) {
        return switch (audience) {
            case EVERYONE -> true;
            case NOBODY -> false;
            default -> to != null && audience.admits(from, to);
        };
    }

    // captured: the three buttons under an incoming request
    private static Component buttons(String from) {
        return button("[ACCEPT]", NamedTextColor.GREEN, "/friend accept " + from, "Click to accept the friend request")
                .append(dim(" - "))
                .append(button("[DENY]", NamedTextColor.RED, "/friend deny " + from, "Click to deny the friend request"))
                .append(dim(" - "))
                .append(button("[BLOCK]", NamedTextColor.GRAY, "/block add " + from,
                        "Click to block all future friend requests and chat messages from this player"));
    }

    private static Component button(String text, NamedTextColor color, String command, String hover) {
        return Component.text(text, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(prose(hover)));
    }

    private static void accept(Player me, String name) {
        guard(me, Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.fail(me, "No player found with name " + name + "!");
                return done();
            }
            return accepted(me, target);
        }));
    }

    private static CompletableFuture<Void> accepted(Player me, Directory.Entry target) {
        var mine = Friends.of(me.getUuid());
        var theirs = Friends.of(target.id());
        return CompletableFuture.allOf(mine, theirs).thenCompose(loaded -> {
            if (mine.join().size() >= Friends.MAX) {
                Msg.fail(me, "You can only have " + Friends.MAX + " friends.");
                return done();
            }
            if (theirs.join().size() >= Friends.MAX) {
                Msg.fail(me, target.name() + "'s friends list is full!");
                return done();
            }
            return Requests.take(me.getUuid(), target.id()).thenCompose(request -> {
                if (request == null) {
                    notInvited(me, target.name());
                    return done();
                }
                return Friends.befriend(me.getUuid(), me.getUsername(), target.id(), target.name(), Requests.clock.getAsLong())
                        .thenRun(() -> {
                            frame(me, good("You are now friends with ").append(who(target.name())));
                            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
                            if (online != null) frame(online, good("You are now friends with ").append(who(me.getUsername())));
                        });
            });
        });
    }

    private static void deny(Player me, String name) {
        guard(me, Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.fail(me, "No player found with name " + name + "!");
                return done();
            }
            return Requests.take(me.getUuid(), target.id()).thenAccept(request -> {
                if (request == null) notInvited(me, target.name());
                else frame(me, prose("Declined " + target.name() + "'s friend request!"));
            });
        }));
    }

    private static void notInvited(Player me, String name) {
        me.sendMessage(bad("That person hasn't invited you to be friends! Try ").append(accent("/friend " + name)));
    }

    private static void remove(Player me, String name) {
        guard(me, friendNamed(me, name).thenCompose(friend -> {
            if (friend == null) {
                Msg.fail(me, name + " isn't on your friends list!");
                return done();
            }
            return Friends.unfriend(me.getUuid(), friend.id()).thenRun(() -> {
                frame(me, prose("You removed ").append(who(friend.name())).append(prose(" from your friends list!")));
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(friend.id());
                if (online != null) frame(online, who(me.getUsername()).append(prose(" removed you from their friends list!")));
            });
        }));
    }

    private static void removeAll(Player me) {
        guard(me, Friends.of(me.getUuid()).thenCompose(list -> {
            List<Friends.Friend> going = list.stream().filter(f -> !f.best()).toList();
            if (going.isEmpty()) {
                Msg.fail(me, "You have no friends to remove. Best friends stay.");
                return done();
            }
            if (!Confirm.again(me, "friend removeall")) {
                frame(me, prose("This removes " + going.size() + " friends, all but your best friends. Run the command again to confirm."));
                return done();
            }
            CompletableFuture<?>[] all = going.stream().map(f -> Friends.unfriend(me.getUuid(), f.id())).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(all).thenRun(() -> frame(me, prose("You removed " + going.size() + " friends.")));
        }));
    }

    private static void best(Player me, String name) {
        guard(me, friendNamed(me, name).thenCompose(friend -> {
            if (friend == null) {
                Msg.fail(me, name + " isn't on your friends list!");
                return done();
            }
            boolean best = !friend.best();
            return Friends.best(me.getUuid(), friend.id(), best).thenRun(() -> frame(me, who(friend.name())
                    .append(best ? good(" is now a best friend!") : prose(" is no longer a best friend!"))));
        }));
    }

    private static void list(Player me, @Nullable String arg) {
        boolean bestOnly = "best".equalsIgnoreCase(arg);
        int page = bestOnly || arg == null ? 1 : parsePage(arg);
        guard(me, Friends.of(me.getUuid()).thenCompose(list -> {
            if (list.isEmpty()) {
                me.sendMessage(prose("You don't have any friends yet! Add some with ").append(accent("/friend add <player>")));
                return done();
            }
            return Seen.directory().byIds(list.stream().map(Friends.Friend::id).toList()).thenAccept(seen ->
                    me.sendMessage(FriendList.page(list, seen, page, bestOnly, view != null, Requests.clock.getAsLong())));
        }));
    }

    // captured: "--- Friend Requests (Page 1 of 1) ---", then a row per request with its buttons
    private static void requests(Player me, @Nullable String arg) {
        int page = arg == null ? 1 : parsePage(arg);
        guard(me, Requests.waiting(me.getUuid()).thenAccept(list -> {
            if (list.isEmpty()) {
                Msg.fail(me, "You have no friend requests.");
                return;
            }
            int pages = (list.size() + FriendList.ROWS - 1) / FriendList.ROWS;
            int at = Math.clamp(page, 1, pages);
            me.sendMessage(prose("--- Friend Requests (Page " + at + " of " + pages + ") ---"));
            for (Requests.Request request : list.subList((at - 1) * FriendList.ROWS, Math.min(list.size(), at * FriendList.ROWS))) {
                me.sendMessage(prose("From ").append(who(request.name())).append(Component.text("  "))
                        .append(button("[ACCEPT]", NamedTextColor.GREEN, "/friend accept " + request.name(), "Click to accept the friend request"))
                        .append(Component.text(" "))
                        .append(button("[DENY]", NamedTextColor.RED, "/friend deny " + request.name(), "Click to deny the friend request"))
                        .append(Component.text(" "))
                        .append(button("[BLOCK]", NamedTextColor.GRAY, "/block add " + request.name(), "Click to block player")));
            }
        }));
    }

    // captured: all, then best friends only, then off, then all again
    private static void notifications(Player me) {
        Friends.Notices next = switch (Friends.notices(me)) {
            case ALL -> Friends.Notices.BEST;
            case BEST -> Friends.Notices.OFF;
            case OFF -> Friends.Notices.ALL;
        };
        Friends.NOTICES.set(me, next);
        frame(me, prose(switch (next) {
            case BEST -> "Enabled best friend join/leave notifications!";
            case OFF -> "Disabled friend join/leave notifications!";
            case ALL -> "Enabled friend join/leave notifications!";
        }));
    }

    private static void view(Player me, String name) {
        BiConsumer<Player, Player> open = view;
        Player friend = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (open == null || friend == null || !Friends.are(me.getUuid(), friend.getUuid())) {
            Msg.fail(me, "That player is not online!");
            return;
        }
        open.accept(me, friend);
    }

    // captured: one framed message, each command suggesting itself on click
    private static void help(Player me) {
        TextComponent.Builder out = Component.text().append(rule()).append(Component.newline())
                .append(good("Friend Commands:"));
        for (String[] row : HELP) {
            String form = "/friend " + row[0] + (row[1].isEmpty() ? "" : " " + row[1]);
            out.append(Component.newline()).append(Component.text()
                    .append(accent(form)).append(dim(" - "))
                    .append(prose(row[2]))
                    .clickEvent(ClickEvent.suggestCommand(form))
                    .hoverEvent(HoverEvent.showText(prose("Click to put the command in chat."))));
        }
        me.sendMessage(out.append(Component.newline()).append(rule()).build());
    }

    private static int parsePage(String arg) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /** The friend {@code name} names, by their current name or the one on the list. */
    private static CompletableFuture<Friends.@Nullable Friend> friendNamed(Player me, String name) {
        return Friends.of(me.getUuid()).thenCombine(Seen.find(name), (list, entry) -> {
            for (Friends.Friend friend : list) {
                if (entry != null && friend.id().equals(entry.id())) return new Friends.Friend(friend.id(), entry.name(), friend.since(), friend.best());
            }
            return list.stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        });
    }

    private static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    private static void guard(Player me, CompletableFuture<?> work) {
        work.exceptionally(failed -> {
            LOGGER.warn("a friend command for {} failed", me.getUsername(), failed);
            Msg.fail(me, "Something went wrong. Try again in a moment.");
            return null;
        });
    }
}
