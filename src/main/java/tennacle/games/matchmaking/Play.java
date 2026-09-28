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
                .usage("/play <mode> [map]", "take a seat, on that map if you name one")
                .usage("/play <mode> <platform> [map]", "on that platform")
                .featured();
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback(Completions.of(Play::modes));
        var where = ArgumentType.Word("where"); // a platform or a map
        where.setSuggestionCallback(Completions.of(Play::wheres));
        var map = ArgumentType.Word("map");
        map.setSuggestionCallback(Completions.of(Play::maps));
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) enter(p, ctx.get(mode), null, null);
        }, mode);
        play.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String word = ctx.get(where);
            if (Matches.platform(word) != null) enter(p, ctx.get(mode), word, null);
            else enter(p, ctx.get(mode), null, word);
        }, mode, where);
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) enter(p, ctx.get(mode), ctx.get(where), ctx.get(map));
        }, mode, where, map);
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

    /** Every joinable mode's maps, as the pools list them. */
    public static Set<String> maps() {
        Set<String> out = new TreeSet<>();
        for (String name : modes()) out.addAll(Matches.mode(name).arena().names());
        return out;
    }

    private static Set<String> wheres() {
        Set<String> out = new TreeSet<>(Matches.platforms());
        out.addAll(maps());
        return out;
    }

    private static void enter(Player me, String modeName, @Nullable String platformName, @Nullable String mapWord) {
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
        String map = mapWord != null ? Matches.mapNamed(mode, mapWord) : null;
        if (mapWord != null && map == null) {
            Msg.fail(me, "No map named ", Msg.v(mapWord), " for ", Msg.v(mode.title()), ". Maps: ", Msg.v(String.join(", ", mode.arena().names())), ".");
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
        Game open = Matches.waiting(mode, platform, map);
        if (open != null && Matches.enter(open, side)) {
            joined(side, mode, open);
            return;
        }
        Matches.open(mode, platform, map).whenComplete((game, failure) -> {
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
