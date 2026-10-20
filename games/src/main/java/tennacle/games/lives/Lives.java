package tennacle.games.lives;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.beds.Banners;
import tennacle.games.stats.Cause;
import tennacle.games.stats.Tally;
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
import io.github.term4.polyp.config.KeySet;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A side respawns while its {@link Anchor} stands (a bed, a wither), and once that is gone the next death is the
 * last, the side going out with its last seat. Walking out is a death like any other: whoever last hit
 * them inside the credit window takes the kill, and the held seat owes a respawn - priced by
 * {@link Respawns#REJOIN_TICKS} on the way back, spent by the bed falling on the empty seat if they never
 * return. A death taken WHILE connected keeps its respawn even if the bed falls mid-wait. {@link #KEEP} items
 * ride a respawn out, back where they were; {@link #SLOW_ITEMS} and {@link #delay} lengthen one death's wait.
 */
public final class Lives implements GameModule {

    /** A mode's own seat counters, so its ledger stays its own. */
    public record Counters(@NotNull StateKey<Integer> kills, @NotNull StateKey<Integer> deaths,
                           @NotNull StateKey<Integer> finalKills, @NotNull StateKey<Integer> finalDeaths) {}

    /** Item keys a respawning death keeps, each in the slot it was in; {@code only()}, nothing, by default. */
    public static final StateKey<KeySet> KEEP = StateKey.of("combat.death.keep", KeySet.CODEC, KeySet.only());
    /** Killing blows from these items add {@link #SLOW_TICKS} to the victim's wait. */
    public static final StateKey<KeySet> SLOW_ITEMS = StateKey.of("combat.respawn.slow-items", KeySet.CODEC, KeySet.only());
    public static final StateKey<Long> SLOW_TICKS = StateKey.of("combat.respawn.slow-ticks", Codec.LONG, 0L);

    /** A death as decided: {@code victim} still holds what they carried, a walk-out's included, less what {@link #KEEP}
     *  set aside. {@code item} is the killing blow's, null for a fall or a walk-out. */
    public record Death(@NotNull Game game, @NotNull Player victim, @NotNull Slot seat, @Nullable Player killer,
                        boolean last, @Nullable String tool, @Nullable ItemStack item, @NotNull Pos where) {}

    /** A hit by something of a member's - a creature - answered for: whose, and what it is called. */
    public record Blame(@NotNull Player by, @Nullable String tool) {}

    private final Counters counters;
    private final List<Function<Entity, @Nullable Blame>> blames = new ArrayList<>();
    private final List<Consumer<Death>> onDeath = new ArrayList<>();
    private final List<ToLongFunction<Death>> delays = new ArrayList<>();
    private final Map<Integer, Map<Integer, ItemStack>> kept = new ConcurrentHashMap<>();
    private Anchor anchor;
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

    /** Ticks a mode adds to one death's wait, beyond the respawn rule; every one added counts. */
    public @NotNull Lives delay(@NotNull ToLongFunction<Death> extra) {
        delays.add(extra);
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
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(KEEP, SLOW_ITEMS, SLOW_TICKS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        credit = game.module(KillCredit.class);
        standing = game.module(LastStanding.class);
        anchor = game.module(Anchor.class);
        if (respawns == null || credit == null || standing == null || anchor == null) {
            throw new IllegalStateException("Lives needs Respawns, KillCredit, LastStanding and an Anchor installed before it");
        }
        // a bed lost while nobody is sitting in the seat spends the respawn its holder walked out owing. Read
        // at the fall rather than kept from the leave - a seat owns nobody whether they left mid-round, walked
        // out of the countdown, or never arrived
        anchor.onFall(team -> {
            for (Slot seat : game.roster().seatsOf(team)) {
                if (game.roster().ownerOf(seat) == null) {
                    if (played(seat)) {
                        Scope scope = Scope.slot(seat.index());
                        game.state().set(scope, counters.finalDeaths(), game.state().get(scope, counters.finalDeaths()) + 1);
                        UUID held = heldBy(seat);
                        if (held != null) Tally.finalDeath(game, held, Cause.OTHER);
                        credit(breakerFor(seat), Cause.OTHER);
                    }
                    standing.out(seat, LastStanding.Cause.eliminated(null, "left the game"));
                    game.roster().release(seat); // nothing to come back to
                }
            }
        });
        respawns.onRespawn((player, seat) -> {
            Lineup.unaim(player); // the compass aim of the life they just lost
            kits().refit(player); // the kit comes back whole on every spawn
            restore(player, seat);
        });
        respawns.onDead((player, seat) -> Spectators.ghostKit(game, player)); // the wait holds a compass, nothing else
        game.on(DamageEvent.class, DamageEvent::target, e -> { // protection is Respawns' own gate
            if (e.target() instanceof Player victim && game.roster().slotOf(victim) != null && game.decided() != null) e.cancel();
        });
        game.on(FatalDamageEvent.class, FatalDamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim) || game.roster().slotOf(victim) == null) return;
            e.cancel(); // the module decides between a respawn and the last death
            Player by = e.source() instanceof Player p && p != victim ? p : null;
            Blame blame = by == null ? blame(e.source()) : null;
            if (blame != null && blame.by() != victim) by = blame.by();
            killed(victim, by, by != null ? "was killed by" : "died", by != null && blame != null ? blame.tool() : null, e.item(),
                    Cause.of(e.type(), e.source()));
        });
        // a creature's hit is its owner's for the credit, as an arrow's is its shooter's
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || e.fatalCancelled() || !(e.target() instanceof Player victim) || e.source() instanceof Player) return;
            Blame blame = blame(e.source());
            if (blame != null && blame.by() != victim && game.roster().slotOf(blame.by()) != null) {
                credit.record(victim, blame.by(), game.state().tick());
                Tally.hit(game, victim.getUuid(), blame.by().getUuid());
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
        if (game.state().get(scope, LastStanding.OUT)) { // out already: nothing to count or announce, nothing to come back to
            game.roster().release(seat);
            return;
        }
        game.state().set(scope, counters.deaths(), game.state().get(scope, counters.deaths()) + 1);
        Player by = credit.creditFor(player);
        String verb = by != null ? "was killed by" : "left the game";
        if (by != null) Match.addSeat(game, by, counters.kills());
        died(new Death(game, player, seat, by, !anchor.stands(seat.team()), null, null, player.getPosition()));
        Tally.died(game, player.getUuid(), by != null ? by.getUuid() : null, !anchor.stands(seat.team()), Cause.OTHER);
        if (!anchor.stands(seat.team())) {
            game.state().set(scope, counters.finalDeaths(), game.state().get(scope, counters.finalDeaths()) + 1);
            if (by != null) Match.addSeat(game, by, counters.finalKills());
            else credit(breakerFor(seat), Cause.OTHER);
            mark(player, seat, by, verb);
            // a kill within the credit window says so; a plain leave was announced as the departure it is
            standing.out(seat, by != null ? LastStanding.Cause.eliminated(by, verb, Banners.finalNote(game))
                    : LastStanding.Cause.departed());
            game.roster().release(seat); // the hold is for a seat with a bed to come back to
            if (standing.standing(seat.team()) == 0) {
                Match.say(game, Banners.eliminated(game, game.roster().team(seat.team())));
            }
            return;
        }
        if (by != null) Match.say(game, Matches.lookOf(game).death().render(game, seat, player.getUsername(), by, verb));
    }

    /** Coming back to a bed that went while they were away changes nothing: the seat is still out. */
    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        if (!reclaimed || anchor.stands(slot.team())) return;
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
        if (game.roster().slotOf(player) == null || respawns.isDead(player)) return; // a waiting ghost cannot die again
        Player by = credit.creditFor(player);
        killed(player, by, by != null ? Banners.voidVerb(game) : "fell into the void", null, null, Cause.VOID);
    }

    // a side with a bed respawns, a side without loses the seat
    private void killed(Player victim, @Nullable Player by, String verb, @Nullable String tool, @Nullable ItemStack item,
                        Cause cause) {
        Slot seat = game.roster().slotOf(victim);
        if (seat == null) return;
        Match.addSeat(game, victim, counters.deaths());
        if (by != null) Match.addSeat(game, by, counters.kills());
        boolean last = !anchor.stands(seat.team());
        Tally.died(game, victim.getUuid(), by != null ? by.getUuid() : null, last, cause);
        if (!last) keep(victim, seat); // before the killer's share and the spill
        Death death = new Death(game, victim, seat, by, last, tool, item, victim.getPosition());
        died(death);
        spill(victim);
        if (victim.getOpenInventory() != null) victim.closeInventory(); // vanilla closes a window at a death
        if (last) {
            Match.addSeat(game, victim, counters.finalDeaths());
            if (by != null) Match.addSeat(game, by, counters.finalKills());
            else credit(breakerFor(seat), cause);
            mark(victim, seat, by, verb);
            // the user, 2026-09-29: the eliminated wait where a death waits, with the wait's compass, and never respawn
            respawns.park(victim);
            Spectators.ghostKit(game, victim);
            // the elimination says the line, in the look's own voice, and cues its own fall
            standing.out(victim, LastStanding.Cause.eliminated(by, verb, Banners.finalNote(game), tool));
            Component you = Banners.eliminatedYou(game);
            if (you != null) victim.sendMessage(you);
            if (standing.standing(seat.team()) == 0) { // the side is gone, not just this seat
                Match.say(game, Banners.eliminated(game, game.roster().team(seat.team())));
            }
            return;
        }
        Match.say(game, Matches.lookOf(game).death().render(game, seat, victim.getUsername(), by, verb, tool));
        Deaths.cue(game, victim, by);
        long extra = extra(death);
        if (extra > 0) respawns.queue(victim, game.rules().resolve(seat, Respawns.RESPAWN_TICKS) + extra);
        else respawns.queue(victim);
    }

    private long extra(Death death) {
        long extra = 0;
        ItemStack item = death.item();
        if (item != null && !item.isAir() && game.rules().resolve(death.seat(), SLOW_ITEMS).admits(item.material().key())) {
            extra += game.rules().resolve(death.seat(), SLOW_TICKS);
        }
        for (ToLongFunction<Death> delay : delays) extra += Math.max(0, delay.applyAsLong(death));
        return extra;
    }

    private void keep(Player victim, Slot seat) {
        KeySet keep = game.rules().resolve(seat, KEEP);
        PlayerInventory inv = victim.getInventory();
        Map<Integer, ItemStack> mine = new LinkedHashMap<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack.isAir() || !keep.admits(stack.material().key())) continue;
            inv.setItemStack(slot, ItemStack.AIR);
            mine.put(slot, stack);
        }
        if (!mine.isEmpty()) kept.put(seat.index(), mine);
    }

    // after the refit: its own slot when the kit left it free, else wherever it fits
    private void restore(Player player, Slot seat) {
        Map<Integer, ItemStack> mine = kept.remove(seat.index());
        if (mine == null) return;
        PlayerInventory inv = player.getInventory();
        mine.forEach((slot, stack) -> {
            if (inv.getItemStack(slot).isAir()) inv.setItemStack(slot, stack);
            else inv.addItemStack(stack);
        });
    }

    // the user, 2026-10-01: a final death nobody else is owed counts for whoever took the bed, while they play on
    // alive; the line stays the one nobody's credit changes (cap: "evanben fell into the void. FINAL KILL!")
    private @Nullable Player breakerFor(Slot seat) {
        Player breaker = anchor.breaker(seat.team());
        Slot theirs = breaker != null ? game.roster().slotOf(breaker) : null;
        if (theirs == null || respawns.isDead(breaker) || game.state().get(Scope.slot(theirs.index()), LastStanding.OUT)) return null;
        return breaker;
    }

    private void credit(@Nullable Player breaker, Cause cause) {
        if (breaker == null) return;
        Match.addSeat(game, breaker, counters.kills());
        Match.addSeat(game, breaker, counters.finalKills());
        Tally.kill(game, breaker.getUuid(), true, cause);
    }

    private @Nullable UUID heldBy(Slot seat) {
        UUID held = null;
        for (var p : game.roster().participants()) if (p.slot().equals(seat)) held = p.uuid();
        return held;
    }

    // a seat somebody sat in, not one nobody ever took
    private boolean played(Slot seat) {
        return game.roster().participants().stream().anyMatch(p -> p.slot().equals(seat));
    }

    private void died(Death death) {
        anchor.died(death);
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
