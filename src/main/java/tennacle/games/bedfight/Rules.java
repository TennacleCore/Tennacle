package tennacle.games.bedfight;

import tennacle.games.beds.Beds;
import tennacle.games.beds.Lives;
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
 * The buildable space and what may be broken. Where a seat may build is the {@code bedfight:build} region: a
 * flat box is an X/Z slice between the floor and the height, a taller one its own volume. What it may break is
 * the same vocabulary bridge uses, over the {@code bedfight:defense} regions: a seat takes its own blocks back
 * up anywhere, the map's own only where a bed is defended. The beds are {@link Beds}' own, the deaths
 * {@link Lives}'.
 */
public final class Rules implements GameModule, Building.Owner {

    private @Nullable Building building;

    // the build and break rules are Building's: bridge declares the same six ids, and two owners of one id is
    // a rule that resolves differently depending on which class the reader found first
    public static final StateKey<Boolean> BUILD = Building.BUILD;
    public static final StateKey<Integer> HEIGHT = Building.HEIGHT;
    public static final StateKey<Integer> FLOOR = Building.FLOOR;
    public static final StateKey<Boolean> OUTSIDE = Building.OUTSIDE;
    public static final String PLACED = Building.PLACED;
    public static final String MAP = Building.MAP;
    /** What may be broken inside a {@code bedfight:defense} region; a kind nobody names stays standing. */
    public static final StateKey<List<String>> BREAK = Building.BREAK;
    /** What may be broken outside one - the rest of the map. */
    public static final StateKey<List<String>> BREAK_OUTSIDE = Building.BREAK_OUTSIDE;

    private List<RegionMarker> defenses;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Building.RULES;
    }

    @Override
    public void install(@NotNull Game game) {
        Respawns respawns = game.module(Respawns.class);
        if (respawns == null) throw new IllegalStateException("bedfight Rules needs Respawns installed before it");
        RegionMarker build = game.arena().markers(BedFight.BUILD).getFirst();
        defenses = game.arena().markers(BedFight.DEFENSE);
        Building.Band band = Building.Band.of(game, BedFight.FLOOR, BedFight.HEIGHT);
        building = Building.install(game, Building.space(band, (placer, pos) -> band.covers(build, placer, pos), this::defended), respawns::unprotect);
    }

    private boolean defended(Point pos) {
        for (RegionMarker defense : defenses) {
            if (defense.contains(pos)) return true;
        }
        return false;
    }

    @Override
    public @Nullable Building building() {
        return building;
    }
}
