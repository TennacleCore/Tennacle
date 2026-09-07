package tennacle.games.map;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The line a map is symmetric about: the middle of its {@code center} region, and the origin for a map that
 * names none. What reads it is anything that asks which half of a map somebody is standing in - which side a
 * seat is pushing into, how far a fight has travelled from the middle.
 */
public final class Center {

    public static final MarkerKey<RegionMarker> MARKER = MarkerKey.region("center");

    private Center() {}

    /** The middle of the map on the horizontal, {@code 0, 0} where no {@code center} region says otherwise. */
    public static @NotNull Point of(@NotNull Game game) {
        List<RegionMarker> centers = game.arena().markers(MARKER);
        if (centers.isEmpty()) return Vec.ZERO;
        RegionMarker.Cuboid box = centers.getFirst().bounds();
        return new Vec((box.min().blockX() + box.max().blockX() + 1) / 2.0, 0,
                (box.min().blockZ() + box.max().blockZ() + 1) / 2.0);
    }

    /**
     * Which side of the middle {@code at} falls on, as the team whose own spawn sits that way, or null on the
     * line itself and for a map whose sides do not divide along one axis. The wider spread of the seats decides
     * the axis, so a map laid along x and a map laid along z both answer.
     */
    public static @Nullable String sideOf(@NotNull Game game, @NotNull Point at) {
        Point middle = of(game);
        double spreadX = 0, spreadZ = 0;
        for (Slot seat : game.roster().slots()) {
            if (seat.spawn() == null) continue;
            spreadX = Math.max(spreadX, Math.abs(seat.spawn().x() - middle.x()));
            spreadZ = Math.max(spreadZ, Math.abs(seat.spawn().z() - middle.z()));
        }
        boolean alongX = spreadX >= spreadZ;
        double side = alongX ? at.x() - middle.x() : at.z() - middle.z();
        if (side == 0) return null;
        for (Slot seat : game.roster().slots()) {
            if (seat.spawn() == null) continue;
            double theirs = alongX ? seat.spawn().x() - middle.x() : seat.spawn().z() - middle.z();
            if (theirs != 0 && Math.signum(theirs) == Math.signum(side)) return seat.team();
        }
        return null;
    }

    /** Whether {@code player} is standing on a side that is not their own - the half of pressure a tick can see. */
    public static boolean pushing(@NotNull Game game, @NotNull Player player) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null) return false;
        String standing = sideOf(game, player.getPosition());
        return standing != null && !standing.equals(seat.team());
    }
}
