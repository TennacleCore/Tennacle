package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Duels;

import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.template.Facing;
import io.github.term4.barnacle.template.Template;
import io.github.term4.barnacle.template.TemplateStore;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The bridge round: the "cage" template stamps over each team's cage spot, the countdown runs as a
 * SUBTITLE under whatever title is held (a goal's "scored!" line rides through the re-cage), and GO
 * restores the cage blocks. Owns the freeze too - bridge doesn't run the shared duel countdown voice.
 */
public final class Cages implements GameModule {

    static final String RECAGE_ALARM = "bridge:recage";
    private static final String COUNT_ALARM = "bridge:cage-count";
    private static final int COUNT_FROM = 5;
    // the store /arena template writes - capture "cage" standing where the player will stand
    private static final TemplateStore TEMPLATES = new TemplateStore(Path.of("templates"));

    // ported termbridge cues: sticks each beat, pling at GO, wither breath as the cage lands
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 0.8f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);
    private static final Sound STAMP = Sound.sound(SoundEvent.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 0.5f, 1.2f);

    private final Map<Point, Block> replaced = new HashMap<>();
    private Component held;

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        st.alarms().register("duel:ghost", (tick, payload) -> { // the shared settle path arms it by name
            UUID uuid = UUID.fromString(payload.getString("uuid"));
            for (Player p : game.members()) {
                if (p.getUuid().equals(uuid)) {
                    game.ghost(p);
                    return;
                }
            }
        });
        st.alarms().register(COUNT_ALARM, (tick, payload) -> beat(game, payload.getInt("n")));
        st.alarms().register(RECAGE_ALARM, (tick, payload) -> recage(game, payload));

        // cancelling would resync with ABSOLUTE view (the client's look rewinds mid-flick); instead let
        // the packet land, then pin back to the seat with yaw/pitch as zero RELATIVE deltas
        Set<UUID> pinning = new HashSet<>();
        game.on(PlayerMoveEvent.class, e -> {
            if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
            Player p = e.getPlayer();
            Pos from = p.getPosition();
            Pos to = e.getNewPosition();
            if (from.x() == to.x() && from.y() == to.y() && from.z() == to.z()) return;
            Slot seat = game.roster().slotOf(p);
            Pos anchor = seat != null && seat.spawn() != null ? seat.spawn() : from;
            if (!pinning.add(p.getUuid())) return;
            MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                pinning.remove(p.getUuid());
                if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
                p.teleport(anchor.withView(0, 0), null, RelativeFlags.VIEW);
            });
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            String phase = st.get(Scope.GAME, Duels.PHASE);
            if ("fighting".equals(phase)) return;
            // the settling tick's KB is the final hit - it lands; anything later in "done" is swallowed
            if ("done".equals(phase) && st.tick() == st.get(Scope.GAME, Duels.DONE_AT)) return;
            e.setCancelled(true);
        });

        stamp(game);
        st.alarms().arm(COUNT_ALARM, st.tick() + 20, CompoundBinaryTag.builder().putInt("n", COUNT_FROM).build());
    }

    private void beat(Game game, int n) {
        GameState st = game.state();
        if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
        if (n > 0) {
            Title beat = Title.title(held != null ? held : Component.empty(),
                    Banners.cageCount(game, n), Duels.times(game, 0, 25, 0));
            Duels.each(game, p -> {
                p.showTitle(beat);
                p.playSound(TICK);
            });
            st.alarms().arm(COUNT_ALARM, st.tick() + 20, CompoundBinaryTag.builder().putInt("n", n - 1).build());
            return;
        }
        if (game.shard().members().size() < 2) { // a duelist never arrived (logged out mid-transfer)
            st.set(Scope.GAME, Duels.PHASE, "done");
            Duels.each(game, p -> Msg.tag(p, "duel", "duel cancelled - your opponent never showed"));
            game.decide(Outcome.draw(), 1);
            return;
        }
        open(game);
        held = null;
        st.set(Scope.GAME, Duels.PHASE, "fighting");
        Title fight = Title.title(Component.empty(), Banners.fight(game), Duels.times(game, 0, 20, 10));
        Duels.each(game, p -> {
            p.showTitle(fight);
            p.playSound(GO);
        });
    }

    private void recage(Game game, CompoundBinaryTag payload) {
        String scorer = payload.getString("scorer");
        held = scorer.isEmpty() ? null
                : Banners.scoredHeld(game, scorer, game.roster().team(payload.getString("team")).color());
        game.state().set(Scope.GAME, Duels.PHASE, "countdown");
        stamp(game);
        game.state().alarms().arm(Rules.REFIT_ALARM, game.state().tick() + 1);
        game.state().alarms().arm(COUNT_ALARM, game.state().tick() + 20,
                CompoundBinaryTag.builder().putInt("n", COUNT_FROM).build());
    }

    // all writes ride the game's shard - the arena container is shared by concurrent games
    private void stamp(Game game) {
        Template cage = TEMPLATES.template("cage");
        if (cage == null) return; // no cage authored yet - the freeze still holds everyone
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
            Duels.each(game, p -> p.playSound(STAMP, at.x(), at.y(), at.z()));
        }
    }

    private void open(Game game) {
        replaced.forEach((pos, block) -> game.shard().setBlock(pos, block));
        replaced.clear();
    }
}
