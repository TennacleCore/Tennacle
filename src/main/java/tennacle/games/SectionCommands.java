package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import io.github.term4.bloom.Session;
import io.github.term4.bloom.command.Say;
import io.github.term4.bloom.edit.Layout;
import io.github.term4.bloom.edit.Templates;
import net.minestom.server.command.CommandSender;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /arena section} - the team arms of a map built from a symmetric starter, which is a shape our starters
 * have and an arbitrary arena does not. Registered rather than built into {@code /arena} for that reason.
 */
public final class SectionCommands {

    private static final String HELP = "add|remove|reset <team> | extend|set|deepen <team|all> <n>";

    private SectionCommands() {}

    public static void install() {
        Bloom.command("section", HELP, SectionCommands::section);
    }

    private static void section(CommandSender sender, Session session, String[] value) throws IOException {
        String[] rest = Arrays.copyOfRange(value, Math.min(1, value.length), value.length);
        switch (value.length > 0 ? value[0] : "") {
            case "remove" -> {
                if (Say.requireValue(sender, rest, "a team")) return;
                int removed = Layout.removeSection(session, Bloom.templates(), rest[0]);
                if (removed == 0) Say.err(sender, "no markers in group '" + rest[0] + "'");
                else Say.msg(sender, rest[0] + " removed: " + removed + " markers dropped, their blocks cleared");
            }
            case "deepen" -> {
                if (rest.length < 2) {
                    Say.err(sender, "usage: /arena section deepen <team|all> <±n> (positive = deeper)");
                    return;
                }
                int n = Integer.parseInt(rest[1]);
                List<String> targets = rest[0].equals("all")
                        ? new ArrayList<>(Layout.sections(session)) : new ArrayList<>(List.of(rest[0]));
                if (rest[0].equals("all")) targets.add("center");
                StringBuilder out = new StringBuilder();
                int followed = 0;
                for (String target : targets) {
                    int thick = Layout.deepen(session, target, n);
                    followed += Templates.followed;
                    if (!out.isEmpty()) out.append(", ");
                    out.append(target).append(" ").append(thick);
                }
                Say.msg(sender, "thickness: " + out + " (the deck stays - depth grows downward)"
                        + (followed > 0 ? " - " + followed + " region" + (followed == 1 ? "" : "s") + " followed" : ""));
            }
            case "extend", "set" -> {
                if (rest.length < 2) {
                    Say.err(sender, "usage: /arena section " + value[0] + " <team|all> <"
                            + (value[0].equals("set") ? "length" : "±n") + ">");
                    return;
                }
                List<String> colors = rest[0].equals("all") ? Layout.sections(session) : List.of(rest[0]);
                if (colors.isEmpty()) {
                    Say.err(sender, "no sections on this map");
                    return;
                }
                int n = Integer.parseInt(rest[1]);
                StringBuilder out = new StringBuilder();
                for (String color : colors) {
                    int current = Layout.extendSection(session, color, 0);
                    int grown = Layout.extendSection(session, color,
                            (value[0].equals("set") ? n : current + n) - current);
                    if (!out.isEmpty()) out.append(", ");
                    out.append(color).append(" ").append(grown);
                }
                Say.msg(sender, "sections: " + out + " blocks long");
            }
            case "add" -> {
                if (Say.requireValue(sender, rest, "a team")) return;
                int copied = Layout.addSection(session, Bloom.starter(session), rest[0]);
                Say.msg(sender, rest[0] + " added from the starter (" + copied + " markers)");
            }
            case "reset" -> {
                Arena starter = Bloom.starter(session);
                List<String> colors = rest.length > 0 ? List.of(rest[0]) : Layout.starterSections(starter);
                for (String color : colors) {
                    Layout.removeSection(session, Bloom.templates(), color);
                    Layout.addSection(session, starter, color);
                }
                Say.msg(sender, "reset from the starter: " + String.join(", ", colors));
            }
            default -> Say.msg(sender, "usage: /arena section " + HELP);
        }
    }

}
