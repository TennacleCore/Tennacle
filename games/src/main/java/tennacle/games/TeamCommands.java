package tennacle.games;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.looks.Palette;
import tennacle.Msg;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
/**
 * {@code /team} - which side is which, in seat order. A team is an ordinal and its color is only the convention
 * an unauthored map falls back to, so the two are worth printing together: the id is what {@code /rules @1} and a
 * marker group take, the color is what a player actually sees. {@code /side} (dev) puts a player on a side live,
 * or trades two players' sides, through {@link Game#reseat}: everything at their seats goes with them.
 */
public final class TeamCommands {

    private TeamCommands() {}

    public static void register() {
        AppCommand team = Commands.player("team", "Which side is which, and who is on it.");
        team.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) game = Matches.grotto().spectating(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game.");
                return;
            }
            Slot mine = game.roster().slotOf(p);
            for (Team side : game.roster().teams()) {
                List<String> members = new ArrayList<>();
                for (int i = 0; game.roster().slot(i) != null; i++) {
                    Slot seat = game.roster().slot(i);
                    String owner = game.roster().ownerOf(seat);
                    if (owner != null && seat.team().equals(side.id())) members.add(owner);
                }
                String who = members.isEmpty() ? "empty" : String.join(", ", members);
                boolean ours = mine != null && mine.team().equals(side.id());
                Msg.say(p, Component.text(Palette.name(side), side.color()), ": ",
                        Msg.v(who), ours ? " (yours)" : "");
            }
        });
        Commands.register(team);

        AppCommand side = Commands.staff("side", "Put a player on a side, live.")
                .usage("/side <player> <team>", "onto a free seat of that side; the id, or its color")
                .usage("/side swap <player> <player>", "the two trade sides");
        var who = ArgumentType.Word("player");
        who.setSuggestionCallback(PlayerNames.suggestAll());
        var other = ArgumentType.Word("other");
        other.setSuggestionCallback(PlayerNames.suggestAll());
        var which = ArgumentType.Word("team");
        side.addSyntax((sender, ctx) -> swap(sender, ctx.get(who), ctx.get(other)), ArgumentType.Literal("swap"), who, other);
        side.addSyntax((sender, ctx) -> put(sender, ctx.get(who), ctx.get(which)), who, which);
        Commands.register(side);
    }

    private static void put(CommandSender sender, String name, String teamWord) {
        Player target = seated(sender, name);
        if (target == null) return;
        Game game = Game.of(target);
        Team team = teamOf(game, teamWord);
        if (team == null) {
            Msg.fail(sender, "No side ", Msg.v(teamWord), " in that game - one of ",
                    Msg.v(String.join(", ", game.roster().teams().stream().map(t -> t.id() + " (" + Palette.name(t) + ")").toList())), ".");
            return;
        }
        move(sender, game, Map.of(target, team.id()));
    }

    private static void swap(CommandSender sender, String name, String otherName) {
        Player a = seated(sender, name);
        if (a == null) return;
        Player b = seated(sender, otherName);
        if (b == null) return;
        Game game = Game.of(a);
        if (Game.of(b) != game) {
            Msg.fail(sender, Msg.v(a.getUsername()), " and ", Msg.v(b.getUsername()), " are not in the same game.");
            return;
        }
        move(sender, game, Map.of(a, Match.teamOf(game, b), b, Match.teamOf(game, a)));
    }

    // the move, then the game hears who sits where now
    private static void move(CommandSender sender, Game game, Map<Player, String> to) {
        try {
            game.reseat(to);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Msg.fail(sender, e.getMessage());
            return;
        }
        to.forEach((player, teamId) -> {
            Team team = game.roster().team(teamId);
            Match.say(game, Component.text(player.getUsername(), team.color())
                    .append(Msg.THEME.prose(" is on ")).append(Component.text(Palette.name(team), team.color()))
                    .append(Msg.THEME.prose(" now.")));
            Msg.ok(sender, Msg.v(player.getUsername()), " is on ", Component.text(Palette.name(team), team.color()), ".");
        });
    }

    private static @Nullable Player seated(CommandSender sender, String name) {
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (target == null) {
            Msg.fail(sender, "No player named ", Msg.v(name), ".");
            return null;
        }
        Game game = Game.of(target);
        if (game == null || game.roster().slotOf(target) == null) {
            Msg.fail(sender, Msg.v(target.getUsername()), " holds no seat in a game.");
            return null;
        }
        return target;
    }

    // the ordinal, with or without the # a rules scope wears, or the color the look gives it
    private static @Nullable Team teamOf(Game game, String word) {
        String id = word.startsWith("#") ? word.substring(1) : word;
        for (Team team : game.roster().teams()) {
            if (team.id().equalsIgnoreCase(id) || Palette.name(team).equalsIgnoreCase(id)) return team;
        }
        return null;
    }
}
