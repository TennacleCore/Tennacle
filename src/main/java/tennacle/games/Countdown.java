package tennacle.games;

import tennacle.Msg;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The shared duel start: freeze + count voice ({@code Duels.START_GRACE_TICKS} of quiet first). Bridge
 * runs its own cage count instead - this stays the sumo/boxing opener. */
public final class Countdown implements GameModule {


        static final String GHOST_ALARM = "duel:ghost";

        @Override
        public void install(@NotNull Game game) {
            GameState st = game.state();
            st.alarms().register(GHOST_ALARM, (tick, payload) -> {
                UUID uuid = UUID.fromString(payload.getString("uuid"));
                for (Player p : game.members()) {
                    if (p.getUuid().equals(uuid)) {
                        game.ghost(p);
                        return;
                    }
                }
            });
            st.alarms().register(Duels.COUNT_ALARM, (tick, payload) -> {
                if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return; // settled early (a quit)
                // the clock starts with the first beat - only the grace sits outside match time
                if (st.get(Scope.GAME, Duels.STARTED_AT) < 0) st.set(Scope.GAME, Duels.STARTED_AT, tick);
                int n = payload.getInt("n");
                if (n > 0) {
                    Duels.Look look = Duels.lookOf(game);
                    Component line = look.count().number().apply(n);
                    // captured beat title: fadeIn 0, stay 20, fadeOut 20
                    Title title = look.count().title() != null ? Title.title(look.count().title().apply(n),
                            Component.empty(),
                            Duels.times(game, 0, 20, 20)) : null;
                    Duels.each(game, p -> {
                        p.sendMessage(line);
                        if (title != null) p.showTitle(title);
                        look.sounds().play(p, SoundSet.COUNTDOWN);
                    });
                    st.alarms().arm(Duels.COUNT_ALARM, tick + 20, CompoundBinaryTag.builder().putInt("n", n - 1).build());
                } else if (game.shard().members().size() < 2) { // a duelist never arrived (logged out mid-transfer)
                    st.set(Scope.GAME, Duels.PHASE, "done");
                    Duels.each(game, p -> Msg.tag(p, "duel", "duel cancelled - your opponent never showed"));
                    game.decide(Outcome.draw(), 1);
                } else {
                    st.set(Scope.GAME, Duels.PHASE, "fighting");
                    Duels.Look look = Duels.lookOf(game);
                    // captured: the beat title fades out empty (10, 0, 10)
                    Title clear = look.count().title() != null ? Title.title(Component.empty(), Component.empty(),
                            Duels.times(game, 10, 0, 10)) : null;
                    Duels.each(game, p -> {
                        Component started = look.count().started().apply(game, p);
                        if (started != null) p.sendMessage(started);
                        if (clear != null) p.showTitle(clear);
                        look.sounds().play(p, SoundSet.START);
                    });
                }
            });
            st.alarms().arm(Duels.COUNT_ALARM, st.tick() + Duels.START_GRACE_TICKS,
                    CompoundBinaryTag.builder().putInt("n", Duels.COUNT_FROM).build());

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
        }
    }
