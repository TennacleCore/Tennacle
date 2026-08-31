package tennacle.games.bridge;

import tennacle.games.Duels;

import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.template.Facing;
import io.github.term4.barnacle.template.Template;
import io.github.term4.barnacle.template.TemplateStore;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The round cage: the "cage" template stamps over each team's spawn for the countdown and the blocks it
 * replaced come back at GO. A goal arms {@link #RECAGE_ALARM} to run the whole cycle again - the shared
 * countdown freeze re-engages off the phase, so no cage template just means an open-air freeze.
 */
public final class Cages implements GameModule {

    static final String RECAGE_ALARM = "bridge:recage";
    private static final String OPEN_ALARM = "bridge:cage-open";
    // the store /arena template writes - capture "cage" standing where the player will stand
    private static final TemplateStore TEMPLATES = new TemplateStore(Path.of("templates"));

    private final Map<Point, Block> replaced = new HashMap<>();

    @Override
    public void install(@NotNull Game game) {
        var alarms = game.state().alarms();
        alarms.register(OPEN_ALARM, (tick, payload) -> open(game));
        alarms.register(RECAGE_ALARM, (tick, payload) -> recage(game));
        stamp(game);
        alarms.arm(OPEN_ALARM, game.state().tick() + Duels.COUNT_TICKS);
    }

    private void recage(Game game) {
        game.state().set(Scope.GAME, Duels.PHASE, "countdown");
        stamp(game);
        long tick = game.state().tick();
        game.state().alarms().arm(Duels.COUNT_ALARM, tick + 20,
                CompoundBinaryTag.builder().putInt("n", Duels.COUNT_FROM).build());
        game.state().alarms().arm(OPEN_ALARM, tick + Duels.COUNT_TICKS);
    }

    // all writes ride the game's shard - the arena container is shared by concurrent games
    private void stamp(Game game) {
        Template cage = TEMPLATES.template("cage");
        if (cage == null) return;
        for (PosMarker spawn : game.arena().markers(Bridge.CAGE)) {
            var at = spawn.pos();
            BlockVec anchor = new BlockVec(at.blockX(), at.blockY(), at.blockZ());
            cage.at(anchor, Facing.fromYaw(at.yaw())).forEach((pos, block) -> {
                if (!game.instance().isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) {
                    game.instance().loadChunk(pos.blockX() >> 4, pos.blockZ() >> 4).join();
                }
                replaced.putIfAbsent(pos, game.shard().getBlock(pos.blockX(), pos.blockY(), pos.blockZ()));
                game.shard().setBlock(pos, block);
            });
        }
    }

    private void open(Game game) {
        replaced.forEach((pos, block) -> game.shard().setBlock(pos, block));
        replaced.clear();
    }
}
