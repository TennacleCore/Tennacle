package tennacle.games;

import tennacle.Msg;

import io.github.term4.canvas.theme.HypixelIntro;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * A presentation preset, and nothing else: chat theme, kill line, verdicts, countdown voice, sound cues. A look
 * reads game state (the phase, a score) and never sets a rule - the platform's gamerules are its ruleset.
 */
public record Look(@NotNull Theme theme, @NotNull DeathLine death, @NotNull Verdict verdict, @NotNull Count count,
                   @NotNull SoundSet sounds) {

    /** Renders a kill line; {@code winner}/{@code verb} null = a forfeit with no killer. */
    @FunctionalInterface
    public interface DeathLine {
        Component render(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb);
    }

    /** Shows the end-of-match title to one viewer. */
    @FunctionalInterface
    public interface Verdict {
        void show(Game game, Player viewer, boolean won, Player winner);
    }

    /** The start countdown's voice: chat line, optional title per beat, and the at-zero line. */
    public record Count(@NotNull IntFunction<Component> number,
                        @Nullable IntFunction<Component> title,
                        @NotNull BiFunction<Game, Player, @Nullable Component> started) {}

    // minemen/scrims cues aren't captured yet - the era-standard pling
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);

    /** Yellow names, gray prose, VICTORY!/DEFEATED! - the minemen profile. */
    public static final Look MINEMEN = new Look(Msg.MINEMEN, Look::minemenDeath, Look::minemenVerdict,
            new Count(n -> Component.text(n + "...", NamedTextColor.YELLOW), null,
                    (g, p) -> Component.text("Match started!", NamedTextColor.GREEN)),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO));

    /** Team-colored names, gray prose, gold Victory!/red Defeat - the scrims profile. */
    public static final Look SCRIMS = new Look(Msg.SCRIMS, Look::scrimsDeath, Look::scrimsVerdict,
            new Count(Look::scrimsNumber, null, (g, p) -> null),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO));

    /** Captured hypixel: scoring modes end on TEAM WINS! + the score; plain duels on VICTORY!/DEFEAT!. */
    public static final Look HYPIXEL = new Look(Msg.HYPIXEL, Look::hypixelDeath, Look::hypixelVerdict,
            new Count(Look::hypixelNumber,
                    n -> Component.text(n, n >= 4 ? NamedTextColor.YELLOW : NamedTextColor.RED),
                    Look::hypixelStarted),
            // captured: note.hat vol 20 each beat, nothing at zero, fireworks.launch vol 3 on a goal
            SoundSet.of()
                    .cue(SoundSet.COUNTDOWN,
                            Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 20f, 1f))
                    .cue(SoundSet.SCORED,
                            Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 3f, 1f)));

    // the MineMen line shape - themed names, themed prose, an exclamation - on the game look's palette
    private static Component minemenDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb) {
        Theme theme = Matches.lookOf(game).theme();
        if (winner == null || verb == null) {
            return theme.value(loser).append(theme.prose(" forfeited!"));
        }
        return theme.value(loser)
                .append(theme.prose(" " + verb + " ")).append(theme.value(winner.getUsername()))
                .append(theme.prose("!"));
    }

    private static void minemenVerdict(Game game, Player viewer, boolean won, Player winner) {
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        viewer.showTitle(Title.title(
                Component.text(won ? "VICTORY!" : "DEFEATED!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), tone)
                        .append(Component.text(" won the Match!", NamedTextColor.WHITE)),
                Match.times(game, 0, 40, 10)));
    }

    // captured: [gold]"IgnasSk"[gray]" was killed by "[aqua]"Dogln"[gray]"." - names are rank-colored on
    // hypixel and always "was killed by", whatever actually happened; aqua names here (no ranks)
    private static Component hypixelDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb) {
        Component name = Component.text(loser, NamedTextColor.AQUA);
        if (winner == null || verb == null) return name.append(Component.text(" forfeited.", NamedTextColor.GRAY));
        return name.append(Component.text(" was killed by ", NamedTextColor.GRAY))
                .append(Component.text(winner.getUsername(), NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.GRAY));
    }

    // captured: yellow prose, red digit, "1 second!" singular; the intro block lands at zero
    private static Component hypixelNumber(int n) {
        return Component.text("The game starts in ", NamedTextColor.YELLOW)
                .append(Component.text(n, NamedTextColor.RED))
                .append(Component.text(n == 1 ? " second!" : " seconds!", NamedTextColor.YELLOW));
    }

    private static @Nullable Component hypixelStarted(Game game, Player viewer) {
        Mode mode = Matches.modeOf(game);
        if (mode == null || mode.intro().isEmpty()) return null;
        String title = Character.toUpperCase(mode.name().charAt(0)) + mode.name().substring(1) + " Duel";
        HypixelIntro intro = HypixelIntro.of(title);
        mode.intro().forEach(intro::line);
        Player foe = Match.opponent(game, viewer);
        return intro.opponent(foe != null ? foe.getUsername() : null).render();
    }

    // captured: gray "Cages open in ", the count green at 5-4, yellow 3-2, red at 1; silent at zero
    private static Component scrimsNumber(int n) {
        NamedTextColor tone = n >= 4 ? NamedTextColor.GREEN : n >= 2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text("Cages open in ", NamedTextColor.GRAY).append(Component.text(n + "s", tone));
    }

    // captured: a scoring mode shows both players "<TEAM> WINS!" over the bold final score; a plain duel
    // shows DEFEAT! (VICTORY! inferred green - only the losing side was captured) over "<name> won the Duel!"
    private static void hypixelVerdict(Game game, Player viewer, boolean won, Player winner) {
        Mode mode = Matches.modeOf(game);
        Title.Times times = Match.times(game, 0, 40, 10);
        if (mode != null && mode.teamScore() != null) {
            Slot seat = game.roster().slotOf(winner);
            Team team = game.roster().team(seat != null ? seat.team() : null);
            List<Team> teams = new ArrayList<>(game.roster().teams());
            teams.sort(Comparator.comparing(t -> t.id().equals(team.id()) ? 0 : 1));
            Component score = Component.empty();
            for (int i = 0; i < teams.size(); i++) {
                Team t = teams.get(i);
                if (i > 0) score = score.append(Component.text(" - ", NamedTextColor.GRAY));
                score = score.append(Component.text(game.state().get(Scope.team(t.id()), mode.teamScore()),
                        t.color(), TextDecoration.BOLD));
            }
            viewer.showTitle(Title.title(
                    Component.text(team.id().toUpperCase(Locale.ROOT) + " WINS!", team.color(), TextDecoration.BOLD),
                    score, times));
            return;
        }
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        viewer.showTitle(Title.title(
                Component.text(won ? "VICTORY!" : "DEFEAT!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), NamedTextColor.AQUA)
                        .append(Component.text(" won the Duel!", NamedTextColor.WHITE)),
                times));
    }

    // scrims: winners "Victory!" gold, losers "Defeat" light red - plain, no subtitle
    private static void scrimsVerdict(Game game, Player viewer, boolean won, Player winner) {
        viewer.showTitle(Title.title(
                won ? Component.text("Victory!", NamedTextColor.GOLD) : Component.text("Defeat", NamedTextColor.RED),
                Component.empty(),
                Match.times(game, 0, 40, 10)));
    }

    // captured: [red]"Dogln "[gray]"fell into the void." - team-colored names, gray prose, a period
    private static Component scrimsDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb) {
        Component line = Component.text(loser + " ", game.roster().team(seat.team()).color());
        if (verb == null) return line.append(Msg.SCRIMS.prose("forfeited."));
        if (winner == null) return line.append(Msg.SCRIMS.prose(verb));
        return line.append(Msg.SCRIMS.prose(verb + " "))
                .append(Component.text(winner.getUsername(), Match.teamColor(game, winner)))
                .append(Msg.SCRIMS.prose("."));
    }
}
