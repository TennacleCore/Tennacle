package tennacle.moderation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.moderation.Punishments.Kind;
import tennacle.moderation.Punishments.Punishment;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A banned player is turned away at login. */
public final class Bans {

    private static final Logger LOGGER = LoggerFactory.getLogger(Bans.class);

    private Bans() {}

    static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerPreLoginEvent.class, e -> {
            Component refused = refusal(e.getPlayerUuid());
            if (refused != null) e.getConnection().kick(refused);
        });
    }

    /** The screen that turns {@code player} away, or null when they may join. */
    static @Nullable Component refusal(@NotNull UUID player) {
        try {
            Punishment ban = Moderation.store().active(player, Kind.BAN, Moderation.now()).get(10, TimeUnit.SECONDS);
            return ban == null ? null : screen(ban, Moderation.now());
        } catch (Exception failed) {
            LOGGER.error("could not check {} for a ban", player, failed);
            return Component.text("Couldn't check your account. Try again in a moment.", NamedTextColor.RED);
        }
    }

    static @NotNull Component screen(@NotNull Punishment ban, long now) {
        String left = ban.permanent() ? "permanently" : "for another " + Lengths.span(ban.until() - now);
        Component screen = Component.text("You are banned " + left + ".", NamedTextColor.RED);
        if (ban.reason().isEmpty()) return screen;
        return screen.append(Component.text("\n\nReason: ", NamedTextColor.GRAY))
                .append(Component.text(ban.reason(), NamedTextColor.WHITE));
    }
}
