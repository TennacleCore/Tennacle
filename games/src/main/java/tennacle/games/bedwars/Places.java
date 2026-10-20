package tennacle.games.bedwars;

import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.beds.Beds;
import tennacle.games.stats.Tally;

import java.util.List;

/**
 * Where a live player spends the game, a second at a time: their base, the middle, by the generators, and another
 * side's base, split by that side's state rather than friend or foe (a cross-team rush visits a base it won't fight).
 */
final class Places implements Tally.Sampler {

    /** The map's middle, drawn on the Bed Wars Zones page: {@code cx, cz, rx, rz}. */
    static final MarkerKey<DataMarker> MID = MarkerKey.data("bedwars:mid");
    /** How near a generator counts as at it: {@code diamond, emerald}. */
    static final MarkerKey<DataMarker> REACH = MarkerKey.data("bedwars:reach");
    private static final double DEFAULT_REACH = 8;
    // a few blocks from the bed, for both the guard and the enemy they guard against
    private static final double GUARD = 6;

    private List<RegionMarker> bases;
    private @Nullable CompoundBinaryTag middle;
    private List<Point> diamonds, emeralds;
    private double diamondReach, emeraldReach;

    @Override
    public void sample(@NotNull Game game, @NotNull Player player, @NotNull Slot seat, @NotNull Tally tally) {
        Respawns respawns = game.module(Respawns.class);
        if (game.isGhost(player) || respawns != null && respawns.isDead(player)) return;
        if (bases == null) read(game);
        Pos at = player.getPosition();
        var id = player.getUuid();
        for (RegionMarker base : bases) {
            if (!base.contains(at)) continue;
            String team = base.group();
            tally.pair(id, team, "time-at-base", 1);
            if (team.equals(seat.team())) tally.add(id, "time-at-base", 1);
            else {
                tally.add(id, "time-at-other-bases", 1);
                tally.add(id, "time-at-bases-" + state(game, team), 1);
            }
        }
        if (middle != null && inside(middle, at)) tally.add(id, "time-at-mid", 1);
        if (near(diamonds, at, diamondReach)) tally.add(id, "time-at-diamonds", 1);
        if (near(emeralds, at, emeraldReach)) tally.add(id, "time-at-emeralds", 1);
        if (guarding(game, player, seat, respawns)) tally.add(id, "time-guarding-bed", 1);
    }

    private static boolean guarding(Game game, Player player, Slot seat, @Nullable Respawns respawns) {
        if (!Beds.stands(game, seat.team())) return false;
        RegionMarker bed = game.arena().marker(BedWars.BED, seat.team());
        if (bed == null) return false;
        var box = bed.bounds();
        Point middle = new Vec((box.min().blockX() + box.max().blockX() + 1) / 2.0, box.min().blockY() + 0.5,
                (box.min().blockZ() + box.max().blockZ() + 1) / 2.0);
        if (player.getPosition().distanceSquared(middle) > GUARD * GUARD) return false;
        for (Player other : game.members()) {
            Slot theirs = game.roster().slotOf(other);
            if (theirs == null || theirs.team().equals(seat.team()) || game.isGhost(other)) continue;
            if (respawns != null && respawns.isDead(other)) continue;
            if (other.getPosition().distanceSquared(middle) <= GUARD * GUARD) return true;
        }
        return false;
    }

    private void read(Game game) {
        bases = game.arena().markers(BedWars.BASE);
        List<DataMarker> mid = game.arena().markers(MID);
        middle = mid.isEmpty() ? null : mid.getFirst().payload();
        diamonds = game.arena().markers(BedWars.DIAMOND).stream().map(PointMarker::point).map(Point.class::cast).toList();
        emeralds = game.arena().markers(BedWars.EMERALD).stream().map(PointMarker::point).map(Point.class::cast).toList();
        List<DataMarker> reach = game.arena().markers(REACH);
        CompoundBinaryTag set = reach.isEmpty() ? CompoundBinaryTag.empty() : reach.getFirst().payload();
        diamondReach = set.keySet().contains("diamond") ? set.getDouble("diamond") : DEFAULT_REACH;
        emeraldReach = set.keySet().contains("emerald") ? set.getDouble("emerald") : DEFAULT_REACH;
    }

    // alive: its bed stands; bedless: players left without one; out: eliminated, or nobody ever sat there
    private static String state(Game game, String team) {
        if (Beds.stands(game, team)) return "alive";
        LastStanding standing = game.module(LastStanding.class);
        return standing != null && standing.standing(team) > 0 ? "bedless" : "out";
    }

    private static boolean inside(CompoundBinaryTag middle, Point at) {
        double dx = (at.x() - middle.getDouble("cx")) / middle.getDouble("rx");
        double dz = (at.z() - middle.getDouble("cz")) / middle.getDouble("rz");
        return dx * dx + dz * dz <= 1;
    }

    private static boolean near(List<Point> generators, Point at, double reach) {
        for (Point generator : generators) {
            if (generator.distanceSquared(at) <= reach * reach) return true;
        }
        return false;
    }
}
