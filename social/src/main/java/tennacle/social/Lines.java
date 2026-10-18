package tennacle.social;

import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;

/** The social lines' shared look: Hypixel's shapes (a framed notice, a paged list) in the house theme's roles. */
public final class Lines {

    private Lines() {}

    public static @NotNull Component rule() {
        return Msg.THEME.rule();
    }

    public static void frame(@NotNull Player to, @NotNull Component... lines) {
        to.sendMessage(rule());
        for (Component line : lines) to.sendMessage(line);
        to.sendMessage(rule());
    }

    /** {@code tag » parts}, the house line for a notice with a subject. */
    public static @NotNull Component tagged(@NotNull String tag, @NotNull Object... parts) {
        return Msg.THEME.line(tag, Msg.THEME.mutedColor(), parts);
    }

    public static @NotNull Component who(@NotNull String name) {
        return Component.text(name, Msg.THEME.valueColor());
    }

    public static @NotNull Component value(@NotNull Object value) {
        return Component.text(String.valueOf(value), Msg.THEME.valueColor());
    }

    public static @NotNull Component prose(@NotNull String text) {
        return Component.text(text, Msg.THEME.mutedColor());
    }

    public static @NotNull Component good(@NotNull String text) {
        return Component.text(text, Msg.THEME.goodColor());
    }

    public static @NotNull Component bad(@NotNull String text) {
        return Component.text(text, Msg.THEME.errorColor());
    }

    /** A command or a page arrow: something to click. */
    public static @NotNull Component accent(@NotNull String text) {
        return Component.text(text, Msg.THEME.accentColor());
    }

    public static @NotNull Component dim(@NotNull String text) {
        return Component.text(text, Msg.THEME.dimColor());
    }
}
