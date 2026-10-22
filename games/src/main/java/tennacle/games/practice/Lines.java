package tennacle.games.practice;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;
import tennacle.games.stats.KnockReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Practice's copy: the hotbar tools and the line after each of the bot's hits. */
final class Lines {

    static final Tag<String> TOOL = Tag.String("tennacle:practice");

    static final ItemStack START = tool(Material.GREEN_DYE, "Start", NamedTextColor.GREEN, "start",
            "Right-click to send the bot at you.");
    static final ItemStack RESET = tool(Material.REDSTONE, "Reset Bot", NamedTextColor.RED, "reset",
            "Right-click to stop the bot and put it", "back in the middle.");
    static final ItemStack SETTINGS = tool(Material.COMPARATOR, "Bot Settings", NamedTextColor.YELLOW, "settings",
            "Right-click to change how the bot fights.");
    static final ItemStack LEAVE = tool(Material.RED_DYE, "Leave Practice", NamedTextColor.RED, "leave",
            "Right-click to go back to the lobby.");

    // tick 0 the highest; past tick 2 nothing
    private static final float[] REDUCE_PITCH = {2.0f, 1.6f, 1.25f};
    private static final Sound JUMP_RESET = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);

    private Lines() {}

    /** A rising tone for a reduce on an early tick. */
    static void reduced(Player player, int tick) {
        if (tick < 0 || tick >= REDUCE_PITCH.length) return;
        player.playSound(Sound.sound(SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, 0.6f, REDUCE_PITCH[tick]));
    }

    static void jumpReset(Player player) {
        player.playSound(JUMP_RESET);
    }

    static void intro(Player player) {
        Msg.say(player, "Right-click the ", Msg.good("Start"), " dye and the bot comes at you. After each of its hits you'll see ",
                "how you reduced, jump reset and hit back. Tick 0 is the first tick you could react.");
    }

    /** Says the line to {@code player}; returns it plain, for the log. */
    static String hit(Player player, int number, KnockReader.Knock knock, boolean hitFirst) {
        List<Object> parts = new ArrayList<>();
        parts.add("Hit " + number + ": ");
        int reduce = knock.firstReduce();
        if (reduce < 0) parts.add(Msg.err0("no reduce"));
        else {
            parts.add(reduce == 0 ? Msg.good("reduced from tick 0") : Msg.err0("reduced from tick " + reduce));
            parts.add(" (" + knock.clicks() + (knock.clicks() == 1 ? " click)" : " clicks)"));
        }
        Object jump = jump(knock.jump());
        if (jump != null) {
            parts.add(", ");
            parts.add(jump);
        }
        parts.add(", ");
        if (hitFirst) parts.add(Msg.err0("you hit first"));
        else if (knock.counterTick() == 0) parts.add(Msg.good("countered on tick 0"));
        else if (knock.counterTick() > 0) parts.add(Msg.v("countered on tick " + knock.counterTick()));
        else parts.add("no counter");
        parts.add(", pushed ");
        parts.add(Msg.v(String.format(Locale.ROOT, "%.2f", Math.max(0, knock.pushed()))));
        parts.add(" blocks.");
        Msg.say(player, parts.toArray());
        StringBuilder plain = new StringBuilder();
        for (Object part : parts) plain.append(part instanceof Component c ? PlainTextComponentSerializer.plainText().serialize(c) : part);
        return plain.toString();
    }

    private static @Nullable Object jump(KnockReader.Jump jump) {
        return switch (jump) {
            case RESET -> Msg.good("jump reset");
            case EARLY -> Msg.err0("jumped too early");
            case MISSED -> "no jump reset";
            case IN_AIR -> "hit in the air";
            case UNKNOWN -> null;
        };
    }

    private static ItemStack tool(Material material, String name, NamedTextColor color, String id, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        return ItemStack.of(material)
                .with(DataComponents.CUSTOM_NAME, Component.text(name, color).decoration(TextDecoration.ITALIC, false))
                .with(DataComponents.LORE, lines)
                .withTag(TOOL, id);
    }
}
