package tennacle.games;

import tennacle.Cooldown;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.hypixel.HypixelApi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.kit.Kit;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Each mode's layouts copied from the player's Hypixel profile: {@code /import}, or a mode's own button. */
public final class HypixelImports {

    /** {@code read} turns the API's player object into the change to make, or null when Hypixel holds none. */
    public record Import(@NotNull String id, @NotNull String title,
                         @NotNull Function<JsonObject, @Nullable Consumer<Player>> read) {}

    private static final Map<String, Import> IMPORTS = new LinkedHashMap<>();
    private static final Cooldown GAP = new Cooldown("hypixel-import", 10_000);

    private HypixelImports() {}

    public static void add(@NotNull Import entry) {
        IMPORTS.put(entry.id(), entry);
    }

    /**
     * A kit's slots from one of Hypixel's Duels layouts ({@code layout_bridge_duel_layout}): {@code names} maps
     * Hypixel's item ids to the kit's names. What Hypixel leaves out keeps its own slot, else the first free one.
     */
    public static void kit(@NotNull String title, @NotNull Kit kit, @NotNull String layout, @NotNull Map<String, String> names) {
        add(new Import(kit.id(), title, player -> {
            JsonObject saved = HypixelApi.object(player, "stats", "Duels", layout);
            if (saved == null) return null;
            Map<String, Integer> slots = new LinkedHashMap<>();
            Set<Integer> taken = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
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

    public static void register() {
        AppCommand command = Commands.player("import", "Copy your layouts from Hypixel.")
                .usage("/import", "every mode's layout Hypixel has for you")
                .usage("/import <mode>", "one mode's only");
        command.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) run(p, null);
        });
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback((sender, ctx, suggestion) -> IMPORTS.keySet().forEach(id ->
                suggestion.addEntry(new net.minestom.server.command.builder.suggestion.SuggestionEntry(id))));
        command.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) run(p, ctx.get(mode).toLowerCase(Locale.ROOT));
        }, mode);
        Commands.register(command);
    }

    /** Imports {@code id}'s layout, or every mode's for null, answering in chat. */
    public static void run(@NotNull Player player, @Nullable String id) {
        List<Import> wanted = id == null ? List.copyOf(IMPORTS.values()) : IMPORTS.containsKey(id) ? List.of(IMPORTS.get(id)) : List.of();
        if (wanted.isEmpty()) {
            Msg.fail(player, "No mode named ", Msg.v(id), ". Modes: " + String.join(", ", IMPORTS.keySet()) + ".");
            return;
        }
        if (!HypixelApi.enabled()) {
            Msg.fail(player, "Importing from Hypixel isn't set up on this server.");
            return;
        }
        if (!GAP.pass(player)) {
            Msg.fail(player, "Wait a moment before importing again.");
            return;
        }
        Msg.say(player, "Fetching your Hypixel layouts...");
        HypixelApi.player(player.getUuid()).whenComplete((profile, failed) -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            if (!player.isOnline()) return;
            if (failed != null) {
                Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
                Msg.fail(player, cause instanceof HypixelApi.Failure ? cause.getMessage() : "Couldn't reach Hypixel. Try again in a minute.");
                return;
            }
            List<String> done = new ArrayList<>();
            for (Import entry : wanted) {
                Consumer<Player> change = entry.read().apply(profile);
                if (change == null) continue;
                change.accept(player);
                done.add(entry.title());
            }
            if (done.isEmpty()) {
                Msg.fail(player, id == null ? "Hypixel has no layouts saved for you." : "Hypixel has no " + wanted.getFirst().title() + " layout saved for you.");
                return;
            }
            Msg.ok(player, "Imported your Hypixel " + String.join(", ", done) + " layout" + (done.size() > 1 ? "s." : "."));
            Screens.repaint(player);
        }));
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
