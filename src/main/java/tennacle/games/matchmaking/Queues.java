package tennacle.games.matchmaking;

import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Options;
import tennacle.games.Platform;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The duel side of {@code /play}: parties that want the same id and platform wait in arrival order, and the first
 * that fill both teams of the id's size start a match, strangers sharing a team as on Hypixel. A party queues
 * through its leader and sits together; {@code /lobby} steps out.
 */
public final class Queues {

    private record Entry(UUID leader, String id, String platform, int size, String label, long since) {
        boolean sameLine(Entry other) {
            return id.equals(other.id) && platform.equals(other.platform);
        }
    }

    private static final Map<UUID, Entry> waiting = new LinkedHashMap<>(); // insertion order = fairness
    private static final List<Runnable> changes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Queues() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> {
            synchronized (waiting) {
                waiting.remove(e.getPlayer().getUuid());
            }
            changed();
        });
    }

    /** Runs {@code action} whenever the line moves: a side joins, leaves or is matched. */
    public static void onChange(@NotNull Runnable action) {
        changes.add(action);
    }

    private static void changed() {
        changes.forEach(Runnable::run);
    }

    /** Drops {@code player} from every wait: an entry they lead, and any party's they follow. A seat anywhere
     *  (a queue match, an accepted duel, a rejoin) calls this, so nobody can be paired twice. */
    public static void dequeue(@NotNull Player player) {
        synchronized (waiting) {
            waiting.remove(player.getUuid());
            waiting.values().removeIf(e -> {
                Player leader = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(e.leader());
                return leader == null || Parties.of(leader).contains(player);
            });
        }
        changed();
    }

    /** {@code true} when {@code player} was waiting. */
    public static boolean leave(@NotNull Player player) {
        Entry gone;
        synchronized (waiting) {
            gone = waiting.remove(player.getUuid());
        }
        if (gone == null) return false;
        changed();
        Msg.say(player, "You have been removed from the queue.");
        return true;
    }

    public static boolean waiting(@NotNull Player player) {
        synchronized (waiting) {
            return waiting.containsKey(player.getUuid());
        }
    }

    /** What {@code player}'s side waits for: the {@code /play} id and since when, by the entry they lead or follow. */
    public record Waiting(@NotNull String id, @NotNull String label, long since) {}

    public static @Nullable Waiting of(@NotNull Player player) {
        UUID leader = Parties.leaderOf(player);
        synchronized (waiting) {
            Entry entry = waiting.get(leader);
            return entry != null ? new Waiting(entry.id(), entry.label(), entry.since()) : null;
        }
    }

    /** How many wait for {@code id}, parties counted by their size. */
    public static int queued(@NotNull String id) {
        synchronized (waiting) {
            return waiting.values().stream().filter(e -> e.id().equals(id)).mapToInt(Entry::size).sum();
        }
    }

    /** Why {@code me}'s side can't take {@code seats} a side in {@code title}, else null. */
    static @Nullable String unfit(@NotNull Player me, int seats, @NotNull String title) {
        if (!Parties.leads(me)) return Parties.NOT_LEADER;
        List<Player> side = Parties.of(me);
        for (Player member : side) {
            if (Matches.grotto().engaged(member)) return member.getUsername() + " is in a game.";
        }
        if (side.size() > seats) return "Your party of " + side.size() + " doesn't fit; " + title + " takes " + seats + " a side.";
        return null;
    }

    /** How {@code id} reads: the title, sized when the mode plays more than one ({@code Bridge 2v2}). */
    public static @NotNull String label(@NotNull Mode mode, @NotNull String id) {
        int side = mode.plays().getOrDefault(id, 1);
        return mode.plays().size() > 1 ? mode.title() + " " + side + "v" + side : mode.title();
    }

    /**
     * Queues {@code me}'s party for the duel {@code id}; the arrival that fills both teams starts the match. Why it
     * can't, else null once queued or started.
     */
    public static @Nullable String enqueue(@NotNull Player me, @NotNull String id, @Nullable String platformName) {
        Mode mode = Matches.played(id);
        Integer seats = mode != null ? mode.plays().get(id) : null;
        if (mode == null || seats == null || Matches.joinable(mode)) return "No queue for " + id + ".";
        if (platformName != null && Matches.platform(platformName) == null) {
            return "No platform named " + platformName + ". Platforms: " + String.join(", ", Matches.platforms()) + ".";
        }
        Platform platform = Matches.platformFor(platformName);
        String label = label(mode, id) + (platform.name().equals(Platform.DEFAULT.name()) ? "" : " (" + platform.name() + ")");
        String unfit = unfit(me, seats, label);
        if (unfit != null) return unfit;
        List<Player> side = Parties.of(me);
        try {
            int onMap = Matches.seatsPerSide(mode);
            if (onMap < seats) return mode.title() + "'s maps seat " + onMap + " a side.";
        } catch (IllegalStateException e) { // the map may not be authored yet
            return e.getMessage();
        }
        Entry mine = new Entry(me.getUuid(), id, platform.name(), side.size(), label, System.currentTimeMillis());
        List<Entry> line;
        synchronized (waiting) {
            waiting.remove(me.getUuid());
            waiting.put(me.getUuid(), mine);
            line = waiting.values().stream().filter(mine::sameLine).toList();
        }
        Map<Entry, List<Player>> parties = new HashMap<>();
        List<Entry> fresh = new ArrayList<>();
        for (Entry entry : line) {
            List<Player> party = entry == mine ? side : standing(entry);
            if (party == null) {
                synchronized (waiting) {
                    waiting.remove(entry.leader(), entry);
                }
                continue;
            }
            fresh.add(entry);
            parties.put(entry, party);
        }
        List<List<Entry>> teams = fill(fresh, seats);
        if (teams != null) {
            synchronized (waiting) {
                // an arrival on another thread may have started some of them first
                if (teams.stream().flatMap(List::stream).allMatch(e -> waiting.get(e.leader()) == e)) {
                    teams.stream().flatMap(List::stream).forEach(e -> waiting.remove(e.leader()));
                } else {
                    teams = null;
                }
            }
        }
        if (teams == null || !teams.get(0).contains(mine) && !teams.get(1).contains(mine)) {
            Msg.ok(me, "You are now queued for " + label + ".");
        }
        if (teams != null) start(mode, platform, label, players(teams.get(0), parties), players(teams.get(1), parties));
        changed();
        return null;
    }

    // the party behind an entry as it stands now; null once it left, took a seat, or changed size
    private static @Nullable List<Player> standing(Entry entry) {
        Player leader = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(entry.leader());
        if (leader == null) return null;
        List<Player> party = Parties.of(leader);
        if (party.size() != entry.size()) return null;
        for (Player p : party) {
            if (Matches.grotto().engaged(p)) return null;
        }
        return party;
    }

    // two teams of exactly `seats` from the line, the oldest parties first; null while it can't fill both
    private static @Nullable List<List<Entry>> fill(List<Entry> line, int seats) {
        int[] team = new int[line.size()];
        if (!place(line, seats, 0, 0, 0, team, new HashSet<>())) return null;
        List<List<Entry>> teams = List.of(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < team.length; i++) {
            if (team[i] > 0) teams.get(team[i] - 1).add(line.get(i));
        }
        return teams;
    }

    // a state seen twice already failed: success returns straight up
    private static boolean place(List<Entry> line, int seats, int i, int a, int b, int[] team, Set<Integer> seen) {
        if (a == seats && b == seats) {
            Arrays.fill(team, i, team.length, 0);
            return true;
        }
        if (i == line.size() || !seen.add((i * (seats + 1) + a) * (seats + 1) + b)) return false;
        int size = line.get(i).size();
        team[i] = 1;
        if (a + size <= seats && place(line, seats, i + 1, a + size, b, team, seen)) return true;
        team[i] = 2;
        if (a != b && b + size <= seats && place(line, seats, i + 1, a, b + size, team, seen)) return true;
        team[i] = 0;
        return place(line, seats, i + 1, a, b, team, seen);
    }

    private static List<Player> players(List<Entry> team, Map<Entry, List<Player>> parties) {
        List<Player> out = new ArrayList<>();
        for (Entry entry : team) out.addAll(parties.get(entry));
        return out;
    }

    private static void start(Mode mode, Platform platform, String label, List<Player> a, List<Player> b) {
        Matches.begin(mode, platform, List.of(a, b), Options.NONE).whenComplete((game, failure) -> {
            if (failure != null) {
                for (Player p : a) Msg.fail(p, "The match could not start: " + failure.getMessage());
                for (Player p : b) Msg.fail(p, "The match could not start: " + failure.getMessage());
                return;
            }
            for (Player p : a) Msg.say(p, label + " vs ", Msg.v(names(b)), ".");
            for (Player p : b) Msg.say(p, label + " vs ", Msg.v(names(a)), ".");
        });
    }

    private static String names(List<Player> side) {
        return String.join(", ", side.stream().map(Player::getUsername).toList());
    }
}
