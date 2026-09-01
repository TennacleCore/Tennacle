package tennacle.games;


import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;


/** The shared duel start: freeze + count voice ({@code Duels.START_GRACE_TICKS} of quiet first). Bridge
 * runs its own cage count instead - this stays the sumo/boxing opener. */
public final class Countdown implements GameModule {


        public static final String GHOST_ALARM = "duel:ghost";

        @Override
        public void install(@NotNull Game game) {
            GameState st = game.state();
            Ghosting.install(game);
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
                } else if (!Duels.abortIfAlone(game)) {
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

            Freeze.pin(game);
        }
    }
