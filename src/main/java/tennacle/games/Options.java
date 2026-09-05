package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.grotto.state.StateKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a request asks for past the mode: the words of {@code /duel <player> <mode> [map] [-flag ...]}. A bare
 * word is the map, from the mode's pool. A flag is {@code -key=value}, or {@code -key} for true: {@code look},
 * {@code platform} and {@code map} are the three the command owns; a dotted key is a rule id and a slashed one
 * a mechanics path, both folded over the platform's ruleset at launch as the game's first live modifier, so
 * {@code /rules} layers over them the same way it layers over anything. Everything is checked here, so a bad
 * word answers the requester rather than failing the launch the accepter triggers.
 */
public record Options(@Nullable String map, @Nullable String look, @Nullable String platform,
                      @NotNull Ruleset rules, @NotNull List<String> words) {

    public static final Options NONE = new Options(null, null, null, Ruleset.named("flags"), List.of());

    public static @NotNull Options parse(@NotNull Mode mode, @NotNull List<String> words) {
        String map = null, look = null, platform = null;
        Ruleset rules = Ruleset.named("flags");
        for (String word : words) {
            if (!word.startsWith("-")) {
                if (map != null) throw new IllegalArgumentException("one map, not " + map + " and " + word);
                map = word;
                continue;
            }
            String flag = word.substring(1);
            int eq = flag.indexOf('=');
            String key = eq < 0 ? flag : flag.substring(0, eq);
            String value = eq < 0 ? "true" : flag.substring(eq + 1);
            if (key.isEmpty()) throw new IllegalArgumentException("a flag needs a name: " + word);
            switch (key) {
                case "look" -> look = value;
                case "platform" -> platform = value;
                case "map" -> map = value;
                default -> {
                    if (key.indexOf('/') >= 0) rules = rules.path(key, value);
                    else if (key.indexOf('.') >= 0) rules = rules.rule(key, value);
                    else throw new IllegalArgumentException("unknown flag -" + key
                            + " (look, platform, map, a rule id like flow.time.limit, or a mechanics path)");
                }
            }
        }
        if (map != null && mode.arena().named(map) == null) {
            throw new IllegalArgumentException("no map named " + map + " for " + mode.name()
                    + " (" + String.join(", ", mode.arena().names()) + ")");
        }
        if (look != null && !Matches.lookNames().contains(look)) {
            throw new IllegalArgumentException("no look named " + look + " (" + String.join(", ", Matches.lookNames()) + ")");
        }
        if (platform != null && Matches.platform(platform) == null) {
            throw new IllegalArgumentException("no platform named " + platform + " (" + String.join(", ", Matches.platforms()) + ")");
        }
        List<String> problems = rules.validate(Mechanics.polyp.profiles().global(), mode.type());
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return new Options(map, look, platform, rules, List.copyOf(words));
    }

    /** What the tail can hold at {@code token}: map names bare, flag keys after a dash, a key's values after its equals. */
    static @NotNull Collection<String> completions(@NotNull Mode mode, @NotNull String token) {
        if (!token.startsWith("-")) return mode.arena().names();
        String flag = token.substring(1);
        int eq = flag.indexOf('=');
        if (eq < 0) {
            List<String> keys = new ArrayList<>(List.of("-look", "-platform", "-map"));
            for (String id : mode.type().supportedRules().keySet()) keys.add("-" + id);
            return keys;
        }
        String key = flag.substring(0, eq);
        Collection<String> values = switch (key) {
            case "look" -> Matches.lookNames();
            case "platform" -> Matches.platforms();
            case "map" -> mode.arena().names();
            default -> {
                StateKey<?> rule = mode.type().supportedRules().get(key);
                yield rule != null && rule.initial() instanceof Boolean ? List.of("true", "false") : List.of();
            }
        };
        return values.stream().map(v -> "-" + key + "=" + v).toList();
    }

    /** The request as the requester typed it, for the messages both sides read. */
    public @NotNull String describe() {
        return words.isEmpty() ? "" : " " + String.join(" ", words);
    }
}
