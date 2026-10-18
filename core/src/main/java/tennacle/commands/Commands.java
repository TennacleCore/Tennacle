package tennacle.commands;

import tennacle.Msg;

import io.github.term4.canvas.Canvas;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static volatile boolean devTools;

    private Commands() {}

    public static @NotNull AppCommand player(@NotNull String name, @NotNull String blurb, @NotNull String... aliases) {
        return new AppCommand(AppCommand.Audience.PLAYER, blurb, name, aliases);
    }

    public static @NotNull AppCommand dev(@NotNull String name, @NotNull String blurb, @NotNull String... aliases) {
        return new AppCommand(AppCommand.Audience.DEV, blurb, name, aliases);
    }

    /** Whether the dev tools exist on this server ({@code tennacle.dev-commands}); set at boot, before any module installs. */
    public static void devTools(boolean on) {
        devTools = on;
    }

    public static boolean devTools() {
        return devTools;
    }

    /** Registers with Minestom; a command without a default executor answers with its usage. A dev tool on a server without them is not registered at all. */
    public static void register(@NotNull AppCommand command) {
        if (command.audience() == AppCommand.Audience.DEV && !devTools) return;
        if (command.getDefaultExecutor() == null) command.setDefaultExecutor((sender, ctx) -> usage(sender, command));
        MinecraftServer.getCommandManager().register(command);
        registered.put(command.getName(), command);
    }

    /** A library's command, so {@code /help} lists it. */
    public static @NotNull AppCommand describe(@NotNull AppCommand.Audience audience, @NotNull String name,
                                               @NotNull String blurb) {
        AppCommand entry = new AppCommand(audience, blurb, name);
        if (audience == AppCommand.Audience.DEV && !devTools) return entry;
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

    /** By name or alias, any case. */
    public static @Nullable AppCommand find(@NotNull String name) {
        String wanted = name.startsWith("/") ? name.substring(1) : name;
        for (AppCommand command : all()) {
            for (String known : command.getNames()) {
                if (known.equalsIgnoreCase(wanted)) return command;
            }
        }
        return null;
    }

    public static void usage(@NotNull CommandSender to, @NotNull AppCommand command) {
        String[][] rows = command.usages().stream()
                .map(usage -> new String[]{usage.form(), usage.what()})
                .toArray(String[][]::new);
        Msg.usage(to, "/" + command.getName() + " - " + command.blurb(), rows);
    }

    public static void usage(@NotNull CommandSender to, @NotNull String name) {
        AppCommand command = find(name);
        if (command != null) usage(to, command);
    }

    static @NotNull CommandExecutor guard(@NotNull AppCommand command, @NotNull CommandExecutor executor) {
        return (sender, context) -> {
            try {
                executor.apply(sender, context);
            } catch (Throwable t) {
                LOGGER.error("/{} threw", command.getName(), t);
                Msg.fail(sender, "Something went wrong running that command. The log has the details.");
            }
        };
    }

    /** {@code /help [page]}, {@code /help <command>}, {@code /help dev [page]}. */
    public static void help() {
        AppCommand help = player("help", "Every command, or one in detail.")
                .usage("/help [page]", "the commands")
                .usage("/help <command>", "one command's forms")
                .usage("/help dev [page]", "the dev tools");
        var what = ArgumentType.Word("what");
        what.setSuggestionCallback(Completions.of(() -> {
            List<String> names = new ArrayList<>(all().stream().map(AppCommand::getName).toList());
            names.add("dev");
            return names;
        }));
        var page = ArgumentType.Integer("page");
        help.setDefaultExecutor((sender, ctx) -> list(sender, AppCommand.Audience.PLAYER, 1));
        help.addSyntax((sender, ctx) -> one(sender, ctx.get(what), 1), what);
        help.addSyntax((sender, ctx) -> one(sender, ctx.get(what), ctx.get(page)), what, page);
        register(help);
    }

    // a page number, the dev listing, or a command by name
    private static void one(CommandSender sender, String what, int page) {
        String word = what.toLowerCase(Locale.ROOT);
        if (word.chars().allMatch(Character::isDigit)) {
            list(sender, AppCommand.Audience.PLAYER, Integer.parseInt(word));
            return;
        }
        if (word.equals("dev")) {
            list(sender, AppCommand.Audience.DEV, page);
            return;
        }
        AppCommand command = find(word);
        if (command == null) {
            Msg.fail(sender, "No command named ", Msg.v(what), ". ", Msg.run("/help"), " lists them.");
            return;
        }
        usage(sender, command);
    }

    private static void list(CommandSender sender, AppCommand.Audience audience, int page) {
        List<Component> rows = new ArrayList<>();
        for (AppCommand command : all()) {
            if (command.audience() != audience) continue;
            rows.add(Msg.run("/" + command.getName()).append(Msg.THEME.prose(" - " + command.blurb())));
        }
        if (rows.isEmpty()) {
            Msg.fail(sender, "No dev tools are on.");
            return;
        }
        String header = audience == AppCommand.Audience.DEV ? "Dev tools" : "Commands";
        String prefix = audience == AppCommand.Audience.DEV ? "/help dev " : "/help ";
        sender.sendMessage(Msg.THEME.page(Canvas.isFlat(sender), header, rows, page, PER_PAGE, n -> prefix + n));
    }
}
