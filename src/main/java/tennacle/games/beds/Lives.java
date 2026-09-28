package tennacle.games.beds;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.watch.Lineup;
import tennacle.games.watch.Replays;
import tennacle.games.watch.Spectators;
import tennacle.looks.Deaths;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one rule a bed mode turns on: a death while the side's bed stands respawns, and once it is gone the next
 * is the last, the side going out with its last seat. Walking out is a death like any other: whoever last hit
 * them inside the credit window takes the kill, and the held seat owes a respawn - priced by
 * {@link Respawns#REJOIN_TICKS} on the way back, spent by the bed falling on the empty seat if they never
 * return. A death taken WHILE connected keeps its respawn even if the bed falls mid-wait.
 */
public final class Lives implements GameModule {

    /** A mode's own seat counters, so its ledger stays its own. */
    public record Counters(@NotNull StateKey<Integer> kills, @NotNull StateKey<Integer> deaths,
                           @NotNull StateKey<Integer> finalKills, @NotNull StateKey<Integer> finalDeaths) {}

    /** A death as decided: {@code victim} still holds what they carried, a walk-out's included. */
    public record Death(@NotNull Game game, @NotNull Player victim, @NotNull Slot seat, @Nullable Player killer,
                        boolean last, @Nullable String tool) {}

    /** A hit by something of a member's - a creature - answered for: whose, and what it is called. */
    public record Blame(@NotNull Player by, @Nullable String tool) {}

    private final Counters counters;
    private final List<Function<Entity, @Nullable Blame>> blames = new ArrayList<>();
    private final List<Consumer<Death>> onDeath = new ArrayList<>();
    private Game game;
    private Respawns respawns;
    private KillCredit credit;
    private LastStanding standing;

    public Lives(@NotNull Counters counters) {
        this.counters = counters;
    }

    public @NotNull Lives onDeath(@NotNull Consumer<Death> action) {
        onDeath.add(action);
        return this;
    }

    /** Answers for a damage source that is no player; the first resolver to know it wins. */
    public @NotNull Lives blame(@NotNull Function<Entity, @Nullable Blame> resolver) {
        blames.add(resolver);
        return this;
    }

    private @Nullable Blame blame(@Nullable Entity source) {
        if (source == null || source instanceof Player) return null;
        for (Function<Entity, @Nullable Blame> resolver : blames) {
            Blame blame = resolver.apply(source);
            if (blame != null) return blame;
        }
        // a fireball's thrower, a TNT's igniter: the death line names them with no tool (captured)
        Player owner = FriendlyFire.attributed(source);
        return owner != null ? new Blame(owner, null) : null;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        credit = game.module(KillCredit.class);
        standing = game.module(LastStanding.class);
        Beds beds = game.module(Beds.class);
        if (respawns == null || credit == null || standing == null || beds == null) {
            throw new IllegalStateException("Lives needs Respawns, KillCredit, LastStanding and Beds installed before it");
        }
        // a bed lost while nobody is sitting in the seat spends the respawn its holder walked out owing. Read
        // at the fall rather than kept from the leave - a seat owns nobody whether they left mid-round, walked
        // out of the countdown, or never arrived
        beds.onFall(team -> {
            for (Slot seat : game.roster().seatsOf(team)) {
                if (game.roster().ownerOf(seat) == null) {
                    standing.out(seat, LastStanding.Cause.eliminated(null, "left the game"));
                    game.roster().release(seat); // nothing to come back to
                }
            }
        });
        respawns.onRespawn((player, seat) -> {
            Lineup.unaim(player); // the compass aim of the life they just lost
            kits().refit(player); // the kit comes back whole on every spawn
        });
        respawns.onDead((player, seat) -> Spectators.ghostKit(game, player)); // the wait holds a compass, nothing else
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim) || game.roster().slotOf(victim) == null) return;
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) return;
            if (source != null) respawns.unprotect(source); // swinging forfeits your own protection
            if (respawns.isProtected(victim) || game.decided() != null) e.cancel();
        });
        game.on(FatalDamageEvent.class, FatalDamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim) || game.roster().slotOf(victim) == null) return;
            e.cancel(); // the module decides between a respawn and the last death
            Player by = e.source() instanceof Player p && p != victim ? p : null;
            Blame blame = by == null ? blame(e.source()) : null;
            if (blame != null && blame.by() != victim) by = blame.by();
            killed(victim, by, by != null ? "was killed by" : "died", by != null && blame != null ? blame.tool() : null);
        });
        // a creature's hit is its owner's for the credit, as an arrow's is its shooter's
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || e.dealt() <= 0 || !(e.target() instanceof Player victim) || e.source() instanceof Player) return;
            Blame blame = blame(e.source());
            if (blame != null && blame.by() != victim && game.roster().slotOf(blame.by()) != null) {
                credit.record(victim, blame.by(), game.state().tick());
            }
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            if (e.source() instanceof Player source && source == victim) return;
            if (respawns.isProtected(victim)) e.setCancelled(true);
        });
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        Slot seat = departure.seat();
        if (seat == null || !departure.reserved() || !Match.live(game) || game.decided() != null) return;
        // the seat is the departure's: by now the roster no longer answers for them
        Scope scope = Scope.slot(seat.index());
        game.state().set(scope, counters.deaths(), game.state().get(scope, counters.deaths()) + 1);
        Player by = credit.creditFor(player);
        String verb = by != null ? "was killed by" : "left the game";
        if (by != null) Match.addSeat(game, by, counters.kills());
        died(new Death(game, player, seat, by, !Beds.stands(game, seat.team()), null));
        if (!Beds.stands(game, seat.team())) {
            game.state().set(scope, counters.finalDeaths(), game.state().get(scope, counters.finalDeaths()) + 1);
            if (by != null) Match.addSeat(game, by, counters.finalKills());
            mark(player, seat, by, verb);
            // a kill within the credit window says so; a plain leave was announced as the departure it is
            standing.out(seat, by != null ? LastStanding.Cause.eliminated(by, verb, Banners.finalNote(game))
                    : LastStanding.Cause.departed());
            game.roster().release(seat); // the hold is for a seat with a bed to come back to
            if (standing.standing(seat.team()) == 0) {
                Component banner = Banners.eliminated(game, game.roster().team(seat.team()));
                if (banner != null) Match.say(game, banner);
            }
            return;
        }
        if (by != null) Match.say(game, Matches.lookOf(game).death().render(game, seat, player.getUsername(), by, verb));
    }

    /** Coming back to a bed that went while they were away changes nothing: the seat is still out. */
    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        if (!reclaimed || Beds.stands(game, slot.team())) return;
        respawns.cancel(player); // the wait the rejoin armed has nothing to land on
        standing.out(slot, LastStanding.Cause.eliminated(null, "left the game"));
    }

    // read at the respawn: a mode installs its kits after its beds
    private Kits kits() {
        Kits kits = game.module(Kits.class);
        if (kits == null) throw new IllegalStateException("Lives needs Kits installed");
        return kits;
    }

    /** A member dropped under the void plane ({@code Limits}): the same end a killing blow gets. */
    public void fell(@NotNull Player player) {
        if (game.roster().slotOf(player) == null) return;
        Player by = credit.creditFor(player);
        killed(player, by, by != null ? Banners.voidVerb(game) : "fell into the void", null);
    }

    // a side with a bed respawns, a side without loses the seat
    private void killed(Player victim, @Nullable Player by, String verb, @Nullable String tool) {
        Slot seat = game.roster().slotOf(victim);
        if (seat == null) return;
        Match.addSeat(game, victim, counters.deaths());
        if (by != null) Match.addSeat(game, by, counters.kills());
        died(new Death(game, victim, seat, by, !Beds.stands(game, seat.team()), tool));
        spill(victim);
        if (victim.getOpenInventory() != null) victim.closeInventory(); // vanilla closes a window at a death
        if (!Beds.stands(game, seat.team())) {
            Match.addSeat(game, victim, counters.finalDeaths());
            if (by != null) Match.addSeat(game, by, counters.finalKills());
            mark(victim, seat, by, verb);
            // the elimination says the line, in the look's own voice, and cues its own fall
            standing.out(victim, LastStanding.Cause.eliminated(by, verb, Banners.finalNote(game), tool));
            if (standing.standing(seat.team()) == 0) { // the side is gone, not just this seat
                Component banner = Banners.eliminated(game, game.roster().team(seat.team()));
                if (banner != null) Match.say(game, banner);
            }
            return;
        }
        Match.say(game, Matches.lookOf(game).death().render(game, seat, victim.getUsername(), by, verb, tool));
        Deaths.cue(game, victim, by);
        respawns.queue(victim);
    }

    private void died(Death death) {
        for (Consumer<Death> action : onDeath) action.accept(death);
    }

    // what falls where they died, after the killer's share: Polyp's death/spill, which kill() would have run
    private static void spill(Player victim) {
        DamageSystem damage = Polyp.getInstance().module(DamageSystem.class);
        if (damage != null) damage.spill(victim);
    }

    // the death nobody comes back from: both sides of it, and the face to put on the bookmark
    private void mark(Player victim, Slot seat, @Nullable Player by, String verb) {
        Team side = game.roster().team(seat.team());
        Team theirs = by != null ? game.roster().team(Match.teamOf(game, by)) : null;
        CompoundBinaryTag.Builder data = CompoundBinaryTag.builder()
                .putString("victim", victim.getUsername())
                .putString("victimTeam", seat.team())
                .putInt("victimColor", side != null ? side.color().value() : 0)
                .putString("verb", verb)
                .putString("killer", by != null ? by.getUsername() : "")
                .putString("killerTeam", theirs != null ? theirs.id() : "")
                .putInt("killerColor", (theirs != null ? theirs.color() : side.color()).value());
        var skin = victim.getSkin();
        if (skin != null) data.putString("skin", skin.textures()).putString("skinSig",
                skin.signature() == null ? "" : skin.signature());
        Replays.mark(game, "final", data.build());
    }
}
