package tennacle.games.imports;

import tennacle.Cooldown;
import tennacle.Msg;
import tennacle.Seen;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Layouts copied in from elsewhere: each mode's shareable {@link LayoutPart}s, the sources that keep them
 * ({@link ImportSource}, {@link LayoutImport}), and other players, who can share any part. {@link ImportMenu}
 * chooses; {@code /kit import <mode> <part> <source>} and {@code /kit copy <player> <mode> <part>} name one.
 */
public final class KitImports {

    public static final String LAYOUT = "layout";

    /** Someone a layout is copied from. */
    public record Owner(@NotNull UUID id, @NotNull String name) {}

    private static final Map<String, ImportSource> SOURCES = new LinkedHashMap<>();
    private static final List<LayoutPart> PARTS = new ArrayList<>();
    private static final List<LayoutImport> IMPORTS = new ArrayList<>();
    private static final Cooldown GAP = new Cooldown("kit-import", 10_000);

    private KitImports() {}

    public static void source(@NotNull ImportSource source) {
        SOURCES.put(source.id(), source);
    }

    /** A mode's own part; a kit arranged in the inventory is shareable without one. */
    public static void part(@NotNull LayoutPart part) {
        PARTS.removeIf(p -> p.mode().equals(part.mode()) && p.part().equals(part.part()));
        PARTS.add(part);
    }

    public static void add(@NotNull LayoutImport entry) {
        IMPORTS.removeIf(e -> e.source().equals(entry.source()) && e.mode().equals(entry.mode()) && e.part().equals(entry.part()));
        IMPORTS.add(entry);
    }

    /**
     * A kit's slots from a source's slot-to-item object ({@code layout} finds it in the profile): {@code names} maps
     * the source's item ids to the kit's names. What it leaves out keeps its own slot, else the first free one.
     */
    public static void kit(@NotNull String source, @NotNull Kit kit, @NotNull Function<JsonObject, @Nullable JsonObject> layout,
                           @NotNull Map<String, String> names) {
        add(new LayoutImport(source, kit.id(), LAYOUT, profile -> {
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
            return slots(slots);
        }));
    }

    public static @NotNull List<ImportSource> sources() {
        return List.copyOf(SOURCES.values());
    }

    public static @Nullable ImportSource sourceOf(@NotNull String id) {
        return SOURCES.get(id);
    }

    /** Every shareable part grouped by mode, in the order modes registered. */
    public static @NotNull Map<String, List<LayoutPart>> byMode() {
        Map<String, List<LayoutPart>> out = new LinkedHashMap<>();
        for (KitLayouts.Editable kit : KitLayouts.editables()) {
            if (kit.screen() != null) continue;
            out.computeIfAbsent(kit.id(), m -> new ArrayList<>()).add(kitPart(kit));
        }
        for (LayoutPart part : PARTS) {
            List<LayoutPart> mode = out.computeIfAbsent(part.mode(), m -> new ArrayList<>());
            mode.removeIf(p -> p.part().equals(part.part()));
            mode.add(part);
        }
        return out;
    }

    /** The sources keeping {@code mode}'s {@code part}; {@code part} null for any of the mode's. */
    public static @NotNull List<ImportSource> sourcesOf(@NotNull String mode, @Nullable String part) {
        List<ImportSource> out = new ArrayList<>();
        for (ImportSource source : SOURCES.values()) {
            if (IMPORTS.stream().anyMatch(e -> e.source().equals(source.id()) && e.mode().equals(mode) && (part == null || e.part().equals(part)))) {
                out.add(source);
            }
        }
        return out;
    }

    /** Adds {@code import} and {@code copy} to {@code /kit}: a menu to choose from, or one part spelled out. */
    public static void attach(@NotNull AppCommand kit) {
        kit.usage("/kit import [mode]", "choose a layout to copy in from another server")
                .usage("/kit import <mode> <part> <source>", "copy one in directly")
                .usage("/kit copy <player> [mode]", "choose one of their layouts to copy")
                .usage("/kit copy <player> <mode> <part>", "copy it directly");
        var mode = ArgumentType.Word("mode");
        var part = ArgumentType.Word("part");
        var source = ArgumentType.Word("source");
        var owner = ArgumentType.Word("player");
        mode.setSuggestionCallback(Completions.of(() -> byMode().keySet()));
        part.setSuggestionCallback((sender, ctx, suggestion) -> {
            List<LayoutPart> parts = byMode().get(lower(ctx.get(mode)));
            Completions.offer(suggestion, parts != null ? parts.stream().map(LayoutPart::part).toList() : List.of());
        });
        source.setSuggestionCallback((sender, ctx, suggestion) -> Completions.offer(suggestion,
                sourcesOf(lower(ctx.get(mode)), lower(ctx.get(part))).stream().map(ImportSource::id).toList()));
        owner.setSuggestionCallback(Completions.of(() -> MinecraftServer.getConnectionManager().getOnlinePlayers()
                .stream().map(Player::getUsername).toList()));

        Command imports = new Command("import");
        imports.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) ImportMenu.open(p, null);
        });
        imports.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && known(p, lower(ctx.get(mode)))) ImportMenu.open(p, lower(ctx.get(mode)));
        }, mode);
        imports.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, lower(ctx.get(source)), lower(ctx.get(mode)), lower(ctx.get(part)));
        }, mode, part, source);
        kit.addSubcommand(imports);

        Command copies = new Command("copy");
        copies.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) Msg.fail(p, "Whose layout? ", Msg.v("/kit copy <player>"), ".");
        });
        copies.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) owner(p, ctx.get(owner), from -> ImportMenu.copying(p, from, null));
        }, owner);
        copies.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p) || !known(p, lower(ctx.get(mode)))) return;
            owner(p, ctx.get(owner), from -> ImportMenu.copying(p, from, lower(ctx.get(mode))));
        }, owner, mode);
        copies.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) owner(p, ctx.get(owner), from -> copy(p, from, lower(ctx.get(mode)), lower(ctx.get(part))));
        }, owner, mode, part);
        kit.addSubcommand(copies);
    }

    /** Imports {@code mode}'s {@code part} (null for every part) from {@code source}; the answer goes to chat. */
    public static void run(@NotNull Player player, @NotNull String sourceId, @NotNull String mode, @Nullable String part) {
        ImportSource source = SOURCES.get(sourceId);
        if (source == null) {
            Msg.fail(player, "Nowhere to import from named ", Msg.v(sourceId), ". Sources: " + String.join(", ", SOURCES.keySet()) + ".");
            return;
        }
        List<LayoutImport> wanted = IMPORTS.stream().filter(e -> e.source().equals(sourceId) && e.mode().equals(mode)
                && (part == null || e.part().equals(part))).toList();
        if (wanted.isEmpty()) {
            Msg.fail(player, "No " + mode + (part != null ? " " + part : "") + " layout comes from " + source.title() + ".");
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
                Msg.fail(player, reason(failed, "Couldn't reach " + source.title() + ". Try again in a minute."));
                return;
            }
            List<LayoutPart> parts = new ArrayList<>();
            List<JsonElement> values = new ArrayList<>();
            for (LayoutImport entry : wanted) {
                LayoutPart into = partOf(entry.mode(), entry.part());
                JsonElement value = into != null ? entry.read().apply(profile) : null;
                if (value == null) continue;
                parts.add(into);
                values.add(value);
            }
            landed(player, parts, values, "Imported from " + source.title(),
                    wanted.size() == 1 ? "You have no " + label(wanted.getFirst()) + " saved on " + source.title() + "."
                            : "You have none of those saved on " + source.title() + ".");
        }));
    }

    /** Copies {@code from}'s {@code mode} {@code part} (null for every part) onto {@code player}. */
    public static void copy(@NotNull Player player, @NotNull Owner from, @NotNull String mode, @Nullable String part) {
        if (from.id().equals(player.getUuid())) {
            Msg.fail(player, "Those are already your layouts.");
            return;
        }
        List<LayoutPart> modeParts = byMode().get(mode);
        List<LayoutPart> wanted = modeParts == null ? List.of() : modeParts.stream().filter(p -> part == null || p.part().equals(part)).toList();
        if (wanted.isEmpty()) {
            Msg.fail(player, "No " + mode + (part != null ? " " + part : "") + " layout to copy.");
            return;
        }
        if (!GAP.pass(player)) {
            Msg.fail(player, "Wait a moment before copying again.");
            return;
        }
        LayoutVisibility.sees(player, from.id()).whenComplete((visible, hidden) -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            if (!player.isOnline()) return;
            if (!Boolean.TRUE.equals(visible)) {
                Msg.fail(player, from.name() + "'s layouts aren't visible to you.");
                return;
            }
            List<CompletableFuture<JsonElement>> reads = wanted.stream().map(p -> p.export().apply(from.id())).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((done, failed) ->
                    MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                        if (!player.isOnline()) return;
                        if (failed != null) {
                            Msg.fail(player, "Couldn't read " + from.name() + "'s layouts. Try again in a minute.");
                            return;
                        }
                        List<LayoutPart> parts = new ArrayList<>();
                        List<JsonElement> values = new ArrayList<>();
                        for (int i = 0; i < wanted.size(); i++) {
                            JsonElement value = reads.get(i).join();
                            if (value == null) continue;
                            parts.add(wanted.get(i));
                            values.add(value);
                        }
                        landed(player, parts, values, "Copied from " + from.name(),
                                wanted.size() == 1 ? from.name() + " still uses the default " + wanted.getFirst().label() + "."
                                        : from.name() + " still uses the defaults for those.");
                    }));
        }));
    }

    private static void landed(Player player, List<LayoutPart> parts, List<JsonElement> values, String done, String none) {
        if (parts.isEmpty()) {
            Msg.fail(player, none);
            return;
        }
        for (int i = 0; i < parts.size(); i++) parts.get(i).apply().accept(player, values.get(i));
        Msg.ok(player, done + ": " + String.join(", ", parts.stream().map(LayoutPart::label).toList()) + ".");
        Screens.repaint(player);
    }

    // an online name first, then anyone who has been here
    private static void owner(Player asker, String name, Consumer<Owner> then) {
        Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (online != null) {
            then.accept(new Owner(online.getUuid(), online.getUsername()));
            return;
        }
        Seen.find(name).whenComplete((entry, failed) -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            if (!asker.isOnline()) return;
            if (entry == null) Msg.fail(asker, "No one named ", Msg.v(name), " has played here.");
            else then.accept(new Owner(entry.id(), entry.name()));
        }));
    }

    private static boolean known(Player player, String mode) {
        if (byMode().containsKey(mode)) return true;
        Msg.fail(player, "No layouts to share for ", Msg.v(mode), ". Modes: " + String.join(", ", byMode().keySet()) + ".");
        return false;
    }

    private static @Nullable LayoutPart partOf(String mode, String part) {
        List<LayoutPart> parts = byMode().get(mode);
        if (parts == null) return null;
        for (LayoutPart candidate : parts) if (candidate.part().equals(part)) return candidate;
        return null;
    }

    private static String label(LayoutImport entry) {
        LayoutPart part = partOf(entry.mode(), entry.part());
        return part != null ? part.label() : entry.mode() + " " + entry.part();
    }

    private static LayoutPart kitPart(KitLayouts.Editable kit) {
        return new LayoutPart(kit.id(), kit.title(), LAYOUT, "Layout", Material.CHEST,
                owner -> KitLayouts.load(owner, kit.id()).thenApply(slots -> slots != null ? slots(slots) : null),
                (player, value) -> {
                    Map<String, Integer> slots = new LinkedHashMap<>();
                    value.getAsJsonObject().entrySet().forEach(e -> slots.put(e.getKey(), e.getValue().getAsInt()));
                    KitLayouts.set(player, kit.id(), slots);
                });
    }

    private static JsonObject slots(Map<String, Integer> slots) {
        JsonObject out = new JsonObject();
        slots.forEach(out::addProperty);
        return out;
    }

    private static String reason(Throwable failed, String fallback) {
        Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
        String reason = cause.getMessage();
        return reason != null && !reason.isBlank() ? reason : fallback;
    }

    private static String lower(@Nullable String typed) {
        return typed != null ? typed.toLowerCase(Locale.ROOT) : "";
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
