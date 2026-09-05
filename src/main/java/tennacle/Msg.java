package tennacle;

import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;

/**
 * The Tennacle look, as a Canvas {@link Theme}: a light blue to light pink gradient on tags and titles (flat
 * light purple for a legacy client), light gray prose, light blue values, light purple accents and
 * click-to-suggest commands, red errors. Installed as the server theme at boot; these statics are the app's
 * shorthand over it.
 */
public final class Msg {

    /** The 1.8 wire's own colors, so both generations read the same; the gradient is the modern extra. */
    public static final Theme THEME = Theme.builder()
            .brand(NamedTextColor.LIGHT_PURPLE)
            .gradient(0x55FFFF, 0xB58CFF, 0xFF55FF)
            .accent(NamedTextColor.LIGHT_PURPLE)
            .value(NamedTextColor.AQUA)
            .good(NamedTextColor.GREEN)
            .error(NamedTextColor.RED)
            .muted(NamedTextColor.GRAY)
            .dim(NamedTextColor.DARK_GRAY)
            .build();

    // network profiles ship with canvas; these are the app's shorthand
    public static final Theme SCRIMS = io.github.term4.canvas.presets.Themes.SCRIMS;
    public static final Theme MINEMEN = io.github.term4.canvas.presets.Themes.MINEMEN;
    public static final Theme HYPIXEL = io.github.term4.canvas.presets.Themes.HYPIXEL;

    private Msg() {}

    public static void shard(CommandSender to, Object... parts) {
        THEME.tag(to, "shard", parts);
    }

    public static void replay(CommandSender to, Object... parts) {
        THEME.tag(to, "replay", parts);
    }

    public static void tag(CommandSender to, String tag, Object... parts) {
        THEME.tag(to, tag, parts);
    }

    public static void err(CommandSender to, String tag, Object... parts) {
        THEME.err(to, tag, parts);
    }

    public static Component v(Object value) {
        return THEME.value(value);
    }

    public static Component good(Object value) {
        return THEME.good(value);
    }

    public static Component err0(Object value) {
        return THEME.bad(value);
    }

    public static Component run(String command) {
        return THEME.run(command);
    }

    /** {@code text} that runs {@code command} on click - a listing that walks itself. */
    public static Component link(Object text, String command, Object hover) {
        return THEME.link(text, command, hover);
    }

    public static void usage(CommandSender to, String title, String[][] rows) {
        THEME.usage(to, title, rows);
    }

    public static Component gradient(String text) {
        return THEME.gradient(text);
    }

    public static Component boardTitle(net.minestom.server.entity.Player viewer, String text) {
        return THEME.boardTitle(viewer, text);
    }

    /** Runs {@code send} once {@code p}'s protocol is known, or after {@code maxTicks} anyway (then the
     *  unknown-as-legacy default applies) - join-time messages would otherwise pick the format blind. */
    public static void onceProtocolKnown(net.minestom.server.entity.Player p, int maxTicks, Runnable send) {
        var polyp = Mechanics.polyp;
        if (polyp == null || polyp.clientInfo() == null) { send.run(); return; }
        int[] waited = {0};
        net.minestom.server.MinecraftServer.getSchedulerManager().scheduleTask(() -> {
            if (!p.isOnline()) return net.minestom.server.timer.TaskSchedule.stop();
            boolean known = polyp.clientInfo().getProtocol(p)
                    != io.github.term4.polyp.tracking.ClientVersion.UNKNOWN_PROTOCOL;
            if (known || (waited[0] += 5) >= maxTicks) {
                send.run();
                return net.minestom.server.timer.TaskSchedule.stop();
            }
            return net.minestom.server.timer.TaskSchedule.tick(5);
        }, net.minestom.server.timer.TaskSchedule.tick(1));
    }
}
