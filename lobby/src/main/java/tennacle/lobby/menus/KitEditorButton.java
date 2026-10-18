package tennacle.lobby.menus;

import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import tennacle.games.KitLayouts;

/** A kit's "Layout Editor" button, the same wherever a menu offers one. */
final class KitEditorButton {

    private KitEditorButton() {}

    static void put(@NotNull Screen.Builder screen, int slot, @NotNull KitLayouts.Editable kit, @NotNull Material icon) {
        screen.press(slot, v -> {
            Entry entry = Entry.of(icon).name(kit.title() + " Layout Editor")
                    .text(kit.screen() != null ? "Set what goes in each hotbar slot for " + kit.title() + "."
                            : "Edit your inventory layout for " + kit.title() + ".");
            if (kit.kit().armorMoves()) entry.text("Your armor can be moved too.");
            return entry.action("Click to edit!").render(v);
        }, (v, click) -> {
            String refused = KitLayouts.open(v, kit.id());
            // an editor screen is open now; only the in-inventory editor needs the menu gone
            if (refused == null && kit.screen() == null) v.closeInventory();
            return refused;
        });
    }
}
