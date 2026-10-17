package tennacle;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What a player reads on joining, in one order whichever module adds it: the welcome first, then what waited for
 * them. Sent once the client's protocol is known, since a line's format can depend on it.
 */
public final class Arrivals {

    /** The welcome's place; anything that should read after it takes a larger number. */
    public static final int WELCOME = 0;

    private record Step(int order, Consumer<Player> send) {}

    private static final List<Step> steps = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private Arrivals() {}

    public static void add(int order, @NotNull Consumer<Player> send) {
        steps.add(new Step(order, send));
        if (installed) return;
        installed = true;
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) Msg.onceProtocolKnown(e.getPlayer(), 60, () -> arrive(e.getPlayer()));
        });
    }

    private static void arrive(Player player) {
        steps.stream().sorted(Comparator.comparingInt(Step::order)).forEach(step -> step.send().accept(player));
    }
}
