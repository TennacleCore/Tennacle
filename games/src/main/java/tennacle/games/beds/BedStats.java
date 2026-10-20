package tennacle.games.beds;

import io.github.term4.grotto.Game;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerCancelDiggingEvent;
import net.minestom.server.event.player.PlayerFinishDiggingEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.block.BlockFace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Match;
import tennacle.games.lives.Lives;
import tennacle.games.stats.Tally;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A bed in play as the stats see it. Digging at another side's bed counts the bed and any block touching it; an
 * attempt runs from the first dig until the bed falls, or the digger dies or leaves with it standing (a failed one).
 * A save is killing someone who was just at your bed, or placing a block between a digger's eyes and what they dig.
 */
final class BedStats {

    // a dig this recent at your side's bed makes the digger's death a save
    private static final long SAVE_TICKS = 100;

    private record Dig(String team, Point at, long since, boolean[] blocked) {}

    private final Game game;
    private final Function<Point, @Nullable String> standingBedAt;
    private final Map<UUID, Dig> digging = new ConcurrentHashMap<>();
    // player -> the beds they are attempting, each with the tick they last dug at it
    private final Map<UUID, Map<String, Long>> attempts = new ConcurrentHashMap<>();

    BedStats(Game game, Function<Point, @Nullable String> standingBedAt) {
        this.game = game;
        this.standingBedAt = standingBedAt;
        game.on(PlayerStartDiggingEvent.class, e -> start(e.getPlayer(), e.getBlockPosition()));
        game.on(PlayerCancelDiggingEvent.class, e -> stop(e.getPlayer()));
        game.on(PlayerFinishDiggingEvent.class, e -> stop(e.getPlayer()));
    }

    void placed(Player placer, Point pos) {
        String mine = Match.teamOf(game, placer);
        if (mine.isEmpty()) return;
        if (mine.equals(touching(pos))) Tally.add(game, placer, "bed-defense-placed");
        for (Map.Entry<UUID, Dig> dig : digging.entrySet()) {
            Dig at = dig.getValue();
            if (!at.team().equals(mine) || at.blocked()[0]) continue;
            Player digger = game.firstMember(m -> m.getUuid().equals(dig.getKey()));
            if (digger == null || !between(digger.getPosition().add(0, digger.getEyeHeight(), 0), at.at(), pos)) continue;
            at.blocked()[0] = true; // once a dig, however many blocks go up
            save(placer, "bed-saves-by-block");
        }
    }

    private void save(Player saver, String how) {
        Tally.add(game, saver, "bed-saves");
        Tally.add(game, saver, how);
    }

    // the segment from the eyes to the dug block's middle runs through block {@code cell}
    static boolean between(Point eyes, Point dug, Point cell) {
        if (cell.sameBlock(dug)) return false;
        double[] from = {eyes.x(), eyes.y(), eyes.z()};
        double[] to = {dug.blockX() + 0.5, dug.blockY() + 0.5, dug.blockZ() + 0.5};
        double[] lo = {cell.blockX(), cell.blockY(), cell.blockZ()};
        double enter = 0, leave = 1;
        for (int axis = 0; axis < 3; axis++) {
            double d = to[axis] - from[axis];
            if (Math.abs(d) < 1e-9) {
                if (from[axis] < lo[axis] || from[axis] > lo[axis] + 1) return false;
                continue;
            }
            double t1 = (lo[axis] - from[axis]) / d, t2 = (lo[axis] + 1 - from[axis]) / d;
            enter = Math.max(enter, Math.min(t1, t2));
            leave = Math.min(leave, Math.max(t1, t2));
            if (enter > leave) return false;
        }
        return true;
    }

    void broke(Player breaker, Point pos) {
        String team = touching(pos);
        if (team != null && !team.equals(Match.teamOf(game, breaker))) Tally.add(game, breaker, "bed-defense-broken");
    }

    // the attempts on it end; whoever took it has beds-broken
    void fell(String team) {
        digging.forEach((player, dig) -> {
            if (dig.team().equals(team)) stop(player);
        });
        attempts.values().forEach(beds -> beds.remove(team));
    }

    void died(Lives.Death death) {
        UUID victim = death.victim().getUuid();
        stop(victim);
        Player killer = death.killer();
        Map<String, Long> theirs = attempts.getOrDefault(victim, Map.of());
        if (killer != null && killer != death.victim()) {
            Long at = theirs.get(Match.teamOf(game, killer));
            if (at != null && game.tick() - at <= SAVE_TICKS) save(killer, "bed-saves-by-kill");
        }
        fail(victim);
    }

    void left(Player player) {
        stop(player.getUuid());
        fail(player.getUuid());
    }

    private void start(Player player, Point pos) {
        if (!Match.live(game) || game.roster().slotOf(player) == null) return;
        String team = standingBedAt.apply(pos);
        if (team == null) team = touching(pos);
        String mine = Match.teamOf(game, player);
        if (team == null || team.equals(mine)) return;
        UUID id = player.getUuid();
        stop(id);
        digging.put(id, new Dig(team, pos, game.tick(), new boolean[1]));
        Map<String, Long> beds = attempts.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
        if (beds.put(team, game.tick()) == null) {
            Tally.add(game, player, "bed-attempts");
            Tally.pair(game, id, team, "bed-attempts", 1);
        }
    }

    private void stop(Player player) {
        stop(player.getUuid());
    }

    private void stop(UUID player) {
        Dig dig = digging.remove(player);
        if (dig == null) return;
        long ticks = game.tick() - dig.since();
        Tally.add(game, player, "bed-dig-ticks", ticks);
        Tally.pair(game, player, dig.team(), "bed-dig-ticks", ticks);
        Map<String, Long> beds = attempts.get(player);
        if (beds != null) beds.computeIfPresent(dig.team(), (team, at) -> game.tick());
    }

    private void fail(UUID player) {
        Map<String, Long> beds = attempts.remove(player);
        if (beds != null) Tally.add(game, player, "bed-attempts-failed", beds.size());
    }

    // the side whose standing bed a block touches
    private @Nullable String touching(Point pos) {
        for (BlockFace face : BlockFace.values()) {
            var dir = face.toDirection();
            String team = standingBedAt.apply(pos.add(dir.normalX(), dir.normalY(), dir.normalZ()));
            if (team != null) return team;
        }
        return null;
    }
}
