package tennacle.moderation;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import tennacle.access.Access;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Out of sight, off the tab list and offline to {@code /msg} for anyone without {@link #SEE}; ends on leaving. */
public final class Vanish {

    public static final String SEE = "tennacle.moderate.vanish";

    private static final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    private Vanish() {}

    // first tab rule wins: registered ahead of the seat and ghost rules that would list them
    static void install() {
        ShardTabList.rule((viewer, subject) -> hides(viewer, subject) ? Boolean.FALSE : null);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> hidden.remove(e.getPlayer().getUuid()));
        Access.onChange(player -> {
            if (!hidden.isEmpty()) refresh(player);
        });
    }

    public static boolean vanished(@NotNull Player player) {
        return hidden.contains(player.getUuid());
    }

    public static boolean hides(@NotNull Player viewer, @NotNull Entity subject) {
        return subject instanceof Player body && body != viewer && hidden.contains(body.getUuid()) && !Access.allows(viewer, SEE);
    }

    /** True when {@code player} is now hidden. */
    public static boolean toggle(@NotNull Player player) {
        boolean now = hidden.add(player.getUuid());
        if (!now) hidden.remove(player.getUuid());
        refresh(player);
        return now;
    }

    private static void refresh(Player player) {
        ShardTabList.resync(player);
        WorldPolicy.refreshSight(player);
    }
}
