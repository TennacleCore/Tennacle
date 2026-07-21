package test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.command.CommandSender;

/**
 * Chat formatting for the playground commands, on the Tennacle palette: gradient (purple - magenta - cyan)
 * tags and titles, muted prose, cyan values, magenta click-to-suggest commands, red errors. {@code Object}
 * parts: a {@link Component} passes through, anything else renders as prose.
 */
final class Msg {

    // the Tennacle palette (oklch sources in comments)
    private static final TextColor PURPLE = TextColor.color(0xAC67EF);   // oklch(0.65 0.20 305)
    private static final TextColor MAGENTA = TextColor.color(0xF147C6);  // oklch(0.68 0.24 340)
    private static final TextColor CYAN = TextColor.color(0x00BEDB);     // oklch(0.72 0.16 210)
    private static final TextColor GREEN = TextColor.color(0x4AC06C);    // oklch(0.72 0.16 150)
    private static final TextColor MUTED = TextColor.color(0x85868C);    // oklch(0.62 0.01 280)
    private static final TextColor DIM = TextColor.color(0x626369);      // oklch(0.50 0.01 280)
    /** The signature gradient's stops (purple - magenta at 50% - cyan). */
    private static final int G_PURPLE = 0x9D57DE;   // oklch(0.60 0.20 305)
    private static final int G_MAGENTA = 0xF147C6;  // oklch(0.68 0.24 340)
    private static final int G_CYAN = 0x00C7E5;     // oklch(0.75 0.16 210)

    private Msg() {}

    static void shard(CommandSender to, Object... parts) {
        send(to, "shard", MUTED, parts);
    }

    static void replay(CommandSender to, Object... parts) {
        send(to, "replay", MUTED, parts);
    }

    static void tag(CommandSender to, String tag, Object... parts) {
        send(to, tag, MUTED, parts);
    }

    static void err(CommandSender to, String tag, Object... parts) {
        send(to, tag, NamedTextColor.RED, parts);
    }

    /** A highlighted value (a shard name, a tick count). */
    static Component v(Object value) {
        return Component.text(String.valueOf(value), CYAN);
    }

    /** A success highlight (lossless verification, an ON state). */
    static Component good(Object value) {
        return Component.text(String.valueOf(value), GREEN);
    }

    /** An inline failure fragment (a LOSSY verdict) inside an otherwise normal message. */
    static Component err0(Object value) {
        return Component.text(String.valueOf(value), NamedTextColor.RED);
    }

    /** A clickable command: click suggests it in chat. */
    static Component run(String command) {
        return Component.text(command, MAGENTA)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to put " + command + " in chat", DIM)));
    }

    /** A structured usage listing: gradient header + one indented clickable row per {@code {command, description}} pair. */
    static void usage(CommandSender to, String title, String[][] rows) {
        TextComponent.Builder out = Component.text().append(title(to, title));
        for (String[] row : rows) {
            out.append(Component.newline())
                    .append(Component.text("  » ", PURPLE))
                    .append(run(row[0]));
            if (row.length > 1) {
                out.append(Component.text(" - " + row[1], DIM));
            }
        }
        to.sendMessage(out.build());
    }

    private static void send(CommandSender to, String tag, TextColor body, Object[] parts) {
        TextComponent.Builder out = Component.text()
                .append(title(to, "[" + tag + "]"))
                .append(Component.text(" "));
        for (Object part : parts) {
            out.append(part instanceof Component c ? c : Component.text(String.valueOf(part), body));
        }
        to.sendMessage(out.build());
    }

    /** The signature look per recipient: the gradient for modern clients, flat brand purple for legacy - a 1.8
     *  client renders each RGB char as its nearest NAMED color, turning the gradient into ugly color chunks. */
    private static Component title(CommandSender to, String text) {
        return legacy(to) ? Component.text(text, NamedTextColor.DARK_PURPLE) : gradient(text);
    }

    // UNKNOWN protocol (the Via/proxy handshake lags the join) renders gradient-free - never chunk a 1.8 client
    private static boolean legacy(CommandSender to) {
        var mm = Mechanics.mm;
        if (!(to instanceof net.minestom.server.entity.Player p) || mm == null || mm.clientInfo() == null) return false;
        return mm.clientInfo().getProtocol(p) == io.github.term4.minestommechanics.tracking.ClientVersion.UNKNOWN_PROTOCOL
                || mm.clientInfo().isLegacy(p);
    }

    /** Runs {@code send} once {@code p}'s protocol is known, or after {@code maxTicks} anyway (then the
     *  unknown default above applies) - join-time messages would otherwise pick the format blind. */
    static void onceProtocolKnown(net.minestom.server.entity.Player p, int maxTicks, Runnable send) {
        var mm = Mechanics.mm;
        if (mm == null || mm.clientInfo() == null) { send.run(); return; }
        int[] waited = {0};
        net.minestom.server.MinecraftServer.getSchedulerManager().scheduleTask(() -> {
            if (!p.isOnline()) return net.minestom.server.timer.TaskSchedule.stop();
            boolean known = mm.clientInfo().getProtocol(p)
                    != io.github.term4.minestommechanics.tracking.ClientVersion.UNKNOWN_PROTOCOL;
            if (known || (waited[0] += 5) >= maxTicks) {
                send.run();
                return net.minestom.server.timer.TaskSchedule.stop();
            }
            return net.minestom.server.timer.TaskSchedule.tick(5);
        }, net.minestom.server.timer.TaskSchedule.tick(1));
    }

    /** The signature gradient across {@code text}, per character: purple to magenta over the first half, magenta to cyan over the second. */
    static Component gradient(String text) {
        int n = text.length();
        if (n == 0) return Component.empty();
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.5 : (double) i / (n - 1);
            int rgb = t < 0.5 ? lerp(G_PURPLE, G_MAGENTA, t * 2) : lerp(G_MAGENTA, G_CYAN, (t - 0.5) * 2);
            out.append(Component.text(text.charAt(i), TextColor.color(rgb)));
        }
        return out.build();
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + t * (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)));
        int g = (int) Math.round(((from >> 8) & 0xFF) + t * (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)));
        int b = (int) Math.round((from & 0xFF) + t * ((to & 0xFF) - (from & 0xFF)));
        return (r << 16) | (g << 8) | b;
    }
}
