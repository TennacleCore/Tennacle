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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /play <game>} by Hypixel's ids ({@code bedwars_eight_two}, {@code duels_bridge_doubles}). A mode that fills
 * up on its map seats the party in a game still filling, or a new one; a duel queues it ({@link Queues}). A party
 * sits together, so it has to fit one team.
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
        where.setSuggestionCallback(Completions.of(Play::wheres));
        var map = ArgumentType.Word("map");
        map.setSuggestionCallback(Completions.of(Play::maps));
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

    private static void play(Player me, String word, @Nullable String platformName, @Nullable String mapWord) {
        String id = word.toLowerCase(Locale.ROOT);
        Mode mode = Matches.played(id);
        if (mode == null) {
            Msg.fail(me, "No game named ", Msg.v(word), ". Games: ", Msg.v(String.join(", ", Matches.plays())), ".");
            return;
        }
        if (!Matches.joinable(mode) && mapWord != null) {
            Msg.fail(me, Msg.v(Queues.label(mode, id)), " picks its own map. ", Msg.run("/duel"), " names one.");
            return;
        }
        String refused = Matches.joinable(mode) ? enter(me, mode, platformName, mapWord) : Queues.enqueue(me, id, platformName);
        if (refused != null) Msg.fail(me, refused);
    }

    /** {@code /play id} for {@code me}'s side on the default platform: why it can't, else null once it is under way. */
    public static @Nullable String start(@NotNull Player me, @NotNull String id) {
        Mode mode = Matches.played(id);
        if (mode == null) return "No game named " + id + ".";
        return Matches.joinable(mode) ? enter(me, mode, null, null) : Queues.enqueue(me, id, null);
    }

    private static @Nullable String enter(Player me, Mode mode, @Nullable String platformName, @Nullable String mapWord) {
        if (platformName != null && Matches.platform(platformName) == null) {
            return "No platform named " + platformName + ". Platforms: " + String.join(", ", Matches.platforms()) + ".";
        }
        String map = mapWord != null ? Matches.mapNamed(mode, mapWord) : null;
        if (mapWord != null && map == null) {
            return "No map named " + mapWord + " for " + mode.title() + ". Maps: " + String.join(", ", mode.arena().names()) + ".";
        }
        String unfit = Queues.unfit(me, Matches.teamSize(mode), mode.title());
        if (unfit != null) return unfit;
        List<Player> side = Parties.of(me);
        Platform platform = Matches.platformFor(platformName);
        Game open = Matches.waiting(mode, platform, map, side.size());
        if (open != null && Matches.enter(open, side)) {
            joined(side, mode, open);
            return null;
        }
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
