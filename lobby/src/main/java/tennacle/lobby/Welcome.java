package tennacle.lobby;

import tennacle.boot.Config;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What an arrival reads first: the name, the message of the day ({@code tennacle.motd}; unset = none), the
 * featured commands off the registry, and where the rest are. The copy lives here and nowhere else.
 */
public final class Welcome {

    static final String TITLE = "Tennacle";
    private static final String MOTD = Config.string("tennacle.motd", "");

    private Welcome() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) return;
            // deferred: at spawn the Via handshake may not have revealed the protocol yet, and the title's
            // format (gradient vs flat) depends on it
            Msg.onceProtocolKnown(e.getPlayer(), 60, () -> send(e.getPlayer()));
        });
    }

    public static void send(@NotNull Player to) {
        List<String[]> rows = new ArrayList<>();
        for (AppCommand command : Commands.featured()) {
            rows.add(new String[]{command.usages().getFirst().form(), command.blurb()});
        }
        rows.add(new String[]{"/help", "everything else"});
        Msg.usage(to, TITLE, MOTD, rows.toArray(String[][]::new));
    }
}
