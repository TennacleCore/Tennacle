package tennacle.commands;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Grotto;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;
import tennacle.GameIds;
import tennacle.Msg;
import tennacle.games.Duels;

import java.util.List;
import java.util.stream.Collectors;

/** {@code /grotto} - DEV tooling, like {@link DevCommands}: chat over the api for the test environment
 *  (the {@code instances()} live map, spectate, {@link GameIds#derive} as {@code id}). Not a player surface. */
public final class GrottoCommands {

    private GrottoCommands() {}

    public static void register(ShardManager shards) {
        Command grotto = new Command("grotto");
        grotto.setDefaultExecutor((sender, ctx) -> list(sender, shards));
        grotto.addSyntax((sender, ctx) -> list(sender, shards), ArgumentType.Literal("list"));
        var target = ArgumentType.Word("player");
        grotto.addSyntax((sender, ctx) -> spectate(sender, ctx.get(target)), ArgumentType.Literal("spectate"), target);
        var code = ArgumentType.Word("code");
        var date = ArgumentType.Word("date");
        grotto.addSyntax((sender, ctx) -> gameId(sender, ctx.get(code), null), ArgumentType.Literal("id"), code);
        grotto.addSyntax((sender, ctx) -> gameId(sender, ctx.get(code), ctx.get(date)),
                ArgumentType.Literal("id"), code, date);
        MinecraftServer.getCommandManager().register(grotto);
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
        for (Game live : Duels.grotto().games()) {
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
            for (Grotto.InstanceView row : Duels.grotto().instances()) {
                for (Game game : row.games()) {
                    if (game.spectators().contains(p)) {
                        game.unspectate(p);
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
        game.spectate(p);
        Msg.tag(p, "grotto", "watching ", Msg.v(subject.getUsername()), "'s " + game.type().id()
                + " (", Msg.run("/grotto spectate off"), ")");
    }

    // marks a .bncl-backed row; foreign instances render bare
    private static final Component BNCL = Component.text("◆", NamedTextColor.GREEN, TextDecoration.BOLD);

    private static void list(CommandSender sender, ShardManager shards) {
        List<Grotto.InstanceView> rows = Duels.grotto().instances();
        if (rows.isEmpty()) {
            Msg.tag(sender, "grotto", "no live games or shard trees");
            return;
        }
        for (Grotto.InstanceView row : rows) {
            boolean idle = row.games().isEmpty() && row.roots().isEmpty();
            long m = row.quietMillis() / 60000;
            String state = idle ? " - idle " + (m > 0 ? m + "m" : row.quietMillis() / 1000 + "s") : " - active";
            if (row.arena() != null) {
                Msg.tag(sender, "grotto", Msg.v(row.arena().meta().id()), " ", BNCL, state);
            } else {
                Msg.tag(sender, "grotto", Msg.v(row.instance().getDimensionName()), state);
            }
            for (Game game : row.games()) {
                String players = game.members().stream()
                        .map(mem -> game.isGhost(mem) ? mem.getUsername() + " (ghost)" : mem.getUsername())
                        .collect(Collectors.joining(", "));
                Msg.tag(sender, "grotto", "  ", Msg.v(game.type().id() + "#" + game.code())
                                .hoverEvent(Component.text(game.id()))
                                .clickEvent(ClickEvent.copyToClipboard(game.id())),
                        " " + game.status().name().toLowerCase() + " t=" + game.tick() + " - ",
                        Msg.v(players.isEmpty() ? "empty" : players));
                String specs = game.spectators().stream().map(Player::getUsername).collect(Collectors.joining(", "));
                if (!specs.isEmpty()) Msg.tag(sender, "grotto", "    watching: ", Msg.v(specs));
            }
            for (Shard root : row.roots()) {
                String name = shards.nameOf(root);
                int under = descendants(shards, root);
                Msg.tag(sender, "grotto", "  tree ", Msg.v(name != null ? name : "unnamed"),
                        under > 0 ? " (+" + under + ")" : "");
            }
        }
    }

    private static int descendants(ShardManager shards, Shard root) {
        int n = 0;
        for (Shard shard : shards.shards()) {
            for (Shard up = shard.parent(); up != null; up = up.parent()) {
                if (up == root) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }
}
