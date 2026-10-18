package tennacle.games.imports;

import tennacle.Confirm;

import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * "Import Layouts": a mode, then one of its parts, then where from. Each import is one part from one source and
 * takes two clicks, as it replaces what the player has.
 */
public final class ImportMenu {

    private static volatile Function<String, Material> icons = mode -> Material.PAPER;

    private ImportMenu() {}

    /** How a mode is drawn; the lobby's icons, set from above. */
    public static void icons(@NotNull Function<String, Material> byMode) {
        icons = byMode;
    }

    /** Pushed over the current screen: every mode to choose from, or straight to {@code mode}'s parts. */
    public static void open(@NotNull Player viewer, @Nullable String mode) {
        Map<String, List<LayoutImport>> modes = KitImports.byMode();
        if (mode != null && modes.containsKey(mode)) {
            Screens.push(viewer, mode(modes.get(mode)));
            return;
        }
        if (modes.isEmpty()) {
            viewer.sendMessage(Component.text("There's nothing to import yet.", NamedTextColor.RED));
            return;
        }
        Screen.Builder screen = Screen.rows(4).title(Component.text("Import Layouts"));
        List<List<LayoutImport>> rows = List.copyOf(modes.values());
        int first = 9 + (9 - rows.size()) / 2;
        for (int i = 0; i < Math.min(rows.size(), 9); i++) {
            List<LayoutImport> parts = rows.get(i);
            LayoutImport head = parts.getFirst();
            screen.button(first + i, v -> Entry.of(icons.apply(head.mode())).name(head.modeTitle())
                    .text(String.join(", ", parts.stream().map(LayoutImport::partTitle).distinct().toList()) + ".")
                    .action("Click to choose!").render(v), (v, click) -> Screens.push(v, mode(parts)));
        }
        screen.back(31);
        Screens.push(viewer, screen.build());
    }

    // a row per part, a button per source that has it; then the whole mode from each source
    private static Screen mode(List<LayoutImport> imports) {
        LayoutImport head = imports.getFirst();
        Screen.Builder screen = Screen.rows(6).title(Component.text("Import " + head.modeTitle()));
        Map<String, List<LayoutImport>> parts = new LinkedHashMap<>();
        for (LayoutImport entry : imports) parts.computeIfAbsent(entry.part(), p -> new ArrayList<>()).add(entry);
        int row = 1;
        for (List<LayoutImport> sources : parts.values()) {
            if (row > 3) break;
            LayoutImport part = sources.getFirst();
            int base = row * 9;
            screen.item(base + 1, v -> Entry.of(part.icon()).name(part.partTitle()).text("Your " + part.label() + ".").render(v));
            for (int i = 0; i < Math.min(sources.size(), 5); i++) {
                LayoutImport from = sources.get(i);
                ImportSource source = KitImports.sourceOf(from.source());
                if (source == null) continue;
                screen.button(base + 3 + i, v -> sourceButton(source, "Replace your " + from.label() + " with",
                                "the one you saved on " + source.title() + ".").render(v),
                        (v, click) -> confirmed(v, source, from.mode(), from.part(), from.label()));
            }
            row++;
        }
        List<String> sourceIds = imports.stream().map(LayoutImport::source).distinct().toList();
        if (parts.size() > 1) {
            int base = row * 9;
            screen.item(base + 1, v -> Entry.of(Material.BOOK).name("Everything").text("Every " + head.modeTitle() + " part.").render(v));
            for (int i = 0; i < Math.min(sourceIds.size(), 5); i++) {
                ImportSource source = KitImports.sourceOf(sourceIds.get(i));
                if (source == null) continue;
                screen.button(base + 3 + i, v -> sourceButton(source, "Replace every " + head.modeTitle() + " part",
                                "you saved on " + source.title() + ".").render(v),
                        (v, click) -> confirmed(v, source, head.mode(), null, head.modeTitle() + " layouts"));
            }
        }
        screen.back(49);
        return screen.build();
    }

    private static Entry sourceButton(ImportSource source, String... text) {
        Entry entry = Entry.of(source.icon()).name("From " + source.title()).text((Object[]) text);
        if (!source.ready().getAsBoolean()) return entry.line(Component.text("Not set up on this server.", NamedTextColor.RED));
        return entry.action("Click twice to import!");
    }

    private static void confirmed(Player viewer, ImportSource source, String mode, @Nullable String part, String what) {
        if (!Confirm.again(viewer, "kit import " + source.id() + " " + mode + " " + part)) {
            viewer.sendMessage(Component.text("Click again to replace your " + what + " with what you saved on " + source.title() + ".", NamedTextColor.YELLOW));
            return;
        }
        KitImports.run(viewer, source.id(), mode, part);
    }
}
