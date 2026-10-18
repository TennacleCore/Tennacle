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
 * ({@link LayoutImport}). {@code /kit import <source> [mode] [part]}, or {@link ImportMenu}.
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

    /** Adds the {@code import} branch to {@code /kit}. */
    public static void attach(@NotNull AppCommand kit) {
        kit.usage("/kit import <source> [mode] [part]", "copy your layouts in from another server");
        Command command = new Command("import");
        var source = ArgumentType.Word("source");
        var mode = ArgumentType.Word("mode");
        var part = ArgumentType.Word("part");
        source.setSuggestionCallback(Completions.of(SOURCES::keySet));
        mode.setSuggestionCallback((sender, ctx, suggestion) -> Completions.offer(suggestion,
                modes(of(Objects.requireNonNullElse(ctx.get(source), ""), null))));
        part.setSuggestionCallback((sender, ctx, suggestion) -> Completions.offer(suggestion,
                of(Objects.requireNonNullElse(ctx.get(source), ""), ctx.get(mode)).stream().map(LayoutImport::part).toList()));
        command.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Msg.say(p, "Import from: ", Msg.v(String.join(", ", SOURCES.keySet())), ". Try ",
                    Msg.v("/kit import " + (SOURCES.isEmpty() ? "<source>" : SOURCES.keySet().iterator().next())),
                    " for everything, or name a mode and part.");
        });
        command.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(source).toLowerCase(Locale.ROOT), null, null);
        }, source);
        command.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(source).toLowerCase(Locale.ROOT), word(ctx.get(mode)), null);
        }, source, mode);
        command.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(source).toLowerCase(Locale.ROOT), word(ctx.get(mode)), word(ctx.get(part)));
        }, source, mode, part);
        kit.addSubcommand(command);
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

    private static @Nullable String word(@Nullable String typed) {
        return typed != null ? typed.toLowerCase(Locale.ROOT) : null;
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
