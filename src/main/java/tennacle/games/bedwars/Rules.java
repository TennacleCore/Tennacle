package tennacle.games.bedwars;

import tennacle.games.map.Building;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The buildable space: a flat circle round 0,0 of the map's radius, between its floor and its height, less the
 * bases. Only what a seat placed comes back up; the map's own blocks stay, and the beds are {@code Beds}' own.
 */
final class Rules implements GameModule {

    private Game game;
    private double radius;
    private int floorY;
    private int ceilingY;
    private List<RegionMarker> bases;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Building.RULES;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Respawns respawns = game.module(Respawns.class);
        if (respawns == null) throw new IllegalStateException("bedwars Rules needs Respawns installed before it");
        radius = game.arena().markers(BedWars.RADIUS).getFirst().payload().getDouble("radius");
        floorY = (int) Math.ceil(plane(BedWars.FLOOR));
        ceilingY = (int) Math.floor(plane(BedWars.HEIGHT));
        bases = game.arena().markers(BedWars.BASE);
        Building.install(game, new Building.Space() {
            @Override
            public boolean buildable(@NotNull Player placer, @NotNull Point pos) {
                return inBand(placer, pos) && inside(pos);
            }

            @Override
            public boolean inBand(@NotNull Player placer, @NotNull Point pos) {
                return Rules.this.inBand(placer, pos);
            }

            @Override
            public boolean guarded(@NotNull Point pos) {
                return false;
            }

            @Override
            public boolean belowFloor(@NotNull Player placer, @NotNull Point pos) {
                return pos.blockY() < floor(placer);
            }

            @Override
            public boolean aboveHeight(@NotNull Player placer, @NotNull Point pos) {
                return pos.blockY() > ceiling(placer);
            }

            @Override
            public boolean reserved(@NotNull Point pos) {
                for (RegionMarker base : bases) {
                    if (base.contains(pos)) return true;
                }
                return false;
            }
        }, respawns::unprotect);
    }

    // captured: a block goes in while its corner's distance from 0,0 is under the radius, the same at every height
    private boolean inside(Point pos) {
        return Math.hypot(pos.blockX(), pos.blockZ()) < radius;
    }

    private boolean inBand(Player placer, Point pos) {
        return pos.blockY() >= floor(placer) && pos.blockY() <= ceiling(placer);
    }

    // the marker, or the rule when a seat carries its own
    private int floor(Player placer) {
        int low = game.rules().resolve(placer, Building.FLOOR);
        return low >= 0 ? low : floorY;
    }

    private int ceiling(Player placer) {
        int high = game.rules().resolve(placer, Building.HEIGHT);
        return high >= 0 ? high : ceilingY;
    }

    private double plane(MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }
}
