package tennacle.games.matchmaking;

import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.commands.Completions;
import tennacle.games.LobbyCommands;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Platform;

import io.github.term4.grotto.Game;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionCallback;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import tennacle.party.Parties;
/**
 * {@code /play <game> [map]} by Hypixel's ids ({@code bedwars_eight_two}, {@code duels_bridge_doubles}), from
 * anywhere: a side in a game leaves it on the way. A mode that fills up on its map seats the party in a game still
 * filling, or a new one; a duel queues it ({@link Queues}). A party sits together, so it has to fit one team.
 */
public final class Play {

    private Play() {}

    public static void install() {
        AppCommand play = Commands.dev("play", "Join a game, or the queue for one.")
                .usage("/play <game> [map]", "take a seat, on that map if you name one")
                .usage("/play <game> <platform> [map]", "on that platform")
                .featured();
        var game = ArgumentType.Word("game");
        game.setSuggestionCallback(Completions.of(Matches::plays));
        var where = ArgumentType.Word("where"); // a platform or a map
        where.setSuggestionCallback(maps(game.getId(), true));
        var map = ArgumentType.Word("map");
        map.setSuggestionCallback(maps(game.getId(), false));
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) play(p, ctx.get(game), null, null);
        }, game);
        play.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String word = ctx.get(where);
            if (Matches.platform(word) != null) play(p, ctx.get(game), word, null);
            else play(p, ctx.get(game), null, word);
        }, game, where);
        play.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) play(p, ctx.get(game), ctx.get(where), ctx.get(map));
        }, game, where, map);
        Commands.register(play);
    }

    // the typed game's maps, or every game's
    private static SuggestionCallback maps(String gameArgument, boolean platforms) {
        return (sender, context, suggestion) -> {
            String id = context.get(gameArgument);
            Mode mode = id != null ? Matches.played(id.toLowerCase(Locale.ROOT)) : null;
            Set<String> out = new TreeSet<>(platforms ? Matches.platforms() : Set.of());
            if (mode != null) out.addAll(Matches.mapNames(mode));
            else for (String name : Matches.modes()) out.addAll(Matches.mapNames(Matches.mode(name)));
            Completions.offer(suggestion, out);
        };
    }

    /** The line that starts {@code mode}: {@code /play} and its first id. */
    public static @NotNull String line(@NotNull Mode mode) {
        return "/play " + mode.plays().keySet().stream().findFirst().orElse(mode.name());
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

    private static void play(Player me, String word, @Nullable String platformName, @Nullable String mapWord) {
        String id = word.toLowerCase(Locale.ROOT);
        Mode mode = Matches.played(id);
        if (mode == null) {
            Msg.fail(me, "No game named ", Msg.v(word), ". Games: ", Msg.v(String.join(", ", Matches.plays())), ".");
            return;
        }
        String again = "Type it again";
        String refused = Matches.joinable(mode) ? enter(me, mode, id, platformName, mapWord, again)
                : Queues.enqueue(me, id, platformName, mapWord, again);
        if (refused != null) Msg.fail(me, refused);
    }

    /** {@code /play id} for {@code me}'s side on the default platform: why it can't, else null once it is under way. */
    public static @Nullable String start(@NotNull Player me, @NotNull String id) {
        return start(me, id, null);
    }

    /** {@link #start(Player, String)} on {@code map}, one of the mode's pool names, or any when null. */
    public static @Nullable String start(@NotNull Player me, @NotNull String id, @Nullable String map) {
        Mode mode = Matches.played(id);
        if (mode == null) return "No game named " + id + ".";
        String again = "Click again";
        return Matches.joinable(mode) ? enter(me, mode, id, null, map, again) : Queues.enqueue(me, id, null, map, again);
    }

    private static @Nullable String enter(Player me, Mode mode, String id, @Nullable String platformName,
                                          @Nullable String mapWord, String again) {
        if (platformName != null && Matches.platform(platformName) == null) {
            return "No platform named " + platformName + ". Platforms: " + String.join(", ", Matches.platforms()) + ".";
        }
        String map = mapWord != null ? Matches.mapNamed(mode, mapWord) : null;
        if (mapWord != null && map == null) {
            return "No map named " + mapWord + " for " + mode.title() + ". Maps: " + String.join(", ", Matches.mapNames(mode)) + ".";
        }
        Platform platform = Matches.platformFor(platformName);
        String unfit = Queues.unfit(me, Matches.teamSize(mode), mode.title(), "play " + id + " " + platform.name() + " " + map, again);
        if (unfit != null) return unfit;
        List<Player> side = Parties.of(me);
        Set<Game> leaving = new HashSet<>();
        for (Player p : side) {
            Game in = LobbyCommands.seatedIn(p);
            if (in != null) leaving.add(in);
        }
        List<Player> left = PartyExit.leaveTogether(side);
        Game open = Matches.waiting(mode, platform, map, side.size(), leaving::contains);
        if (open != null && Matches.enter(open, side)) {
            joined(side, mode, open);
            return null;
        }
        for (Player p : left) Matches.toLobby(p); // a new game loads off the tick: wait in the lobby
        Matches.open(mode, platform, map).whenComplete((game, failure) -> {
            if (failure != null) {
                for (Player p : side) Msg.fail(p, "The game could not start: " + failure.getMessage());
                return;
            }
            if (Matches.enter(game, side)) joined(side, mode, game);
            else for (Player p : side) Msg.fail(p, "No seat left in ", Msg.v(mode.title()), ".");
        });
        return null;
    }

    private static void joined(List<Player> side, Mode mode, Game game) {
        for (Player p : side) Msg.ok(p, "Joined ", Msg.v(mode.title()), " on ", Msg.v(game.arena().meta().name()), ".");
    }
}
