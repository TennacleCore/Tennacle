package tennacle.games;

import tennacle.Msg;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A leader and the players who accepted: one SIDE wherever a match is started. {@code /party invite <player>},
 * {@code /party accept}, {@code /party leave}, {@code /party list}. Only the leader duels or queues; the
 * party follows as their side, so a 2v2 mode seats it on one team.
 */
public final class Parties {

    private record Invite(UUID leader, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > 60_000;
        }
    }

    private static final class Party {
        final UUID leader;
        final List<UUID> members = new CopyOnWriteArrayList<>(); // leader first

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    private static final Map<UUID, Party> byMember = new ConcurrentHashMap<>();
    private static final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    private Parties() {}

    /** The player's side: their party's online members, leader first; alone = just them. */
    public static @NotNull List<Player> of(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        if (party == null) return List.of(player);
        List<Player> out = new ArrayList<>();
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) out.add(member);
        }
        return out;
    }

    /** Whether {@code player} speaks for their side - alone, or the party's leader. */
    public static boolean leads(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        return party == null || party.leader.equals(player.getUuid());
    }

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> leave(e.getPlayer(), true));
        Command party = new Command("party");
        var name = ArgumentType.Word("player");
        var sub = ArgumentType.Word("sub").from("accept", "leave", "list");
        party.setDefaultExecutor((sender, ctx) ->
                Msg.tag(sender, "party", "usage: ", Msg.run("/party invite <player> | accept | leave | list")));
        party.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) invite(p, ctx.get(name));
        }, ArgumentType.Literal("invite"), name);
        party.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            switch (ctx.get(sub)) {
                case "accept" -> accept(p);
                case "leave" -> {
                    if (!leave(p, false)) Msg.err(p, "party", "you're not in a party");
                }
                default -> list(p);
            }
        }, sub);
        MinecraftServer.getCommandManager().register(party);
    }

    private static void invite(Player leader, String targetName) {
        if (!leads(leader)) {
            Msg.err(leader, "party", "only the leader invites");
            return;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (target == null || target == leader) {
            Msg.err(leader, "party", "no player named ", Msg.v(targetName));
            return;
        }
        if (byMember.containsKey(target.getUuid())) {
            Msg.err(leader, "party", Msg.v(target.getUsername()), " is already in a party");
            return;
        }
        invites.put(target.getUuid(), new Invite(leader.getUuid(), System.currentTimeMillis()));
        Msg.tag(leader, "party", "invited ", Msg.v(target.getUsername()));
        Msg.tag(target, "party", Msg.v(leader.getUsername()), " invited you - ", Msg.run("/party accept"));
    }

    private static void accept(Player player) {
        Invite invite = invites.remove(player.getUuid());
        if (invite == null || invite.expired()) {
            Msg.err(player, "party", "no invite is waiting");
            return;
        }
        Player leader = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(invite.leader());
        if (leader == null) {
            Msg.err(player, "party", "the leader left");
            return;
        }
        leave(player, false);
        Party party = byMember.computeIfAbsent(leader.getUuid(), Party::new);
        party.members.add(player.getUuid());
        byMember.put(player.getUuid(), party);
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) Msg.tag(member, "party", Msg.v(player.getUsername()), " joined the party");
        }
    }

    /** {@code true} when the player was in a party; a leader leaving disbands it. */
    private static boolean leave(Player player, boolean quiet) {
        invites.remove(player.getUuid());
        Party party = byMember.remove(player.getUuid());
        if (party == null) return false;
        if (party.leader.equals(player.getUuid())) {
            for (UUID uuid : party.members) {
                byMember.remove(uuid);
                Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
                if (member != null && member != player) Msg.tag(member, "party", "the party disbanded");
            }
            if (!quiet) Msg.tag(player, "party", "party disbanded");
            return true;
        }
        party.members.remove(player.getUuid());
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) Msg.tag(member, "party", Msg.v(player.getUsername()), " left the party");
        }
        if (!quiet) Msg.tag(player, "party", "left the party");
        return true;
    }

    private static void list(Player player) {
        List<Player> side = of(player);
        if (side.size() == 1) {
            Msg.tag(player, "party", "you're not in a party");
            return;
        }
        Msg.tag(player, "party", "party: ", Msg.v(String.join(", ", side.stream().map(Player::getUsername).toList())));
    }
}
