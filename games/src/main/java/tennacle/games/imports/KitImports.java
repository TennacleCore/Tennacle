package tennacle.games.imports;

import tennacle.Cooldown;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Completions;
import tennacle.games.KitLayouts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.kit.Kit;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Layouts copied in from elsewhere: sources ({@link ImportSource}), and per source each mode's parts
 * ({@link LayoutImport}). {@link ImportMenu} chooses; {@code /kit import <mode> <part> <source>} names one.
 */
public final class KitImports {

    public static final String LAYOUT = "layout";

    private static final Map<String, ImportSource> SOURCES = new LinkedHashMap<>();
    private static final List<LayoutImport> IMPORTS = new ArrayList<>();
    private static final Cooldown GAP = new Cooldown("kit-import", 10_000);

    private KitImports() {}

    public static void source(@NotNull ImportSource source) {
        SOURCES.put(source.id(), source);
    }

    public static void add(@NotNull LayoutImport entry) {
        IMPORTS.removeIf(e -> e.source().equals(entry.source()) && e.mode().equals(entry.mode()) && e.part().equals(entry.part()));
        IMPORTS.add(entry);
    }

    /**
     * A kit's slots from a source's slot-to-item object ({@code layout} finds it in the profile): {@code names} maps
     * the source's item ids to the kit's names. What it leaves out keeps its own slot, else the first free one.
     */
    public static void kit(@NotNull String source, @NotNull String title, @NotNull Kit kit,
                           @NotNull Function<JsonObject, @Nullable JsonObject> layout, @NotNull Map<String, String> names) {
        add(new LayoutImport(source, kit.id(), title, LAYOUT, "Layout", Material.CHEST, profile -> {
            JsonObject saved = layout.apply(profile);
            if (saved == null) return null;
            Map<String, Integer> slots = new LinkedHashMap<>();
            Set<Integer> taken = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String name = names.get(entry.getValue().getAsString());
                int slot = slot(entry.getKey());
                if (name == null || slot < 0 || slots.containsKey(name) || !taken.add(slot)) continue;
                slots.put(name, slot);
            }
            if (slots.isEmpty()) return null;
            kit.defaultSlots().forEach((name, own) -> {
                if (slots.containsKey(name)) return;
                int at = taken.contains(own) ? free(taken) : own;
                if (at < 0) return;
                taken.add(at);
                slots.put(name, at);
            });
            return p -> KitLayouts.set(p, kit.id(), slots);
        }));
    }

    public static @NotNull List<ImportSource> sources() {
        return List.copyOf(SOURCES.values());
    }

    public static @Nullable ImportSource sourceOf(@NotNull String id) {
        return SOURCES.get(id);
    }

    /** {@code source}'s imports, in the order modes registered them; {@code mode} null for every mode. */
    public static @NotNull List<LayoutImport> of(@NotNull String source, @Nullable String mode) {
        return IMPORTS.stream().filter(e -> e.source().equals(source) && (mode == null || e.mode().equals(mode))).toList();
    }

    /** Every import grouped by mode, in the order modes registered them. */
    public static @NotNull Map<String, List<LayoutImport>> byMode() {
        Map<String, List<LayoutImport>> out = new LinkedHashMap<>();
        for (LayoutImport entry : IMPORTS) out.computeIfAbsent(entry.mode(), m -> new ArrayList<>()).add(entry);
        return out;
    }

    /** Adds the {@code import} branch to {@code /kit}: a menu to choose from, or one part from one source spelled out. */
    public static void attach(@NotNull AppCommand kit) {
        kit.usage("/kit import [mode]", "choose a layout to copy in from another server")
                .usage("/kit import <mode> <part> <source>", "copy one in directly");
        Command command = new Command("import");
        var mode = ArgumentType.Word("mode");
        var part = ArgumentType.Word("part");
        var source = ArgumentType.Word("source");
        mode.setSuggestionCallback(Completions.of(() -> byMode().keySet()));
        part.setSuggestionCallback((sender, ctx, suggestion) -> Completions.offer(suggestion,
                matching(ctx.get(mode), null).stream().map(LayoutImport::part).distinct().toList()));
        source.setSuggestionCallback((sender, ctx, suggestion) -> Completions.offer(suggestion,
                matching(ctx.get(mode), ctx.get(part)).stream().map(LayoutImport::source).distinct().toList()));
        command.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) ImportMenu.open(p, null);
        });
        command.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String wanted = ctx.get(mode).toLowerCase(Locale.ROOT);
            if (!byMode().containsKey(wanted)) {
                Msg.fail(p, "Nothing to import for ", Msg.v(wanted), ". Modes: " + String.join(", ", byMode().keySet()) + ".");
                return;
            }
            ImportMenu.open(p, wanted);
        }, mode);
        command.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) {
                run(p, ctx.get(source).toLowerCase(Locale.ROOT), ctx.get(mode).toLowerCase(Locale.ROOT), ctx.get(part).toLowerCase(Locale.ROOT));
            }
        }, mode, part, source);
        kit.addSubcommand(command);
    }

    private static List<LayoutImport> matching(@Nullable String mode, @Nullable String part) {
        return IMPORTS.stream().filter(e -> e.mode().equalsIgnoreCase(Objects.requireNonNullElse(mode, ""))
                && (part == null || e.part().equalsIgnoreCase(part))).toList();
    }

    /** Imports from {@code source}: every mode's parts, one mode's, or one part; the answer goes to chat. */
    public static void run(@NotNull Player player, @NotNull String sourceId, @Nullable String mode, @Nullable String part) {
        ImportSource source = SOURCES.get(sourceId);
        if (source == null) {
            Msg.fail(player, "Nowhere to import from named ", Msg.v(sourceId), ". Sources: " + String.join(", ", SOURCES.keySet()) + ".");
            return;
        }
        List<LayoutImport> wanted = of(sourceId, mode).stream().filter(e -> part == null || e.part().equals(part)).toList();
        if (wanted.isEmpty()) {
            List<LayoutImport> modes = of(sourceId, null);
            if (mode != null && of(sourceId, mode).isEmpty()) {
                Msg.fail(player, "No ", Msg.v(mode), " layouts come from " + source.title() + ". Modes: " + String.join(", ", modes(modes)) + ".");
            } else {
                Msg.fail(player, "No ", Msg.v(part), " part for " + mode + ". Parts: "
                        + String.join(", ", of(sourceId, mode).stream().map(LayoutImport::part).toList()) + ".");
            }
            return;
        }
        if (!source.ready().getAsBoolean()) {
            Msg.fail(player, "Importing from " + source.title() + " isn't set up on this server.");
            return;
        }
        if (!GAP.pass(player)) {
            Msg.fail(player, "Wait a moment before importing again.");
            return;
        }
        Msg.say(player, "Fetching your layouts from " + source.title() + "...");
        source.fetch().apply(player.getUuid()).whenComplete((profile, failed) -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            if (!player.isOnline()) return;
            if (failed != null) {
                Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
                String reason = cause.getMessage();
                Msg.fail(player, reason != null && !reason.isBlank() ? reason : "Couldn't reach " + source.title() + ". Try again in a minute.");
                return;
            }
            List<String> done = new ArrayList<>();
            for (LayoutImport entry : wanted) {
                Consumer<Player> change = entry.read().apply(profile);
                if (change == null) continue;
                change.accept(player);
                done.add(entry.label());
            }
            if (done.isEmpty()) {
                Msg.fail(player, wanted.size() == 1 ? "You have no " + wanted.getFirst().label() + " saved on " + source.title() + "."
                        : "You have none of those saved on " + source.title() + ".");
                return;
            }
            Msg.ok(player, "Imported from " + source.title() + ": " + String.join(", ", done) + ".");
            Screens.repaint(player);
        }));
    }

    private static List<String> modes(List<LayoutImport> imports) {
        return List.copyOf(new LinkedHashSet<>(imports.stream().map(LayoutImport::mode).toList()));
    }

    private static int slot(String key) {
        try {
            int slot = Integer.parseInt(key);
            return slot >= 0 && slot < 36 ? slot : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int free(Set<Integer> taken) {
        for (int i = 0; i < 36; i++) if (!taken.contains(i)) return i;
        return -1;
    }
}
