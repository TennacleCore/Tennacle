package tennacle.games;

import tennacle.games.watch.Spectators;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.polyp.platform.player.PlayerNames;
import tennacle.GameIds;
import tennacle.Msg;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.Grotto;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
/** {@code /grotto} - DEV tooling, like {@code DevCommands}: chat over the api for the test environment (spectate,
 *  {@link GameIds#derive} as {@code id}). Not a player surface; the live map is {@code /audit}. */
public final class GrottoCommands {

    private GrottoCommands() {}

    public static void register() {
        AppCommand grotto = Commands.dev("grotto", "Spectate a player's game; derive a game id from a board code.")
                .usage("/grotto spectate <player>", "follow them into their game")
                .usage("/grotto id <code> [date]", "derive a game id from a board code");
        var target = ArgumentType.Word("player");
        target.setSuggestionCallback(PlayerNames.suggestAll());
        grotto.addSyntax((sender, ctx) -> spectate(sender, ctx.get(target)), ArgumentType.Literal("spectate"), target);
        var code = ArgumentType.Word("code");
        var date = ArgumentType.Word("date");
        grotto.addSyntax((sender, ctx) -> gameId(sender, ctx.get(code), null), ArgumentType.Literal("id"), code);
        grotto.addSyntax((sender, ctx) -> gameId(sender, ctx.get(code), ctx.get(date)),
                ArgumentType.Literal("id"), code, date);
        Commands.register(grotto);
    }

    /** {@link GameIds#derive} over chat, for testing: board code + printed date (today when omitted). */
    private static void gameId(CommandSender sender, String code, @Nullable String date) {
        java.time.LocalDate day;
        try {
            day = date == null ? java.time.LocalDate.now() : java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            Msg.err(sender, "grotto", "dates read like ", Msg.v("2026-08-30"));
            return;
        }
        String id;
        try {
            id = GameIds.derive(day, code);
        } catch (IllegalArgumentException e) {
            Msg.err(sender, "grotto", "that's not a board code");
            return;
        }
        if (id == null) {
            Msg.err(sender, "grotto", "no launch minute of ", Msg.v(day.toString()), " fits that code");
            return;
        }
        Msg.tag(sender, "grotto", Msg.v(code), " on " + day + " - ",
                Msg.v(id).clickEvent(ClickEvent.copyToClipboard(id))
                        .hoverEvent(Component.text("click to copy")));
        for (Game live : Matches.grotto().games()) {
            if (live.id().equals(id)) {
                Msg.tag(sender, "grotto", "  matches the live ", Msg.v(live.type().id() + "#" + live.code()));
                return;
            }
        }
        Msg.tag(sender, "grotto", "  not a live game - ended already, or the date/code is off");
    }

    /** Follows {@code name}'s game from anywhere - {@code Game.spectate} transfers and scopes the view; "off" returns. */
    private static void spectate(CommandSender sender, String name) {
        if (!(sender instanceof Player p)) return;
        if (name.equals("off")) {
            for (Grotto.InstanceView row : Matches.grotto().instances()) {
                for (Game game : row.games()) {
                    if (game.spectators().contains(p)) {
                        Spectators.exit(p); // the app's exit: body and HUD back, then the game's unspectate
                        Msg.tag(p, "grotto", "no longer watching");
                        return;
                    }
                }
            }
            Msg.tag(p, "grotto", "you weren't watching anything");
            return;
        }
        if (Game.of(p) != null) {
            Msg.err(p, "grotto", "you're mid-game - finish or leave first");
            return;
        }
        Player subject = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (subject == null) {
            Msg.err(p, "grotto", "no player named ", Msg.v(name));
            return;
        }
        Game game = Game.of(subject);
        if (game == null) {
            Msg.err(p, "grotto", Msg.v(subject.getUsername()), " isn't in a game");
            return;
        }
        if (!Spectating.allowed(game)) {
            Msg.err(p, "grotto", "that game doesn't allow spectators");
            return;
        }
        Spectators.exit(p); // a view elsewhere ends first
        Spectators.enter(game, p, null); // the same entry as /spectate: body swapped, HUD, tab
        Msg.tag(p, "grotto", "watching ", Msg.v(subject.getUsername()), "'s " + game.type().id()
                + " (", Msg.run("/grotto spectate off"), ")");
    }
}
