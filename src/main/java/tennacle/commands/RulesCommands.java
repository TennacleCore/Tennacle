package tennacle.commands;

import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

import java.util.Map;

/**
 * {@code /rules}: the running game's rules with their live values; {@code /rules <id|path> <value>} layers one
 * entry over the game right now - a rule by id ({@code score:target 3}) or a mechanics path
 * ({@code projectiles/minecraft:arrow/critDamage true}). Checked before it lands; a bad entry changes nothing.
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
            for (StateKey<?> k : supported.values()) {
                Msg.tag(p, "rules", "  " + k.id() + " = ", Msg.v(game.rules().resolve(Scope.GAME, k)));
            }
        });
        rules.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) {
                Msg.err(p, "rules", "you're not in a game");
                return;
            }
            String id = ctx.get(key);
            String raw = String.join(" ", ctx.get(value));
            Ruleset live = id.contains("/") ? Ruleset.named("live").path(id, raw) : Ruleset.named("live").rule(id, raw);
            try {
                Matches.modify(game, live);
            } catch (RuntimeException e) {
                Msg.err(p, "rules", e.getMessage());
                return;
            }
            Msg.tag(p, "rules", id + " = ", Msg.v(raw));
        }, key, value);
        MinecraftServer.getCommandManager().register(rules);
    }
}
