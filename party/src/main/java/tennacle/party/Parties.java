package tennacle.party;

import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.timer.Task;
import tennacle.Confirm;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.function.BiConsumer;
/**
 * A leader and the players who accepted: one SIDE wherever a match is started. Only the leader duels or queues
 * outward; the party follows as their side, so a mode seats it on one team. The command set and its lines follow
 * the hypixel shape: an invite that runs on click, a framed announcement for every change, {@code /pl} for the
 * lineup, {@code /pc} and {@code /chat party} for the party's channel, {@code /party warp} to bring everyone to the
 * leader. The first invite makes the party; an invite stands a minute, and a party down to its leader with no
 * invite standing is disbanded. A disconnect starts a five-minute grace: a member back in time stays, one who is
 * not is removed; a leader who does not return disbands the party.
 */
public final class Parties {

    /** How many a party holds; a mode seats this many per side. */
    public static final int MAX = 8;
    /** The one line for anything only the leader does. */
    public static final String NOT_LEADER = "You are not party leader.";
    /** Who may invite a player. */
    public static final Choice INVITES = Choice.of("lobby:party-invites", Audience.EVERYONE, Audience.NOBODY);
    private static volatile BiConsumer<Player, String> cues = (player, cue) -> {};
    private static volatile Warp warps = new Warp() {
        @Override
        public boolean busy(@NotNull Player member, @NotNull Player leader) {
            return false;
        }

        @Override
        public boolean here(@NotNull Player member, @NotNull Player leader) {
            return member.getInstance() == leader.getInstance();
        }

        @Override
        public @Nullable String bring(@NotNull Player member, @NotNull Player leader) {
            Instance world = leader.getInstance();
            if (world == null) return "You aren't anywhere yet.";
            member.setInstance(world, leader.getPosition());
            return null;
        }
    };
    /** How long an invite stands. */
    static final long INVITE_SECONDS = 60;
    /** {@link #INVITE_SECONDS} in ticks; a test shortens it. */
    static volatile long inviteTicks = INVITE_SECONDS * 20;
    /** The rejoin grace after a disconnect, in ticks; a test shortens it. */
    static volatile long rejoinTicks = 5 * 60 * 20;
    static final String CHANNEL_PARTY = "party";
    static final String CHANNEL_ALL = "all";
    private static final Tag<String> CHANNEL = Tag.Transient("tennacle:chat-channel");

    /** An invite standing between a party and a player until accepted, or until its clock runs out. */
    private record Invite(Party party, String from, String to, Task expiry) {}

    private static final class Party {
        final UUID id = UUID.randomUUID(); // stable across a transfer, unlike the leader
        volatile UUID leader;
        final List<UUID> members = new CopyOnWriteArrayList<>(); // leader first
        final Map<UUID, String> names = new ConcurrentHashMap<>();
        final Set<UUID> invited = ConcurrentHashMap.newKeySet();
        volatile boolean allInvite;

        Party(Player leader) {
            this.leader = leader.getUuid();
            members.add(leader.getUuid());
            names.put(leader.getUuid(), leader.getUsername());
        }

        void add(Player player) {
            members.add(player.getUuid());
            names.put(player.getUuid(), player.getUsername());
        }

        void remove(UUID uuid) {
            members.remove(uuid);
            names.remove(uuid);
        }
    }

    private static final Map<UUID, Party> byMember = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Party, Invite>> invites = new ConcurrentHashMap<>(); // by the invited
    private static final Map<UUID, Task> graces = new ConcurrentHashMap<>();

    private Parties() {}

    /** The player's side: their party's online members, leader first; alone = just them. */
    public static @NotNull List<Player> of(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        if (party == null) return List.of(player);
        return online(party);
    }

    /** Whether {@code player} speaks for their side - alone, or the party's leader. */
    public static boolean leads(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        return party == null || party.leader.equals(player.getUuid());
    }

    /** {@link #leads}, and the one line when not. */
    public static boolean requireLeader(@NotNull Player player) {
        if (leads(player)) return true;
        Msg.fail(player, NOT_LEADER);
        return false;
    }

    /** What a party is filed under: its leader's id, or the player's own when alone. */
    public static @NotNull UUID leaderOf(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        return party != null ? party.leader : player.getUuid();
    }

    /** What a request to a party is filed against: the party's own id, the same after a transfer; alone, the player's. */
    public static @NotNull UUID partyOf(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        return party != null ? party.id : player.getUuid();
    }

    /** One of a party's members, online or not. */
    public record Member(@NotNull UUID id, @NotNull String name, boolean leader, boolean online) {}

    /** {@code player}'s party, the leader first; alone, just them. */
    public static @NotNull List<Member> members(@NotNull Player player) {
        Party party = byMember.get(player.getUuid());
        if (party == null) return List.of(new Member(player.getUuid(), player.getUsername(), true, true));
        List<Member> out = new ArrayList<>();
        for (UUID id : party.members) {
            boolean online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id) != null;
            out.add(new Member(id, party.names.getOrDefault(id, "?"), id.equals(party.leader), online));
        }
        return out;
    }

    /** {@code /party <target>} from {@code from}. */
    public static void invite(@NotNull Player from, @NotNull Player target) {
        invite(from, target.getUsername());
    }

    /** {@code /party kick}, {@code /party transfer}, {@code /party disband} and {@code /party leave} for a menu. */
    public static void kick(@NotNull Player leader, @NotNull Member member) {
        kick(leader, member.name());
    }

    public static void transfer(@NotNull Player leader, @NotNull Member member) {
        transfer(leader, member.name());
    }

    public static void disbandBy(@NotNull Player leader) {
        disband(leader);
    }

    public static void leaveBy(@NotNull Player player) {
        leave(player);
    }

    /** Whether the two share a party. */
    public static boolean together(@NotNull Player a, @NotNull Player b) {
        Party party = byMember.get(a.getUuid());
        return party != null && party == byMember.get(b.getUuid());
    }

    /** The party's online members when {@code speaker} speaks in the party channel and has one; else null. */
    public static @Nullable List<Player> chatRoom(@NotNull Player speaker) {
        if (!CHANNEL_PARTY.equals(speaker.getTag(CHANNEL))) return null;
        Party party = byMember.get(speaker.getUuid());
        return party != null ? online(party) : null;
    }

    /** {@code P» name: message}. */
    public static @NotNull Component chatLine(@NotNull Player speaker, @NotNull String message) {
        return Component.text("P» ", NamedTextColor.BLUE)
                .append(Component.text(speaker.getUsername() + ": ", NamedTextColor.AQUA))
                .append(Component.text(message, NamedTextColor.WHITE));
    }

    /** How the house sounds an invite ({@link SoundSet#PARTY_INVITE}); silent unset. */
    public static void cues(@NotNull BiConsumer<Player, String> play) {
        cues = play;
    }

    /** Where a warp takes the party: the module that knows the worlds and games sets it. */
    public interface Warp {
        /** Whether {@code member} plays a game {@code leader} is not in, which a warp takes them out of. */
        boolean busy(@NotNull Player member, @NotNull Player leader);

        /** Whether {@code member} is already where {@code leader} is. */
        boolean here(@NotNull Player member, @NotNull Player leader);

        /** Takes {@code member} to where {@code leader} is: why it can't, else null. */
        @Nullable String bring(@NotNull Player member, @NotNull Player leader);
    }

    public static void warps(@NotNull Warp warp) {
        warps = warp;
    }

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> disconnected(e.getPlayer()));
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) rejoined(e.getPlayer());
        });

        AppCommand party = Commands.player("party", "Play as one side with friends.", "p")
                .usage("/party <player>", "invite a player to the party")
                .usage("/party join <player>", "join a player's party")
                .usage("/party kick <player>", "kick a player from your party")
                .usage("/party transfer <player>", "transfer ownership of your party")
                .usage("/party list [player]", "list the party members of a player")
                .usage("/party leave", "leave the party you're in")
                .usage("/party kickoffline", "kick all offlines from your party")
                .usage("/party disband", "disband your party")
                .usage("/party allinvite", "everyone can invite people to the party")
                .usage("/party warp", "bring your party to where you are")
                .usage("/pl", "who is in your party")
                .usage("/pc <message>", "say something to your party")
                .usage("/chat <party|all>", "choose the chat to speak in")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggestAll()); // a party reaches into games and lobbies alike
        var sub = ArgumentType.Word("sub").from("invite", "join", "accept", "kick", "transfer", "list", "leave",
                "kickoffline", "disband", "allinvite", "warp", "help");
        party.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            switch (ctx.get(sub)) {
                case "accept" -> accept(p, null);
                case "list" -> list(p, p);
                case "leave" -> leave(p);
                case "kickoffline" -> kickOffline(p);
                case "disband" -> disband(p);
                case "allinvite" -> allInvite(p);
                case "warp" -> warp(p);
                case "help" -> Commands.usage(p, "party");
                default -> Commands.usage(p, "party"); // a subcommand that takes a name, given none
            }
        }, sub);
        party.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String who = ctx.get(name);
            switch (ctx.get(sub)) {
                case "invite" -> invite(p, who);
                case "join", "accept" -> accept(p, who);
                case "kick" -> kick(p, who);
                case "transfer" -> transfer(p, who);
                case "list" -> {
                    Player subject = online(p, who);
                    if (subject != null) list(p, subject);
                }
                default -> Commands.usage(p, "party");
            }
        }, sub, name);
        // "/party <player>" reads as a subcommand word: anything not a subcommand is the invite
        party.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) invite(p, ctx.get(name));
        }, name);
        Commands.register(party);

        AppCommand pl = Commands.player("pl", "Who is in your party.");
        pl.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) list(p, p);
        });
        Commands.register(pl);

        AppCommand pc = Commands.player("pc", "Say something to your party.")
                .usage("/pc <message>", "one line to the party, whatever channel you are in");
        var text = ArgumentType.StringArray("text");
        pc.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) say(p, String.join(" ", ctx.get(text)));
        }, text);
        Commands.register(pc);

        AppCommand chat = Commands.player("chat", "Choose the chat to speak in.")
                .usage("/chat party", "everything you say goes to your party")
                .usage("/chat all", "back to everyone around you");
        var channel = ArgumentType.Word("channel").from("party", "p", "all", "a");
        chat.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) channel(p, ctx.get(channel).startsWith("p") ? CHANNEL_PARTY : CHANNEL_ALL);
        }, channel);
        Commands.register(chat);

        AppCommand parties = Commands.dev("parties", "Every party and its members.");
        parties.setDefaultExecutor((sender, ctx) -> listAll(sender));
        Commands.register(parties);
    }

    private static void invite(Player from, String targetName) {
        Party party = byMember.get(from.getUuid());
        if (party != null && !party.leader.equals(from.getUuid()) && !party.allInvite) {
            Msg.fail(from, NOT_LEADER);
            return;
        }
        if (party != null && party.members.size() >= MAX) {
            Msg.fail(from, "The party is full.");
            return;
        }
        Player target = online(from, targetName);
        if (target == null) return;
        if (target == from) {
            Msg.fail(from, "You can't invite yourself.");
            return;
        }
        if (!INVITES.of(target).admits(from, target)) {
            Msg.fail(from, Msg.v(target.getUsername()), " takes no party invites.");
            return;
        }
        if (party != null && party.members.contains(target.getUuid())) {
            Msg.fail(from, Msg.v(target.getUsername()), " is already in your party.");
            return;
        }
        if (byMember.containsKey(target.getUuid())) {
            Msg.fail(from, Msg.v(target.getUsername()), " is already in a party.");
            return;
        }
        if (party != null && party.invited.contains(target.getUuid())) {
            Msg.fail(from, "You already invited ", Msg.v(target.getUsername()), ". Wait for them to answer.");
            return;
        }
        if (party == null) {
            party = new Party(from);
            byMember.put(from.getUuid(), party);
        }
        Party theirs = party;
        UUID invited = target.getUuid();
        Task expiry = MinecraftServer.getSchedulerManager().buildTask(() -> expire(invited, theirs))
                .delay(TaskSchedule.tick((int) inviteTicks)).schedule();
        party.invited.add(invited);
        invites.computeIfAbsent(invited, uuid -> new ConcurrentHashMap<>())
                .put(party, new Invite(party, from.getUsername(), target.getUsername(), expiry));
        Component sent = Component.text()
                .append(who(from.getUsername())).append(yellow(" invited ")).append(who(target.getUsername()))
                .append(yellow(" to the party! They have ")).append(Component.text(INVITE_SECONDS, NamedTextColor.RED))
                .append(yellow(" seconds to accept.")).build();
        for (Player member : online(party)) frame(member, sent);
        String leaderName = party.names.getOrDefault(party.leader, from.getUsername());
        frame(target, Component.text()
                .append(who(from.getUsername())).append(yellow(" has invited you to join their party! You have "))
                .append(Component.text(INVITE_SECONDS, NamedTextColor.RED)).append(yellow(" seconds to accept. "))
                .append(Component.text("Click here to join!", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/party join " + leaderName))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to join!", NamedTextColor.YELLOW))))
                .build());
        cues.accept(target, SoundSet.PARTY_INVITE);
    }

    /** Accepts the invite from the party {@code name} is in (the inviter, the leader, anyone), or the one standing when null. */
    private static void accept(Player player, @Nullable String name) {
        Map<Party, Invite> mine = invites.getOrDefault(player.getUuid(), Map.of());
        if (mine.isEmpty()) {
            Msg.fail(player, "No party invite is waiting for you.");
            return;
        }
        Invite invite = null;
        if (name == null) {
            if (mine.size() > 1) {
                Msg.fail(player, "Several parties invited you. Name one: ", Msg.v("/party join <player>"), ".");
                return;
            }
            invite = mine.values().iterator().next();
        } else {
            for (Invite candidate : mine.values()) {
                if (candidate.from().equalsIgnoreCase(name) || memberNamed(candidate.party(), name) != null) invite = candidate;
            }
            if (invite == null) {
                Msg.fail(player, "No party invite from ", Msg.v(name), ".");
                return;
            }
        }
        if (byMember.containsKey(player.getUuid())) {
            Msg.fail(player, "You're already in a party. Leave it first.");
            return;
        }
        Party party = invite.party();
        if (party.members.size() >= MAX) {
            Msg.fail(player, "That party is full.");
            return;
        }
        withdraw(invite, player.getUuid());
        party.add(player);
        byMember.put(player.getUuid(), party);
        Component line = who(player.getUsername()).append(yellow(" joined the party."));
        for (Player member : online(party)) frame(member, line);
    }

    // the clock ran out: both sides hear it, and a party left with nobody and nothing standing is over
    private static void expire(UUID invited, Party party) {
        Map<Party, Invite> mine = invites.get(invited);
        Invite invite = mine != null ? mine.get(party) : null;
        if (invite == null) return; // accepted, or voided with its party
        withdraw(invite, invited);
        Component line = yellow("The party invite to ").append(who(invite.to())).append(yellow(" has expired."));
        for (Player member : online(party)) frame(member, line);
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(invited);
        if (target != null) {
            frame(target, yellow("The party invite from ").append(who(invite.from())).append(yellow(" has expired.")));
        }
        settle(party);
    }

    private static void withdraw(Invite invite, UUID invited) {
        invite.expiry().cancel();
        invite.party().invited.remove(invited);
        Map<Party, Invite> mine = invites.get(invited);
        if (mine != null && mine.remove(invite.party()) != null && mine.isEmpty()) invites.remove(invited);
    }

    /** A party down to its leader with no invite standing is disbanded, hypixel's line and all. */
    private static void settle(Party party) {
        if (byMember.get(party.leader) != party || party.members.size() > 1 || !party.invited.isEmpty()) return;
        Component line = Component.text("The party was disbanded because all invites expired and the party was empty.",
                NamedTextColor.RED);
        for (Player member : online(party)) frame(member, line);
        dissolve(party);
    }

    private static void leave(Player player) {
        Party party = byMember.get(player.getUuid());
        if (party == null) {
            Msg.fail(player, "You're not in a party.");
            return;
        }
        if (party.leader.equals(player.getUuid())) {
            disband(player);
            return;
        }
        drop(party, player.getUuid());
        player.removeTag(CHANNEL);
        frame(player, yellow("You left the party."));
        Component line = who(player.getUsername()).append(yellow(" has left the party."));
        for (Player member : online(party)) frame(member, line);
        settle(party);
    }

    private static void kick(Player leader, String targetName) {
        Party party = led(leader);
        if (party == null) return;
        UUID target = memberNamed(party, targetName);
        if (target == null || target.equals(leader.getUuid())) {
            Msg.fail(leader, Msg.v(targetName), " is not in your party.");
            return;
        }
        String name = party.names.get(target);
        drop(party, target);
        Player kicked = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(target);
        if (kicked != null) {
            kicked.removeTag(CHANNEL);
            frame(kicked, yellow("You have been kicked from the party by ").append(who(leader.getUsername())));
        }
        Component line = who(name).append(yellow(" has been removed from the party."));
        for (Player member : online(party)) frame(member, line);
        settle(party);
    }

    private static void transfer(Player leader, String targetName) {
        Party party = led(leader);
        if (party == null) return;
        UUID target = memberNamed(party, targetName);
        if (target == null || target.equals(leader.getUuid())) {
            Msg.fail(leader, Msg.v(targetName), " is not in your party.");
            return;
        }
        party.leader = target;
        party.members.remove(target);
        party.members.addFirst(target);
        Component line = yellow("The party was transferred to ").append(who(party.names.get(target)))
                .append(yellow(" by ")).append(who(leader.getUsername()));
        for (Player member : online(party)) frame(member, line);
    }

    private static void disband(Player leader) {
        Party party = led(leader);
        if (party == null) return;
        Component line = who(leader.getUsername()).append(yellow(" has disbanded the party!"));
        for (Player member : online(party)) frame(member, line);
        dissolve(party);
    }

    private static void kickOffline(Player leader) {
        Party party = led(leader);
        if (party == null) return;
        List<String> gone = new ArrayList<>();
        for (UUID uuid : List.copyOf(party.members)) {
            if (MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) == null) {
                gone.add(party.names.get(uuid));
                drop(party, uuid);
            }
        }
        if (gone.isEmpty()) {
            Msg.fail(leader, "Nobody in your party is offline.");
            return;
        }
        Component line = yellow("Kicked ").append(who(String.join(", ", gone))).append(yellow(" because they were offline."));
        for (Player member : online(party)) frame(member, line);
        settle(party);
    }

    private static void allInvite(Player leader) {
        Party party = led(leader);
        if (party == null) return;
        party.allInvite = !party.allInvite;
        Component line = yellow("Party Settings: All Invite is now ")
                .append(party.allInvite ? Component.text("enabled", NamedTextColor.GREEN) : Component.text("disabled", NamedTextColor.RED))
                .append(yellow("."));
        for (Player member : online(party)) frame(member, line);
    }

    // a member playing a game elsewhere holds it up until the leader asks again
    private static void warp(Player leader) {
        Party party = led(leader);
        if (party == null) return;
        Warp warp = warps;
        List<Player> away = new ArrayList<>();
        for (Player member : online(party)) {
            if (member != leader && !warp.here(member, leader)) away.add(member);
        }
        if (away.isEmpty()) {
            Msg.fail(leader, "Your party is already with you.");
            return;
        }
        boolean busy = away.stream().anyMatch(member -> warp.busy(member, leader));
        // captured: Hypixel's line, the window ours (Confirm)
        if (busy && !Confirm.again(leader, "party warp")) {
            frame(leader, yellow("Some players are still in-game, run the command again to confirm warp!"));
            return;
        }
        List<String> warped = new ArrayList<>();
        for (Player member : away) {
            String refused = warp.bring(member, leader);
            if (refused != null) {
                Msg.fail(leader, "Couldn't warp ", Msg.v(member.getUsername()), ": " + refused);
                continue;
            }
            warped.add(member.getUsername());
            // captured: Hypixel's line to a summoned member
            frame(member, yellow("Party Leader, ").append(who(leader.getUsername())).append(yellow(", summoned you to their server.")));
        }
        if (!warped.isEmpty()) {
            frame(leader, yellow("You summoned ").append(who(String.join(", ", warped))).append(yellow(" to your server.")));
        }
    }

    /** {@code subject}'s party, with an online dot per member. */
    private static void list(Player to, Player subject) {
        Party party = byMember.get(subject.getUuid());
        if (party == null) {
            if (subject == to) Msg.fail(to, "You're not in a party.");
            else Msg.fail(to, Msg.v(subject.getUsername()), " is not in a party.");
            return;
        }
        Component leader = who(party.names.get(party.leader)).append(dot(party.leader));
        Component members = Component.empty();
        for (UUID uuid : party.members) {
            if (uuid.equals(party.leader)) continue;
            members = members.append(who(party.names.get(uuid))).append(dot(uuid)).append(Component.text(" "));
        }
        frame(to, Component.text("Party Size (" + party.members.size() + ")", NamedTextColor.GOLD),
                yellow("Party Leader: ").append(leader),
                yellow("Party Members: ").append(members));
    }

    private static void say(Player speaker, String message) {
        Party party = byMember.get(speaker.getUuid());
        if (party == null) {
            Msg.fail(speaker, "You're not in a party.");
            return;
        }
        Component line = chatLine(speaker, message);
        for (Player member : online(party)) member.sendMessage(line);
    }

    private static void channel(Player player, String channel) {
        if (CHANNEL_PARTY.equals(channel) && !byMember.containsKey(player.getUuid())) {
            Msg.fail(player, "You're not in a party.");
            return;
        }
        if (CHANNEL_PARTY.equals(channel)) player.setTag(CHANNEL, CHANNEL_PARTY);
        else player.removeTag(CHANNEL);
        Msg.ok(player, "You are now in the " + channel + " channel.");
    }

    private static void listAll(CommandSender to) {
        List<Party> all = byMember.values().stream().distinct().toList();
        if (all.isEmpty()) {
            Msg.say(to, "No parties.");
            return;
        }
        for (Party party : all) {
            List<String> names = new ArrayList<>();
            for (UUID uuid : party.members) names.add(party.names.get(uuid));
            Msg.say(to, Msg.v(party.names.get(party.leader)), " leads ", Msg.v(String.join(", ", names)));
        }
    }

    // the grace: five minutes to come back before a member is removed or, for the leader, the party disbands
    static void disconnected(Player player) {
        Party party = byMember.get(player.getUuid());
        if (party == null) return;
        UUID uuid = player.getUuid();
        Component minutes = Component.text(rejoinTicks / 1200, NamedTextColor.RED);
        Component line = party.leader.equals(uuid)
                ? yellow("The party leader, ").append(who(player.getUsername())).append(yellow(" has disconnected, they have "))
                        .append(minutes).append(yellow(" minutes to rejoin before the party is disbanded."))
                : who(player.getUsername()).append(yellow(" has disconnected, they have ")).append(minutes)
                        .append(yellow(" minutes to rejoin before they are removed from the party."));
        for (Player member : online(party)) if (member != player) frame(member, line);
        Task grace = MinecraftServer.getSchedulerManager().buildTask(() -> graceOver(party, uuid))
                .delay(TaskSchedule.tick((int) rejoinTicks)).schedule();
        Task previous = graces.put(uuid, grace);
        if (previous != null) previous.cancel();
    }

    // a rejoin cancelled the task, so reaching here means they never came back
    private static void graceOver(Party party, UUID uuid) {
        graces.remove(uuid);
        if (byMember.get(uuid) != party) return; // left, kicked, or moved on meanwhile
        if (party.leader.equals(uuid)) {
            Component line = yellow("The party was disbanded because the party leader disconnected.");
            for (Player member : online(party)) frame(member, line);
            dissolve(party);
            return;
        }
        String name = party.names.get(uuid);
        drop(party, uuid);
        Component line = who(name).append(yellow(" was removed from the party because they disconnected."));
        for (Player member : online(party)) frame(member, line);
        settle(party);
    }

    static void rejoined(Player player) {
        Task grace = graces.remove(player.getUuid());
        if (grace != null) grace.cancel();
        Party party = byMember.get(player.getUuid());
        if (party == null) return;
        party.names.put(player.getUuid(), player.getUsername());
        Component line = who(player.getUsername()).append(yellow(" has rejoined."));
        for (Player member : online(party)) frame(member, line);
    }

    private static void dissolve(Party party) {
        for (UUID uuid : party.members) {
            byMember.remove(uuid);
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) member.removeTag(CHANNEL);
        }
        for (UUID invited : List.copyOf(party.invited)) { // an invite to a party that is gone stands for nothing
            Map<Party, Invite> mine = invites.get(invited);
            Invite invite = mine != null ? mine.get(party) : null;
            if (invite != null) withdraw(invite, invited);
        }
    }

    private static void drop(Party party, UUID uuid) {
        party.remove(uuid);
        byMember.remove(uuid);
    }

    /** The party {@code leader} leads, else a refusal. */
    private static @Nullable Party led(Player leader) {
        Party party = byMember.get(leader.getUuid());
        if (party == null) {
            Msg.fail(leader, "You're not in a party.");
            return null;
        }
        if (!party.leader.equals(leader.getUuid())) {
            Msg.fail(leader, NOT_LEADER);
            return null;
        }
        return party;
    }

    private static @Nullable UUID memberNamed(Party party, String name) {
        for (UUID uuid : party.members) {
            if (party.names.get(uuid).equalsIgnoreCase(name)) return uuid;
        }
        return null;
    }

    private static @Nullable Player online(Player asker, String name) {
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (target == null) Msg.fail(asker, "No player named ", Msg.v(name), ".");
        return target;
    }

    private static @NotNull List<Player> online(Party party) {
        List<Player> out = new ArrayList<>();
        for (UUID uuid : party.members) {
            Player member = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
            if (member != null) out.add(member);
        }
        return out;
    }

    private static Component dot(UUID uuid) {
        boolean online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) != null;
        return Component.text(" •", online ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    // a name as Hypixel shows a player with no rank
    private static Component who(String name) {
        return Component.text(name, NamedTextColor.GRAY);
    }

    private static Component yellow(String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    // hypixel's frame: a blue rule above and below
    private static void frame(Player to, Component... lines) {
        Component rule = Msg.THEME.rule(53, NamedTextColor.BLUE);
        to.sendMessage(rule);
        for (Component line : lines) to.sendMessage(line);
        to.sendMessage(rule);
    }
}
