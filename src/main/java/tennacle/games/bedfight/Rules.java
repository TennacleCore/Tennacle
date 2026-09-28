package tennacle.games.bedfight;

import tennacle.games.beds.Beds;
import tennacle.games.beds.Lives;
import tennacle.games.map.Building;

import io.github.term4.barnacle.marker.LimitMarker;
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
 * The buildable space and what may be broken. Where a seat may build is the {@code bedfight:build} region: a
 * flat box is an X/Z slice between the floor and the height, a taller one its own volume. What it may break is
 * the same vocabulary bridge uses, over the {@code bedfight:defense} regions: a seat takes its own blocks back
 * up anywhere, the map's own only where a bed is defended. The beds are {@link Beds}' own, the deaths
 * {@link Lives}'.
 */
public final class Rules implements GameModule {

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

    private Game game;
    private RegionMarker build;
    private List<RegionMarker> defenses;
    private int floorY;
    private int ceilingY;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Building.RULES;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Respawns respawns = game.module(Respawns.class);
        if (respawns == null) throw new IllegalStateException("bedfight Rules needs Respawns installed before it");
        build = game.arena().markers(BedFight.BUILD).getFirst();
        defenses = game.arena().markers(BedFight.DEFENSE);
        floorY = (int) Math.ceil(plane(BedFight.FLOOR));
        ceilingY = (int) Math.floor(plane(BedFight.HEIGHT));
        Building.install(game, Building.space(this::buildable, this::inBand, this::defended), respawns::unprotect);
    }

    private double plane(io.github.term4.barnacle.marker.MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    private boolean defended(Point pos) {
        for (RegionMarker defense : defenses) {
            if (defense.contains(pos)) return true;
        }
        return false;
    }

    // a flat (1-tall) box is an X/Z slice under the band, which is what the editor's wand draws over a deck; a
    // taller box is its own 3D volume, for maps whose buildable ceiling varies
    private boolean buildable(Player placer, Point pos) {
        for (RegionMarker.Cuboid box : build.boxes()) {
            if (pos.blockX() < box.min().blockX() || pos.blockX() > box.max().blockX()
                    || pos.blockZ() < box.min().blockZ() || pos.blockZ() > box.max().blockZ()) continue;
            if (box.min().blockY() == box.max().blockY() ? inBand(placer, pos)
                    : pos.blockY() >= box.min().blockY() && pos.blockY() <= box.max().blockY()) return true;
        }
        return false;
    }

    // the marker, or the rule when a seat carries its own
    private boolean inBand(Player placer, Point pos) {
        int low = game.rules().resolve(placer, FLOOR);
        int high = game.rules().resolve(placer, HEIGHT);
        return pos.blockY() >= (low >= 0 ? low : floorY) && pos.blockY() <= (high >= 0 ? high : ceilingY);
    }
}
