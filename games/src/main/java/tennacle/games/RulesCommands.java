package tennacle.games;

import tennacle.boot.Mechanics;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.command.builder.arguments.ArgumentType;
import io.github.term4.polyp.config.PathEdits;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tennacle.commands.AppCommand;
import tennacle.access.Access;
import tennacle.commands.Commands;
import tennacle.commands.RulesCompletions;
/**
 * {@code /rules}: the running game's rules with their live values; {@code /rules <id|path> <value>} layers one
 * entry over the game right now - a rule by id ({@code outcome.score.target 3}) or a mechanics path
 * ({@code projectiles/minecraft:arrow/critDamage true}). Checked before it lands; a bad entry changes nothing.
 *
 * <p>Writes land on the whole game unless an {@code @team} or {@code @player} leads: {@code /rules @1 <id|path>
 * <value>} sets it for that side alone. A scoped path becomes a knob varying by subject inside the game's one
 * profile, so one seat's bow can pull differently without a profile of its own.
 */
public final class RulesCommands {

    /** Changing a live game's rules. */
    public static final String WRITE = "tennacle.staff.rules";

    private RulesCommands() {}

    public static void register() {
        AppCommand rules = Commands.player("rules", "The running game's rules, live.")
                .usage("/rules [package|id]", "read them, or one part, each marked with where its value comes from")
                .usage("/rules <code>", "another live game's, by the code its board prints")
                .usage("/rules <@player|#team>", "that seat or side alone")
                .usage("/rules paths [path]", "the mechanics tree a path can reach")
                .featured();
        rules.usage("/rules <id|path> <value>", "set one for the game", WRITE)
                .usage("/rules <@player|#team> <id|path> <value>", "for one seat or side only", WRITE)
                .usage("/rules [@player|#team] <id|path> unset", "drop it there: the scope above shows through", WRITE);
        var key = ArgumentType.Word("key");
        var value = ArgumentType.StringArray("value");
        key.setSuggestionCallback(RulesCompletions.key());
        value.setSuggestionCallback(RulesCompletions.tail(key, value));
        rules.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = watched(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game or watching one. ", Msg.v("/rules <code>"), " reads another.");
                return;
            }
            list(p, game, Scope.GAME, "");
        });
        rules.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = watched(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game or watching one. ", Msg.v("/rules <code>"), " reads another.");
                return;
            }
            String word = ctx.get(key);
            Game named = byCode(word);
            if (named != null) { // a board code names the game to read, wherever the reader is standing
                list(p, named, Scope.GAME, "");
                return;
            }
            if (word.equals("paths")) {
                paths(p, game, "");
                return;
            }
            Scope scope = scopeOf(game, word);
            if (scope != null) {
                list(p, game, scope, "");
                return;
            }
            if (word.startsWith("@") || word.startsWith("#")) {
                Msg.fail(p, nobody(word));
                return;
            }
            if (game.type().supportedRules().keySet().stream().noneMatch(id -> under(word, id))) {
                Msg.fail(p, "No rule named ", Msg.v(word), ".");
                return;
            }
            list(p, game, Scope.GAME, word);
        }, key);
        rules.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = watched(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game or watching one. ", Msg.v("/rules <code>"), " reads another.");
                return;
            }
            List<String> words = new ArrayList<>(List.of(ctx.get(value)));
            String id = ctx.get(key);
            if (id.equals("paths")) {
                paths(p, game, String.join("/", words).replace('.', '/'));
                return;
            }
            Scope scope = Scope.GAME;
            if (id.startsWith("@") || id.startsWith("#")) { // the whole game unless a seat or a side leads
                scope = scopeOf(game, id);
                if (scope == null) {
                    Msg.fail(p, nobody(id));
                    return;
                }
                if (words.isEmpty()) {
                    list(p, game, scope, "");
                    return;
                }
                id = words.removeFirst();
            }
            if (words.isEmpty() && !id.contains("/")) { // a name with no value reads it back, at whatever scope leads
                list(p, game, scope, id);
                return;
            }
            if (!Access.allows(p, WRITE)) { // anyone in a game or watching it reaches here
                Msg.fail(p, "You can't change a game's rules.");
                return;
            }
            if (!words.isEmpty() && words.getFirst().equals("=")) words.removeFirst(); // the listing prints "id = value"
            String raw = String.join(" ", words);
            if (raw.equalsIgnoreCase("unset")) {
                String where = scope == Scope.GAME ? "" : scopeName(game, scope) + " ";
                if (Matches.unset(game, scope, id)) Msg.ok(p, where + id + " unset. The scope above shows through.");
                else Msg.fail(p, "Nothing is set for " + where + id + ".");
                return;
            }
            Ruleset live = id.contains("/") ? Ruleset.named("live").path(scope, id, raw)
                    : Ruleset.named("live").rule(scope, id, raw);
            try {
                Matches.modify(game, live);
            } catch (RuntimeException e) {
                Msg.fail(p, e.getMessage());
                return;
            }
            Msg.ok(p, (scope == Scope.GAME ? "" : scopeName(game, scope) + " ") + id + " = ", Msg.v(raw));
        }, key, value);
        Commands.register(rules);
    }

    /** The rules under {@code prefix} ({@code ""} = all of them) with their live values at {@code scope}. */
    /** The game a reader means: the one they play, else the one they watch. A watcher reads rules too. */
    private static @Nullable Game watched(Player p) {
        Game playing = Game.of(p);
        return playing != null ? playing : Matches.grotto().spectating(p);
    }

    /** A live game by the code its board prints - never a rule id, so the two never read as each other. */
    private static @Nullable Game byCode(String word) {
        for (Game game : Matches.grotto().games()) {
            if (game.code().equalsIgnoreCase(word)) return game;
        }
        return null;
    }

    private static void list(Player p, Game game, Scope scope, String prefix) {
        Map<String, StateKey<?>> supported = game.type().supportedRules();
        // sorted: ids lead with their package, so the listing groups itself
        List<String> ids = new java.util.TreeSet<>(supported.keySet()).stream().filter(id -> under(prefix, id)).toList();
        String where = scope == Scope.GAME ? game.type().id() : scopeName(game, scope);
        Msg.say(p, where + (prefix.isEmpty() ? "" : " " + prefix) + ": " + ids.size() + (ids.size() == 1 ? " rule" : " rules"));
        if (prefix.isEmpty()) {
            Msg.say(p, "  preset ", Msg.v(Matches.platformNameOf(game)), ": rules ", Msg.v(Matches.rulesNameOf(game)),
                    ", mechanics ", Msg.v(Matches.mechanicsNameOf(game)), ", look ", Msg.v(Matches.lookNameOf(game)));
        }
        for (String id : ids) {
            StateKey<?> key = supported.get(id);
            List<String> words = RulesCompletions.words(id);
            Msg.say(p, "  " + id + " = ", valued(game, scope, key, id),
                    words.isEmpty() ? "" : "  any of " + String.join(", ", words));
        }
        String lead = scope == Scope.GAME ? "" : scopeName(game, scope) + " ";
        List<String> packages = packagesUnder(ids, prefix);
        if (!packages.isEmpty()) {
            var line = Component.text("  narrow: ");
            for (int i = 0; i < packages.size(); i++) {
                if (i > 0) line = line.append(Component.text(", "));
                line = line.append(Msg.link(packages.get(i), "/rules " + lead + packages.get(i), "the " + packages.get(i) + " rules"));
            }
            Msg.say(p, line);
        }
        if (prefix.isEmpty()) {
            Msg.say(p, "Mechanics paths take a value too: ", Msg.run("/rules paths"), ".");
            Msg.say(p, "One seat or side only: ", Msg.run("/rules <@player|#team> <id|path> <value>"), ".");
        }
    }

    // the default reads dim; anything chosen reads as a value with a star, its hover naming the level that chose it
    private static Component valued(Game game, Scope scope, StateKey<?> key, String id) {
        Object value = game.rules().resolve(scope, key);
        String from = from(game, scope, key, id);
        if (from == null) return Msg.THEME.dim(value).hoverEvent(HoverEvent.showText(Msg.THEME.prose("the default")));
        return Msg.v(value).append(Msg.THEME.accent(" *").hoverEvent(HoverEvent.showText(Msg.THEME.prose(from))));
    }

    /**
     * Which level hands {@code scope} the value it reads, and whether it is yours to drop; {@code null} = the
     * key's own default. A seat set to what its side already says stops following the side, and reads the same
     * until the side moves without it - the one thing the value alone cannot show. The fold seeds the launch
     * ruleset as cells too, so a live entry is what tells a change made in this game from the one it started with.
     */
    private static @Nullable String from(Game game, Scope scope, StateKey<?> key, String id) {
        if (Matches.overrides(game, scope, id)) return "set here, in this game";
        if (game.state().lookup(scope, key) != null) return "as launched: the ruleset, or a flag";
        if (scope instanceof Scope.Slot s) {
            Slot seat = game.roster().slot(s.index());
            if (seat != null && !seat.team().isEmpty() && game.state().lookup(Scope.team(seat.team()), key) != null) {
                return "from #" + seat.team();
            }
        }
        if (scope == Scope.GAME) return null;
        if (Matches.overrides(game, Scope.GAME, id)) return "from the game, set here";
        if (game.state().lookup(Scope.GAME, key) != null) return "from the game, as launched";
        return null;
    }

    /** A dotted id under {@code prefix}: whole segments, so {@code flow} takes flow.time.limit and {@code flo} nothing. */
    private static boolean under(String prefix, String id) {
        return prefix.isEmpty() || id.equals(prefix) || id.startsWith(prefix + ".");
    }

    /** The next segment down from {@code prefix}, for the ids that have one - the listing's own way further in. */
    private static List<String> packagesUnder(List<String> ids, String prefix) {
        int at = prefix.isEmpty() ? 0 : prefix.length() + 1;
        java.util.SortedSet<String> out = new java.util.TreeSet<>();
        for (String id : ids) {
            int dot = id.indexOf('.', at);
            if (dot > 0) out.add(id.substring(0, dot));
        }
        return List.copyOf(out);
    }

    /** One level of the mechanics tree: what is addressable under {@code path}, and what it holds right now. */
    private static void paths(Player p, Game game, String path) {
        List<PathEdits.Step> steps;
        try {
            steps = PathEdits.browse(path, k -> Mechanics.polyp.profiles().resolveWorld(game.shard(), k));
        } catch (RuntimeException e) {
            Msg.fail(p, e.getMessage());
            return;
        }
        Msg.say(p, (path.isEmpty() ? "mechanics" : path) + ": " + steps.size() + (steps.size() == 1 ? " step" : " steps"));
        List<String> unset = new ArrayList<>();
        for (PathEdits.Step step : steps) {
            String under = path.isEmpty() ? step.name() : path + "/" + step.name();
            if (step.more() || step.type() == null) {
                Msg.say(p, "  ", Msg.link(step.name(), "/rules paths " + under, "browse " + under),
                        step.type() == null ? "" : "  " + step.type());
            } else if (step.value() != null) {
                Msg.say(p, "  " + step.name() + " = ", Msg.v(step.value()), "  " + step.type());
            } else {
                unset.add(step.name()); // a line each would bury the ones this profile actually sets
            }
        }
        if (!unset.isEmpty()) Msg.say(p, "  inherited: ", Msg.v(String.join(", ", unset)));
        if (!path.isEmpty()) Msg.say(p, "One knob: ", Msg.run("/rules paths " + path + "/<knob>"), ".");
    }

    /** {@code #1} a side, {@code @Term4} that member's seat - a player may be named like a team; anything else is nobody. */
    private static @Nullable Scope scopeOf(Game game, String who) {
        String name = who.substring(1);
        if (who.startsWith("#")) {
            return game.roster().teams().stream().anyMatch(t -> t.id().equals(name)) ? Scope.team(name) : null;
        }
        if (!who.startsWith("@")) return null;
        Player member = game.memberNamed(name);
        Slot seat = member != null ? game.roster().slotOf(member) : null;
        return seat != null ? Scope.slot(seat.index()) : null;
    }

    private static String nobody(String who) {
        return (who.startsWith("#") ? "No team " : "No player named ") + who.substring(1) + " in this game.";
    }

    private static String scopeName(Game game, Scope scope) {
        return switch (scope) {
            case Scope.Team t -> "#" + t.id();
            case Scope.Slot s -> {
                String owner = game.roster().ownerOf(game.roster().slot(s.index()));
                yield "@" + (owner != null ? owner : "seat " + s.index());
            }
            default -> game.type().id();
        };
    }
}
