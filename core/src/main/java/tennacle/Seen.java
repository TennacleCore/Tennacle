package tennacle;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import tennacle.storage.Directory;
import tennacle.storage.MemoryDirectory;

/** The {@link Directory} the server keeps, stamped with every join and leave. */
public final class Seen {

    private static volatile Directory directory = new MemoryDirectory();
    private static volatile boolean installed;

    private Seen() {}

    public static void use(@NotNull Directory store) {
        directory = store;
    }

    public static @NotNull Directory directory() {
        return directory;
    }

    public static void install() {
        if (installed) return;
        installed = true;
        var node = MinecraftServer.getGlobalEventHandler();
        node.addListener(AsyncPlayerConfigurationEvent.class, e -> stamp(e.getPlayer()));
        node.addListener(PlayerDisconnectEvent.class, e -> stamp(e.getPlayer()));
    }

    private static void stamp(Player player) {
        directory.seen(player.getUuid(), player.getUsername(), System.currentTimeMillis());
    }
}
