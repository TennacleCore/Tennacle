package tennacle.games.practice;

import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import tennacle.games.practice.Drill.Knob;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** The bot's settings, a screen a topic: left-click steps a value up, right-click down. */
final class DrillMenu {

    private record Spec(Knob knob, Material icon, String name, String text, IntFunction<String> value) {}

    private static final int[] TICK_SLOTS = {28, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42};

    private DrillMenu() {}

    static void open(Player viewer, Consumer<Player> changed) {
        Screen.Builder screen = Screen.rows(4).title(Component.text("Bot Settings")).close(31);
        topic(screen, 10, Material.IRON_SWORD, "Hitting", "How the bot hits: sprint or not, its stick, its reach.",
                v -> hitting(v, changed));
        topic(screen, 11, Material.CLOCK, "Timing", "How many hits, and how long it waits between them.",
                v -> timing(v, changed));
        topic(screen, 12, Material.GOLDEN_SWORD, "Hit Selecting", "Whether it waits for you to hit first, and how it counters.",
                v -> selecting(v, changed));
        topic(screen, 13, Material.FEATHER, "Reducing", "When and how fast it clicks while it takes knockback.",
                v -> reducing(v, changed));
        topic(screen, 14, Material.REPEATER, "Latency", "The bot's ping, and how it spikes.",
                v -> latency(v, changed));
        topic(screen, 15, Material.POTION, "Effects", "Speed and Slowness, the bot's and yours.",
                v -> effects(v, changed));
        topic(screen, 16, Material.LEATHER_BOOTS, "Movement and Aim", "Strafing, the distance it keeps, and how it aims.",
                v -> movement(v, changed));
        screen.press(22, v -> Entry.of(Material.BARRIER).name("Back to Defaults").text("Puts every setting back.")
                .action("Click to reset!").danger().render(v), (v, click) -> {
            Drill.set(v, Drill.DEFAULT);
            changed.accept(v);
            Screens.repaint(v);
            return null;
        });
        Screens.open(viewer, screen.build());
    }

    private static void hitting(Player viewer, Consumer<Player> changed) {
        page(viewer, "Hitting", 4, changed,
                new int[]{10, 11, 12, 14, 15, 16, 28, 29},
                new Spec(Knob.HIT_TYPE, Material.IRON_SWORD, "Hit Type",
                        "Sprint and Normal always; Random by its chance; Spacing walks a hit when you're already flying away "
                                + "and holds back while you can't be hit yet.",
                        v -> switch (v) {
                            case 0 -> "Sprint hit";
                            case 1 -> "Normal hit";
                            case 2 -> "Random";
                            default -> "Spacing";
                        }),
                new Spec(Knob.SPRINT_CHANCE, Material.FEATHER, "Sprint Chance", "With Random hits, how many are sprint hits.",
                        v -> v + "%"),
                new Spec(Knob.KNOCKBACK, Material.STICK, "Knockback Stick", "The bot hits with a Knockback stick of this level.",
                        v -> v == 0 ? "Off" : "Knockback " + roman(v)),
                new Spec(Knob.REACH_MIN, Material.ARROW, "Closest Reach", "Each hit comes from between its closest and farthest reach.",
                        DrillMenu::quarters),
                new Spec(Knob.REACH_MAX, Material.BOW, "Farthest Reach", "Each hit comes from between its closest and farthest reach.",
                        DrillMenu::quarters),
                new Spec(Knob.REACH_LEAN, Material.COMPASS, "Reach Lean", "Where in that range its hits tend to fall.",
                        v -> lean(v, "close", "far")),
                new Spec(Knob.COMBO_GAP, Material.CLOCK, "Combo Resets After",
                        "A hit this soon after its last landed hit carries on the combo; later, it starts fresh.", DrillMenu::ticks),
                new Spec(Knob.COMBO_SPRINT, Material.LEATHER_BOOTS, "Combo Sprint Loss",
                        "For each hit already landed in the combo, this much less chance the next counts as a sprint hit. "
                                + "It keeps sprinting; the server just never hears its sprint start again, as a missed w-tap.",
                        v -> "-" + v + "%"));
    }

    private static void timing(Player viewer, Consumer<Player> changed) {
        page(viewer, "Timing", 3, changed,
                new int[]{10, 11, 13, 14, 15},
                new Spec(Knob.CONTINUOUS, Material.REDSTONE_TORCH, "Continuous", "The bot fights until you stop it.",
                        DrillMenu::onOff),
                new Spec(Knob.HITS, Material.PAPER, "Hits", "When not continuous, how many hits it lands before it stops.",
                        String::valueOf),
                new Spec(Knob.WAIT_MIN, Material.CLOCK, "Shortest Wait", "Ticks it waits once you can be hit again.", DrillMenu::ticks),
                new Spec(Knob.WAIT_MAX, Material.CLOCK, "Longest Wait", "Ticks it waits once you can be hit again.", DrillMenu::ticks),
                new Spec(Knob.WAIT_LEAN, Material.COMPASS, "Wait Lean", "Where in that range its waits tend to fall.",
                        v -> lean(v, "short", "long")));
    }

    private static void selecting(Player viewer, Consumer<Player> changed) {
        page(viewer, "Hit Selecting", 3, changed,
                new int[]{10, 11, 13, 14, 15, 16},
                new Spec(Knob.HIT_SELECT, Material.GOLDEN_SWORD, "Hit Select", "In reach, the bot waits for you to hit first.",
                        DrillMenu::onOff),
                new Spec(Knob.PATIENCE, Material.CLOCK, "Patience", "Ticks in reach before it gives up and hits first.",
                        v -> v == Drill.NEVER ? "Never gives up" : ticks(v)),
                new Spec(Knob.COUNTER, Material.DIAMOND_SWORD, "Counter", "Whether it hits back after you hit it.", DrillMenu::onOff),
                new Spec(Knob.COUNTER_MIN, Material.ARROW, "Earliest Counter", "The tick after your hit reaches it that it hits back, at the earliest.",
                        DrillMenu::tick),
                new Spec(Knob.COUNTER_MAX, Material.ARROW, "Latest Counter", "The tick after your hit reaches it that it hits back, at the latest.",
                        DrillMenu::tick),
                new Spec(Knob.COUNTER_LEAN, Material.COMPASS, "Counter Lean", "Where in that range its counters tend to fall.",
                        v -> lean(v, "early", "late")));
    }

    private static void reducing(Player viewer, Consumer<Player> changed) {
        Screen.Builder screen = Screen.rows(6).title(Component.text("Reducing"));
        Spec[] specs = {
                new Spec(Knob.REDUCE, Material.FEATHER, "Reduce", "The bot clicks you while it takes knockback, to cut it.",
                        DrillMenu::onOff),
                new Spec(Knob.REDUCE_START_MIN, Material.ARROW, "Earliest Start", "The first tick it clicks on, at the earliest.",
                        DrillMenu::tick),
                new Spec(Knob.REDUCE_START_MAX, Material.ARROW, "Latest Start", "The first tick it clicks on, at the latest.",
                        DrillMenu::tick),
                new Spec(Knob.REDUCE_START_LEAN, Material.COMPASS, "Start Lean", "Where in that range its start tends to fall.",
                        v -> lean(v, "early", "late")),
                new Spec(Knob.REDUCE_PATTERN, Material.NOTE_BLOCK, "Click Pattern",
                        "Every tick from its start, a chance each tick from its clicks a second, or the ticks picked below.",
                        v -> switch (v) {
                            case 0 -> "Every tick";
                            case 1 -> "Clicks a second";
                            default -> "Picked ticks";
                        }),
                new Spec(Knob.REDUCE_CPS, Material.LEVER, "Clicks a Second", "With that pattern: 20 is every tick, 10 half of them.",
                        v -> v + " CPS"),
                new Spec(Knob.REDUCE_FOR, Material.HOPPER, "Reduce For", "How many ticks it keeps clicking, from its start.",
                        v -> v == 0 ? "Until it lands" : ticks(v)),
                new Spec(Knob.REDUCE_MISS, Material.SPIDER_EYE, "Miss Chance", "How many of its reduce clicks miss you and cut nothing.",
                        v -> v + "%"),
                new Spec(Knob.JUMP_RESET, Material.RABBIT_FOOT, "Jump Reset Chance", "How often it jumps on the tick your hit reaches it.",
                        v -> v + "%"),
        };
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 20, 24};
        for (int i = 0; i < specs.length; i++) knob(screen, slots[i], specs[i], changed);
        topic(screen, 22, Material.IRON_BARS, "Chains and Swings",
                "How it reduces worse through a combo, and how much each knockback varies.", v -> chains(v, changed));
        for (int b = 0; b < Drill.PATTERN_TICKS; b++) {
            int bit = b;
            screen.press(TICK_SLOTS[b], v -> {
                boolean set = (Drill.of(v).get(Knob.REDUCE_TICKS) & 1 << bit) != 0;
                return Entry.of(set ? Material.LIME_DYE : Material.GRAY_DYE).name("Tick " + bit)
                        .text("With picked ticks: whether it clicks on this tick after its start.")
                        .fact("Currently", set ? "Clicks" : "No click").action("Click to toggle!").render(v);
            }, (v, click) -> {
                Drill drill = Drill.of(v);
                Drill.set(v, drill.with(Knob.REDUCE_TICKS, drill.get(Knob.REDUCE_TICKS) ^ 1 << bit));
                changed.accept(v);
                Screens.repaint(v);
                return null;
            });
        }
        back(screen, 49);
        Screens.push(viewer, screen.build());
    }

    private static void chains(Player viewer, Consumer<Player> changed) {
        page(viewer, "Chains and Swings", 3, changed,
                new int[]{10, 11, 12, 13, 15, 16},
                new Spec(Knob.CHAIN_GAP, Material.CLOCK, "Chain Resets After",
                        "A knockback this soon after its last reduce click carries on the chain; later, it starts fresh.",
                        DrillMenu::ticks),
                new Spec(Knob.CHAIN_MISS, Material.SPIDER_EYE, "Chained Misses",
                        "Added to its miss chance for each knockback it already reduced in the chain.", v -> "+" + v + "%"),
                new Spec(Knob.CHAIN_CPS, Material.LEVER, "Chained Slowdown",
                        "Taken off its clicks a second for each knockback it already reduced in the chain.", v -> "-" + v + " CPS"),
                new Spec(Knob.CHAIN_DELAY, Material.REPEATER, "Chained Delay",
                        "Added to its first reduce tick for each knockback it already reduced in the chain.", v -> "+" + ticks(v)),
                new Spec(Knob.MISS_SWING, Material.COMPASS, "Miss Swing",
                        "Each knockback, its miss chance moves up or down by up to this much.", v -> "up to " + v + "%"),
                new Spec(Knob.CPS_SWING, Material.COMPASS, "Clicks Swing",
                        "Each knockback, its clicks a second move up or down by up to this much.", v -> "up to " + v + " CPS"));
    }

    private static void latency(Player viewer, Consumer<Player> changed) {
        page(viewer, "Latency", 3, changed,
                new int[]{10, 11, 13, 14, 15, 16},
                new Spec(Knob.PING_MIN, Material.REPEATER, "Lowest Ping", "Its round trip moves between its lowest and highest each tick.",
                        v -> v + " ms"),
                new Spec(Knob.PING_MAX, Material.REPEATER, "Highest Ping", "Its round trip moves between its lowest and highest each tick.",
                        v -> v + " ms"),
                new Spec(Knob.SPIKE_CHANCE, Material.REDSTONE, "Spike Chance", "The chance a spike starts in any second.",
                        v -> v + "% a second"),
                new Spec(Knob.SPIKE_MIN, Material.COMPARATOR, "Spike Low", "A spike holds a ping between its low and high, above or below the usual.",
                        v -> v + " ms"),
                new Spec(Knob.SPIKE_MAX, Material.COMPARATOR, "Spike High", "A spike holds a ping between its low and high, above or below the usual.",
                        v -> v + " ms"),
                new Spec(Knob.SPIKE_TICKS, Material.CLOCK, "Spike Length", "How long a spike lasts.", DrillMenu::ticks));
    }

    private static void movement(Player viewer, Consumer<Player> changed) {
        page(viewer, "Movement and Aim", 4, changed,
                new int[]{10, 11, 12, 14, 15, 16, 20, 21},
                new Spec(Knob.STRAFE, Material.FEATHER, "Strafing", "How much of the fight it spends holding A or D.", v -> v + "%"),
                new Spec(Knob.STRAFE_MIN, Material.CLOCK, "Shortest Strafe", "How long it holds a side before picking again, at the least.",
                        DrillMenu::ticks),
                new Spec(Knob.STRAFE_MAX, Material.CLOCK, "Longest Strafe", "How long it holds a side before picking again, at the most.",
                        DrillMenu::ticks),
                new Spec(Knob.SPACING, Material.LEATHER_BOOTS, "Spacing",
                        "While you can't be hit yet, it stops at its keep distance and backs off with S if you get too close. "
                                + "Letting go of W re-arms its sprint, as a w-tap.", DrillMenu::onOff),
                new Spec(Knob.KEEP, Material.ARROW, "Keep Distance",
                        "How far it stays while it waits, counting how far your knockback will still carry you.", DrillMenu::quarters),
                new Spec(Knob.TRADE, Material.IRON_SWORD, "Trade Window",
                        "When you and the bot have both landed a hit this recently, it's a trade: it holds W, no strafing or spacing.",
                        DrillMenu::ticks),
                new Spec(Knob.TURN, Material.COMPASS, "Turn Speed", "The most it turns a tick, like a mouse. Too slow and it misses.",
                        v -> v >= 180 ? "Instant" : v + " degrees a tick"),
                new Spec(Knob.JITTER, Material.SPIDER_EYE, "Aim Jitter", "How far its aim drifts off you. A click only counts when its "
                        + "crosshair is on you.", v -> v == 0 ? "None" : "up to " + v + " degrees"));
    }

    private static void effects(Player viewer, Consumer<Player> changed) {
        page(viewer, "Effects", 3, changed,
                new int[]{11, 13, 15},
                new Spec(Knob.BOT_SPEED, Material.RABBIT_FOOT, "Bot Speed", "The bot's Speed effect.", DrillMenu::level),
                new Spec(Knob.SPEED, Material.SUGAR, "Your Speed", "Your Speed effect.", DrillMenu::level),
                new Spec(Knob.SLOWNESS, Material.SOUL_SAND, "Your Slowness", "Your Slowness effect.", DrillMenu::level));
    }

    private static void page(Player viewer, String title, int rows, Consumer<Player> changed, int[] slots, Spec... specs) {
        Screen.Builder screen = Screen.rows(rows).title(Component.text(title));
        for (int i = 0; i < specs.length; i++) knob(screen, slots[i], specs[i], changed);
        back(screen, rows * 9 - 5);
        Screens.push(viewer, screen.build());
    }

    private static void topic(Screen.Builder screen, int slot, Material icon, String name, String text, Consumer<Player> open) {
        screen.press(slot, v -> Entry.of(icon).name(name).text(text).action("Click to open!").render(v), (v, click) -> {
            open.accept(v);
            return null;
        });
    }

    private static void knob(Screen.Builder screen, int slot, Spec spec, Consumer<Player> changed) {
        screen.press(slot, v -> Entry.of(spec.icon()).name(spec.name()).text(spec.text())
                .fact("Currently", spec.value().apply(Drill.of(v).get(spec.knob())))
                .action("Left-click for more, right-click for less!").render(v), (v, click) -> {
            Drill.set(v, Drill.of(v).step(spec.knob(), !(click instanceof Click.Right)));
            changed.accept(v);
            Screens.repaint(v);
            return null;
        });
    }

    private static void back(Screen.Builder screen, int slot) {
        screen.press(slot, v -> Entry.of(Material.ARROW).name("Back").action("Click to go back!").render(v), (v, click) -> {
            Screens.back(v);
            return null;
        });
    }

    private static String onOff(int v) {
        return v != 0 ? "On" : "Off";
    }

    private static String quarters(int v) {
        return String.format(Locale.ROOT, "%.2f blocks", v / 4.0);
    }

    private static String ticks(int v) {
        return v == 1 ? "1 tick" : v + " ticks";
    }

    private static String tick(int v) {
        return "Tick " + v;
    }

    private static String lean(int v, String low, String high) {
        return switch (v) {
            case 0 -> "Even";
            case 1 -> "Leans " + low;
            case 2 -> "Leans " + high;
            default -> "Toward the middle";
        };
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
