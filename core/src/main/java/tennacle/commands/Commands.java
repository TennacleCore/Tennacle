package tennacle.commands;

import tennacle.Msg;
import tennacle.access.Access;
import tennacle.access.AccessCommands;

import io.github.term4.canvas.Canvas;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.condition.CommandCondition;
import net.minestom.server.event.player.PlayerCommandEvent;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The app's commands, each declared once with its blurb and usage rows: {@code /help}, the welcome screen and a
 * bare command's answer all read from here. A command exists only through {@link #register}, so none ships
 * without a description.
 */
public final class Commands {

    private static final Logger LOGGER = LoggerFactory.getLogger(Commands.class);
    private static final int PER_PAGE = 8;
    private static final Map<String, AppCommand> registered = new LinkedHashMap<>();
    /** Listed, never dispatched here: a library registers these itself. */
    private static final Map<String, AppCommand> described = new LinkedHashMap<>();
    private static final Set<String> adopted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Commands() {}

    public static @NotNull AppCommand player(@NotNull String name, @NotNull String blurb, @NotNull String... aliases) {
        return new AppCommand(AppCommand.Audience.PLAYER, blurb, name, aliases);
    }

    public static @NotNull AppCommand staff(@NotNull String name, @NotNull String blurb, @NotNull String... aliases) {
        return new AppCommand(AppCommand.Audience.STAFF, blurb, name, aliases);
    }

    /**
     * Registers with Minestom behind its node: a player without it never sees it, in the command list or on tab, and
     * running it reads as an unknown command. A command without a default executor answers with its usage.
     */
    public static void register(@NotNull AppCommand command) {
        if (command.getDefaultExecutor() == null) command.setDefaultExecutor((sender, ctx) -> usage(sender, command));
        behind(command, command.node());
        if (command.role() != null) Access.lowest(command.node(), command.role());
        MinecraftServer.getCommandManager().register(command);
        registered.put(command.getName(), command);
    }

    /** Puts what {@code registers} adds to Minestom (a library's own commands) behind {@code prefix + name}. */
    public static void adopt(@NotNull String prefix, @NotNull Runnable registers) {
        CommandManager manager = MinecraftServer.getCommandManager();
        Set<Command> before = Set.copyOf(manager.getCommands());
        registers.run();
        for (Command command : List.copyOf(manager.getCommands())) {
            if (before.contains(command)) continue;
            String node = prefix + command.getName().replace("/", "");
            behind(command, node);
            adopted.add(node);
            manager.unregister(command); // the graph captured the old condition when it was built
            manager.register(command);
        }
    }

    /** The registry's own wiring: {@code /help}, the unknown-command answer, command lists that follow grants, {@code /role}. */
    public static void install() {
        MinecraftServer.getCommandManager().setUnknownCommandCallback((sender, typed) -> unknown(sender));
        MinecraftServer.getGlobalEventHandler().addListener(PlayerCommandEvent.class, e -> e.setCommand(lowerName(e.getCommand())));
        Access.onChange(Player::refreshCommands);
        help();
        AccessCommands.register();
    }

    // Bukkit's: /AC runs /ac; what follows keeps its case, a name or a message being typed as meant
    static @NotNull String lowerName(@NotNull String typed) {
        int space = typed.indexOf(' ');
        String name = space < 0 ? typed : typed.substring(0, space);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals(name) ? typed : lower + (space < 0 ? "" : typed.substring(space));
    }

    /** Every node a command or a usage row asks for: what a grant can name. */
    public static @NotNull Set<String> nodes() {
        Set<String> out = new java.util.TreeSet<>(adopted);
        for (AppCommand command : registered.values()) {
            out.add(command.node());
            for (AppCommand.Usage usage : command.usages()) if (usage.node() != null) out.add(usage.node());
        }
        return out;
    }

    /** Whether {@code sender} sees {@code name}: a console sees everything. */
    public static boolean visible(@NotNull CommandSender sender, @NotNull String name) {
        Command command = MinecraftServer.getCommandManager().getCommand(name);
        if (command == null) return false;
        CommandCondition condition = command.getCondition();
        return condition == null || condition.canUse(sender, null);
    }

    // the condition runs bare while the command list is built, and with what was typed when it runs
    private static void behind(Command command, String node) {
        CommandCondition existing = command.getCondition();
        command.setCondition((sender, typed) -> {
            if (existing != null && !existing.canUse(sender, typed)) return false;
            if (!(sender instanceof Player player) || Access.allows(player, node)) return true;
            if (typed != null) unknown(sender);
            return false;
        });
    }

    // a hidden command and one that doesn't exist answer alike
    private static void unknown(CommandSender sender) {
        Msg.fail(sender, "Unknown command. Type ", Msg.run("/help"), " for help.");
    }

    /** A library's command, so {@code /help} lists it. */
    public static @NotNull AppCommand describe(@NotNull AppCommand.Audience audience, @NotNull String name,
                                               @NotNull String blurb) {
        AppCommand entry = new AppCommand(audience, blurb, name);
        described.put(name, entry);
        return entry;
    }

    public static @NotNull List<AppCommand> all() {
        List<AppCommand> out = new ArrayList<>(registered.values());
        out.addAll(described.values());
        return out;
    }

    public static @NotNull List<AppCommand> featured() {
        return all().stream().filter(AppCommand::isFeatured).toList();
    }

    /** By name or alias, any case: one {@code sender} can see. */
    public static @Nullable AppCommand find(@NotNull CommandSender sender, @NotNull String name) {
        String wanted = name.startsWith("/") ? name.substring(1) : name;
        for (AppCommand command : all()) {
            if (!visible(sender, command.getName())) continue;
            for (String known : command.getNames()) {
                if (known.equalsIgnoreCase(wanted)) return command;
            }
        }
        return null;
    }

    public static void usage(@NotNull CommandSender to, @NotNull AppCommand command) {
        String[][] rows = command.usages().stream()
                .filter(usage -> usage.node() == null || !(to instanceof Player player) || Access.allows(player, usage.node()))
                .map(usage -> new String[]{usage.form(), usage.what()})
                .toArray(String[][]::new);
        Msg.usage(to, "/" + command.getName() + " - " + command.blurb(), rows);
    }

    public static void usage(@NotNull CommandSender to, @NotNull String name) {
        AppCommand command = find(to, name);
        if (command != null) usage(to, command);
    }

    static @NotNull CommandExecutor guard(@NotNull AppCommand command, @NotNull CommandExecutor executor) {
        return (sender, context) -> {
            try {
                executor.apply(sender, context);
            } catch (Throwable t) {
                LOGGER.error("/{} threw", command.getName(), t);
                Msg.fail(sender, "Something went wrong. Try again in a moment.");
            }
        };
    }

    /** {@code /help [page]}, {@code /help <command>}, {@code /help staff [page]}: what the reader can see. */
    private static void help() {
        AppCommand help = player("help", "Every command, or one in detail.")
                .usage("/help [page]", "the commands")
                .usage("/help <command>", "one command's forms")
                .usage("/help staff [page]", "the staff tools");
        var what = ArgumentType.Word("what");
        what.setSuggestionCallback((sender, ctx, suggestion) -> {
            List<String> names = new ArrayList<>(all().stream().map(AppCommand::getName).filter(name -> visible(sender, name)).toList());
            if (all().stream().anyMatch(c -> c.audience() == AppCommand.Audience.STAFF && visible(sender, c.getName()))) names.add("staff");
            Completions.offer(suggestion, names);
        });
        var page = ArgumentType.Integer("page");
        help.setDefaultExecutor((sender, ctx) -> list(sender, AppCommand.Audience.PLAYER, 1));
        help.addSyntax((sender, ctx) -> one(sender, ctx.get(what), 1), what);
        help.addSyntax((sender, ctx) -> one(sender, ctx.get(what), ctx.get(page)), what, page);
        register(help);
    }

    // a page number, the staff listing, or a command by name
    private static void one(CommandSender sender, String what, int page) {
        String word = what.toLowerCase(Locale.ROOT);
        if (word.chars().allMatch(Character::isDigit)) {
            list(sender, AppCommand.Audience.PLAYER, Integer.parseInt(word));
            return;
        }
        if (word.equals("staff")) {
            list(sender, AppCommand.Audience.STAFF, page);
            return;
        }
        AppCommand command = find(sender, word);
        if (command == null) {
            Msg.fail(sender, "No command named ", Msg.v(what), ". ", Msg.run("/help"), " lists them.");
            return;
        }
        usage(sender, command);
    }

    private static void list(CommandSender sender, AppCommand.Audience audience, int page) {
        List<Component> rows = new ArrayList<>();
        for (AppCommand command : all()) {
            if (command.audience() != audience || !visible(sender, command.getName())) continue;
            rows.add(Msg.run("/" + command.getName()).append(Msg.THEME.prose(" - " + command.blurb())));
        }
        if (rows.isEmpty()) {
            Msg.fail(sender, audience == AppCommand.Audience.STAFF ? "You have no staff tools." : "You have no commands.");
            return;
        }
        String header = audience == AppCommand.Audience.STAFF ? "Staff tools" : "Commands";
        String prefix = audience == AppCommand.Audience.STAFF ? "/help staff " : "/help ";
        sender.sendMessage(Msg.THEME.page(Canvas.isFlat(sender), header, rows, page, PER_PAGE, n -> prefix + n));
    }
}
