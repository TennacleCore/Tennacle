package tennacle.access;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * May this player do that: the one question feature code asks, by node ({@code tennacle.build.arena}). Who holds what
 * is the {@link Backend}'s business: our own grants on the player's account now, a database or LuckPerms later.
 *
 * <p>A node {@code a.b.*} holds {@code a.b} and everything under it; {@code *} holds everything.
 */
public final class Access {

    /** Where grants live and how a check is answered. */
    public interface Backend {
        /** Answered from what was loaded at login, never a fetch; anyone not loaded holds what a player does. */
        boolean holds(@NotNull UUID player, @NotNull String node);

        @NotNull CompletableFuture<Grants> grants(@NotNull UUID player);

        /** Online or not; an online player's checks follow at once. */
        @NotNull CompletableFuture<Grants> change(@NotNull UUID player, @NotNull UnaryOperator<Grants> change);
    }

    private static volatile Backend backend = new PrefsBackend();
    private static volatile Set<String> everyone = Set.of();
    private static volatile Set<String> owners = Set.of();
    private static final Map<Role, Set<String>> lowest = new ConcurrentHashMap<>();
    private static final List<Consumer<Player>> changed = new CopyOnWriteArrayList<>();

    private Access() {}

    /** Registers the default backend's grants before the first login fetch. */
    public static void install() {
        PrefsBackend.install();
    }

    public static void use(@NotNull Backend source) {
        backend = source;
    }

    /** Nodes every player holds on this server: the staff tools on a dev build. */
    public static void everyone(@NotNull Set<String> nodes) {
        everyone = Set.copyOf(nodes);
    }

    /** Usernames or uuids that hold everything, from config: the first admin on a fresh server, never stored. */
    public static void owners(@NotNull Set<String> namesOrIds) {
        owners = namesOrIds.stream().map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean owner(@NotNull Player player) {
        return owner(player.getUuid(), player.getUsername());
    }

    public static boolean owner(@NotNull UUID id, @NotNull String name) {
        return owners.contains(name.toLowerCase(Locale.ROOT)) || owners.contains(id.toString());
    }

    public static boolean allows(@NotNull Player player, @NotNull String node) {
        return matches(everyone, node) || owner(player) || backend.holds(player.getUuid(), node);
    }

    public static @NotNull CompletableFuture<Grants> grants(@NotNull UUID player) {
        return backend.grants(player);
    }

    public static @NotNull CompletableFuture<Grants> role(@NotNull UUID player, @NotNull Role role) {
        return change(player, grants -> grants.withRole(role));
    }

    public static @NotNull CompletableFuture<Grants> grant(@NotNull UUID player, @NotNull String node) {
        return change(player, grants -> grants.with(node));
    }

    public static @NotNull CompletableFuture<Grants> revoke(@NotNull UUID player, @NotNull String node) {
        return change(player, grants -> grants.without(node));
    }

    /** Hears an online player whose grants just changed: what they may see has to follow. */
    public static void onChange(@NotNull Consumer<Player> listener) {
        changed.add(listener);
    }

    /** {@code role} and every role above it hold {@code node}: a tool names the lowest staff role that uses it. */
    public static void lowest(@NotNull String node, @NotNull Role role) {
        lowest.computeIfAbsent(role, r -> ConcurrentHashMap.newKeySet()).add(node);
    }

    /** What {@code role} holds of its own: its ladder nodes and the tools that name it lowest. */
    public static @NotNull Set<String> held(@NotNull Role role) {
        Set<String> out = new HashSet<>(role.nodes());
        out.addAll(lowest.getOrDefault(role, Set.of()));
        return out;
    }

    /** Every node {@code grants} holds: its role's, the roles' below it, and its own. */
    public static @NotNull Set<String> effective(@NotNull Grants grants) {
        Set<String> out = new HashSet<>(grants.nodes());
        for (Role role : Role.values()) {
            if (grants.role().atLeast(role)) out.addAll(held(role));
        }
        return out;
    }

    public static boolean matches(@NotNull Set<String> held, @NotNull String node) {
        if (held.contains(node) || held.contains("*")) return true;
        for (String grant : held) {
            if (!grant.endsWith(".*")) continue;
            String base = grant.substring(0, grant.length() - 2);
            if (node.equals(base) || node.startsWith(base + ".")) return true;
        }
        return false;
    }

    private static CompletableFuture<Grants> change(UUID player, UnaryOperator<Grants> change) {
        return backend.change(player, change).thenApply(grants -> {
            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(player);
            if (online != null) for (Consumer<Player> listener : changed) listener.accept(online);
            return grants;
        });
    }
}
