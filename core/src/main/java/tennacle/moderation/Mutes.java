package tennacle.moderation;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerCommandEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;
import tennacle.PlayerPrefs;
import tennacle.commands.AppCommand;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stored with the account, so a mute outlasts logouts and restarts; it silences chat and {@link AppCommand#speaks} commands. */
public final class Mutes {

    private static final PlayerPrefs.Entry<Mute, Mute> ENTRY = PlayerPrefs.register(VaultKey.of("moderation:mute", Mute.CODEC, Mute.NONE));
    private static final Pattern LENGTH = Pattern.compile("(\\d{1,6})([smhdw])");
    private static final long[] UNITS = {604_800_000L, 86_400_000L, 3_600_000L, 60_000L, 1_000L};
    private static final String[] UNIT_NAMES = {"week", "day", "hour", "minute", "second"};

    static volatile LongSupplier clock = System::currentTimeMillis;

    private Mutes() {}

    static void install() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerChatEvent.class, e -> {
            if (silenced(e.getPlayer())) e.setCancelled(true);
        });
        events.addListener(PlayerCommandEvent.class, e -> {
            String typed = e.getCommand();
            int space = typed.indexOf(' ');
            Command command = MinecraftServer.getCommandManager().getCommand(space < 0 ? typed : typed.substring(0, space));
            if (command instanceof AppCommand app && app.speech() && silenced(e.getPlayer())) e.setCancelled(true);
        });
    }

    public static @Nullable Mute of(@NotNull Player player) {
        Mute mute = ENTRY.of(player.getUuid());
        return mute != null && mute.active(clock.getAsLong()) ? mute : null;
    }

    public static @NotNull CompletableFuture<Mute> set(@NotNull UUID player, @NotNull Mute mute) {
        return ENTRY.update(player, old -> mute);
    }

    public static @NotNull CompletableFuture<Mute> load(@NotNull UUID player) {
        return ENTRY.load(player);
    }

    private static boolean silenced(Player player) {
        Mute mute = of(player);
        if (mute == null) return false;
        Msg.fail(player, "You are muted for another " + span(mute.until() - clock.getAsLong()) + "." + why(mute));
        return true;
    }

    static String why(Mute mute) {
        return mute.reason().isEmpty() ? "" : " Reason: " + mute.reason();
    }

    // rounded up in the largest unit it fills: 89 minutes reads 2 hours
    static String span(long millis) {
        long left = Math.max(millis, 1);
        for (int i = 0; i < UNITS.length; i++) {
            if (left < UNITS[i] && i < UNITS.length - 1) continue;
            long n = (left + UNITS[i] - 1) / UNITS[i];
            return n + " " + UNIT_NAMES[i] + (n == 1 ? "" : "s");
        }
        throw new AssertionError();
    }

    /** {@code 45s}, {@code 30m}, {@code 2h}, {@code 7d} or {@code 1w} in millis; -1 when it isn't one. */
    static long parse(String typed) {
        Matcher m = LENGTH.matcher(typed.toLowerCase(Locale.ROOT));
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        if (n == 0) return -1;
        return n * UNITS["wdhms".indexOf(m.group(2))];
    }
}
