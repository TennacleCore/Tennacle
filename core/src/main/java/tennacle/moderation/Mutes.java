package tennacle.moderation;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerCommandEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.moderation.Punishments.Kind;
import tennacle.moderation.Punishments.Punishment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** A muted player's chat and {@link AppCommand#speaks} commands go nowhere; their mute is read once, at login. */
public final class Mutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mutes.class);

    private static final Map<UUID, Punishment> live = new ConcurrentHashMap<>();

    private Mutes() {}

    static void install() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            UUID id = e.getPlayer().getUuid();
            try {
                held(id, Moderation.store().active(id, Kind.MUTE, Moderation.now()).get(10, TimeUnit.SECONDS));
            } catch (Exception failed) {
                LOGGER.warn("{}'s mute did not load", e.getPlayer().getUsername(), failed);
            }
        });
        events.addListener(PlayerDisconnectEvent.class, e -> live.remove(e.getPlayer().getUuid()));
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

    public static @Nullable Punishment of(@NotNull Player player) {
        Punishment mute = live.get(player.getUuid());
        return mute != null && mute.active(Moderation.now()) ? mute : null;
    }

    static void held(UUID player, @Nullable Punishment mute) {
        if (mute == null) live.remove(player);
        else live.put(player, mute);
    }

    private static boolean silenced(Player player) {
        Punishment mute = of(player);
        if (mute == null) return false;
        String left = mute.permanent() ? "permanently" : "for another " + Lengths.span(mute.until() - Moderation.now());
        Msg.fail(player, "You are muted " + left + "." + Lengths.why(mute));
        return true;
    }
}
