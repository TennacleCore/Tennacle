package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.games.Match;
import tennacle.storage.MatchStore;
import tennacle.storage.StatStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A game's numbers per player, not per seat, written once at a decided end: as the match's own rows, and onto each
 * player's career under the family and under the mode ({@code bedwars:kills}, {@code bedwars-solo:kills}). An abort
 * writes nothing; a draw is a game with neither a win nor a loss, and leaves the win streaks standing. Pairs (a player against another player, or a team) stay with the match only.
 */
public final class Tally implements GameModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(Tally.class);
    private static final String SAMPLE = "tally.sample";
    private static final long SECOND = 20;

    private static volatile StatStore careers;
    private static volatile MatchStore matches;

    private final String family;
    private final String mode;
    private final Map<UUID, Map<String, Long>> sheets = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Map<String, Long>>> pairs = new ConcurrentHashMap<>();
    private final List<Sampler> samplers = new ArrayList<>();
    private boolean closing;
    private final Map<UUID, Map<UUID, Long>> hitBy = new ConcurrentHashMap<>();
    private Game game;

    /** Runs once a second for each seated, online player still in the game while it is live. */
    @FunctionalInterface
    public interface Sampler {
        void sample(@NotNull Game game, @NotNull Player player, @NotNull Slot seat, @NotNull Tally tally);
    }

    private Tally(String family, String mode) {
        this.family = family;
        this.mode = mode;
    }

    public static @NotNull Tally of(@NotNull String family, @NotNull String mode) {
        return new Tally(family, mode);
    }

    /** Where every game's numbers go; unset, a game still tallies and writes nothing. */
    public static void install(@NotNull StatStore stats, @NotNull MatchStore store) {
        careers = stats;
        matches = store;
    }

    /** A module asked at a decided end, just before the sheet is written, for what it still holds open. */
    public interface Closes {
        void close();
    }

    public @NotNull Tally sampler(@NotNull Sampler sampler) {
        samplers.add(sampler);
        return this;
    }

    public static void add(@NotNull Game game, @NotNull Player player, @NotNull String stat) {
        add(game, player.getUuid(), stat, 1);
    }

    public static void add(@NotNull Game game, @NotNull UUID player, @NotNull String stat, long n) {
        Tally tally = game.module(Tally.class);
        if (tally != null) tally.add(player, stat, n);
    }

    public static void pair(@NotNull Game game, @NotNull UUID player, @NotNull String other, @NotNull String stat, long n) {
        Tally tally = game.module(Tally.class);
        if (tally != null) tally.pair(player, other, stat, n);
    }

    public void add(@NotNull UUID player, @NotNull String stat, long n) {
        if (n == 0 || game.decided() != null && !closing) return;
        sheets.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).merge(stat, n, Long::sum);
    }

    public void pair(@NotNull UUID player, @NotNull String other, @NotNull String stat, long n) {
        if (n == 0 || game.decided() != null) return;
        pairs.computeIfAbsent(player, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(other, id -> new ConcurrentHashMap<>()).merge(stat, n, Long::sum);
    }

    /** A death: the victim's, the killer's, and an assist for everyone else who hit them inside the credit window. */
    public static void died(@NotNull Game game, @NotNull UUID victim, @Nullable UUID killer, boolean last, @NotNull Cause cause) {
        Tally tally = game.module(Tally.class);
        if (tally == null) return;
        tally.count(victim, "deaths", last, cause);
        if (killer != null) tally.kill(killer, last, cause);
        tally.assists(victim, killer);
    }

    /** A final death with no death of its own: a seat left empty when its bed fell. */
    public static void finalDeath(@NotNull Game game, @NotNull UUID victim, @NotNull Cause cause) {
        Tally tally = game.module(Tally.class);
        if (tally == null) return;
        tally.add(victim, "final-deaths", 1);
        tally.add(victim, cause.id() + "-final-deaths", 1);
    }

    /** A kill credited with no death to go with it: the bed breaker's, for a final death nobody else is owed. */
    public static void kill(@NotNull Game game, @NotNull UUID killer, boolean last, @NotNull Cause cause) {
        Tally tally = game.module(Tally.class);
        if (tally != null) tally.kill(killer, last, cause);
    }

    /** A hit the events don't show as a player's: a creature's, for its owner. */
    public static void hit(@NotNull Game game, @NotNull UUID victim, @NotNull UUID by) {
        Tally tally = game.module(Tally.class);
        if (tally != null) tally.hitBy.computeIfAbsent(victim, id -> new ConcurrentHashMap<>()).put(by, game.tick());
    }

    private void kill(UUID killer, boolean last, Cause cause) {
        count(killer, "kills", last, cause);
    }

    // deaths, melee-deaths, final-deaths, melee-final-deaths
    private void count(UUID player, String what, boolean last, Cause cause) {
        add(player, what, 1);
        add(player, cause.id() + "-" + what, 1);
        if (!last) return;
        add(player, "final-" + what, 1);
        add(player, cause.id() + "-final-" + what, 1);
    }

    private void assists(UUID victim, @Nullable UUID killer) {
        Map<UUID, Long> hits = hitBy.remove(victim);
        if (hits == null) return;
        long window = game.rules().resolve(Scope.GAME, KillCredit.TICKS);
        hits.forEach((by, at) -> {
            if (!by.equals(killer) && !by.equals(victim) && game.tick() - at <= window) add(by, "assists", 1);
        });
    }

    public long get(@NotNull UUID player, @NotNull String stat) {
        Map<String, Long> sheet = sheets.get(player);
        return sheet == null ? 0 : sheet.getOrDefault(stat, 0L);
    }

    public long pairOf(@NotNull UUID player, @NotNull String other, @NotNull String stat) {
        Map<String, Map<String, Long>> theirs = pairs.get(player);
        Map<String, Long> with = theirs == null ? null : theirs.get(other);
        return with == null ? 0 : with.getOrDefault(stat, 0L);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.state().alarms().register(SAMPLE, (tick, payload) -> {
            game.state().alarms().arm(SAMPLE, tick + SECOND);
            if (Match.live(game) && game.decided() == null) sample();
        });
        game.state().alarms().arm(SAMPLE, game.tick() + SECOND);
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || !(e.target() instanceof Player victim) || !(e.source() instanceof Player by)) return;
            if (by == victim || game.roster().slotOf(by) == null || game.roster().slotOf(victim) == null) return;
            hitBy.computeIfAbsent(victim.getUuid(), id -> new ConcurrentHashMap<>()).put(by.getUuid(), game.tick());
        });
    }

    // eliminated is out of the game; a death's wait is still playing
    private void sample() {
        for (Player player : game.members()) {
            Slot seat = game.roster().slotOf(player);
            if (seat == null || game.state().get(Scope.slot(seat.index()), LastStanding.OUT)) continue;
            add(player.getUuid(), "time-played", 1);
            for (Sampler sampler : samplers) sampler.sample(game, player, seat, this);
        }
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        String winner = outcome.winner();
        boolean draw = "draw".equals(outcome.kind());
        if (winner == null && !draw) return;
        closing = true;
        for (Closes module : game.modules(Closes.class)) module.close();
        for (Sampler sampler : samplers) if (sampler instanceof Closes closes) closes.close();
        closing = false;
        Map<UUID, Boolean> won = new LinkedHashMap<>();
        for (Roster.Participant p : game.roster().participants()) {
            won.merge(p.uuid(), !draw && p.slot().team().equals(winner), Boolean::logicalOr);
        }
        List<MatchStore.Line> lines = new ArrayList<>();
        List<MatchStore.Pair> pairLines = new ArrayList<>();
        won.forEach((player, win) -> {
            Map<String, Long> sheet = new HashMap<>(sheets.getOrDefault(player, Map.of()));
            sheet.put("games", 1L);
            sheet.put(draw ? "draws" : win ? "wins" : "losses", 1L);
            Map<String, Long> deltas = new HashMap<>();
            sheet.forEach((stat, value) -> {
                lines.add(new MatchStore.Line(player, family + ":" + stat, value));
                deltas.put(family + ":" + stat, value);
                deltas.put(mode + ":" + stat, value);
            });
            pairs.getOrDefault(player, Map.of()).forEach((other, stats) -> stats.forEach((stat, value) ->
                    pairLines.add(new MatchStore.Pair(player, other, family + ":" + stat, value))));
            write(player, deltas, draw ? null : win);
        });
        MatchStore store = matches;
        if (store != null) store.stats(game.id(), lines, pairLines).exceptionally(failed -> {
            LOGGER.error("the stats of match {} were not recorded", game.id(), failed);
            return null;
        });
    }

    // a draw leaves the streaks where they were
    private void write(UUID player, Map<String, Long> deltas, @Nullable Boolean win) {
        StatStore stats = careers;
        if (stats == null) return;
        stats.add(player, deltas).exceptionally(failed -> {
            LOGGER.error("career numbers for {} were not added", player, failed);
            return null;
        });
        if (win == null) return;
        for (String scope : family.equals(mode) ? List.of(family) : List.of(family, mode)) streak(stats, player, scope, win);
    }

    private static void streak(StatStore stats, UUID player, String scope, boolean win) {
        stats.update(player, scope + ":winstreak", old -> win ? old + 1 : 0)
                .thenCompose(now -> stats.update(player, scope + ":best-winstreak", best -> Math.max(best, now)))
                .exceptionally(failed -> {
                    LOGGER.error("the {} win streak of {} was not kept", scope, player, failed);
                    return null;
                });
    }
}
