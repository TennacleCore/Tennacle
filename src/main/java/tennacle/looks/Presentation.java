package tennacle.looks;

import tennacle.games.watch.Replays;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.term4.grotto.modules.combat.Respawns;
import tennacle.Msg;

import io.github.term4.canvas.tab.TabList;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.blocking.BlockingDamageEvent;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import java.util.Map;
import net.minestom.server.network.packet.server.play.EntityStatusPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What the look SAYS about what the engine decided: death lines and ghosting off {@link LastStanding}, verdict
 * titles and cues off the decision, the countdown's beats through {@link #voice}. Deciding is Grotto's;
 * this module never calls {@code decide}.
 */
public final class Presentation implements GameModule {

    private static final String GHOST_ALARM = "match:ghost";
    private static final String DEAD_ALARM = "match:dead-count";
    // the per-network death screen is UNCAPTURED: one plain screen for every look until the captures land
    private static final Component DIED = Component.text("You died!", NamedTextColor.RED);
    // captured hypixel: the line's own period, a space, then the note in bold aqua
    private static final Component NOTE_GAP = Component.text(" ");

    private final Set<UUID> fallen = new HashSet<>();
    private final Set<UUID> counting = new HashSet<>();

    @Override
    public void install(@NotNull Game game) {
        game.state().alarms().register(GHOST_ALARM, (tick, payload) -> {
            UUID uuid = UUID.fromString(payload.getString("uuid"));
            Player p = game.firstMember(m -> m.getUuid().equals(uuid));
            if (p != null) game.ghost(p);
        });
        LastStanding standing = game.module(LastStanding.class);
        if (standing != null) standing.onOut((g, seat, player, cause) -> out(g, seat, player, cause));
        Respawns respawns = game.module(Respawns.class);
        if (respawns != null) {
            game.state().alarms().register(DEAD_ALARM, (tick, payload) -> {
                Player p = game.playerNamed(payload.getString("name"));
                if (p != null && respawns.isDead(p)) deadScreen(game, respawns, p);
            });
            respawns.onDead((p, seat) -> deadScreen(game, respawns, p));
            respawns.onRespawn((p, seat) -> {
                if (counting.remove(p.getUuid())) wipe(game, p); // the count goes before the world comes back
            });
        }
        game.on(BlockingDamageEvent.class, BlockingDamageEvent::defender, e -> {
            if (!e.isCancelled()) GameSounds.play(game, e.defender(), SoundSet.BLOCKED); // cancelled = no block happened
        });
        Mode mode = Matches.modeOf(game);
        if (mode != null) { // the tab line reads a seat's own counters: a change re-sends it
            for (Mode.SeatStat stat : mode.tabStats()) {
                game.rules().watch(stat.key(), (scope, value) -> game.forEachMember(TabList::refresh));
            }
        }
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(GameSounds.CUES, Frames.CLOCK, tennacle.games.watch.Replays.RECORD);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        TabList.refresh(player); // the seat line leaves with the seat
        // a freed seat is a forfeit, and the elimination says so; a held one is only a walk-out
        if (departure.seat() != null && departure.reserved()) {
            Match.say(game, Look.departure(game, departure.seat(), player.getUsername(), false));
        }
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        game.forEachMember(TabList::refresh);
    }

    // "You died!" and the seconds left, refreshed every second until the respawn lands
    private void deadScreen(Game game, Respawns respawns, Player p) {
        long left = respawns.remaining(p);
        if (left <= 0) return; // the respawn has this tick: a "0..." would flash under it
        int seconds = (int) Math.ceil(left / 20.0);
        counting.add(p.getUuid());
        wipe(game, p);
        p.showTitle(Title.title(DIED, Component.text("Respawning in " + seconds + "...", NamedTextColor.YELLOW),
                Match.times(game, 0, 30, 0)));
        game.state().alarms().arm(DEAD_ALARM, game.state().tick() + Math.min(20, left),
                CompoundBinaryTag.builder().putString("name", p.getUsername()).build());
    }

    // hypixel's wipe: an empty title on a 1-tick stay, which drops what is up with no fade and no CLEAR packet
    private static void wipe(Game game, Player p) {
        p.showTitle(Title.title(Component.empty(), Component.empty(), Match.times(game, 0, 1, 0)));
    }

    private void out(Game game, Slot seat, @Nullable Player player, LastStanding.Cause cause) {
        Look look = Matches.lookOf(game);
        String name = player != null ? player.getUsername() : lastOwner(game, seat);
        if (name == null) return;
        Component line = look.death().render(game, seat, name, cause.by(), cause.verb());
        if (cause.note() != null) line = line.append(NOTE_GAP).append(Component.text(cause.note(), NamedTextColor.AQUA)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        Match.say(game, line);
        if (player != null) {
            Deaths.cue(game, player, cause.by());
            fall(game, player);
        }
    }

    /** The loser goes down: the death pose, then the ghosting. Once per player - a decision follows an elimination. */
    private void fall(Game game, Player player) {
        Mode mode = Matches.modeOf(game);
        if ((mode != null && !mode.ghostLoser()) || !fallen.add(player.getUuid())) return;
        // everyone watching (spectators included) sees a vanilla death: pose now, and the ghosting's destroy
        // packet lands at +20 ticks - right when a real corpse would poof. The loser gets no status 3, only the flight.
        EntityMetaDataPacket downed = downed(player);
        for (Player watcher : game.shard().watchers()) {
            if (watcher == player) continue;
            watcher.sendPacket(new EntityStatusPacket(player.getEntityId(), (byte) 3));
            watcher.sendPacket(downed);
        }
        player.setFlying(true); // no mayfly: hovering, can't toggle - and a ring-out ghost stops mid-fall
        game.state().alarms().arm(GHOST_ALARM, game.state().tick() + 20,
                CompoundBinaryTag.builder().putString("uuid", player.getUuid().toString()).build());
    }

    // a client only advances the death animation while the body reads dead, and a match that cancels the fatal
    // hit leaves it standing at whatever health it had: the pose would freeze halfway and the corpse stay upright
    private static EntityMetaDataPacket downed(Player player) {
        MetadataDef.Entry.Index<Float> health = (MetadataDef.Entry.Index<Float>) MetadataDef.LivingEntity.HEALTH;
        return new EntityMetaDataPacket(player.getEntityId(),
                Map.of(health.index(), health.function().apply(0f)));
    }

    // the seat's owner is gone by the time a forfeit fires; the of-record lineup still names them
    private static @Nullable String lastOwner(Game game, Slot seat) {
        String name = null;
        for (Roster.Participant p : game.roster().participants()) {
            if (p.slot().equals(seat)) name = p.username();
        }
        return name;
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        TabList.refresh(player);
        if (reclaimed) Match.say(game, Look.departure(game, slot, player.getUsername(), true));
    }

    @Override
    public void onSpectate(@NotNull Game game, @NotNull Player player) {
        TabList.refresh(player);
    }

    @Override
    public void onDecided(@NotNull Game game, @NotNull Outcome outcome) {
        Look look = Matches.lookOf(game);
        if (outcome.equals(Outcome.aborted())) {
            Match.say(game, Msg.THEME.prose("The duel was cancelled: your opponent never showed."));
            return;
        }
        if (outcome.winner() == null) {
            Match.say(game, look.theme().prose(TimeLimit.expired(game) ? "Time's up - draw." : "Draw."));
            return;
        }
        Player winner = game.firstMember(m -> outcome.winner().equals(Match.teamOf(game, m)));
        if (winner != null) {
            Match.each(game, p -> p.showTitle(game.roster().slotOf(p) == null ? Look.over(game)
                    : look.verdict().title(game, outcome.winner().equals(Match.teamOf(game, p)), winner)));
            Replays.shown(game, look.verdict().title(game, true, winner)); // the replay keeps the winner's
        }
        // a score or time ending eliminates nobody, so the losing side falls here instead of at LastStanding
        for (Player p : game.members()) {
            if (game.roster().slotOf(p) != null && !outcome.winner().equals(Match.teamOf(game, p))) fall(game, p);
        }
    }

    /** The look's count as the opener's {@link Countdown.Voice}. */
    public static @NotNull Countdown.Voice voice(@NotNull Game game) {
        Look look = Matches.lookOf(game);
        return new Countdown.Voice() {
            @Override
            public void beat(@NotNull Game g, int n) {
                Component line = look.count().number().apply(n);
                Look.Count.Beat beat = look.count().beat();
                Title title = look.count().title() != null ? Title.title(look.count().title().apply(n),
                        Component.empty(), Match.times(g, beat.fadeIn(), beat.stay(), beat.fadeOut())) : null;
                Match.say(g, line);
                if (title != null) Match.title(g, title);
                Match.each(g, p -> GameSounds.play(g, p, SoundSet.COUNTDOWN));
            }

            @Override
            public void go(@NotNull Game g) {
                // captured: the beat title fades out empty (10, 0, 10)
                Title clear = look.count().title() != null ? Title.title(Component.empty(), Component.empty(),
                        Match.times(g, 10, 0, 10)) : null;
                Match.say(g, p -> look.count().started().apply(g, p));
                if (clear != null) Match.title(g, clear);
                Match.each(g, p -> GameSounds.play(g, p, SoundSet.START));
            }
        };
    }
}
