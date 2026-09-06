package tennacle.games.watch;

import tennacle.games.Matches;
import tennacle.looks.Frames;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.time.LocalDate;
import tennacle.games.Mode;
import tennacle.looks.Look;
import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.board.Board;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.tab.TabList;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.polyp.platform.SharedTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.color.TeamColor;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.DisplayScoreboardPacket;
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.network.packet.server.play.UpdateScorePacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a theater's watchers see besides the world: the lineup's colors on names and nameplates, the health column
 * the game showed, the game's sidebar as its first seat saw it, the look's tab chrome, and each other greyed and
 * sunk below the players the way a game lists its spectators. A watcher's own ghost shares their name, and
 * teams key on names: the watcher lists under a sort name on 1.8 (a team past the lineup's sinks it) and on
 * nothing but the list order on a modern client, so the ghost keeps the seat's color either way. One per
 * playback; {@link #enter} dresses a watcher, {@link #exit} undresses.
 */
final class Dressing {

    private static final Map<Playback, Dressing> BY_PLAYBACK = new ConcurrentHashMap<>();
    private static final Component HEALTH_LABEL = Component.text("❤", NamedTextColor.RED);

    private record Seat(String name, String team, TextColor color) {}

    private final Playback playback;
    private final String code;
    private final String lookName;
    private final Look look;
    private final List<Seat> lineup;
    private final boolean tabHealth;
    private final boolean nameHealth;
    private final String objective;
    private final String teamPrefix;
    private final String spectatorTeam;
    private final Set<Player> dressed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> scores = new ConcurrentHashMap<>();
    private final Runnable untick;
    private final Remote remote;
    private volatile @Nullable Replays.Rows rows;
    private volatile @Nullable Board board;

    Dressing(@NotNull Shard stage, @NotNull Playback playback, @NotNull String gameId, @NotNull String code,
             @Nullable Mode mode, @NotNull String lookName, @NotNull List<String> lineup, @NotNull List<String> health) {
        this.playback = playback;
        this.code = code;
        this.lookName = lookName;
        this.look = Matches.look(lookName);
        this.lineup = lineup.stream().map(Dressing::seat).toList();
        this.tabHealth = health.contains(HealthDisplay.TAB);
        this.nameHealth = health.contains(HealthDisplay.NAME);
        // 1.8 reads at most 16 chars of an objective or team name
        this.objective = "rh-" + gameId.substring(0, Math.min(8, gameId.length()));
        this.teamPrefix = "r" + gameId.substring(0, Math.min(6, gameId.length())) + "-";
        this.spectatorTeam = "r" + gameId.substring(0, Math.min(6, gameId.length())) + "~";
        Mode.Marks marks = mode != null ? mode.marks() : null;
        this.remote = new Remote(playback, gameId, this::alive,
                marks != null ? mark -> marks.render(mark, look.theme()) : Remote::plainMark);
        this.untick = stage.onTick(tick -> tick());
        BY_PLAYBACK.put(playback, this);
    }

    // a seat whose ghost stands in the theater right now: the compass lists these
    private List<Lineup.Entry> alive() {
        List<Lineup.Entry> out = new ArrayList<>();
        for (Seat seat : lineup) {
            Ghost ghost = playback.ghost(seat.name());
            if (ghost == null || ghost.getInstance() == null || playback.hidden(seat.name())) continue;
            out.add(new Lineup.Entry(seat.name(), seat.color(), playback.skin(seat.name()), () -> {
                Ghost now = playback.ghost(seat.name());
                return now != null && now.getInstance() != null ? now.getPosition() : null;
            }));
        }
        return out;
    }

    /** The dressing of {@code playback}, or null for a playback nobody is watching through {@link Replays}. */
    static @Nullable Dressing of(@NotNull Playback playback) {
        return BY_PLAYBACK.get(playback);
    }

    // name|team|color, as Replays wrote the lineup
    private static Seat seat(String entry) {
        String[] parts = entry.split("\\|", 3);
        TextColor color = parts.length > 2 ? TextColor.color(Integer.parseInt(parts[2])) : NamedTextColor.WHITE;
        return new Seat(parts[0], parts.length > 1 ? parts[1] : "", color);
    }

    @NotNull Look look() {
        return look;
    }

    /** {@code controller}: the watcher who holds the remote - the one who started the watch, a party's leader. */
    void enter(@NotNull Player viewer, boolean controller) {
        if (!dressed.add(viewer)) return;
        remote.enter(viewer, controller);
        viewer.setTag(ShardTabList.SPECTATOR, true); // both eras sink and grey a spectator entry natively
        viewer.setTag(ShardTabList.LIST_ORDER, -1); // a modern client's own row sorts on this
        viewer.setTag(ShardTabList.SORT_NAME, sortName(viewer.getUsername())); // a 1.8 row apart from the ghost's
        viewer.setDisplayName(Component.text(viewer.getUsername(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        ShardTabList.resync(viewer);
        TabList.refresh(viewer);
        SharedTeam.suspend(viewer, true);
        // a 1.8 client sorts its own row by team name: the watchers' team sits past the lineup's ('~' after '-')
        TeamsPacket joined = new TeamsPacket(spectatorTeam,
                new TeamsPacket.AddEntitiesToTeamAction(List.of(ShardTabList.listName(viewer))));
        for (Player other : dressed) if (other != viewer && Canvas.isLegacy(other)) other.sendPacket(joined);
        teams().forEach((team, names) -> viewer.sendPacket(new TeamsPacket(teamPrefix + team,
                new TeamsPacket.CreateTeamAction(settings(colorOf(team), TeamsPacket.NameTagVisibility.ALWAYS), names))));
        if (Canvas.isLegacy(viewer)) {
            viewer.sendPacket(new TeamsPacket(spectatorTeam, new TeamsPacket.CreateTeamAction(
                    settings(NamedTextColor.GRAY, TeamsPacket.NameTagVisibility.NEVER),
                    dressed.stream().map(ShardTabList::listName).toList())));
        }
        if (tabHealth || nameHealth) {
            viewer.sendPacket(new ScoreboardObjectivePacket(objective, (byte) 0, HEALTH_LABEL,
                    ScoreboardObjectivePacket.Type.INTEGER, null));
            if (tabHealth) viewer.sendPacket(new DisplayScoreboardPacket((byte) 0, objective));
            if (nameHealth) viewer.sendPacket(new DisplayScoreboardPacket((byte) 2, objective));
            scores.forEach((name, score) -> viewer.sendPacket(new UpdateScorePacket(name, objective, score, null, null)));
        }
        Board current = board;
        if (current != null) Boards.show(viewer, current, Scoreboard.PRIORITY);
    }

    void exit(@NotNull Player viewer) {
        if (!dressed.remove(viewer)) return;
        remote.exit(viewer);
        Board current = board;
        if (current != null) Boards.drop(viewer, current);
        if (tabHealth || nameHealth) {
            viewer.sendPacket(new ScoreboardObjectivePacket(objective, (byte) 1, null, null, null));
        }
        for (String team : teams().keySet()) {
            viewer.sendPacket(new TeamsPacket(teamPrefix + team, new TeamsPacket.RemoveTeamAction()));
        }
        if (Canvas.isLegacy(viewer)) viewer.sendPacket(new TeamsPacket(spectatorTeam, new TeamsPacket.RemoveTeamAction()));
        TeamsPacket left = new TeamsPacket(spectatorTeam,
                new TeamsPacket.RemoveEntitiesToTeamAction(List.of(ShardTabList.listName(viewer))));
        for (Player other : dressed) if (Canvas.isLegacy(other)) other.sendPacket(left);
        SharedTeam.suspend(viewer, false);
        viewer.removeTag(ShardTabList.SPECTATOR);
        viewer.removeTag(ShardTabList.LIST_ORDER);
        viewer.removeTag(ShardTabList.SORT_NAME);
        viewer.setDisplayName(null);
        ShardTabList.resync(viewer);
        TabList.refresh(viewer);
    }

    void close() {
        untick.run();
        BY_PLAYBACK.remove(playback);
        for (Player viewer : List.copyOf(dressed)) exit(viewer);
        remote.close();
    }

    /** The recorded sidebar moved: the same board re-renders, a new row count raises a fresh one. */
    void rows(@NotNull Replays.Rows recorded) {
        Replays.Rows now = adorn(recorded);
        Replays.Rows before = rows;
        rows = now;
        Board current = board;
        if (current != null && before != null && before.lines().size() == now.lines().size()) {
            Boards.touchAll(current);
            return;
        }
        Board.Builder builder = Board.builder().width(0) // the recorded lines carry their own padding
                .title(viewer -> look.theme().boardTitle(viewer, "REPLAY"));
        for (int i = 0; i < now.lines().size(); i++) {
            int index = i;
            builder.line(viewer -> {
                Replays.Rows shown = rows;
                return shown != null && index < shown.lines().size() ? shown.lines().get(index) : Component.empty();
            });
        }
        Board fresh = builder.build();
        board = fresh;
        for (Player viewer : dressed) {
            Boards.show(viewer, fresh, Scoreboard.PRIORITY);
            if (current != null) Boards.drop(viewer, current);
        }
    }

    // the theater's frame over the recorded rows: today's date goes over the recorded date and code
    private Replays.Rows adorn(Replays.Rows recorded) {
        List<Component> lines = new ArrayList<>(recorded.lines());
        for (int i = 0; !code.isEmpty() && i < lines.size(); i++) {
            if (PlainTextComponentSerializer.plainText().serialize(lines.get(i)).contains(code)) {
                lines.add(i, look.theme().prose(Frames.date(lookName, LocalDate.now())));
                break;
            }
        }
        return new Replays.Rows(recorded.title(), lines);
    }

    // the recorded health at the cursor, scored under the ghost's name: the tab column and the nameplate's read
    private void tick() {
        remote.tick();
        if (!tabHealth && !nameHealth) return;
        for (Seat seat : lineup) {
            Float health = StateChannels.health(playback, seat.name());
            if (health == null) continue;
            int points = (int) Math.ceil(health);
            Integer last = scores.put(seat.name(), points);
            if (last != null && last == points) continue;
            UpdateScorePacket packet = new UpdateScorePacket(seat.name(), objective, points, null, null);
            for (Player viewer : dressed) viewer.sendPacket(packet);
        }
    }

    // the italic code marks a spectator row, as Nametags does; the 1.8 profile-name cap holds the rest
    private static String sortName(String username) {
        return "\u00a7o" + username.substring(0, Math.min(username.length(), 14));
    }

    private Map<String, List<String>> teams() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Seat seat : lineup) out.computeIfAbsent(seat.team(), t -> new ArrayList<>()).add(seat.name());
        return out;
    }

    private TextColor colorOf(String team) {
        for (Seat seat : lineup) if (seat.team().equals(team)) return seat.color();
        return NamedTextColor.WHITE;
    }

    // the prefix carries the color to a 1.8 nameplate, the team color to a modern one - Nametags' shape
    private static TeamsPacket.Settings settings(TextColor color, TeamsPacket.NameTagVisibility names) {
        return new TeamsPacket.Settings(Component.empty(), Component.empty().color(color), Component.empty(),
                names, TeamsPacket.CollisionRule.NEVER,
                TeamColor.fromName(NamedTextColor.nearestTo(color).toString()), (byte) 0);
    }
}
