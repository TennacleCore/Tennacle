package tennacle.commands;

import tennacle.Mechanics;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import io.github.term4.polyp.config.PathEdits;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private RulesCommands() {}

    public static void register() {
        Command rules = new Command("rules");
        var key = ArgumentType.Word("key");
        var value = ArgumentType.StringArray("value");
        rules.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) {
                Msg.err(p, "rules", "you're not in a game");
                return;
            }
            Map<String, StateKey<?>> supported = game.type().supportedRules();
            Msg.tag(p, "rules", game.type().id() + ": " + supported.size() + " rule(s)");
            list(p, game, Scope.GAME);
        });
        rules.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) {
                Msg.err(p, "rules", "you're not in a game");
                return;
            }
            String word = ctx.get(key);
            if (word.equals("paths")) {
                paths(p, game, "");
                return;
            }
            Scope scope = scopeOf(game, word);
            if (scope == null) {
                Msg.err(p, "rules", "no team or player named ", Msg.v(word.startsWith("@") ? word.substring(1) : word));
                return;
            }
            list(p, game, scope);
        }, key);
        rules.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) {
                Msg.err(p, "rules", "you're not in a game");
                return;
            }
            List<String> words = new ArrayList<>(List.of(ctx.get(value)));
            String id = ctx.get(key);
            if (id.equals("paths")) {
                paths(p, game, String.join("/", words).replace('.', '/'));
                return;
            }
            Scope scope = Scope.GAME;
            if (id.startsWith("@")) { // the whole game unless a side or a seat leads
                scope = scopeOf(game, id);
                if (scope == null) {
                    Msg.err(p, "rules", "no team or player named ", Msg.v(id.substring(1)));
                    return;
                }
                if (words.isEmpty()) {
                    Msg.err(p, "rules", "usage: ", Msg.run("/rules " + id + " <id|path> <value>"));
                    return;
                }
                id = words.removeFirst();
            }
            if (!words.isEmpty() && words.getFirst().equals("=")) words.removeFirst(); // the listing prints "id = value"
            String raw = String.join(" ", words);
            Ruleset live = id.contains("/") ? Ruleset.named("live").path(scope, id, raw)
                    : Ruleset.named("live").rule(scope, id, raw);
            try {
                Matches.modify(game, live);
            } catch (RuntimeException e) {
                Msg.err(p, "rules", e.getMessage());
                return;
            }
            Msg.tag(p, "rules", (scope == Scope.GAME ? "" : scopeName(game, scope) + " ") + id + " = ", Msg.v(raw));
        }, key, value);
        MinecraftServer.getCommandManager().register(rules);
    }

    private static void list(Player p, Game game, Scope scope) {
        Map<String, StateKey<?>> supported = game.type().supportedRules();
        String where = scope == Scope.GAME ? game.type().id() : scopeName(game, scope);
        Msg.tag(p, "rules", where + ": " + supported.size() + " rule(s)");
        // sorted: ids lead with their package, so the listing groups itself
        for (String id : new java.util.TreeSet<>(supported.keySet())) {
            Msg.tag(p, "rules", "  " + id + " = ", Msg.v(game.rules().resolve(scope, supported.get(id))));
        }
        Msg.tag(p, "rules", "mechanics paths take a value too - ", Msg.run("/rules paths"));
        Msg.tag(p, "rules", "one side or seat only: ", Msg.run("/rules @<team|player> <id|path> <value>"));
    }

    /** One level of the mechanics tree: what is addressable under {@code path}, and what it holds right now. */
    private static void paths(Player p, Game game, String path) {
        List<PathEdits.Step> steps;
        try {
            steps = PathEdits.browse(path, k -> Mechanics.polyp.profiles().resolveWorld(game.shard(), k));
        } catch (RuntimeException e) {
            Msg.err(p, "rules", e.getMessage());
            return;
        }
        Msg.tag(p, "rules", (path.isEmpty() ? "mechanics" : path) + ": " + steps.size() + " step(s)");
        List<String> unset = new ArrayList<>();
        for (PathEdits.Step step : steps) {
            String under = path.isEmpty() ? step.name() : path + "/" + step.name();
            if (step.more() || step.type() == null) {
                Msg.tag(p, "rules", "  ", Msg.link(step.name(), "/rules paths " + under, "browse " + under),
                        step.type() == null ? "" : "  " + step.type());
            } else if (step.value() != null) {
                Msg.tag(p, "rules", "  " + step.name() + " = ", Msg.v(step.value()), "  " + step.type());
            } else {
                unset.add(step.name()); // a line each would bury the ones this profile actually sets
            }
        }
        if (!unset.isEmpty()) Msg.tag(p, "rules", "  inherited: ", Msg.v(String.join(", ", unset)));
        if (!path.isEmpty()) Msg.tag(p, "rules", "one knob: ", Msg.run("/rules paths " + path + "/<knob>"));
    }

    /** {@code @1} a side, {@code @Term4} that member's seat; anything else is nobody. */
    private static @Nullable Scope scopeOf(Game game, String who) {
        if (!who.startsWith("@")) return null;
        String name = who.substring(1);
        if (game.roster().teams().stream().anyMatch(t -> t.id().equals(name))) return Scope.team(name);
        Player member = game.memberNamed(name);
        Slot seat = member != null ? game.roster().slotOf(member) : null;
        return seat != null ? Scope.slot(seat.index()) : null;
    }

    private static String scopeName(Game game, Scope scope) {
        return switch (scope) {
            case Scope.Team t -> "@" + t.id();
            case Scope.Slot s -> {
                String owner = game.roster().ownerOf(game.roster().slot(s.index()));
                yield "@" + (owner != null ? owner : "seat " + s.index());
            }
            default -> game.type().id();
        };
    }
}
