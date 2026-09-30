package tennacle.party;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Who a privacy setting lets through. */
public enum Audience {
    EVERYONE("Everyone"), PARTY("Your party"), NOBODY("Nobody");

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
            case PARTY -> Parties.together(from, to);
            case NOBODY -> false;
        };
    }
}
