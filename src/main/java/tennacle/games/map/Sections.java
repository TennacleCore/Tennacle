package tennacle.games.map;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.edit.Teams;
import io.github.term4.canvas.theme.Role;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.instance.block.Block;
import io.github.term4.grotto.roster.Team;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A map's team arms under the network's palette: at launch every dyed cell inside a {@code section} region is
 * rewritten to its team's {@code section} value. The write lands in the game's SHARD, so the arena on disk is
 * untouched and two matches on one map can wear different looks. A cell in no dyed family - the stone under a
 * bridge, the glowstone in a wall - is left alone, so a section built out of anything stays what it was built
 * out of, and a seasonal palette is {@link io.github.term4.canvas.theme.Livery#ordinals} at boot.
 *
 * <p>{@code tint} regions stamp the same way and mean nothing else: no mode counts them, so a map may carry any
 * number of any shape. A {@code section} is a side's TERRITORY - bridge reads its floor and its footprint - and
 * a map wanting decoration alone wants these. Their group is {@code <team>} for the side's own
 * {@code section} fill, or {@code <team>:<role>} for any other block role its look paints - so one map can
 * carry as many differently-tinted areas as its palette names. A role no look names still paints - {@code base}
 * is every role's last fallback - so an area always wears its side rather than nothing.
 */
public final class Sections implements GameModule {

    private static final MarkerKey<RegionMarker> SECTION = MarkerKey.region("section");
    /** Decoration only - never geometry, and never counted. */
    private static final MarkerKey<RegionMarker> TINT = MarkerKey.region("tint");
    private static final String STAMP_ALARM = "sections:stamp";

    @Override
    public void install(@NotNull Game game) {
        game.state().alarms().register(STAMP_ALARM, (tick, payload) -> stamp(game));
        game.state().alarms().arm(STAMP_ALARM, game.state().tick() + 1);
    }

    /** The block role of that name, registering it where no code declared one; null where a look calls it a color. */
    @SuppressWarnings("unchecked")
    private static @Nullable Role<Role.Fill> fillRole(String name) {
        for (Role<?> known : Role.all()) {
            if (known.name().equals(name)) {
                return "block".equals(known.kind()) ? (Role<Role.Fill>) known : null;
            }
        }
        return Role.block(name);
    }

    private static void stamp(Game game) {
        for (MarkerKey<RegionMarker> key : List.of(SECTION, TINT)) {
            for (RegionMarker section : game.arena().markers(key)) paint(game, section);
        }
    }

    private static void paint(Game game, RegionMarker section) {
        String group = section.group();
        int cut = group.indexOf(':');
        Role<Role.Fill> role = cut < 0 ? Teams.SECTION : fillRole(group.substring(cut + 1));
        if (role == null) return; // the look calls that name a color, not a fill: nothing to paint with
        Team side = game.roster().team(cut < 0 ? group : group.substring(0, cut));
        if (side == null) return;
        Role.Fill fill = side.livery().get(role);
        for (RegionMarker.Cuboid box : section.boxes()) {
            Chunks.load(game.instance(), box.min().blockX() >> 4, box.max().blockX() >> 4,
                    box.min().blockZ() >> 4, box.max().blockZ() >> 4);
            for (int x = box.min().blockX(); x <= box.max().blockX(); x++) {
                for (int z = box.min().blockZ(); z <= box.max().blockZ(); z++) {
                    for (int y = box.min().blockY(); y <= box.max().blockY(); y++) {
                        Block authored = game.shard().getBlock(x, y, z);
                        // the fill's own family is only there to make the role resolve: the cell keeps its
                        if (Tint.family(authored.key().value()) == null) continue;
                        Block themed = fill.over(authored);
                        if (themed != null && !themed.equals(authored)) {
                            game.shard().setBlock(new BlockVec(x, y, z), themed);
                        }
                    }
                }
            }
        }
    }
}
