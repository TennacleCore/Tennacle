package tennacle.games;

import tennacle.Msg;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.Countdown;
import io.github.term4.grotto.modules.LastStanding;
import io.github.term4.grotto.modules.TimeLimit;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
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

    private final Set<UUID> fallen = new HashSet<>();

    @Override
    public void install(@NotNull Game game) {
        game.state().alarms().register(GHOST_ALARM, (tick, payload) -> {
            UUID uuid = UUID.fromString(payload.getString("uuid"));
            Player p = game.firstMember(m -> m.getUuid().equals(uuid));
            if (p != null) game.ghost(p);
        });
        LastStanding standing = game.module(LastStanding.class);
        if (standing != null) standing.onOut((g, seat, player, cause) -> out(g, seat, player, cause));
    }

    private void out(Game game, Slot seat, @Nullable Player player, LastStanding.Cause cause) {
        Look look = Matches.lookOf(game);
        String name = player != null ? player.getUsername() : lastOwner(game, seat);
        if (name == null) return;
        Component line = look.death().render(game, seat, name, cause.by(), cause.verb());
        Match.each(game, p -> p.sendMessage(line));
        if (player != null) fall(game, player);
    }

    /** The loser goes down: the death pose, then the ghosting. Once per player - a decision follows an elimination. */
    private void fall(Game game, Player player) {
        Mode mode = Matches.modeOf(game);
        if ((mode != null && !mode.ghostLoser()) || !fallen.add(player.getUuid())) return;
        // everyone watching (spectators included) sees a vanilla death: pose now, and the ghosting's destroy
        // packet lands at +20 ticks - right when a real corpse would poof. The loser gets no status 3, only the flight.
        for (Player watcher : game.shard().watchers()) {
            if (watcher != player) watcher.sendPacket(new EntityStatusPacket(player.getEntityId(), (byte) 3));
        }
        player.setFlying(true); // no mayfly: hovering, can't toggle - and a ring-out ghost stops mid-fall
        game.state().alarms().arm(GHOST_ALARM, game.state().tick() + 20,
                CompoundBinaryTag.builder().putString("uuid", player.getUuid().toString()).build());
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
    public void onDecided(@NotNull Game game, @NotNull Outcome outcome) {
        Look look = Matches.lookOf(game);
        if (outcome.equals(Outcome.aborted())) {
            Match.each(game, p -> Msg.tag(p, "duel", "duel cancelled - your opponent never showed"));
            return;
        }
        if (outcome.winner() == null) {
            Component line = look.theme().prose(TimeLimit.expired(game) ? "Time's up - draw." : "Draw.");
            Match.each(game, p -> p.sendMessage(line));
            return;
        }
        Player winner = game.firstMember(m -> outcome.winner().equals(Match.teamOf(game, m)));
        if (winner != null) {
            Match.each(game, p -> look.verdict().show(game, p, outcome.winner().equals(Match.teamOf(game, p)), winner));
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
                // captured beat title: fadeIn 0, stay 20, fadeOut 20
                Title title = look.count().title() != null ? Title.title(look.count().title().apply(n),
                        Component.empty(), Match.times(g, 0, 20, 20)) : null;
                Match.each(g, p -> {
                    p.sendMessage(line);
                    if (title != null) p.showTitle(title);
                    look.sounds().play(p, SoundSet.COUNTDOWN);
                });
            }

            @Override
            public void go(@NotNull Game g) {
                // captured: the beat title fades out empty (10, 0, 10)
                Title clear = look.count().title() != null ? Title.title(Component.empty(), Component.empty(),
                        Match.times(g, 10, 0, 10)) : null;
                Match.each(g, p -> {
                    Component started = look.count().started().apply(g, p);
                    if (started != null) p.sendMessage(started);
                    if (clear != null) p.showTitle(clear);
                    look.sounds().play(p, SoundSet.START);
                });
            }
        };
    }
}
