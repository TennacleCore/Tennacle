package tennacle.commands;

import tennacle.Mechanics;
import tennacle.games.Completions;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.config.PathEdits;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.suggestion.SuggestionCallback;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What {@code /rules} completes, a segment at a time: {@code spa} offers {@code space}, {@code space.} the packages
 * and ids under it, and {@code paths att} walks the mechanics tree the same way a slash at a time. Read at the
 * request, from the sender's game; the last word is what completes, the words before it say where we are.
 */
final class RulesCompletions {

    private RulesCompletions() {}

    /** The first word: {@code paths}, a seat by {@code @} or a side by {@code #}, or a rule id by segment. */
    static SuggestionCallback key() {
        return (sender, context, suggestion) -> {
            Game game = gameOf(sender);
            if (game == null) return;
            String partial = Completions.partial(suggestion);
            List<String> out = new ArrayList<>();
            boolean scoped = partial.startsWith("@") || partial.startsWith("#");
            if (scoped || partial.isEmpty()) out.addAll(scopes(game));
            if (!scoped) {
                out.add("paths");
                out.addAll(ids(game, partial));
            }
            Completions.offer(suggestion, out);
        };
    }

    /** The words after the first: a path below {@code paths}, an id after a scope, then the value. */
    static SuggestionCallback tail(Argument<String> key, Argument<String[]> value) {
        return (sender, context, suggestion) -> {
            Game game = gameOf(sender);
            if (game == null) return;
            String lead = context.get(key);
            String[] words = context.get(value);
            // the words before the one under the cursor; the listing prints "id = value", so a lone = is skipped
            List<String> done = new ArrayList<>(words == null ? List.of() : Arrays.asList(words).subList(0, words.length - 1));
            done.removeIf("="::equals);
            String partial = Completions.partial(suggestion);
            List<String> out;
            if (lead.equals("paths")) {
                out = paths(game, String.join("/", done), partial);
            } else if (lead.startsWith("@") || lead.startsWith("#")) {
                out = done.isEmpty() ? ids(game, partial) : done.size() == 1 ? values(game, done.getFirst()) : List.of();
            } else {
                out = done.isEmpty() ? values(game, lead) : List.of();
            }
            Completions.offer(suggestion, out);
        };
    }

    private static @Nullable Game gameOf(CommandSender sender) {
        return sender instanceof Player p ? Game.of(p) : null;
    }

    private static List<String> scopes(Game game) {
        List<String> out = new ArrayList<>();
        game.roster().teams().forEach(t -> out.add("#" + t.id()));
        game.members().forEach(m -> out.add("@" + m.getUsername()));
        return out;
    }

    /** The ids under {@code partial}, one segment past what it reaches: {@code spa} gives space, {@code space.} space.build. */
    private static List<String> ids(Game game, String partial) {
        int depth = (int) partial.chars().filter(c -> c == '.').count();
        SortedSet<String> out = new TreeSet<>();
        for (String id : game.type().supportedRules().keySet()) {
            if (!id.regionMatches(true, 0, partial, 0, partial.length())) continue;
            int end = -1;
            for (int i = 0, from = 0; i <= depth; i++, from = end + 1) {
                end = id.indexOf('.', from);
                if (end < 0) break;
            }
            out.add(end < 0 ? id : id.substring(0, end));
        }
        return List.copyOf(out);
    }

    /** One level of the mechanics tree below {@code typed} plus the partial's own parent, either separator. */
    private static List<String> paths(Game game, String typed, String partial) {
        int cut = Math.max(partial.lastIndexOf('/'), partial.lastIndexOf('.'));
        String below = cut < 0 ? "" : partial.substring(0, cut);
        String parent = (typed.isEmpty() ? below : below.isEmpty() ? typed : typed + "/" + below).replace('.', '/');
        String lead = cut < 0 ? "" : partial.substring(0, cut + 1);
        List<PathEdits.Step> steps;
        try {
            steps = PathEdits.browse(parent, k -> Mechanics.polyp.profiles().resolveWorld(game.shard(), k));
        } catch (RuntimeException e) {
            return List.of();
        }
        return steps.stream().map(step -> lead + step.name()).toList();
    }

    /** A boolean's two values, an enum knob's constants; anything else is typed by hand. */
    private static List<String> values(Game game, String id) {
        if (id.contains("/")) {
            int cut = id.lastIndexOf('/');
            try {
                for (PathEdits.Step step : PathEdits.browse(id.substring(0, cut), k -> Mechanics.polyp.profiles().resolveWorld(game.shard(), k))) {
                    if (step.name().equals(id.substring(cut + 1))) return choices(step.type());
                }
            } catch (RuntimeException e) {
                return List.of();
            }
            return List.of();
        }
        StateKey<?> rule = game.type().supportedRules().get(id);
        return rule != null && rule.initial() instanceof Boolean ? List.of("true", "false") : List.of();
    }

    // an enum's type reads "Kind (a|b|c)": the constants, as the editor takes them
    private static List<String> choices(@Nullable String type) {
        if (type == null) return List.of();
        if (type.equalsIgnoreCase("boolean")) return List.of("true", "false");
        int open = type.indexOf('('), close = type.lastIndexOf(')');
        return open >= 0 && close > open ? List.of(type.substring(open + 1, close).split("[|]")) : List.of();
    }
}
