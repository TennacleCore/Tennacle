package tennacle.games;

import tennacle.Msg;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;
import tennacle.PlayerPrefs;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.vault.VaultKey;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a cue the look maps actually plays: {@link #CUES} is the game's rule - a team mode drops
 * {@code enemy-down} so every other side's fall is not a ding - and {@code /sounds} is the player's own mute list,
 * kept with their preferences. The look decides what a cue sounds like; this decides whether it reaches an ear.
 */
public final class GameSounds {

    /** Every cue a look can map, by short name; the last two reach a player outside any game. */
    public static final List<String> ALL = List.of("countdown", "start", "scored", "scored-beat", "kill", "died",
            "ally-down", "enemy-down", "blocked", "party-invite", "duel-request");
    /** The cues this game plays, by short name; the look's mapping is the ceiling, this the floor. */
    public static final StateKey<List<String>> CUES = StateKey.of("sound.cues", Codec.STRING.list(), ALL);
    public static final VaultKey<String> MUTED = VaultKey.of("sounds:muted", Codec.STRING, "");
    private static final PlayerPrefs.Entry<String, List<String>> MUTE = PlayerPrefs.register(MUTED,
            stored -> stored.isEmpty() ? List.of() : List.of(stored.split(",")),
            muted -> String.join(",", muted));

    private GameSounds() {}

    /** The first of {@code cues} the game's look maps, to {@code to}, when the game and the player both allow it. */
    public static void first(@NotNull Game game, @NotNull Player to, @NotNull String... cues) {
        first(game, Matches.lookOf(game).sounds(), to, cues);
    }

    static void first(@NotNull Game game, @NotNull SoundSet sounds, @NotNull Player to, @NotNull String... cues) {
        for (String cue : cues) {
            Sound sound = sounds.get(cue);
            if (sound == null) continue;
            if (allows(game, to, name(cue))) to.playSound(sound);
            return;
        }
    }

    public static void play(@NotNull Game game, @NotNull Player to, @NotNull String cue) {
        first(game, to, cue);
    }

    /** A cue outside any game - an invite, a request - in the house look's voice, past the player's mutes alone. */
    public static void lobby(@NotNull Player to, @NotNull String cue) {
        lobby(to, Look.TENNACLE.sounds(), cue);
    }

    static void lobby(@NotNull Player to, @NotNull SoundSet sounds, @NotNull String cue) {
        Sound sound = sounds.get(cue);
        if (sound == null) return;
        List<String> muted = MUTE.of(to.getUuid());
        if (muted == null || !muted.contains(name(cue))) to.playSound(sound);
    }

    /** {@code canvas:kill:red} reads as {@code kill}: the team variant is the same cue to a rule or a mute. */
    static @NotNull String name(@NotNull String cue) {
        String bare = cue.startsWith("canvas:") ? cue.substring("canvas:".length()) : cue;
        int variant = bare.indexOf(':');
        return variant < 0 ? bare : bare.substring(0, variant);
    }

    static boolean allows(@NotNull Game game, @NotNull Player to, @NotNull String name) {
        if (!game.rules().resolve(to, CUES).contains(name)) return false;
        List<String> muted = MUTE.of(to.getUuid());
        return muted == null || !muted.contains(name);
    }

    public static void register() {
        AppCommand sounds = Commands.player("sounds", "Which game sounds you hear.")
                .usage("/sounds", "each cue, on or off")
                .usage("/sounds <cue|all> on|off", "mute one, or all of them");
        var cue = ArgumentType.Word("cue");
        cue.setSuggestionCallback(Completions.of(() -> ALL));
        var state = ArgumentType.Word("state").from("on", "off");
        sounds.setDefaultExecutor((sender, ctx) -> show(sender));
        sounds.addSyntax((sender, ctx) -> set(sender, ctx.get(cue), ctx.get(state).equals("on")), cue, state);
        Commands.register(sounds);
    }

    private static void show(CommandSender sender) {
        if (!(sender instanceof Player p)) return;
        List<String> muted = MUTE.of(p.getUuid());
        for (String name : ALL) {
            boolean on = muted == null || !muted.contains(name);
            Msg.say(p, "  " + name + ": ", on ? Msg.good("on") : Msg.err0("off"));
        }
        Msg.say(p, "Toggle one with ", Msg.run("/sounds <cue> on|off"), ".");
    }

    private static void set(CommandSender sender, String which, boolean on) {
        if (!(sender instanceof Player p)) return;
        String name = which.toLowerCase(Locale.ROOT);
        if (!name.equals("all") && !ALL.contains(name)) {
            Msg.fail(p, "No sound named ", Msg.v(which), ". Sounds: ", Msg.v(String.join(", ", ALL) + ", all"), ".");
            return;
        }
        List<String> muted = new ArrayList<>(MUTE.of(p.getUuid()) == null ? List.of() : MUTE.of(p.getUuid()));
        if (name.equals("all")) {
            muted.clear();
            if (!on) muted.addAll(ALL);
        } else if (on) {
            muted.remove(name);
        } else if (!muted.contains(name)) {
            muted.add(name);
        }
        MUTE.set(p, muted);
        Msg.say(p, Msg.v(name), " is now ", on ? Msg.good("on") : Msg.err0("off"), ".");
    }
}
