package tennacle.social;

import tennacle.social.friends.Friends;
import tennacle.social.party.Parties;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Who a privacy setting lets through. */
public enum Audience {
    EVERYONE("Everyone"), FRIENDS("Friends"), PARTY("Your party"), NOBODY("Nobody");

    private final String label;

    Audience(String label) {
        this.label = label;
    }

    public @NotNull String label() {
        return label;
    }

    /** Whether {@code from} gets through to {@code to} under this setting. */
    public boolean admits(@NotNull Player from, @NotNull Player to) {
        return switch (this) {
            case EVERYONE -> true;
            case FRIENDS -> Friends.are(from.getUuid(), to.getUuid());
            case PARTY -> Parties.together(from, to);
            case NOBODY -> false;
        };
    }

    /** The same for a {@code to} who may be offline: a party needs them online. */
    public boolean admits(@NotNull Player from, @NotNull UUID to) {
        return switch (this) {
            case EVERYONE -> true;
            case FRIENDS -> Friends.are(from.getUuid(), to);
            case PARTY -> {
                Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(to);
                yield online != null && Parties.together(from, online);
            }
            case NOBODY -> false;
        };
    }
}
