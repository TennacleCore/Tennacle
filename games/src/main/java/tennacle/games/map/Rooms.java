package tennacle.games.map;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The room a game fills up in, authored once in the rooms arena ({@code room} regions, the group naming the
 * variant) and laid at the mode's room marker before the first seat opens. A map ships the marker alone;
 * {@link io.github.term4.grotto.modules.flow.Staging#clear} takes the room down at GO.
 */
public final class Rooms implements GameModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(Rooms.class);
    private static final MarkerKey<RegionMarker> ROOM = MarkerKey.region("room");

    private record Read(Arena source, Map<String, List<Prefab.Cell>> rooms) {}

    private static final Map<Maps, Read> read = new HashMap<>();
    private static boolean missingLogged;

    private final Maps source;
    private final String variant;
    private final MarkerKey<RegionMarker> at;

    private Rooms(Maps source, String variant, MarkerKey<RegionMarker> at) {
        this.source = source;
        this.variant = variant;
        this.at = at;
    }

    public static @NotNull Rooms of(@NotNull Maps source, @NotNull String variant, @NotNull MarkerKey<RegionMarker> at) {
        return new Rooms(source, variant, at);
    }

    /** Reads the rooms off the boot thread, so the first game does not instantiate and unload an arena on its clock. */
    public static void warm(@NotNull Maps source) {
        rooms(source);
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @Override
    public void install(@NotNull Game game) {
        List<Prefab.Cell> cells = rooms(source).get(variant);
        if (cells == null) {
            LOGGER.warn("no room {} in the rooms arena - the game waits in the open", variant);
            return;
        }
        for (RegionMarker room : game.arena().markers(at)) Prefab.stamp(game, room.boxes().getFirst().min(), cells);
    }

    /** The authored rooms by variant; re-read when the arena behind {@code source} changes. */
    private static synchronized Map<String, List<Prefab.Cell>> rooms(Maps source) {
        Arena arena;
        try {
            arena = source.get();
        } catch (IllegalStateException missing) {
            if (!missingLogged) {
                missingLogged = true;
                LOGGER.warn("no rooms arena - every game waits in the open until one is saved");
            }
            return Map.of();
        }
        missingLogged = false;
        Read cached = read.get(source);
        if (cached != null && cached.source() == arena) return cached.rooms();
        Map<String, List<Prefab.Cell>> rooms = new HashMap<>();
        Instance instance = arena.instantiate();
        for (RegionMarker box : arena.markers(ROOM)) {
            List<Prefab.Cell> cells = List.copyOf(Prefab.read(instance, box, box.boxes().getFirst().min()));
            rooms.put(box.group(), cells);
            LOGGER.info("room {}: {} blocks", box.group(), cells.size());
        }
        arena.unload();
        if (rooms.isEmpty()) LOGGER.warn("the rooms arena has no room region");
        read.put(source, new Read(arena, Map.copyOf(rooms)));
        return rooms;
    }
}
