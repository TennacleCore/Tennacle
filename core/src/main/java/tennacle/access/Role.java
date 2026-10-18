package tennacle.access;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** The staff ladder: each role holds its own nodes and every one below it. */
public enum Role {
    PLAYER("Player", Set.of()),
    BUILDER("Builder", Set.of("tennacle.build.*")),
    MOD("Mod", Set.of("tennacle.moderate.*")),
    ADMIN("Admin", Set.of("*"));

    private final String label;
    private final Set<String> nodes;

    Role(String label, Set<String> nodes) {
        this.label = label;
        this.nodes = nodes;
    }

    public @NotNull String label() {
        return label;
    }

    /** Its own nodes only; {@link Access} walks the ladder. */
    public @NotNull Set<String> nodes() {
        return nodes;
    }

    /** Whether this role is {@code other} or above it. */
    public boolean atLeast(@NotNull Role other) {
        return ordinal() >= other.ordinal();
    }
}
