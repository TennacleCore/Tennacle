package tennacle.games;

import org.jetbrains.annotations.Nullable;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Role;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

/** The block roles a side's livery fills, and a seat's block of one. */
public final class TeamBlocks {

    public static final Role<Role.Fill> WOOL = Role.block("blocks.wool");
    public static final Role<Role.Fill> CLAY = Role.block("blocks.clay");
    public static final Role<Role.Fill> GLASS = Role.block("blocks.glass");

    private TeamBlocks() {}

    public static @NotNull Material material(@NotNull Game game, @NotNull Slot seat, @NotNull Role<Role.Fill> role) {
        Team team = game.roster().team(seat.team());
        return team.livery().get(role).material();
    }

    /** As {@link #material(Game, Slot, Role)}, with {@code fallback}'s for a viewer with no seat. */
    public static @NotNull Material material(@NotNull Game game, @Nullable Slot seat, @NotNull Role<Role.Fill> role, @NotNull Livery fallback) {
        return seat == null ? fallback.get(role).material() : material(game, seat, role);
    }

    /** The seat's side's wool as a block, for what a build lays. */
    public static @NotNull Block wool(@NotNull Game game, @NotNull Slot seat) {
        return Block.fromKey(material(game, seat, WOOL).key());
    }
}
