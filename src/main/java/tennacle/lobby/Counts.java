package tennacle.lobby;

import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.event.GameLaunchEvent;
import io.github.term4.grotto.event.PhaseChangeEvent;
import io.github.term4.grotto.event.SeatChangeEvent;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Queues;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The numbers a lobby shows, and when they move: a launch, a seat taken or left, a phase, an end, the queue. Every
 * change in a tick repaints once, at its end.
 */
public final class Counts {

    /** The key of every screen that shows these numbers. */
    public static final String KEY = "lobby:counts";

    private static final AtomicBoolean MOVED = new AtomicBoolean();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final List<Runnable> WATCHERS = new CopyOnWriteArrayList<>();

    private Counts() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(GameLaunchEvent.class, e -> moved());
        events.addListener(SeatChangeEvent.class, e -> moved());
        events.addListener(PhaseChangeEvent.class, e -> moved());
        Matches.grotto().onGameEnd((game, outcome) -> moved());
        Queues.onChange(Counts::moved);
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (!MOVED.getAndSet(false)) return;
            Screens.repaint(KEY);
            WATCHERS.forEach(Runnable::run);
        }).repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Runs {@code action} after the numbers moved, once a tick at most. */
    public static void onMove(@NotNull Runnable action) {
        WATCHERS.add(action);
    }

    public static void moved() {
        MOVED.set(true);
    }

    public static int inLobby(@NotNull Lobbies.Lobby lobby) {
        return lobby.world().getPlayers().size();
    }

    /** Everyone seated in a live game a lobby hosts. */
    public static int playing(@NotNull Lobbies.Lobby lobby) {
        int sum = 0;
        for (Mode mode : lobby.modes()) sum += playing(mode);
        return sum;
    }

    public static int playing(@NotNull Mode mode) {
        int sum = 0;
        for (Game game : games(mode)) if (Match.live(game)) sum += Match.seated(game);
        return sum;
    }

    /** Everyone seated in a live game of {@code mode} played at {@code id}'s team size. */
    public static int playing(@NotNull Mode mode, @NotNull String id) {
        if (mode.plays().size() < 2) return playing(mode);
        int size = mode.plays().getOrDefault(id, 1);
        int sum = 0;
        for (Game game : games(mode)) if (Match.live(game) && teamSize(game) == size) sum += Match.seated(game);
        return sum;
    }

    /** Everyone seated in a game of {@code mode} still filling or counting down. */
    public static int waiting(@NotNull Mode mode) {
        int sum = 0;
        for (Game game : games(mode)) if (Match.opening(game)) sum += Match.seated(game);
        return sum;
    }

    public static int queued(@NotNull String id) {
        return Queues.queued(id);
    }

    private static List<Game> games(Mode mode) {
        return Matches.grotto().games().stream()
                .filter(game -> game.status() == Game.Status.OPEN && Matches.modeOf(game) == mode).toList();
    }

    // the team size rule when it is set, else the fullest team's seats taken
    private static int teamSize(Game game) {
        int rule = game.rules().resolve(Scope.GAME, Roster.TEAM_SIZE);
        if (rule > 0) return rule;
        Map<String, Integer> byTeam = new HashMap<>();
        for (Slot seat : game.roster().slots()) {
            if (game.roster().ownerOf(seat) != null) byTeam.merge(seat.team(), 1, Integer::sum);
        }
        return byTeam.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
