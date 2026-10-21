package tennacle.games.practice;

import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** The bot's settings: left-click steps a value up, right-click down. */
final class DrillMenu {

    private DrillMenu() {}

    static void open(Player viewer, Consumer<Player> changed) {
        Screen.Builder screen = Screen.rows(5).title(Component.text("Bot Settings")).close(40);
        setting(screen, 10, Material.IRON_SWORD, "Hit Type", "Whether the bot sprints into its hits.",
                d -> d.sprint() ? "Sprint hit" : "Normal hit", (d, up) -> d.withSprint(!d.sprint()), changed);
        setting(screen, 11, Material.STICK, "Knockback Stick", "The bot hits with a Knockback stick of this level.",
                d -> d.knockback() == 0 ? "Off" : "Knockback " + roman(d.knockback()),
                (d, up) -> d.withKnockback(step(d.knockback(), up, 0, Drill.MAX_KNOCKBACK)), changed);
        setting(screen, 12, Material.ARROW, "Closest Reach", "The bot hits from somewhere between its closest and farthest reach.",
                d -> blocks(d.reachMin()), (d, up) -> d.withReachMin(reach(d.reachMin(), up)), changed);
        setting(screen, 13, Material.BOW, "Farthest Reach", "The bot hits from somewhere between its closest and farthest reach.",
                d -> blocks(d.reachMax()), (d, up) -> d.withReachMax(reach(d.reachMax(), up)), changed);
        setting(screen, 14, Material.PAPER, "Hits", "How many hits the bot lands before it stops.",
                d -> d.endless() ? "Until you stop it" : String.valueOf(d.hits()),
                (d, up) -> d.withHits(step(d.hits(), up, 0, Drill.MAX_HITS)), changed);
        setting(screen, 15, Material.CLOCK, "Shortest Wait", "Ticks the bot waits once you can be hit again.",
                d -> ticks(d.delayMin()), (d, up) -> d.withDelayMin(step(d.delayMin(), up, 0, Drill.MAX_DELAY)), changed);
        setting(screen, 16, Material.CLOCK, "Longest Wait", "Ticks the bot waits once you can be hit again.",
                d -> ticks(d.delayMax()), (d, up) -> d.withDelayMax(step(d.delayMax(), up, 0, Drill.MAX_DELAY)), changed);
        setting(screen, 28, Material.REPEATER, "Bot Latency", "How late the bot's hits land and its knockback reaches it.",
                d -> d.latency() * 50 + " ms", (d, up) -> d.withLatency(step(d.latency(), up, 0, Drill.MAX_LATENCY)), changed);
        setting(screen, 30, Material.RABBIT_FOOT, "Bot Speed", "The bot's Speed effect.",
                d -> level(d.botSpeed()), (d, up) -> d.withBotSpeed(step(d.botSpeed(), up, 0, Drill.MAX_LEVEL)), changed);
        setting(screen, 32, Material.SUGAR, "Your Speed", "Your Speed effect.",
                d -> level(d.speed()), (d, up) -> d.withSpeed(step(d.speed(), up, 0, Drill.MAX_LEVEL)), changed);
        setting(screen, 34, Material.SOUL_SAND, "Your Slowness", "Your Slowness effect.",
                d -> level(d.slowness()), (d, up) -> d.withSlowness(step(d.slowness(), up, 0, Drill.MAX_LEVEL)), changed);
        Screens.open(viewer, screen.build());
    }

    private static void setting(Screen.Builder screen, int slot, Material icon, String name, String text,
                                Function<Drill, String> value, BiFunction<Drill, Boolean, Drill> step,
                                Consumer<Player> changed) {
        screen.press(slot, v -> Entry.of(icon).name(name).text(text).fact("Currently", value.apply(Drill.of(v)))
                .action("Left-click for more, right-click for less!").render(v), (v, click) -> {
            Drill.set(v, step.apply(Drill.of(v), !(click instanceof Click.Right)));
            changed.accept(v);
            Screens.repaint(v);
            return null;
        });
    }

    private static int step(int value, boolean up, int low, int high) {
        return Math.max(low, Math.min(high, value + (up ? 1 : -1)));
    }

    private static double reach(double value, boolean up) {
        return Math.max(Drill.REACH_LOW, Math.min(Drill.REACH_HIGH, value + (up ? Drill.REACH_STEP : -Drill.REACH_STEP)));
    }

    private static String blocks(double reach) {
        return String.format(Locale.ROOT, "%.2f blocks", reach);
    }

    private static String ticks(int ticks) {
        return ticks == 1 ? "1 tick" : ticks + " ticks";
    }

    private static String level(int level) {
        return level == 0 ? "None" : roman(level);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
