package tennacle.social.channels;

import io.github.term4.canvas.chat.Chat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.social.messages.Messages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static tennacle.social.Lines.green;

/** What {@code /chat} picks between: everyone around you, or a channel a group registers (party, later guild). */
public final class Channels {

    /**
     * A group's channel. {@code members}: who hears the speaker, null when the speaker has no such group;
     * {@code outside}: the refusal then.
     */
    public record Channel(@NotNull String name, @NotNull List<String> aliases,
                          @NotNull Function<Player, @Nullable List<Player>> members,
                          @NotNull BiFunction<Player, String, Component> line, @NotNull String outside) {}

    private static final String ALL = "all";
    private static final Tag<String> CHOSEN = Tag.Transient("tennacle:chat-channel");
    private static final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private static final Map<String, String> names = new ConcurrentHashMap<>(Map.of(ALL, ALL, "a", ALL));

    private Channels() {}

    public static void register(@NotNull Channel channel) {
        channels.put(channel.name(), channel);
        names.put(channel.name(), channel.name());
        for (String alias : channel.aliases()) names.put(alias, channel.name());
    }

    public static void install() {
        AppCommand chat = Commands.player("chat", "Choose the chat to speak in.")
                .usage("/chat <channel>", "everything you say goes to that channel")
                .usage("/chat all", "back to everyone around you");
        var channel = ArgumentType.Word("channel");
        channel.setSuggestionCallback((sender, ctx, suggestion) -> names.keySet().stream().sorted()
                .forEach(name -> suggestion.addEntry(new SuggestionEntry(name))));
        chat.setDefaultExecutor((sender, ctx) -> Msg.fail(sender, "Invalid usage! '/chat <" + choices() + ">'"));
        chat.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) choose(p, ctx.get(channel));
        }, channel);
        Commands.register(chat);
    }

    /** Where {@code speaker}'s line goes when they chose a channel; null when they speak to everyone around. */
    public static Chat.@Nullable Room room(@NotNull Player speaker, @NotNull String message) {
        String chosen = speaker.getTag(CHOSEN);
        Channel channel = chosen != null ? channels.get(chosen) : null;
        if (channel == null) return null;
        List<Player> members = channel.members().apply(speaker);
        if (members == null) { // left the group since choosing it
            speaker.removeTag(CHOSEN);
            return null;
        }
        return new Chat.Room(members, channel.line().apply(speaker, message));
    }

    /** Sends {@code player} back to everyone around, if they had chosen {@code name}. */
    public static void leave(@NotNull Player player, @NotNull String name) {
        if (name.equals(player.getTag(CHOSEN))) player.removeTag(CHOSEN);
    }

    private static void choose(Player player, String asked) {
        String name = names.get(asked.toLowerCase(Locale.ROOT));
        if (name == null) {
            Msg.fail(player, "Invalid channel! Valid channels: " + choices().toUpperCase(Locale.ROOT).replace("|", ", "));
            return;
        }
        Channel channel = channels.get(name);
        if (channel != null && channel.members().apply(player) == null) {
            Msg.fail(player, channel.outside());
            return;
        }
        Messages.leave(player);
        if (channel == null) player.removeTag(CHOSEN);
        else player.setTag(CHOSEN, name);
        // captured: "You are now in the ALL channel"
        player.sendMessage(green("You are now in the ")
                .append(Component.text(name.toUpperCase(Locale.ROOT), NamedTextColor.GOLD)).append(green(" channel")));
    }

    private static String choices() {
        return ALL + channels.keySet().stream().sorted().map(name -> "|" + name).collect(Collectors.joining());
    }
}
