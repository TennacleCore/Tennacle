package tennacle.games.matchmaking;

import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.commands.Completions;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Platform;

import io.github.term4.grotto.Game;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /play <mode> [platform]}: a seat in a game of {@code mode} still filling up on its map, or a new one.
 * A party sits together, so it has to fit one team.
 */
public final class Play {

    private Play() {}

    public static void install() {
        AppCommand play = Commands.dev("play", "Join a game that is filling up, or start one.")
                .usage("/play <mode> [platform]", "take a seat")
                .featured();
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback(Completions.of(Play::modes));
        var platform = ArgumentType.Word("platform");
        platform.setSuggestionCallback(Completions.of(Matches::platforms));
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) enter(p, ctx.get(mode), null);
        }, mode);
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) enter(p, ctx.get(mode), ctx.get(platform));
        }, mode, platform);
        Commands.register(play);
    }

    /** The modes that fill up on their map. */
    public static Set<String> modes() {
        Set<String> out = new TreeSet<>();
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode != null && Matches.joinable(mode)) out.add(name);
        }
        return out;
    }

    private static void enter(Player me, String modeName, @Nullable String platformName) {
        Mode mode = Matches.mode(modeName);
        if (mode == null) {
            Msg.fail(me, "No mode named ", Msg.v(modeName), ". Modes: ", Msg.v(String.join(", ", modes())), ".");
            return;
        }
        if (!Matches.joinable(mode)) {
            Msg.fail(me, Msg.v(mode.title()), " is played through ", Msg.run("/queue"), " or ", Msg.run("/duel"), ".");
            return;
        }
        if (platformName != null && Matches.platform(platformName) == null) {
            Msg.fail(me, "No platform named ", Msg.v(platformName), ". Platforms: ", Msg.v(String.join(", ", Matches.platforms())), ".");
            return;
        }
        if (!Parties.requireLeader(me)) return;
        List<Player> side = Parties.of(me);
        for (Player member : side) {
            if (Matches.grotto().engaged(member)) {
                Msg.fail(me, Msg.v(member.getUsername()), " is in a game.");
                return;
            }
        }
        int seats = Matches.teamSize(mode);
        if (side.size() > seats) {
            Msg.fail(me, mode.title() + " seats " + seats + " a team; your party is " + side.size() + ".");
            return;
        }
        Platform platform = Matches.platformFor(platformName);
        Game open = Matches.waiting(mode, platform);
        if (open != null && Matches.enter(open, side)) {
            joined(side, mode, open);
            return;
        }
        Matches.open(mode, platform).whenComplete((game, failure) -> {
            if (failure != null) {
                for (Player p : side) Msg.fail(p, "The game could not start: " + failure.getMessage());
                return;
            }
            if (Matches.enter(game, side)) joined(side, mode, game);
            else for (Player p : side) Msg.fail(p, "No seat left in ", Msg.v(mode.title()), ".");
        });
    }

    private static void joined(List<Player> side, Mode mode, Game game) {
        for (Player p : side) Msg.ok(p, "Joined ", Msg.v(mode.title()), " on ", Msg.v(game.arena().meta().name()), ".");
    }
}
