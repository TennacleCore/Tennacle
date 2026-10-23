package tennacle.games;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * Named events at offsets into the live phase. Each owned event is a rule ({@code timeline.<id>}, ticks), armed
 * as the game goes live and re-armed when the rule changes, with a hook per event; a watched one belongs to
 * another module and is here for the board alone. {@link #next} is the one the board reads: the first still
 * to come. An offset below zero drops the event.
 */
public final class Timeline implements GameModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(Timeline.class);

    /** The next event: what the board calls it, and ticks until it. */
    public record Next(@NotNull String id, @NotNull String label, long left) {}

    private record Event(String id, @Nullable String label, @Nullable StateKey<Long> at, @Nullable ToLongFunction<Game> left) {}

    private static final String ALARM = "timeline:";

    private final List<Event> events = new ArrayList<>();
    private final Map<String, List<Consumer<Game>>> hooks = new HashMap<>();
    private final Set<String> fired = new HashSet<>();
    private Game game;

    private Timeline() {}

    public static @NotNull Timeline of() {
        return new Timeline();
    }

    /** An event of this timeline's own, {@code ticks} into the live phase by default; no label keeps it off the board. */
    public @NotNull Timeline event(@NotNull String id, @Nullable String label, long ticks) {
        events.add(new Event(id, label, StateKey.of("timeline." + id, Codec.LONG, ticks), null));
        return this;
    }

    /** An event another module fires: {@code left} reads its ticks to come, below zero once it has. */
    public @NotNull Timeline watched(@NotNull String id, @NotNull String label, @NotNull ToLongFunction<Game> left) {
        events.add(new Event(id, label, null, left));
        return this;
    }

    public @NotNull Timeline on(@NotNull String id, @NotNull Consumer<Game> hook) {
        hooks.computeIfAbsent(id, k -> new ArrayList<>()).add(hook);
        return this;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        Set<StateKey<?>> out = new LinkedHashSet<>();
        for (Event event : events) if (event.at() != null) out.add(event.at());
        return out;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Phases phases = game.module(Phases.class);
        if (phases == null) throw new IllegalStateException("Timeline needs Phases installed first");
        for (Event event : events) {
            if (event.at() == null) continue;
            game.state().alarms().register(ALARM + event.id(), (tick, payload) -> fire(event));
            game.rules().watch(event.at(), (scope, value) -> {
                if (Phases.enteredAt(game, Match.LIVE) >= 0) arm(event);
            });
        }
        if (Phases.is(game, Match.LIVE)) start();
        else phases.onEnter(Match.LIVE, g -> start());
    }

    /** Whether {@code id} has happened in this game. */
    public boolean fired(@NotNull String id) {
        return fired.contains(id);
    }

    /** {@code id}'s label on the board, or null. */
    public @Nullable String label(@NotNull String id) {
        for (Event event : events) if (event.id().equals(id)) return event.label();
        return null;
    }

    /** The first event still to come, or null once none is. */
    public @Nullable Next next() {
        long start = Phases.enteredAt(game, Match.LIVE);
        Next best = null;
        for (Event event : events) {
            if (event.label() == null) continue;
            long left;
            if (event.at() != null) {
                if (fired.contains(event.id()) || start < 0) continue;
                long offset = game.rules().resolve(Scope.GAME, event.at());
                if (offset < 0) continue;
                left = start + offset - game.tick();
            } else {
                left = event.left().applyAsLong(game);
                if (left < 0) continue;
            }
            if (best == null || left < best.left()) best = new Next(event.id(), event.label(), Math.max(0, left));
        }
        return best;
    }

    /** Ticks of the live phase gone by. */
    public static long elapsed(@NotNull Game game) {
        long start = Phases.enteredAt(game, Match.LIVE);
        return start < 0 ? 0 : game.tick() - start;
    }

    private void start() {
        for (Event event : events) if (event.at() != null) arm(event);
    }

    // a rule lowered under the clock fires on the next tick, never twice
    private void arm(Event event) {
        String id = ALARM + event.id();
        game.state().alarms().cancel(id);
        if (fired.contains(event.id())) return;
        long offset = game.rules().resolve(Scope.GAME, event.at());
        if (offset < 0) return;
        game.state().alarms().arm(id, Math.max(game.tick() + 1, Phases.enteredAt(game, Match.LIVE) + offset));
    }

    private void fire(Event event) {
        if (game.decided() != null || !fired.add(event.id())) return;
        for (Consumer<Game> hook : hooks.getOrDefault(event.id(), List.of())) {
            try {
                hook.accept(game);
            } catch (RuntimeException e) {
                LOGGER.warn("a hook on timeline event {} threw", event.id(), e);
            }
        }
    }
}
