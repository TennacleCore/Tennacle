package tennacle.games.imports;

import tennacle.access.Access;
import tennacle.social.Audience;
import tennacle.social.Choice;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Who may see a player's layouts, and so copy them: the one check every reader of someone else's layouts goes
 * through, the menus now and an API later. Staff see past it.
 */
public final class LayoutVisibility {

    public static final Choice VISIBILITY = Choice.of("kits:visibility", Audience.EVERYONE, Audience.FRIENDS, Audience.NOBODY);
    public static final String STAFF = "tennacle.moderate.layouts";

    private LayoutVisibility() {}

    /** Loads the setting with everyone's preferences; at boot, before anyone logs in. */
    public static void install() {}

    public static @NotNull CompletableFuture<Boolean> sees(@NotNull Player viewer, @NotNull UUID owner) {
        if (viewer.getUuid().equals(owner) || Access.allows(viewer, STAFF)) return CompletableFuture.completedFuture(true);
        return VISIBILITY.admits(viewer, owner);
    }

    /** {@link #sees} for someone online, at once. */
    public static boolean seesNow(@NotNull Player viewer, @NotNull Player owner) {
        return viewer == owner || Access.allows(viewer, STAFF) || VISIBILITY.admits(viewer, owner);
    }
}
