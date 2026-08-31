package tennacle;

import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.command.CommandSender;

/**
 * The Tennacle look, as a Canvas {@link Theme}: gradient (purple - magenta - cyan) tags and titles,
 * muted prose, cyan values, magenta click-to-suggest commands, red errors. Installed as the server
 * theme at boot; these statics are the app's shorthand over it.
 */
public final class Msg {

    /** The palette (oklch sources in comments). */
    public static final Theme THEME = Theme.builder()
            .brand(NamedTextColor.DARK_PURPLE)          // the flat legacy stand-in for the gradient
            .gradient(0x9D57DE, 0xF147C6, 0x00C7E5)     // oklch(0.60 0.20 305) - (0.68 0.24 340) - (0.75 0.16 210)
            .accent(TextColor.color(0xF147C6))          // oklch(0.68 0.24 340)
            .value(TextColor.color(0x00BEDB))           // oklch(0.72 0.16 210)
            .good(TextColor.color(0x4AC06C))            // oklch(0.72 0.16 150)
            .error(NamedTextColor.RED)
            .muted(TextColor.color(0x85868C))           // oklch(0.62 0.01 280)
            .dim(TextColor.color(0x626369))             // oklch(0.50 0.01 280)
            .build();

    // network profiles ship with canvas; these are the app's shorthand
    public static final Theme SCRIMS = io.github.term4.canvas.theme.Themes.SCRIMS;
    public static final Theme MINEMEN = io.github.term4.canvas.theme.Themes.MINEMEN;
    public static final Theme HYPIXEL = io.github.term4.canvas.theme.Themes.HYPIXEL;

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
