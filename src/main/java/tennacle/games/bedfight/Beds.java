package tennacle.games.bedfight;

import tennacle.games.Match;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The beds: one a side, a real bed under a {@code bedfight:bed} region over both its blocks, in the owning
 * team's group. While a side's bed stands its seats respawn; breaking it takes that away, and the next death
 * there is the last. Nobody breaks their own. The elimination is {@code LastStanding}'s, which {@link Rules}
 * calls when a bedless seat falls.
 */
public final class Beds implements GameModule {

    /** Whether the team's bed still stands; team-scoped, so a board reads it a side at a time. */
    public static final StateKey<Boolean> STANDS = StateKey.of("ledger.bedfight.bed", Codec.BOOLEAN, true, true);

    private static final Sound BROKEN = Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1f);

    private Game game;
    /** Every block of every bed, to the side that sleeps in it. */
    private final Map<BlockVec, String> cells = new HashMap<>();

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of();
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
        Match.say(game, Component.text("BED DESTROYED! ", NamedTextColor.RED)
                .append(Component.text(Match.teamName(game, breaker), NamedTextColor.GRAY))
                .append(Component.text(" broke ", NamedTextColor.GRAY))
                .append(Component.text(owner, broken.color()))
                .append(Component.text("'s bed - they no longer respawn.", NamedTextColor.GRAY)));
    }
}
