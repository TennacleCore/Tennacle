package tennacle.access;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.Seen;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.commands.Completions;
import tennacle.storage.Directory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/** {@code /role}, {@code /grant} and {@code /revoke}: an admin's hand on the ladder, online or not. */
public final class AccessCommands {

    public static final String ROLES = "tennacle.admin.role";
    public static final String GRANTS = "tennacle.admin.grant";

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessCommands.class);
    private static final Pattern NODE = Pattern.compile("\\*|[a-z0-9_-]+(\\.[a-z0-9_-]+)*(\\.\\*)?");

    private AccessCommands() {}

    public static void register() {
        var who = ArgumentType.Word("player");
        who.setSuggestionCallback(PlayerNames.suggestAll());
        var role = ArgumentType.Word("role").from(Arrays.stream(Role.values()).map(r -> r.name().toLowerCase(Locale.ROOT)).toArray(String[]::new));
        var node = ArgumentType.Word("permission");
        node.setSuggestionCallback(Completions.of(AccessCommands::grantable));

        AppCommand roles = Commands.player("role", "See or set a player's role.").node(ROLES)
                .usage("/role <player>", "their role and the permissions granted on top")
                .usage("/role <player> <player|builder|mod|admin>", "set it");
        roles.addSyntax((sender, ctx) -> show(sender, ctx.get(who)), who);
        roles.addSyntax((sender, ctx) -> set(sender, ctx.get(who), Role.valueOf(ctx.get(role).toUpperCase(Locale.ROOT))), who, role);
        Commands.register(roles);

        AppCommand grant = Commands.player("grant", "Give a player one permission on top of their role.").node(GRANTS)
                .usage("/grant <player> <permission>", "tennacle.build.* holds everything under it");
        grant.addSyntax((sender, ctx) -> change(sender, ctx.get(who), ctx.get(node), true), who, node);
        Commands.register(grant);

        AppCommand revoke = Commands.player("revoke", "Take back a permission given with /grant.").node(GRANTS)
                .usage("/revoke <player> <permission>", "only what /grant gave; a role's own stay with the role");
        revoke.addSyntax((sender, ctx) -> change(sender, ctx.get(who), ctx.get(node), false), who, node);
        Commands.register(revoke);
    }

    private static void show(CommandSender sender, String name) {
        found(sender, name, target -> Access.grants(target.id()).thenAccept(grants -> {
            String owner = Access.owner(target.id(), target.name()) ? " Owner (from the server config), so every permission." : "";
            String extra = grants.nodes().isEmpty() ? "" : " On top: " + String.join(", ", new TreeSet<>(grants.nodes())) + ".";
            Msg.tag(sender, "role", Msg.v(target.name()), " is " + a(grants.role()) + "." + extra + owner);
        }));
    }

    private static void set(CommandSender sender, String name, Role role) {
        found(sender, name, target -> Access.role(target.id(), role).thenRun(() -> {
            Msg.tag(sender, "role", Msg.v(target.name()), " is now " + a(role) + ".");
            Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target.id());
            if (online != null && online != sender) Msg.tag(online, "role", "You are now " + a(role) + ".");
        }));
    }

    private static void change(CommandSender sender, String name, String node, boolean give) {
        String wanted = node.toLowerCase(Locale.ROOT);
        if (!NODE.matcher(wanted).matches()) {
            Msg.err(sender, give ? "grant" : "revoke", Msg.v(node), " isn't a permission. They read like tennacle.build.arena.");
            return;
        }
        found(sender, name, target -> {
            if (give) {
                return Access.grant(target.id(), wanted).thenRun(() ->
                        Msg.tag(sender, "grant", Msg.v(target.name()), " now holds ", Msg.v(wanted), "."));
            }
            return Access.grants(target.id()).thenCompose(grants -> {
                if (!grants.nodes().contains(wanted)) {
                    Msg.err(sender, "revoke", Msg.v(target.name()), " wasn't granted ", Msg.v(wanted),
                            " on its own. A role's permissions go with ", Msg.run("/role"), ".");
                    return CompletableFuture.completedFuture(null);
                }
                return Access.revoke(target.id(), wanted).thenRun(() ->
                        Msg.tag(sender, "revoke", Msg.v(target.name()), " no longer holds ", Msg.v(wanted), "."));
            });
        });
    }

    // the player the name means, online or anyone who has joined
    private static void found(CommandSender sender, String name, Function<Directory.Entry, CompletableFuture<?>> then) {
        Seen.find(name).thenCompose(target -> {
            if (target == null) {
                Msg.err(sender, "role", "No player named ", Msg.v(name), ".");
                return CompletableFuture.completedFuture(null);
            }
            return then.apply(target);
        }).exceptionally(failed -> {
            LOGGER.warn("a role change for {} failed", name, failed);
            Msg.err(sender, "role", "That didn't save. Try again in a moment.");
            return null;
        });
    }

    private static Set<String> grantable() {
        Set<String> out = new TreeSet<>(Commands.nodes());
        for (Role role : Role.values()) out.addAll(Access.held(role));
        for (String node : List.copyOf(out)) {
            int dot = node.lastIndexOf('.');
            if (dot > 0) out.add(node.substring(0, dot) + ".*");
        }
        return out;
    }

    private static String a(Role role) {
        return (role == Role.ADMIN ? "an " : "a ") + role.label();
    }
}
