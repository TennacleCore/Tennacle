package tennacle.commands;

import tennacle.Msg;
import tennacle.audit.Audit;
import tennacle.audit.AuditViews;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /audit} - the server's worlds for an admin: instances on top, the games and shard trees over each, and
 * every player's whereabouts, with the counts hovering their names and the trees and rosters opening as screens
 * ({@link Audit}, {@link AuditViews}).
 */
public final class AuditCommands {

    private AuditCommands() {}

    public static void register() {
        AppCommand audit = Commands.dev("audit", "The server's worlds: instances, games, shards, players.")
                .usage("/audit", "every instance, with its games and shard trees")
                .usage("/audit instances", "every world and what stands in it")
                .usage("/audit minis", "every game: who is in, who watches, its tree")
                .usage("/audit shards", "every shard tree and what owns it")
                .usage("/audit players", "everyone online and where they are")
                .usage("/audit player <name>", "one player in detail")
                .usage("/audit go <name>", "to them: their game as a spectator, their replay, or their side")
                .usage("/audit tree <shard>", "the tree as a screen that follows it")
                .usage("/audit who <shard>", "who stands in a shard, as heads");
        audit.setDefaultExecutor((sender, ctx) -> Audit.overview(sender));
        audit.addSyntax((sender, ctx) -> Audit.instances(sender), ArgumentType.Literal("instances"));
        audit.addSyntax((sender, ctx) -> Audit.minis(sender), ArgumentType.Literal("minis"));
        audit.addSyntax((sender, ctx) -> Audit.minis(sender), ArgumentType.Literal("games"));
        audit.addSyntax((sender, ctx) -> Audit.shards(sender), ArgumentType.Literal("shards"));
        audit.addSyntax((sender, ctx) -> Audit.players(sender), ArgumentType.Literal("players"));
        var name = ArgumentType.Word("name");
        name.setSuggestionCallback(PlayerNames.suggest());
        audit.addSyntax((sender, ctx) -> {
            Player p = online(sender, ctx.get(name));
            if (p != null) Audit.player(sender, p);
        }, ArgumentType.Literal("player"), name);
        audit.addSyntax((sender, ctx) -> {
            Player p = online(sender, ctx.get(name));
            if (p != null && sender instanceof Player viewer) Audit.go(viewer, p);
        }, ArgumentType.Literal("go"), name);
        var handle = ArgumentType.Word("shard");
        handle.setSuggestionCallback(Completions.of(AuditCommands::handles));
        audit.addSyntax((sender, ctx) -> {
            Shard shard = shard(sender, ctx.get(handle));
            if (shard != null && sender instanceof Player viewer) AuditViews.tree(viewer, shard);
        }, ArgumentType.Literal("tree"), handle);
        audit.addSyntax((sender, ctx) -> {
            Shard shard = shard(sender, ctx.get(handle));
            if (shard != null && sender instanceof Player viewer) AuditViews.who(viewer, shard);
        }, ArgumentType.Literal("who"), handle);
        Commands.register(audit);
    }

    private static List<String> handles() {
        List<String> out = new ArrayList<>();
        for (Shard shard : Audit.shards().shards()) out.add(Audit.handle(shard));
        return out;
    }

    private static Player online(CommandSender sender, String name) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (p == null) Msg.err(sender, "audit", "no player named ", Msg.v(name));
        return p;
    }

    private static Shard shard(CommandSender sender, String handle) {
        Shard shard = Audit.shard(handle);
        if (shard == null) Msg.err(sender, "audit", "no shard or game named ", Msg.v(handle));
        return shard;
    }
}
