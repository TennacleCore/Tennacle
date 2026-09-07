package tennacle.games.bedfight;

import tennacle.games.Match;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.Team;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.title.Title;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The beds: one a side, a real bed under a {@code bedfight:bed} region over both its blocks, in the owning
 * team's group. While a side's bed stands its seats respawn; breaking it takes that away, and the next death
 * there is the last. Nobody breaks their own. {@link #DESTROY} takes every bed left at once, so a match nobody
 * wins still ends. The elimination is {@code LastStanding}'s, which {@link Rules} calls when a bedless seat falls.
 */
public final class Beds implements GameModule {

    /** Whether the team's bed still stands; team-scoped, so a board reads it a side at a time. */
    public static final StateKey<Boolean> STANDS = StateKey.of("ledger.bedfight.bed", Codec.BOOLEAN, true, true);
    /** Ticks of live play before every standing bed falls at once; negative = never. */
    public static final StateKey<Long> DESTROY = StateKey.of("beds.destroy", Codec.LONG, 10L * 60 * 20);
    private static final StateKey<Long> FALLS_AT = StateKey.of("ledger.bedfight.beds-at", Codec.LONG, -1L);
    private static final StateKey<Long> STARTED = StateKey.of("ledger.bedfight.beds-from", Codec.LONG, -1L);
    private static final String FALL_ALARM = "bedfight:bed-destruction";

    private static final Sound BROKEN = Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1f);
    private static final Component LOST = Component.text("BED DESTROYED!", NamedTextColor.RED);
    private static final Component NO_RESPAWN = Component.text("You will no longer respawn!", NamedTextColor.WHITE);

    private Game game;
    /** Every block of every bed, to the side that sleeps in it. */
    private final Map<BlockVec, String> cells = new HashMap<>();

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(DESTROY);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        for (Team team : game.roster().teams()) {
            game.state().set(Scope.team(team.id()), STANDS, true);
            RegionMarker bed = game.arena().marker(BedFight.BED, team.id());
            if (bed == null) continue;
            for (RegionMarker.Cuboid box : bed.boxes()) {
                for (int x = box.min().blockX(); x <= box.max().blockX(); x++) {
                    for (int y = box.min().blockY(); y <= box.max().blockY(); y++) {
                        for (int z = box.min().blockZ(); z <= box.max().blockZ(); z++) {
                            cells.put(new BlockVec(x, y, z), team.id());
                        }
                    }
                }
            }
        }
        game.on(PlayerBlockBreakEvent.class, e -> {
            Point pos = e.getBlockPosition();
            String owner = cells.get(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            if (owner == null) return;
            Player breaker = e.getPlayer();
            if (game.roster().slotOf(breaker) == null) return;
            e.setCancelled(true); // both halves go together, so a half is never left standing
            if (owner.equals(Match.teamOf(game, breaker))) return; // your own bed is not a target
            if (stands(game, owner)) broke(owner, breaker);
        });
        game.state().alarms().register(FALL_ALARM, (tick, payload) -> fall());
        game.rules().watch(DESTROY, (scope, value) -> rearm());
        Phases phases = game.module(Phases.class);
        if (phases == null || Phases.is(game, Match.LIVE)) start();
        else phases.onEnter(Match.LIVE, g -> start());
    }

    /** The tick every remaining bed falls on, {@code -1} where no clock runs. */
    public static long fallsAt(@NotNull Game game) {
        return game.state().get(Scope.GAME, FALLS_AT);
    }

    /** Ticks until the beds fall, {@code 0} once they have; {@code -1} where no clock runs. */
    public static long fallsIn(@NotNull Game game) {
        long at = fallsAt(game);
        return at < 0 ? -1 : Math.max(0, at - game.state().tick());
    }

    private void start() {
        game.state().set(Scope.GAME, STARTED, game.state().tick());
        rearm();
    }

    private void rearm() {
        long from = game.state().get(Scope.GAME, STARTED);
        if (from < 0 || standing(game).isEmpty()) return; // before the clock starts, or nothing left to take
        long ticks = game.rules().resolve(Scope.GAME, DESTROY);
        if (ticks < 0) {
            game.state().set(Scope.GAME, FALLS_AT, -1L);
            game.state().alarms().cancel(FALL_ALARM);
            return;
        }
        long at = from + ticks;
        game.state().set(Scope.GAME, FALLS_AT, at);
        game.state().alarms().arm(FALL_ALARM, at);
    }

    // every side at once, so nobody is the one who lost their bed to the clock
    private void fall() {
        List<String> left = standing(game);
        if (left.isEmpty()) return;
        for (String team : left) game.state().set(Scope.team(team), STANDS, false);
        cells.forEach((cell, team) -> {
            if (left.contains(team)) game.shard().setBlock(cell, Block.AIR);
        });
        game.forEachAudience(viewer -> viewer.playSound(BROKEN));
        told(left::contains);
        Match.say(game, Component.text("BED DESTRUCTION! ", NamedTextColor.RED)
                .append(Component.text("Every bed is gone - the next death is final.", NamedTextColor.GRAY)));
    }

    // the side that lost it hears it on their screen; the rest read the line
    private void told(Predicate<String> teams) {
        Title title = Title.title(LOST, NO_RESPAWN, Match.times(game, 0, 40, 20));
        Match.each(game, p -> {
            String team = Match.teamOf(game, p);
            if (!team.isEmpty() && teams.test(team)) p.showTitle(title);
        });
    }

    /** Whether {@code team} still has a bed to respawn at. */
    public static boolean stands(@NotNull Game game, @NotNull String team) {
        return game.state().get(Scope.team(team), STANDS);
    }

    /** The sides whose bed still stands, in roster order. */
    public static @NotNull List<String> standing(@NotNull Game game) {
        return game.roster().teams().stream().map(Team::id).filter(id -> stands(game, id)).toList();
    }

    private void broke(String owner, Player breaker) {
        game.state().set(Scope.team(owner), STANDS, false);
        cells.forEach((cell, team) -> {
            if (team.equals(owner)) game.shard().setBlock(cell, Block.AIR);
        });
        Match.addSeat(game, breaker, BedFight.BEDS);
        Team broken = game.roster().team(owner);
        game.forEachAudience(viewer -> viewer.playSound(BROKEN));
        told(owner::equals);
        Match.say(game, Component.text("BED DESTROYED! ", NamedTextColor.RED)
                .append(Component.text(Match.teamName(game, breaker), NamedTextColor.GRAY))
                .append(Component.text(" broke ", NamedTextColor.GRAY))
                .append(Component.text(tennacle.looks.Palette.name(broken), broken.color()))
                .append(Component.text("'s bed - they no longer respawn.", NamedTextColor.GRAY)));
    }
}
