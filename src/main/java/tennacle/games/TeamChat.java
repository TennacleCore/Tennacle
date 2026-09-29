package tennacle.games;

import io.github.term4.canvas.chat.Chat;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.games.watch.Replays;
import tennacle.looks.Look;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where a member's chat goes: to their team under {@link #TEAM}, else to the whole game; {@code /shout} reaches the
 * whole game either way, once a cooldown. Hypixel: team modes default to team chat with a 60 s shout, Solo is global.
 */
public final class TeamChat implements GameModule {

    /** Chat stays within the speaker's team; off, everyone in the game hears everyone. */
    public static final StateKey<Boolean> TEAM = StateKey.of("chat.team", Codec.BOOLEAN, false);
    /** Ticks between one player's shouts. */
    public static final StateKey<Long> SHOUT_COOLDOWN = StateKey.of("chat.shout.cooldown", Codec.LONG, 1200L);

    private static final Tag<Long> LAST_SHOUT = Tag.Long("tennacle:last-shout");

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(TEAM, SHOUT_COOLDOWN);
    }

    @Override
    public void install(@NotNull Game game) {}

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        player.removeTag(LAST_SHOUT); // another game's clock
    }

    /** The team room a member's line goes to, or null when the whole game hears it. */
    public static Chat.@Nullable Room room(@NotNull Game game, @NotNull Player speaker, @NotNull String message) {
        if (game.module(TeamChat.class) == null || !game.rules().resolve(Scope.GAME, TEAM)) return null;
        String team = Match.teamOf(game, speaker);
        if (team.isEmpty()) return null;
        List<Player> to = new ArrayList<>();
        game.forEachAudience(p -> {
            if (team.equals(Match.teamOf(game, p))) to.add(p);
        });
        return new Chat.Room(to, Look.teamChat(game, speaker, message));
    }

    /** {@code /shout <message>}: the whole game hears it. */
    public static void register() {
        AppCommand cmd = Commands.player("shout", "Say something to everyone in your game, not just your team.")
                .usage("/shout <message>", "the whole game hears it, once a cooldown");
        var text = ArgumentType.StringArray("text");
        cmd.setDefaultExecutor((sender, ctx) -> Commands.usage(sender, cmd));
        cmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) shout(p, String.join(" ", ctx.get(text)));
        }, text);
        Commands.register(cmd);
    }

    public static void shout(@NotNull Player me, @NotNull String message) {
        Game game = gameOf(me);
        if (game == null) {
            Msg.fail(me, "You are not in a game.");
            return;
        }
        if (game.module(TeamChat.class) == null || !game.rules().resolve(Scope.GAME, TEAM)) {
            Msg.fail(me, "Everyone in this game hears you already.");
            return;
        }
        long cooldown = game.rules().resolve(Scope.GAME, SHOUT_COOLDOWN);
        Long last = me.getTag(LAST_SHOUT);
        if (last != null && game.tick() - last < cooldown) {
            long seconds = (cooldown - (game.tick() - last) + 19) / 20;
            Msg.fail(me, "You can shout again in ", Msg.v(seconds + "s"), ".");
            return;
        }
        me.setTag(LAST_SHOUT, game.tick());
        Component line = Look.shout(game, me, message);
        game.forEachAudience(p -> p.sendMessage(line));
        Replays.said(game, line);
    }

    // a ghost is unbound: their seat still names the game
    private static @Nullable Game gameOf(Player player) {
        Game game = Game.of(player);
        if (game != null) return game;
        for (Game candidate : Matches.grotto().games()) {
            if (candidate.isGhost(player)) return candidate;
        }
        return null;
    }
}
