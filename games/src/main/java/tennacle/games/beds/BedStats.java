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
 */
final class BedStats {

    // a dig this recent at your side's bed makes the digger's death a save
    private static final long SAVE_TICKS = 100;

    private record Dig(String team, long since) {}

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
        if (!mine.isEmpty() && mine.equals(touching(pos))) Tally.add(game, placer, "bed-defense-placed");
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
            if (at != null && game.tick() - at <= SAVE_TICKS) Tally.add(game, killer, "bed-saves");
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
        digging.put(id, new Dig(team, game.tick()));
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
