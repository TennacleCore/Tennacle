package tennacle.games.bedwars;

import tennacle.games.map.OffMap;
import tennacle.games.map.Building;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The buildable space: a flat circle round 0,0 of the map's radius, between its floor and its height, less the
 * bases. Only what a seat placed comes back up; the map's own blocks stay, and the beds are {@code Beds}' own.
 */
final class Rules implements GameModule, Building.Owner, OffMap.Limit {

    private @Nullable Building building;

    private double radius;
    private Building.Band band;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Building.RULES;
    }

    @Override
    public void install(@NotNull Game game) {
        Respawns respawns = game.module(Respawns.class);
        if (respawns == null) throw new IllegalStateException("bedwars Rules needs Respawns installed before it");
        radius = game.arena().markers(BedWars.RADIUS).getFirst().payload().getDouble("radius");
        band = Building.Band.of(game, BedWars.FLOOR, BedWars.HEIGHT);
        List<RegionMarker> bases = game.arena().markers(BedWars.BASE);
        building = Building.install(game, Building.space(band, (placer, pos) -> band.contains(placer, pos) && inside(pos),
                pos -> false, pos -> inABase(bases, pos)), respawns::unprotect);
    }

    // captured: a block goes in while its corner's distance from 0,0 is under the radius, the same at every height
    private boolean inside(Point pos) {
        return Math.hypot(pos.blockX(), pos.blockZ()) < radius;
    }

    @Override
    public boolean beyond(@NotNull Point pos, double margin) {
        return Math.hypot(pos.x(), pos.z()) > radius + margin
                || pos.y() > band.ceiling() + margin || pos.y() < band.floor() - margin;
    }

    private static boolean inABase(List<RegionMarker> bases, Point pos) {
        for (RegionMarker base : bases) {
            if (base.contains(pos)) return true;
        }
        return false;
    }

    @Override
    public @Nullable Building building() {
        return building;
    }
}
