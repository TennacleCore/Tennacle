package tennacle.games;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.edit.Teams;
import io.github.term4.canvas.theme.Role;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * A map's team arms under the network's palette: at launch every dyed cell inside a {@code section} region is
 * rewritten to its team's {@code section} value. The write lands in the game's SHARD, so the arena on disk is
 * untouched and two matches on one map can wear different looks. A cell in no dyed family - the stone under a
 * bridge, the glowstone in a wall - is left alone, so a section built out of anything stays what it was built
 * out of, and a seasonal palette is {@link io.github.term4.canvas.theme.Livery#ordinals} at boot.
 */
public final class Sections implements GameModule {

    private static final MarkerKey<RegionMarker> SECTION = MarkerKey.region("section");
    private static final String STAMP_ALARM = "sections:stamp";

    @Override
    public void install(@NotNull Game game) {
        game.state().alarms().register(STAMP_ALARM, (tick, payload) -> stamp(game));
        game.state().alarms().arm(STAMP_ALARM, game.state().tick() + 1);
    }

    private static void stamp(Game game) {
        for (RegionMarker section : game.arena().markers(SECTION)) {
            Role.Fill fill = game.roster().team(section.group()).livery().get(Teams.SECTION);
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
}
