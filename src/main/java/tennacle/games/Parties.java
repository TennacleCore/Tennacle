package tennacle.games;

import tennacle.Msg;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;
import io.github.term4.polyp.platform.player.PlayerNames;

import net.minestom.server.MinecraftServer;
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
        AppCommand party = Commands.player("party", "Play as one side with friends.")
                .usage("/party invite <player>", "ask them along")
                .usage("/party accept", "join the party that invited you")
                .usage("/party leave", "leave it; the leader leaving disbands it")
                .usage("/party list", "who is in yours")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggest());
        var sub = ArgumentType.Word("sub").from("accept", "leave", "list");
        party.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) invite(p, ctx.get(name));
        }, ArgumentType.Literal("invite"), name);
        party.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            switch (ctx.get(sub)) {
                case "accept" -> accept(p);
                case "leave" -> {
                    if (!leave(p, false)) Msg.fail(p, "You're not in a party.");
                }
                default -> list(p);
            }
        }, sub);
        Commands.register(party);
    }

    private static void invite(Player leader, String targetName) {
        if (!leads(leader)) {
            Msg.fail(leader, "Only the party leader invites.");
            return;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (target == null) {
            Msg.fail(leader, "No player named ", Msg.v(targetName), ".");
            return;
        }
        if (target == leader) {
            Msg.fail(leader, "You can't invite yourself.");
            return;
        }
        if (byMember.containsKey(target.getUuid())) {
            Msg.fail(leader, Msg.v(target.getUsername()), " is already in a party.");
            return;
        }
        invites.put(target.getUuid(), new Invite(leader.getUuid(), System.currentTimeMillis()));
        Msg.ok(leader, "Invited ", Msg.v(target.getUsername()), " to your party.");
        Msg.say(target, Msg.v(leader.getUsername()), " invited you to their party. ", Msg.run("/party accept"), " to join.");
    }

    private static void accept(Player player) {
        Invite invite = invites.remove(player.getUuid());
        if (invite == null || invite.expired()) {
            Msg.fail(player, "No party invite is waiting for you.");
            return;
        }
        Player leader = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(invite.leader());
        if (leader == null) {
            Msg.fail(player, "The party leader left.");
            return;
        }
        Party theirs = byMember.get(leader.getUuid());
        if (theirs != null && !theirs.leader.equals(leader.getUuid())) {
            // they joined someone else's party between the invite and the accept
            Msg.fail(player, Msg.v(leader.getUsername()), " no longer leads a party.");
            return;
        }
        leave(player, false);
        Party party = byMember.computeIfAbsent(leader.getUuid(), Party::new);
        party.members.add(player.getUuid());
        byMember.put(player.getUuid(), party);
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) Msg.ok(member, Msg.v(player.getUsername()), " joined the party.");
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
                if (member != null && member != player) Msg.say(member, "The party disbanded.");
            }
            if (!quiet) Msg.say(player, "Party disbanded.");
            return true;
        }
        party.members.remove(player.getUuid());
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) Msg.say(member, Msg.v(player.getUsername()), " left the party.");
        }
        if (!quiet) Msg.say(player, "You left the party.");
        return true;
    }

    private static void list(Player player) {
        List<Player> side = of(player);
        if (side.size() == 1) {
            Msg.say(player, "You're not in a party.");
            return;
        }
        Msg.say(player, "Party: ", Msg.v(String.join(", ", side.stream().map(Player::getUsername).toList())), ".");
    }
}
