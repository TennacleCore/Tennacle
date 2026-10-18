package tennacle.access;

import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A player's role and the single nodes granted on top of it. */
public record Grants(@NotNull Role role, @NotNull Set<String> nodes) {

    public static final Grants NONE = new Grants(Role.PLAYER, Set.of());

    static final Codec<Grants> CODEC = StructCodec.struct(
            "role", Codec.STRING, grants -> grants.role().name(),
            "nodes", Codec.STRING.list(), grants -> List.copyOf(grants.nodes()),
            (role, nodes) -> new Grants(role(role), Set.copyOf(nodes)));

    public Grants {
        nodes = Set.copyOf(nodes);
    }

    public @NotNull Grants withRole(@NotNull Role role) {
        return new Grants(role, nodes);
    }

    public @NotNull Grants with(@NotNull String node) {
        Set<String> more = new HashSet<>(nodes);
        more.add(node);
        return new Grants(role, more);
    }

    public @NotNull Grants without(@NotNull String node) {
        Set<String> fewer = new HashSet<>(nodes);
        fewer.remove(node);
        return new Grants(role, fewer);
    }

    private static Role role(String name) {
        try {
            return Role.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unreadable) {
            return Role.PLAYER;
        }
    }
}
