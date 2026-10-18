package tennacle.games.imports;

import tennacle.Confirm;
import tennacle.games.imports.KitImports.Owner;

import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * "Import Layouts": a mode, then one of its parts, then where from: a server that keeps it, or another player.
 * Opened for one player ({@link #copying}), that player is the only source. Each copy is one part (or one mode's
 * every part) and takes two clicks, as it replaces what the player has.
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
        show(viewer, null, mode);
    }

    /** The same, every part taken from {@code from}. */
    public static void copying(@NotNull Player viewer, @NotNull Owner from, @Nullable String mode) {
        show(viewer, from, mode);
    }

    private static void show(Player viewer, @Nullable Owner from, @Nullable String mode) {
        Map<String, List<LayoutPart>> modes = KitImports.byMode();
        if (mode != null && modes.containsKey(mode)) {
            Screens.push(viewer, mode(modes.get(mode), from));
            return;
        }
        if (modes.isEmpty()) {
            viewer.sendMessage(Component.text("There are no layouts to share yet.", NamedTextColor.RED));
            return;
        }
        Screen.Builder screen = Screen.rows(4).title(Component.text(from != null ? "Copy " + from.name() + "'s Layouts" : "Import Layouts"));
        List<List<LayoutPart>> rows = List.copyOf(modes.values());
        int first = 9 + (9 - Math.min(rows.size(), 9)) / 2;
        for (int i = 0; i < Math.min(rows.size(), 9); i++) {
            List<LayoutPart> parts = rows.get(i);
            LayoutPart head = parts.getFirst();
            screen.button(first + i, v -> Entry.of(icons.apply(head.mode())).name(head.modeTitle())
                    .text(String.join(", ", parts.stream().map(LayoutPart::partTitle).toList()) + ".")
                    .action("Click to choose!").render(v), (v, click) -> Screens.push(v, mode(parts, from)));
        }
        screen.back(31);
        Screens.push(viewer, screen.build());
    }

    // a row per part, a button per place it can come from; then the whole mode from each
    private static Screen mode(List<LayoutPart> parts, @Nullable Owner from) {
        LayoutPart head = parts.getFirst();
        Screen.Builder screen = Screen.rows(6).title(Component.text((from != null ? "Copy " : "Import ") + head.modeTitle()));
        int row = 1;
        for (LayoutPart part : parts) {
            if (row > 3) break;
            int base = row * 9;
            screen.item(base + 1, v -> Entry.of(part.icon()).name(part.partTitle()).text("Your " + part.label() + ".").render(v));
            sources(screen, base + 3, head.mode(), part.part(), part.label(), from);
            row++;
        }
        if (parts.size() > 1) {
            int base = row * 9;
            screen.item(base + 1, v -> Entry.of(Material.BOOK).name("Everything").text("Every " + head.modeTitle() + " part.").render(v));
            sources(screen, base + 3, head.mode(), null, head.modeTitle() + " layouts", from);
        }
        screen.back(49);
        return screen.build();
    }

    private static void sources(Screen.Builder screen, int at, String mode, @Nullable String part, String what, @Nullable Owner from) {
        if (from != null) {
            screen.button(at, v -> Entry.of(head(from)).name("From " + from.name())
                            .text("Replace your " + what, "with " + from.name() + "'s.").action("Click twice to copy!").render(v),
                    (v, click) -> {
                        if (twice(v, "copy " + from.id() + " " + mode + " " + part, "Click again to replace your " + what + " with " + from.name() + "'s.")) {
                            KitImports.copy(v, from, mode, part);
                        }
                    });
            return;
        }
        List<ImportSource> sources = KitImports.sourcesOf(mode, part);
        for (int i = 0; i < Math.min(sources.size(), 4); i++) {
            ImportSource source = sources.get(i);
            screen.button(at + i, v -> {
                Entry entry = Entry.of(source.icon()).name("From " + source.title())
                        .text("Replace your " + what, "with what you saved on " + source.title() + ".");
                if (!source.ready().getAsBoolean()) return entry.line(Component.text("Not set up on this server.", NamedTextColor.RED)).render(v);
                return entry.action("Click twice to import!").render(v);
            }, (v, click) -> {
                if (twice(v, "import " + source.id() + " " + mode + " " + part,
                        "Click again to replace your " + what + " with what you saved on " + source.title() + ".")) {
                    KitImports.run(v, source.id(), mode, part);
                }
            });
        }
        screen.button(at + Math.min(sources.size(), 4), v -> Entry.of(Material.PLAYER_HEAD).name("From a Player")
                        .text("Copy someone else's " + what + ".").action("Click to choose!").render(v),
                (v, click) -> Screens.push(v, players(v, mode, part, what)));
    }

    // who's online to copy from; anyone else by /kit copy <player>
    private static Screen players(Player viewer, String mode, @Nullable String part, String what) {
        Screen.Builder screen = Screen.rows(6).title(Component.text("Copy From a Player"));
        List<Player> online = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .filter(p -> p != viewer).limit(36).toList();
        for (int i = 0; i < online.size(); i++) {
            Owner from = new Owner(online.get(i).getUuid(), online.get(i).getUsername());
            PlayerSkin skin = online.get(i).getSkin();
            screen.button(9 + i, v -> Entry.of(Items.head(skin, Component.empty())).name(from.name())
                            .text("Copy their " + what + ".").action("Click twice to copy!").render(v),
                    (v, click) -> {
                        if (twice(v, "copy " + from.id() + " " + mode + " " + part, "Click again to replace your " + what + " with " + from.name() + "'s.")) {
                            KitImports.copy(v, from, mode, part);
                        }
                    });
        }
        if (online.isEmpty()) {
            screen.item(22, v -> Entry.of(Material.PAPER).name("No One Else Online")
                    .text("Copy anyone who has played here", "with /kit copy <player>.").render(v));
        }
        screen.back(49);
        return screen.build();
    }

    private static ItemStack head(Owner owner) {
        Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(owner.id());
        return Items.head(online != null ? online.getSkin() : null, Component.empty());
    }

    private static boolean twice(Player viewer, String key, String again) {
        if (Confirm.again(viewer, "kit " + key)) return true;
        viewer.sendMessage(Component.text(again, NamedTextColor.YELLOW));
        return false;
    }
}
