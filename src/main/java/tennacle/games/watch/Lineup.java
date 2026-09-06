package tennacle.games.watch;

import tennacle.Msg;

import io.github.term4.canvas.screen.ClickRule;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The players alive right now, as heads on a screen; a click lands the viewer at one. A replay reads its ghosts,
 * a live game its seated members - each hands over a {@link Supplier} of {@link Entry entries}, read at the
 * moment the compass is used.
 */
public final class Lineup {

    /** One alive player: the name in the side's color, their skin, and where they stand. */
    public record Entry(@NotNull String name, @NotNull TextColor color, @Nullable PlayerSkin skin,
                        @NotNull Supplier<@Nullable Pos> at) {}

    private static final Component NAME = Component.text("Players", NamedTextColor.AQUA);

    private Lineup() {}

    /** The compass the picker opens from. */
    public static @NotNull ItemStack compass() {
        return Items.named(Material.COMPASS, NAME);
    }

    /** Opens the picker on {@code entries}; nobody alive says so instead. */
    public static void open(@NotNull Player viewer, @NotNull List<Entry> entries) {
        if (entries.isEmpty()) {
            Msg.say(viewer, "Nobody is alive right now.");
            return;
        }
        int rows = Math.min(6, (entries.size() + 8) / 9);
        Screen.Builder screen = Screen.rows(rows).title(NAME).ownClicks(ClickRule.DENY);
        for (int i = 0; i < Math.min(entries.size(), rows * 9); i++) {
            Entry entry = entries.get(i);
            screen.button(i, Items.head(entry.skin(), Component.text(entry.name(), entry.color())), (who, click) -> {
                Pos at = entry.at().get();
                who.closeInventory();
                if (at == null) {
                    Msg.fail(who, Msg.v(entry.name()), " is gone.");
                    return;
                }
                who.teleport(at);
            });
        }
        Screens.open(viewer, screen.build());
    }
}
