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
 * "Import Layouts": a tab per source across the top, a row per mode beneath with a button per part and one for the
 * whole mode. Each import takes two clicks, as it replaces what the player has.
 */
public final class ImportMenu {

    private static volatile Function<String, Material> icons = mode -> Material.PAPER;

    private ImportMenu() {}

    /** How a mode's row is drawn; the lobby's icons, set from above. */
    public static void icons(@NotNull Function<String, Material> byMode) {
        icons = byMode;
    }

    /** Pushed over the current screen; {@code mode} null for every mode. */
    public static void open(@NotNull Player viewer, @Nullable String mode) {
        List<ImportSource> sources = KitImports.sources();
        if (sources.isEmpty()) {
            viewer.sendMessage(Component.text("There's nowhere to import layouts from yet.", NamedTextColor.RED));
            return;
        }
        Screens.push(viewer, screen(sources.getFirst(), mode));
    }

    private static Screen screen(ImportSource open, @Nullable String only) {
        Screen.Builder screen = Screen.rows(6).title(Component.text("Import Layouts"));
        List<ImportSource> sources = KitImports.sources();
        int first = (9 - sources.size()) / 2;
        for (int i = 0; i < sources.size(); i++) {
            ImportSource source = sources.get(i);
            boolean lit = source == open;
            screen.button(first + i, v -> {
                Entry entry = Entry.of(source.icon()).name(source.title()).glint(lit)
                        .text("Layouts you saved on " + source.title() + ".");
                if (!source.ready().getAsBoolean()) entry.line(Component.text("Not set up on this server.", NamedTextColor.RED));
                return lit ? entry.render(v) : entry.action("Click to view!").render(v);
            }, (v, click) -> {
                if (!lit) Screens.swap(v, screen(source, only));
            });
        }
        Map<String, List<LayoutImport>> modes = new LinkedHashMap<>();
        for (LayoutImport entry : KitImports.of(open.id(), only)) modes.computeIfAbsent(entry.mode(), m -> new ArrayList<>()).add(entry);
        int row = 1;
        for (List<LayoutImport> parts : modes.values()) {
            if (row > 4) break;
            LayoutImport head = parts.getFirst();
            int base = row * 9;
            screen.item(base + 1, v -> Entry.of(icons.apply(head.mode())).name(head.modeTitle())
                    .text("From " + open.title() + ".").render(v));
            for (int i = 0; i < Math.min(parts.size(), 4); i++) {
                LayoutImport part = parts.get(i);
                screen.button(base + 3 + i, v -> Entry.of(part.icon()).name(part.partTitle())
                                .text("Replace your " + part.label() + " with", "the one you saved on " + open.title() + ".")
                                .action("Click twice to import!").render(v),
                        (v, click) -> confirmed(v, open, part.mode(), part.part(), part.label()));
            }
            if (parts.size() > 1) {
                screen.button(base + 8, v -> Entry.of(Material.BOOK).name("Everything")
                                .text("Every " + head.modeTitle() + " part from " + open.title() + ".")
                                .action("Click twice to import!").render(v),
                        (v, click) -> confirmed(v, open, head.mode(), null, head.modeTitle() + " layouts"));
            }
            row++;
        }
        if (modes.isEmpty()) {
            screen.item(22, v -> Entry.of(Material.PAPER).name("Nothing to Import").text("No layouts come from " + open.title() + " yet.").render(v));
        }
        if (only == null && modes.size() > 1) {
            screen.button(50, v -> Entry.of(Material.ENCHANTED_BOOK).name("Import Everything")
                            .text("Every layout you saved on " + open.title() + ".")
                            .action("Click twice to import!").render(v),
                    (v, click) -> confirmed(v, open, null, null, "layouts"));
        }
        screen.back(48);
        return screen.build();
    }

    private static void confirmed(Player viewer, ImportSource source, @Nullable String mode, @Nullable String part, String what) {
        if (!Confirm.again(viewer, "kit import " + source.id() + " " + mode + " " + part)) {
            viewer.sendMessage(Component.text("Click again to replace your " + what + " with what you saved on " + source.title() + ".", NamedTextColor.YELLOW));
            return;
        }
        KitImports.run(viewer, source.id(), mode, part);
    }
}
