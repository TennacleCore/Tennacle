package tennacle.looks;

import tennacle.commands.Completions;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.Msg;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;
import tennacle.PlayerPrefs;

import io.github.term4.canvas.tab.TabList;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a mode's seat counters a player's tab menu lists ({@code /tabstats}): the mode declares what a seat
 * can show ({@link Mode#tabStats}), the player picks from it by label, and the house tab renders the pick in the
 * player's order. No pick = everything the mode offers. A label is picked once for every mode that has it, and
 * the pick persists with the player's other preferences.
 */
public final class TabStats {

    public static final VaultKey<String> CHOICE = VaultKey.of("tab:stats", Codec.STRING, "");
    private static final PlayerPrefs.Entry<String, List<String>> PICK = PlayerPrefs.register(CHOICE,
            stored -> stored.isEmpty() ? List.of() : List.of(stored.split(",")),
            picked -> String.join(",", picked));

    private TabStats() {}

    /** The counters {@code viewer}'s tab lists in {@code mode}: their pick in their order, else the mode's whole list. */
    public static @NotNull List<Mode.SeatStat> chosen(@NotNull Player viewer, @NotNull Mode mode) {
        List<String> pick = PICK.of(viewer.getUuid());
        if (pick == null || pick.isEmpty()) return mode.tabStats();
        List<Mode.SeatStat> out = new ArrayList<>();
        for (String label : pick) {
            for (Mode.SeatStat stat : mode.tabStats()) {
                if (stat.label().equalsIgnoreCase(label)) out.add(stat);
            }
        }
        return out;
    }

    /** Every label some mode offers - what a pick is checked against. */
    static @NotNull List<String> labels() {
        Set<String> out = new LinkedHashSet<>();
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode != null) mode.tabStats().forEach(stat -> out.add(stat.label()));
        }
        return List.copyOf(out);
    }

    public static void register() {
        AppCommand tabstats = Commands.player("tabstats", "Which of your stats the tab menu lists.")
                .usage("/tabstats", "your pick, and what each mode offers")
                .usage("/tabstats <stat> [stat ...]", "list these, in this order")
                .usage("/tabstats all", "everything a mode offers");
        var stats = ArgumentType.StringArray("stats");
        stats.setSuggestionCallback(Completions.of(TabStats::labels));
        tabstats.setDefaultExecutor((sender, ctx) -> show(sender));
        tabstats.addSyntax((sender, ctx) -> pick(sender, List.of(ctx.get(stats))), stats);
        Commands.register(tabstats);
    }

    private static void show(CommandSender sender) {
        if (!(sender instanceof Player p)) return;
        List<String> pick = PICK.of(p.getUuid());
        Msg.say(p, "Your tab lists ",
                Msg.v(pick == null || pick.isEmpty() ? "everything a mode offers" : String.join(", ", pick)), ".");
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode == null || mode.tabStats().isEmpty()) continue;
            Msg.say(p, "  " + name + ": ",
                    Msg.v(String.join(", ", mode.tabStats().stream().map(Mode.SeatStat::label).toList())));
        }
        Msg.say(p, "Pick with ", Msg.run("/tabstats <stat> [stat ...]"), ", or ", Msg.run("/tabstats all"), ".");
    }

    private static void pick(CommandSender sender, List<String> words) {
        if (!(sender instanceof Player p)) return;
        List<String> chosen = new ArrayList<>();
        if (!(words.size() == 1 && words.getFirst().equalsIgnoreCase("all"))) {
            List<String> known = labels();
            for (String word : words) {
                String label = known.stream().filter(l -> l.equalsIgnoreCase(word)).findFirst().orElse(null);
                if (label == null) {
                    Msg.fail(p, "No stat named ", Msg.v(word), ". Stats: ", Msg.v(String.join(", ", known)), ".");
                    return;
                }
                if (!chosen.contains(label)) chosen.add(label);
            }
        }
        PICK.set(p, chosen);
        Msg.ok(p, "Your tab lists ", Msg.v(chosen.isEmpty() ? "everything a mode offers" : String.join(", ", chosen)), ".");
        TabList.refresh(p);
    }
}
