package tennacle.games.bedfight;

import tennacle.commands.RulesCompletions;
import tennacle.games.Match;
import tennacle.games.watch.Spectators;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The buildable space, protection, kills and falls. Where a seat may build is the {@code bedfight:build}
 * region: a flat box is an X/Z slice between the floor and the height, a taller one its own volume. What it
 * may break is the same vocabulary bridge uses, over the {@code bedfight:defense} regions: a seat takes its
 * own blocks back up anywhere, the map's own only where a bed is defended. The beds are {@link Beds}' own. A
 * death respawns while the side's bed stands and eliminates once it does not.
 */
public final class Rules implements GameModule {

    /** Building at all: off, and a seat neither places nor breaks. */
    public static final StateKey<Boolean> BUILD = StateKey.of("space.build", Codec.BOOLEAN, true);
    /** Absolute max build Y; {@code -1} keeps the {@code bedfight:height} marker. */
    public static final StateKey<Integer> HEIGHT = StateKey.of("space.build.height", Codec.INT, -1);
    /** Lowest placeable Y; {@code -1} keeps the {@code bedfight:floor} marker. */
    public static final StateKey<Integer> FLOOR = StateKey.of("space.build.floor", Codec.INT, -1);
    /** May place outside the {@code bedfight:build} region - still under the height and above the floor. */
    public static final StateKey<Boolean> OUTSIDE = StateKey.of("space.build.outside", Codec.BOOLEAN, false);
    /** A block a member put there. */
    public static final String PLACED = "placed";
    /** The map's own. */
    public static final String MAP = "map";
    /** What may be broken inside a {@code bedfight:defense} region; a kind nobody names stays standing. */
    public static final StateKey<List<String>> BREAK = StateKey.of("space.break", Codec.STRING.list(), List.of(PLACED, MAP));
    /** What may be broken outside one - the rest of the map. */
    public static final StateKey<List<String>> BREAK_OUTSIDE =
            StateKey.of("space.break.outside", Codec.STRING.list(), List.of(PLACED));

    static {
        RulesCompletions.words(BREAK.id(), List.of(PLACED, MAP));
        RulesCompletions.words(BREAK_OUTSIDE.id(), List.of(PLACED, MAP));
    }

    private final Set<Point> placed = new HashSet<>();
    private Game game;
    private Respawns respawns;
    private KillCredit credit;
    private LastStanding standing;
    private RegionMarker build;
    private List<RegionMarker> defenses;
    private int floorY;
    private int ceilingY;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(BUILD, HEIGHT, FLOOR, OUTSIDE, BREAK, BREAK_OUTSIDE);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        credit = game.module(KillCredit.class);
        standing = game.module(LastStanding.class);
        Kits kits = game.module(Kits.class);
        if (respawns == null || credit == null || standing == null || kits == null) {
            throw new IllegalStateException("bedfight Rules needs Respawns, KillCredit, LastStanding and Kits installed before it");
        }
        build = game.arena().markers(BedFight.BUILD).getFirst();
        defenses = game.arena().markers(BedFight.DEFENSE);
        floorY = (int) Math.ceil(plane(BedFight.FLOOR));
        ceilingY = (int) Math.floor(plane(BedFight.HEIGHT));
        respawns.onRespawn((player, seat) -> kits.refit(player)); // the kit comes back whole on every spawn
        respawns.onDead((player, seat) -> Spectators.ghostKit(player)); // the wait holds a compass, nothing else
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
            killed(victim, e.source() instanceof Player by && by != victim ? by : null);
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            if (e.source() instanceof Player source && source == victim) return;
            if (respawns.isProtected(victim)) e.setCancelled(true);
        });
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean allowed = game.rules().resolve(player, BUILD) && (buildable(player, pos)
                    || (game.rules().resolve(player, OUTSIDE) && inBand(player, pos))); // off the slices, still in the band
            if (!allowed) {
                e.setCancelled(true); // Refusals says so
                return;
            }
            placed.add(new net.minestom.server.coordinate.BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            respawns.unprotect(player);
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            Point key = new net.minestom.server.coordinate.BlockVec(pos.blockX(), pos.blockY(), pos.blockZ());
            boolean own = placed.contains(key);
            List<String> may = game.rules().resolve(player, defended(pos) ? BREAK : BREAK_OUTSIDE);
            if (!game.rules().resolve(player, BUILD) || !may.contains(own ? PLACED : MAP)) {
                e.setCancelled(true);
                return;
            }
            placed.remove(key); // after the refusal: a block that stays is still the one who placed it
            respawns.unprotect(player);
        });
    }

    /** A member dropped under the void plane ({@code Limits}): the same end a killing blow gets. */
    void fell(@NotNull Player player) {
        if (game.roster().slotOf(player) == null) return;
        killed(player, credit.creditFor(player));
    }

    // the one decision this mode turns on: a side with a bed respawns, a side without loses the seat
    private void killed(Player victim, @Nullable Player by) {
        Slot seat = game.roster().slotOf(victim);
        if (seat == null) return;
        Match.addSeat(game, victim, BedFight.DEATHS);
        if (by != null) Match.addSeat(game, by, BedFight.KILLS);
        boolean last = !Beds.stands(game, seat.team());
        Match.say(game, line(victim, by, last));
        if (last) {
            standing.out(victim, LastStanding.Cause.eliminated(by, by != null ? "was killed by" : null));
            return; // the elimination cues its own fall
        }
        tennacle.looks.Deaths.cue(game, victim, by);
        respawns.queue(victim);
    }

    private Component line(Player victim, @Nullable Player by, boolean last) {
        Component out = Component.text(victim.getUsername(), Match.teamColor(game, victim));
        out = by != null
                ? out.append(Component.text(" was killed by ", NamedTextColor.GRAY))
                        .append(Component.text(by.getUsername(), Match.teamColor(game, by)))
                : out.append(Component.text(" died", NamedTextColor.GRAY));
        return last ? out.append(Component.text(" - FINAL KILL!", NamedTextColor.RED))
                : out.append(Component.text(".", NamedTextColor.GRAY));
    }

    private double plane(io.github.term4.barnacle.marker.MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    private boolean defended(Point pos) {
        for (RegionMarker defense : defenses) {
            if (defense.contains(pos)) return true;
        }
        return false;
    }

    // a flat (1-tall) box is an X/Z slice under the band, which is what the editor's wand draws over a deck; a
    // taller box is its own 3D volume, for maps whose buildable ceiling varies
    private boolean buildable(Player placer, Point pos) {
        for (RegionMarker.Cuboid box : build.boxes()) {
            if (pos.blockX() < box.min().blockX() || pos.blockX() > box.max().blockX()
                    || pos.blockZ() < box.min().blockZ() || pos.blockZ() > box.max().blockZ()) continue;
            if (box.min().blockY() == box.max().blockY() ? inBand(placer, pos)
                    : pos.blockY() >= box.min().blockY() && pos.blockY() <= box.max().blockY()) return true;
        }
        return false;
    }

    // the marker, or the rule when a seat carries its own
    private boolean inBand(Player placer, Point pos) {
        int low = game.rules().resolve(placer, FLOOR);
        int high = game.rules().resolve(placer, HEIGHT);
        return pos.blockY() >= (low >= 0 ? low : floorY) && pos.blockY() <= (high >= 0 ? high : ceilingY);
    }

}
