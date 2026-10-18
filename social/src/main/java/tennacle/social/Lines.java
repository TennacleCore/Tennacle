package tennacle.social;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;

/** The social lines' shared grammar, Hypixel's: a blue rule above and below, names gray as a player with no rank. */
public final class Lines {

    public static final int RULE_WIDTH = 53;

    private Lines() {}

    public static @NotNull Component rule() {
        return Msg.THEME.rule(RULE_WIDTH, NamedTextColor.BLUE);
    }

    public static void frame(@NotNull Player to, @NotNull Component... lines) {
        to.sendMessage(rule());
        for (Component line : lines) to.sendMessage(line);
        to.sendMessage(rule());
    }

    public static @NotNull Component who(@NotNull String name) {
        return Component.text(name, NamedTextColor.GRAY);
    }

    public static @NotNull Component yellow(@NotNull String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    public static @NotNull Component green(@NotNull String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    public static @NotNull Component red(@NotNull String text) {
        return Component.text(text, NamedTextColor.RED);
    }
}
