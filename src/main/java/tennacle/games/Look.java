package tennacle.games;

import java.util.function.Function;
import io.github.term4.canvas.chat.Chat;
import tennacle.Msg;

import io.github.term4.canvas.presets.HypixelIntro;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.HitReport;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
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
                   @NotNull SoundSet sounds, @NotNull ChatLine chat, @NotNull Tab tab, boolean tabHealth,
                   @NotNull HitReport.Line hit, @NotNull Tiebreak tiebreak) {

    /** The call when a drawn clock plays on as a tiebreaker. */
    @FunctionalInterface
    public interface Tiebreak {
        void show(Game game, Player viewer);
    }

    /** A member's chat line in this look. */
    @FunctionalInterface
    public interface ChatLine {
        Component render(Game game, Player speaker, String message);
    }

    /** The tab menu's header and footer for a viewer inside a game of this look. */
    public record Tab(@NotNull Function<Player, Component> header, @NotNull Function<Player, Component> footer) {}

    /** The address and store lines the tab chrome names - the app's, whatever network shape wraps them. */
    public static final String ADDRESS = "play.tennacle.gg";
    public static final String STORE = "store.tennacle.gg";

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

    /** Yellow names, gray prose, VICTORY!/DEFEATED! - the minemen profile. Chat and tab are server-wide there. */
    public static final Look MINEMEN = new Look(Msg.MINEMEN, Look::minemenDeath, Look::minemenVerdict,
            new Count(n -> Component.text(n + "...", NamedTextColor.YELLOW), null,
                    (g, p) -> Component.text("Match started!", NamedTextColor.GREEN)),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO),
            (g, speaker, message) -> Chat.plain(speaker, message),
            new Tab(p -> minemenHeader(), p -> minemenFooter()), false, Look::minemenHit, Look::minemenTiebreak);

    /** Team-colored names, gray prose, gold Victory!/red Defeat - the scrims profile. */
    public static final Look SCRIMS = new Look(Msg.SCRIMS, Look::scrimsDeath, Look::scrimsVerdict,
            new Count(Look::scrimsNumber, null, (g, p) -> null),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO),
            // captured: [RED] Dogln: message - the team tag in the team color, the rest white
            (g, speaker, message) -> Component.text("[" + Match.teamName(g, speaker) + "] ", Match.teamColor(g, speaker))
                    .append(Component.text(speaker.getUsername() + ": ", NamedTextColor.GRAY))
                    .append(Component.text(message, NamedTextColor.WHITE)),
            // captured: bold aqua network line, yellow "Playing on <address>", aqua store line
            new Tab(p -> Component.text("TENNACLE", NamedTextColor.AQUA, TextDecoration.BOLD)
                            .append(Component.text("\nPlaying on " + ADDRESS + "\n", NamedTextColor.YELLOW)),
                    p -> Component.text("\n" + STORE, NamedTextColor.AQUA)), true, Look::scrimsHit, Look::scrimsTiebreak);

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
                            Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 3f, 1f)),
            // captured: green [GAME], then rank + name (no ranks here: gray name), white message
            (g, speaker, message) -> Component.text("[GAME] ", NamedTextColor.GREEN)
                    .append(Component.text(speaker.getUsername(), NamedTextColor.GRAY))
                    .append(Component.text(": " + message, NamedTextColor.WHITE)),
            // captured: green "You are playing on" + bold yellow address; red pitch + bold yellow store
            new Tab(p -> Component.text("You are playing on ", NamedTextColor.GREEN)
                            .append(Component.text(ADDRESS.toUpperCase(Locale.ROOT), NamedTextColor.YELLOW, TextDecoration.BOLD)),
                    p -> Component.text("Ranks, Boosters & MORE! ", NamedTextColor.RED)
                            .append(Component.text(STORE.toUpperCase(Locale.ROOT), NamedTextColor.YELLOW, TextDecoration.BOLD))), true, Look::hypixelHit, Look::hypixelTiebreak);

    /** The house look: the brand gradient on titles, muted prose, cyan values, names in their team's tint. */
    public static final Look TENNACLE = new Look(Msg.THEME, Look::tennacleDeath, Look::tennacleVerdict,
            new Count(n -> Msg.THEME.prose("Starting in ").append(Msg.THEME.value(n)),
                    // the beat on screen walks the palette in: muted, then cyan, then magenta at one
                    n -> Component.text(n, n >= 4 ? Msg.THEME.mutedColor() : n >= 2 ? Msg.THEME.valueColor()
                            : Msg.THEME.accentColor(), TextDecoration.BOLD),
                    (g, p) -> Msg.THEME.title(p, "GO!")),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO)
                    .cue(SoundSet.SCORED, Sound.sound(SoundEvent.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1.4f))
                    .cue(SoundSet.KILL, Sound.sound(SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, 1f, 1.6f)),
            (g, speaker, message) -> Component.text(speaker.getUsername(), Match.teamColor(g, speaker))
                    .append(Msg.THEME.dim(" \u00bb "))
                    .append(Component.text(message, NamedTextColor.WHITE)),
            new Tab(p -> Msg.THEME.boardTitle(p, "TENNACLE").append(Component.newline()).append(Msg.THEME.prose(ADDRESS)),
                    p -> Component.newline().append(Msg.THEME.dim(STORE))),
            true, Look::tennacleHit, Look::tennacleTiebreak);

    // the tiebreaker call - the same beat in each network's palette, so a theme swap carries it
    private static void scrimsTiebreak(Game game, Player viewer) {
        tiebreak(game, viewer, Msg.SCRIMS);
    }

    private static void hypixelTiebreak(Game game, Player viewer) {
        tiebreak(game, viewer, Msg.HYPIXEL);
    }

    private static void minemenTiebreak(Game game, Player viewer) {
        tiebreak(game, viewer, Msg.MINEMEN);
    }

    private static void tennacleTiebreak(Game game, Player viewer) {
        tiebreak(game, viewer, Msg.THEME);
    }

    // team-tinted names, muted prose, a dim period
    private static Component tennacleDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb) {
        Component line = Component.text(loser + " ", game.roster().team(seat.team()).color());
        if (verb == null) return line.append(Msg.THEME.prose("forfeited")).append(Msg.THEME.dim("."));
        if (winner == null) return line.append(Msg.THEME.prose(verb));
        return line.append(Msg.THEME.prose(verb + " "))
                .append(Component.text(winner.getUsername(), Match.teamColor(game, winner)))
                .append(Msg.THEME.dim("."));
    }

    // VICTORY in the gradient, DEFEAT muted and bold; the winner's name in its tint, a scoring mode's score after it
    private static void tennacleVerdict(Game game, Player viewer, boolean won, Player winner) {
        Component title = won ? Msg.THEME.boardTitle(viewer, "VICTORY")
                : Component.text("DEFEAT", Msg.THEME.mutedColor(), TextDecoration.BOLD);
        Component subtitle = Component.text(winner.getUsername(), Match.teamColor(game, winner))
                .append(Msg.THEME.prose(" wins"));
        Mode mode = Matches.modeOf(game);
        if (mode != null && mode.teamScore() != null) {
            subtitle = subtitle.append(Msg.THEME.dim("  ")).append(scoreLine(game, mode.teamScore(), winner));
        }
        viewer.showTitle(Title.title(title, subtitle, Match.times(game, 0, 40, 10)));
    }

    // the winner's side first, each score in its team's tint
    private static Component scoreLine(Game game, StateKey<Integer> key, Player winner) {
        Slot seat = game.roster().slotOf(winner);
        List<Team> teams = new ArrayList<>(game.roster().teams());
        teams.sort(Comparator.comparing(t -> seat != null && t.id().equals(seat.team()) ? 0 : 1));
        Component line = Component.empty();
        for (int i = 0; i < teams.size(); i++) {
            Team t = teams.get(i);
            if (i > 0) line = line.append(Msg.THEME.dim(" - "));
            line = line.append(Component.text(game.state().get(Scope.team(t.id()), key), t.color()));
        }
        return line;
    }

    // the house callout: cyan name, muted prose, the health as the theme's hearts
    private static Component tennacleHit(Player target, float health, float impact) {
        return Msg.THEME.value(target.getUsername())
                .append(Msg.THEME.prose(" is at "))
                .append(Msg.THEME.hearts(num(Math.max(0f, health))));
    }

    private static void tiebreak(Game game, Player viewer, Theme theme) {
        viewer.showTitle(Title.title(theme.boardTitle(viewer, "SUDDEN DEATH"),
                theme.prose("Next goal wins!"), Match.times(game, 5, 50, 10)));
        viewer.sendMessage(theme.prose("Sudden death! ").append(theme.value("Next goal wins")).append(theme.prose(".")));
    }

    // captured bow-duel callouts to the shooter, each network's own colors. `impact` is the signed health change:
    // negative for damage (shown -N in red), positive for a heal (+N in green). scrims/hypixel show only the health.
    // scrims: light-blue name, yellow "has", light-green health, a red heart
    private static Component scrimsHit(Player target, float health, float impact) {
        return Component.text(target.getUsername(), NamedTextColor.AQUA)
                .append(Component.text(" has ", NamedTextColor.YELLOW))
                .append(Component.text(num(Math.max(0f, health)), NamedTextColor.GREEN))
                .append(Component.text("❤", NamedTextColor.RED));
    }

    // hypixel: light-blue name, grey "is on", red value, grey "HP!"
    private static Component hypixelHit(Player target, float health, float impact) {
        return Component.text(target.getUsername(), NamedTextColor.AQUA)
                .append(Component.text(" is on ", NamedTextColor.GRAY))
                .append(Component.text(num(Math.max(0f, health)), NamedTextColor.RED))
                .append(Component.text(" HP!", NamedTextColor.GRAY));
    }

    // minemen: yellow name, bold grey arrow, light-purple hearts, then the signed change in parens
    // (damage -N red, heal +N green) - e.g. Dogln ➡ 7❤ (-3❤)
    private static Component minemenHit(Player target, float health, float impact) {
        boolean heal = impact > 0;
        Component change = Component.text((heal ? "+" : "-") + hearts(Math.abs(impact)) + "❤",
                heal ? NamedTextColor.GREEN : NamedTextColor.RED);
        return Component.text(target.getUsername(), NamedTextColor.YELLOW)
                .append(Component.text(" ➡ ", NamedTextColor.GRAY, TextDecoration.BOLD))
                .append(Component.text(hearts(Math.max(0f, health)) + "❤", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(change)
                .append(Component.text(")", NamedTextColor.GRAY));
    }

    // captured minemen: health in HEARTS, and only ever a whole one or a half - a whole hp either way
    private static String hearts(float health) {
        return num(Math.round(health) / 2f);
    }

    /** One decimal, but a whole value drops the {@code .0} - 20, not 20.0; 15.4 stays. */
    public static String num(float value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // captured minemen: pink @ around a bold white title; a pink-label footer row and the address
    static Component minemenHeader() {
        return Component.text("@ ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text("Tennacle", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" @", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
    }

    static Component minemenFooter() {
        int online = net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayerCount();
        return Component.text("Discord: ", NamedTextColor.LIGHT_PURPLE).append(Component.text("/discord", NamedTextColor.WHITE))
                .append(Component.text("    Players: ", NamedTextColor.LIGHT_PURPLE)).append(Component.text(online, NamedTextColor.WHITE))
                .append(Component.text("    Store: ", NamedTextColor.LIGHT_PURPLE)).append(Component.text("/store", NamedTextColor.WHITE))
                .append(Component.text("\n" + ADDRESS, NamedTextColor.LIGHT_PURPLE));
    }

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
                    Component.text(Palette.name(team) + " WINS!", team.color(), TextDecoration.BOLD),
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
