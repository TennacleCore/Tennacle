package tennacle.looks;

import io.github.term4.grotto.modules.flow.Staging;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import io.github.term4.grotto.modules.view.Refusals;
import java.util.function.Function;
import io.github.term4.canvas.chat.Chat;
import tennacle.Msg;
import tennacle.games.watch.Theaters;
import tennacle.games.watch.Replays;

import io.github.term4.canvas.presets.HypixelIntro;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.HitReport;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * A presentation preset, and nothing else: chat theme, kill line, verdicts, countdown voice, sound cues. A look
 * reads game state (the phase, a score) and never sets a rule - the platform's gamerules are its ruleset.
 */
public record Look(@NotNull Theme theme, @NotNull DeathLine death, @NotNull Verdict verdict, @NotNull Count count,
                   @NotNull SoundSet sounds, @NotNull ChatLine chat, @NotNull Tab tab, boolean tabHealth,
                   @NotNull HitReport.Line hit, @NotNull Tiebreak tiebreak, @NotNull Map<Cue<?>, Object> cues) {

    /** A look's answer to one presentation question a mode asks, with what a look that says nothing means. */
    public record Cue<T>(@NotNull String id, @NotNull T fallback) {
        @Override public String toString() { return id; }
    }

    public Look(@NotNull Theme theme, @NotNull DeathLine death, @NotNull Verdict verdict, @NotNull Count count,
                @NotNull SoundSet sounds, @NotNull ChatLine chat, @NotNull Tab tab, boolean tabHealth,
                @NotNull HitReport.Line hit, @NotNull Tiebreak tiebreak) {
        this(theme, death, verdict, count, sounds, chat, tab, tabHealth, hit, tiebreak, Map.of());
    }

    /** The name alone wears the team's tint and the prose stays yellow, rather than the whole line tinting. */
    public static final Cue<Boolean> NAMED_ACCENT = new Cue<>("named-accent", false);
    /** A countdown number is always green and trails dots, rather than greening down to red. */
    public static final Cue<Boolean> COUNT_DOTS = new Cue<>("count-dots", false);
    /** The cage countdown is mirrored into chat, not just the title. */
    public static final Cue<Boolean> COUNT_IN_CHAT = new Cue<>("count-in-chat", false);
    /** The instruction block lands right before the first cage beat. */
    public static final Cue<Boolean> INTRO_BEFORE_CAGES = new Cue<>("intro-before-cages", false);
    /** A goal sets off fireworks at the center. */
    public static final Cue<Boolean> GOAL_FIREWORKS = new Cue<>("goal-fireworks", false);
    /** Which goal banner this look prints; the modes register their own renderers under it. */
    public static final Cue<String> GOAL_BANNER = new Cue<>("goal-banner", "scrims");

    /** This look's answer, or the cue's fallback. */
    @SuppressWarnings("unchecked")
    public <T> @NotNull T cue(@NotNull Cue<T> cue) {
        Object set = cues.get(cue);
        return set != null ? (T) set : cue.fallback();
    }

    /** A copy answering {@code cue}; the constants chain these instead of growing another constructor argument. */
    public <T> @NotNull Look with(@NotNull Cue<T> cue, @NotNull T value) {
        Map<Cue<?>, Object> next = new java.util.LinkedHashMap<>(cues);
        next.put(cue, value);
        return new Look(theme, death, verdict, count, sounds, chat, tab, tabHealth, hit, tiebreak, Map.copyOf(next));
    }

    /** A seat taken while the game fills: the name as this look names players, then the count. */
    public static @NotNull Component arrival(Game game, String name, int seated, int seats) {
        Theme theme = Matches.lookOf(game).theme();
        return switch (Matches.lookNameOf(game)) {
            case "hypixel" -> hypixelArrival(Component.text(name, NamedTextColor.GRAY), seated, seats);
            default -> theme.value(name).append(theme.prose(" joined (" + seated + "/" + seats + ")."));
        };
    }

    /** A seat given up while the game fills. */
    public static @NotNull Component quit(Game game, String name) {
        Theme theme = Matches.lookOf(game).theme();
        return switch (Matches.lookNameOf(game)) {
            case "hypixel" -> hypixelQuit(Component.text(name, NamedTextColor.GRAY));
            default -> theme.value(name).append(theme.prose(" left."));
        };
    }

    /** A seat walked out on, or one re-claimed: the name as this look names players, then the fact. */
    public static @NotNull Component departure(Game game, Slot seat, String name, boolean back) {
        Theme theme = Matches.lookOf(game).theme();
        // captured hypixel: the name in the side's color, then gray "disconnected." / "reconnected."
        if (Matches.lookNameOf(game).equals("hypixel")) {
            return Component.text(name + " ", game.roster().team(seat.team()).color())
                    .append(Component.text(back ? "reconnected." : "disconnected.", NamedTextColor.GRAY));
        }
        Component who = switch (Matches.lookNameOf(game)) {
            case "scrims", "tennacle" -> Component.text(name, game.roster().team(seat.team()).color());
            default -> theme.value(name);
        };
        return who.append(theme.prose(back ? " rejoined!" : " left!"));
    }

    /** The line for an action the game refused, in light red: hypixel's copy, kept for every look. */
    public static @NotNull Component refusal(Game game, Player player, Refusals.Kind kind, @Nullable String reason) {
        return Component.text(switch (kind) {
            case PROTECTED -> "This player has spawn protection!";
            case PLACE -> "You can't place blocks here!";
            case BREAK -> "You can't break this block!";
            case HIT -> "You can't do that here!";
        }, NamedTextColor.RED);
    }

    /** The call when a drawn clock plays on as a tiebreaker; {@code flat} picks the title's generation. */
    @FunctionalInterface
    public interface Tiebreak {
        Call render(Game game, boolean flat);
    }

    /** A title with its chat line. */
    public record Call(@NotNull Title title, @NotNull Component line) {}

    /** A member's chat line in this look. */
    @FunctionalInterface
    public interface ChatLine {
        Component render(Game game, Player speaker, String message);
    }

    /** The tab menu's header and footer for a viewer inside a game of this look. */
    public record Tab(@NotNull Function<Player, Component> header, @NotNull Function<Player, Component> footer) {}

    /** The address and store lines the tab chrome names - the app's, whatever network shape wraps them. */
    public static final String ADDRESS = "tennacle.net";
    public static final String STORE = "store.tennacle.net";

    /**
     * Renders a kill line; {@code winner}/{@code verb} null = a forfeit with no killer. {@code tool} names what the
     * winner killed with when it was not their hand - a creature of theirs, possessive.
     */
    @FunctionalInterface
    public interface DeathLine {
        Component render(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb,
                         @Nullable String tool);

        default Component render(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb) {
            return render(game, seat, loser, winner, verb, null);
        }
    }

    static String winnerName(Player winner, @Nullable String tool) {
        return tool == null ? winner.getUsername() : winner.getUsername() + "'s " + tool;
    }

    /** The end-of-match title as one side reads it. */
    @FunctionalInterface
    public interface Verdict {
        Title title(Game game, boolean won, Player winner);
    }

    /** What a watcher reads at the end: they took no side, so the verdict names none. */
    public static @NotNull Title over(@NotNull Game game) {
        return Title.title(Component.text("Game Over!", NamedTextColor.GOLD), Component.empty(),
                Match.times(game, 0, 40, 10));
    }

    /** The start countdown's voice: chat line and title per beat (null = a silent beat), the at-zero line,
     *  and the line for a count stopped short of players. */
    public record Count(@NotNull IntFunction<@Nullable Component> number,
                        @Nullable IntFunction<@Nullable Component> title,
                        @NotNull Beat beat,
                        @NotNull BiFunction<Game, Player, @Nullable Component> started,
                        @Nullable Component cancelled) {

        /** How long a beat's title holds, in client ticks. */
        public record Beat(int fadeIn, int stay, int fadeOut) {}

        /** The captured hypixel timing, which a look with no beat title never reads. */
        public Count(@NotNull IntFunction<@Nullable Component> number, @Nullable IntFunction<@Nullable Component> title,
                     @NotNull BiFunction<Game, Player, @Nullable Component> started) {
            this(number, title, new Beat(0, 20, 20), started, null);
        }

        public Count(@NotNull IntFunction<@Nullable Component> number, @Nullable IntFunction<@Nullable Component> title,
                     @NotNull Beat beat, @NotNull BiFunction<Game, Player, @Nullable Component> started) {
            this(number, title, beat, started, null);
        }
    }

    // scrims cues aren't captured yet - the era-standard pling
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);
    // captured minemen: note.hat on the beat, the firework's blast at zero, both at volume 10
    private static final Sound MMC_TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 10f, 1f);
    private static final Sound MMC_GO = Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_BLAST, Sound.Source.MASTER, 10f, 1f);

    // placeholders: neither ding nor block sound is a capture
    private static final Sound DING = Sound.sound(SoundEvent.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1f, 1f);
    private static final Sound BLOCK = Sound.sound(SoundEvent.BLOCK_ANVIL_LAND, Sound.Source.MASTER, 0.3f, 2f);
    // captured on hypixel: fireworks.launch vol 3; scrims plays the same launch once on the goal
    private static final Sound FIREWORK = Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 3f, 1f);

    private static SoundSet plings() {
        return SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO);
    }
    private static final Count SCRIMS_COUNT = new Count(Look::scrimsNumber, null, (g, p) -> null);

    /** Yellow names, gray prose, VICTORY!/DEFEATED! - the minemen profile. Chat and tab are server-wide there. */
    public static final Look MINEMEN = new Look(Msg.MINEMEN, Look::minemenDeath, Look::minemenVerdict,
            // captured: "5..." in chat, a bold yellow digit held 40 ticks, "Match started!" at zero
            new Count(n -> Component.text(n + "...", NamedTextColor.YELLOW),
                    n -> Component.text(n, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                    new Count.Beat(0, 40, 0),
                    (g, p) -> Component.text("Match started!", NamedTextColor.GREEN)),
            SoundSet.of().cue(SoundSet.COUNTDOWN, MMC_TICK).cue(SoundSet.START, MMC_GO)
                    .cue(SoundSet.KILL, DING), // the ding on kill credit
            (g, speaker, message) -> Chat.plain(speaker, message),
            new Tab(p -> minemenHeader(), p -> minemenFooter()), false, Look::minemenHit, Look::minemenTiebreak).with(NAMED_ACCENT, true).with(GOAL_BANNER, "minemen");

    /** Team-colored names, gray prose, gold Victory!/red Defeat - the scrims profile. */
    public static final Look SCRIMS = new Look(Msg.SCRIMS, Look::scrimsDeath, Look::scrimsVerdict,
            SCRIMS_COUNT, plings().cue(SoundSet.ENEMY_DOWN, DING).cue(SoundSet.SCORED, FIREWORK), Look::scrimsChat, // the ding on any enemy's fall
            // captured: bold aqua network line, yellow "Playing on <address>", aqua store line
            new Tab(p -> Component.text("TENNACLE", NamedTextColor.AQUA, TextDecoration.BOLD)
                            .append(Component.text("\nPlaying on " + ADDRESS + "\n", NamedTextColor.YELLOW)),
                    p -> Component.text("\n" + STORE, NamedTextColor.AQUA)), true, Look::scrimsHit, Look::scrimsTiebreak).with(COUNT_IN_CHAT, true);

    /** Captured hypixel: scoring modes end on TEAM WINS! + the score; plain duels on VICTORY!/DEFEAT!. */
    public static final Look HYPIXEL = new Look(Msg.HYPIXEL, Look::hypixelDeath, Look::hypixelVerdict,
            new Count(Look::hypixelNumber, Look::hypixelDigit, new Count.Beat(0, 20, 20), Look::hypixelStarted,
                    Component.text("We don't have enough players! Start cancelled.", NamedTextColor.RED)),
            // captured: note.hat vol 20 each beat, nothing at zero, the firework launch on each beat after a goal
            SoundSet.of()
                    .cue(SoundSet.COUNTDOWN,
                            Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 20f, 1f))
                    .cue(SoundSet.SCORED_BEAT, FIREWORK)
                    .cue(SoundSet.KILL, DING), // the ding on kill credit
            Look::hypixelChat,
            // captured: green "You are playing on" + bold yellow address; the seat's counters, then red pitch + bold yellow store
            new Tab(p -> Component.text("You are playing on ", NamedTextColor.GREEN)
                            .append(Component.text(ADDRESS.toUpperCase(Locale.ROOT), NamedTextColor.YELLOW, TextDecoration.BOLD)),
                    p -> hypixelStats(p).append(Component.text("Ranks, Boosters & MORE! ", NamedTextColor.RED))
                            .append(Component.text(STORE.toUpperCase(Locale.ROOT), NamedTextColor.YELLOW, TextDecoration.BOLD))), true, Look::hypixelHit, Look::hypixelTiebreak).with(COUNT_DOTS, true).with(INTRO_BEFORE_CAGES, true).with(GOAL_FIREWORKS, true).with(GOAL_BANNER, "hypixel");

    /** The house look: the gradient on titles, light gray prose, light blue values, names in their team's tint. */
    public static final Look TENNACLE = new Look(Msg.THEME, Look::scrimsDeath, Look::scrimsVerdict,
            SCRIMS_COUNT, plings().cue(SoundSet.ENEMY_DOWN, DING).cue(SoundSet.BLOCKED, BLOCK).cue(SoundSet.SCORED, FIREWORK),
            Look::scrimsChat,
            new Tab(Look::houseHeader,
                    p -> seatStats(p).append(Component.newline()).append(Msg.THEME.accent(STORE))),
            true, Look::scrimsHit, Look::tennacleTiebreak);

    // the tiebreaker call - the same beat in each network's palette, so a theme swap carries it
    private static Call scrimsTiebreak(Game game, boolean flat) {
        return tiebreak(game, flat, Msg.SCRIMS);
    }

    private static Call hypixelTiebreak(Game game, boolean flat) {
        return tiebreak(game, flat, Msg.HYPIXEL);
    }

    private static Call minemenTiebreak(Game game, boolean flat) {
        return tiebreak(game, flat, Msg.MINEMEN);
    }

    private static Call tennacleTiebreak(Game game, boolean flat) {
        return tiebreak(game, flat, Msg.THEME);
    }

    // the network in the house gradient, then the room the viewer is actually in. The title is bold, and a
    // child inherits it: appended to the title, the line under it would render bold and outgrow the tab panel
    private static Component houseHeader(Player viewer) {
        return Component.text()
                .append(Msg.THEME.boardTitle(viewer, ADDRESS.toUpperCase(Locale.ROOT)))
                .append(Component.newline())
                .append(houseWhere(viewer))
                .build();
    }

    // what they are doing in white, where in the value color, the code that names it dim - the board's own
    // shape. The accent is the gradient's middle, which fights the title rather than sitting under it
    private static Component houseWhere(Player viewer) {
        Theaters.Watched replay = Theaters.watched(viewer);
        if (replay != null) { // the game's own line, marked as a replay, dated: a code names a game only beside its date
            Component where = white((replay.mode() != null ? replay.mode().title() : replay.game()) + " replay")
                    .append(Msg.THEME.prose(" on ")).append(Msg.THEME.value(replay.arena().meta().name()));
            String when = Frames.date(Matches.look(replay.look()), replay.played());
            return where.append(Msg.THEME.dim(" · " + (replay.code().isEmpty() ? when : when + " " + replay.code())));
        }
        Game game = Game.of(viewer);
        if (game == null) game = Matches.grotto().spectating(viewer); // a ghost holds no seat to read
        if (game == null) {
            int online = MinecraftServer.getConnectionManager().getOnlinePlayerCount();
            return white(online).append(Msg.THEME.prose(online == 1 ? " player online" : " players online"));
        }
        Mode mode = Matches.modeOf(game);
        return white(mode != null ? mode.title() : game.type().id())
                .append(Msg.THEME.prose(" on ")).append(Msg.THEME.value(game.arena().meta().name()))
                .append(Msg.THEME.dim(" · " + game.code()));
    }

    private static Component white(Object v) {
        return Component.text(String.valueOf(v), NamedTextColor.WHITE);
    }

    // captured duels: green [GAME], then rank + name (no ranks here: gray name), white message; a game of teams
    // carries the team tag in its color instead (captured Solo: [49✫] [PINK] [VIP+] name: message, rankless here)
    private static Component hypixelChat(Game game, Player speaker, String message) {
        Mode mode = Matches.modeOf(game);
        if (Staging.unsided(game)) { // no team to name before the draw
            return Component.text(speaker.getUsername(), NamedTextColor.GRAY).append(Component.text(": " + message, NamedTextColor.WHITE));
        }
        String side = mode != null && mode.family() == Mode.Family.GAME ? Match.teamOf(game, speaker) : "";
        Component tag = side.isEmpty() ? Component.text("[GAME] ", NamedTextColor.GREEN)
                : Component.text("[" + Palette.titled(game.roster().team(side)).toUpperCase(Locale.ROOT) + "] ", game.roster().team(side).color());
        return tag.append(Component.text(speaker.getUsername(), NamedTextColor.GRAY))
                .append(Component.text(": " + message, NamedTextColor.WHITE));
    }

    /** Team chat: the game's own line, heard by the team alone (the user, 2026-09-29); a tag elsewhere. */
    public static @NotNull Component teamChat(Game game, Player speaker, String message) {
        return switch (Matches.lookNameOf(game)) {
            case "hypixel" -> hypixelChat(game, speaker, message);
            default -> Chat.tagged("team", speaker, message);
        };
    }

    /** Captured hypixel: bold good {@code TEAM > }, the sender as prose, a white colon, the message. */
    public static @NotNull Component teamSays(Game game, String sender, Component message) {
        Theme theme = Matches.lookOf(game).theme();
        return Component.text()
                .append(theme.good("TEAM > ").decorate(TextDecoration.BOLD))
                .append(theme.prose(sender))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(message)
                .build();
    }

    /** A shout to the whole game. Captured 2026-09-29: gold {@code [SHOUT] }, then the game line as it is. */
    public static @NotNull Component shout(Game game, Player speaker, String message) {
        return switch (Matches.lookNameOf(game)) {
            case "hypixel" -> Component.text("[SHOUT] ", NamedTextColor.GOLD).append(hypixelChat(game, speaker, message));
            default -> Chat.tagged("shout", speaker, message);
        };
    }

    /** A stranger's seat while a public game fills (captured): the alias gray and obfuscated, the rest yellow, the counts aqua. */
    public static @NotNull Component arrivalHidden(String alias, int seated, int seats) {
        return hypixelArrival(Component.text(alias, NamedTextColor.GRAY, TextDecoration.OBFUSCATED), seated, seats);
    }

    // captured: the name in its rank's color (rankless here: gray), the rest yellow, the counts aqua
    private static Component hypixelArrival(Component name, int seated, int seats) {
        return Component.empty().append(name)
                .append(Component.text(" has joined (", NamedTextColor.YELLOW))
                .append(Component.text(seated, NamedTextColor.AQUA))
                .append(Component.text("/", NamedTextColor.YELLOW))
                .append(Component.text(seats, NamedTextColor.AQUA))
                .append(Component.text(")!", NamedTextColor.YELLOW));
    }

    /** A stranger's seat given up: the join line's colors (the quit itself uncaptured). */
    public static @NotNull Component quitHidden(String alias) {
        return hypixelQuit(Component.text(alias, NamedTextColor.GRAY, TextDecoration.OBFUSCATED));
    }

    private static Component hypixelQuit(Component name) {
        return Component.empty().append(name).append(Component.text(" has quit!", NamedTextColor.YELLOW));
    }

    // captured Bed Wars: the seat's counters, aqua labels and yellow counts on one line over the store line
    private static Component hypixelStats(Player viewer) {
        Game game = Game.of(viewer);
        if (game == null || game.status() != Game.Status.OPEN) return Component.empty();
        Mode mode = Matches.modeOf(game);
        Slot seat = game.roster().slotOf(viewer);
        if (mode == null || seat == null) return Component.empty();
        List<Mode.SeatStat> stats = TabStats.chosen(viewer, mode);
        if (stats.isEmpty()) return Component.empty();
        TextComponent.Builder line = Component.text();
        for (int i = 0; i < stats.size(); i++) {
            Mode.SeatStat stat = stats.get(i);
            line.append(Component.text((i > 0 ? " " : "") + stat.label() + ": ", NamedTextColor.AQUA))
                    .append(Component.text(String.valueOf(game.state().get(Scope.slot(seat.index()), stat.key())), NamedTextColor.YELLOW));
        }
        return line.append(Component.newline()).build();
    }

    // bedwars' tab line for the viewer's own seat - "Kills: 0 Deaths: 0 Goals: 0" - the counters they picked
    private static Component seatStats(Player viewer) {
        Game game = Game.of(viewer);
        if (game == null || game.status() != Game.Status.OPEN) return Component.empty();
        Mode mode = Matches.modeOf(game);
        Slot seat = game.roster().slotOf(viewer);
        if (mode == null || seat == null) return Component.empty();
        List<Mode.SeatStat> stats = TabStats.chosen(viewer, mode);
        if (stats.isEmpty()) return Component.empty();
        Component line = Component.newline();
        for (int i = 0; i < stats.size(); i++) {
            Mode.SeatStat stat = stats.get(i);
            line = line.append(Component.text((i > 0 ? " " : "") + stat.label() + ": ", NamedTextColor.WHITE))
                    .append(Msg.THEME.value(game.state().get(Scope.slot(seat.index()), stat.key())));
        }
        return line;
    }

    // captured: [RED] Dogln: message - the team tag in the team color, the rest white
    private static Component scrimsChat(Game game, Player speaker, String message) {
        Component line = Component.text(speaker.getUsername() + ": ", NamedTextColor.GRAY)
                .append(Component.text(message, NamedTextColor.WHITE));
        Mode mode = Matches.modeOf(game);
        if (mode != null && !mode.sided()) return line; // a plain duel has no side worth naming
        return Component.text("[" + Match.teamName(game, speaker) + "] ", Match.teamColor(game, speaker)).append(line);
    }

    private static Call tiebreak(Game game, boolean flat, Theme theme) {
        return new Call(Title.title(theme.boardTitle(flat, "SUDDEN DEATH"),
                theme.prose("Next goal wins!"), Match.times(game, 5, 50, 10)),
                theme.prose("Sudden death! ").append(theme.value("Next goal wins")).append(theme.prose(".")));
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

    // hypixel: light-blue name, gray "is on", red value, gray "HP!"
    private static Component hypixelHit(Player target, float health, float impact) {
        return Component.text(target.getUsername(), NamedTextColor.AQUA)
                .append(Component.text(" is on ", NamedTextColor.GRAY))
                .append(Component.text(num(Math.max(0f, health)), NamedTextColor.RED))
                .append(Component.text(" HP!", NamedTextColor.GRAY));
    }

    // minemen: yellow name, bold gray arrow, light-purple hearts, then the signed change in parens
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
    private static Component minemenDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb,
                                          @Nullable String tool) {
        Component name = Component.text(loser, game.roster().team(seat.team()).color());
        if (verb == null) return name.append(Component.text(" forfeited!", NamedTextColor.YELLOW));
        if (winner == null) return name.append(Component.text(" " + verb + "!", NamedTextColor.YELLOW));
        return name.append(Component.text(" " + verb + " ", NamedTextColor.YELLOW))
                .append(Component.text(winnerName(winner, tool), Match.teamColor(game, winner)))
                .append(Component.text("!", NamedTextColor.YELLOW));
    }

    private static Title minemenVerdict(Game game, boolean won, Player winner) {
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        return Title.title(
                Component.text(won ? "VICTORY!" : "DEFEATED!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), tone)
                        .append(Component.text(" won the Match!", NamedTextColor.WHITE)),
                Match.times(game, 0, 40, 10));
    }

    // captured: [gold]"IgnasSk"[gray]" was killed by "[aqua]"Dogln"[gray]"." - names are rank-colored on
    // hypixel and always "was killed by", whatever actually happened; aqua names here (no ranks)
    private static Component hypixelDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb,
                                          @Nullable String tool) {
        Component name = Component.text(loser + " ", game.roster().team(seat.team()).color());
        if (verb == null) return name.append(Component.text("forfeited.", NamedTextColor.GRAY));
        // captured: "Dogln fell into the void." - a death nobody is credited for is no forfeit
        if (winner == null) return name.append(Component.text(verb + ".", NamedTextColor.GRAY));
        return name.append(Component.text(verb + " ", NamedTextColor.GRAY))
                .append(Component.text(winnerName(winner, tool), Match.teamColor(game, winner)))
                .append(Component.text(".", NamedTextColor.GRAY));
    }

    // captured: yellow prose, red digit, "1 second!" singular; the intro block lands at zero
    // captured: a 5-second count speaks every second, a 20-second one at 20, 10 and the last five; the number
    // yellow at 20, gold at 10, red from 5
    private static @Nullable Component hypixelNumber(int n) {
        if (n > 5 && n % 10 != 0) return null;
        NamedTextColor tone = n > 10 ? NamedTextColor.YELLOW : n > 5 ? NamedTextColor.GOLD : NamedTextColor.RED;
        return Component.text("The game starts in ", NamedTextColor.YELLOW)
                .append(Component.text(n, tone))
                .append(Component.text(n == 1 ? " second!" : " seconds!", NamedTextColor.YELLOW));
    }

    // captured: the digit titled at 10 (green) and from 5 (5 and 4 yellow, 3 to 1 red)
    private static @Nullable Component hypixelDigit(int n) {
        if (n == 10) return Component.text(n, NamedTextColor.GREEN);
        if (n > 5) return null;
        return Component.text(n, n >= 4 ? NamedTextColor.YELLOW : NamedTextColor.RED);
    }

    private static @Nullable Component hypixelStarted(Game game, Player viewer) {
        Mode mode = Matches.modeOf(game);
        if (mode == null || mode.intro().isEmpty()) return null;
        boolean duel = mode.family() == Mode.Family.DUEL;
        String title = duel ? Character.toUpperCase(mode.name().charAt(0)) + mode.name().substring(1) + " Duel" : mode.banner();
        HypixelIntro intro = HypixelIntro.of(title);
        mode.intro().forEach(intro::line);
        Player foe = duel ? Match.opponent(game, viewer) : null; // captured: a game's banner names no opponent
        return intro.opponent(foe != null ? foe.getUsername() : null).render();
    }

    // captured: gray "Cages open in ", the count green at 5-4, yellow 3-2, red at 1; silent at zero
    private static Component scrimsNumber(int n) {
        NamedTextColor tone = n >= 4 ? NamedTextColor.GREEN : n >= 2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text("Cages open in ", NamedTextColor.GRAY).append(Component.text(n + "s", tone));
    }

    // captured: a scoring mode shows both players "<TEAM> WINS!" over the bold final score; a plain duel
    // shows DEFEAT! (VICTORY! inferred green - only the losing side was captured) over "<name> won the Duel!"
    private static Title hypixelVerdict(Game game, boolean won, Player winner) {
        Mode mode = Matches.modeOf(game);
        Title.Times times = Match.times(game, 0, 40, 10);
        // captured (Bed Wars): gold bold VICTORY! and red bold GAME OVER!, no subtitle, held 100 ticks
        if (mode != null && mode.family() == Mode.Family.GAME) {
            return Title.title(won ? Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD)
                    : Component.text("GAME OVER!", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.empty(), Match.times(game, 0, 100, 0));
        }
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
            return Title.title(
                    Component.text(Palette.name(team) + " WINS!", team.color(), TextDecoration.BOLD),
                    score, times);
        }
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        return Title.title(
                Component.text(won ? "VICTORY!" : "DEFEAT!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), NamedTextColor.AQUA)
                        .append(Component.text(" won the Duel!", NamedTextColor.WHITE)),
                times);
    }

    // scrims: winners "Victory!" gold, losers "Defeat" light red - plain, no subtitle
    private static Title scrimsVerdict(Game game, boolean won, Player winner) {
        return Title.title(
                won ? Component.text("Victory!", NamedTextColor.GOLD) : Component.text("Defeat", NamedTextColor.RED),
                Component.empty(),
                Match.times(game, 0, 40, 10));
    }

    // captured: [red]"Dogln "[gray]"fell into the void." - team-colored names, gray prose, a period
    private static Component scrimsDeath(Game game, Slot seat, String loser, @Nullable Player winner, @Nullable String verb,
                                         @Nullable String tool) {
        Component line = Component.text(loser + " ", game.roster().team(seat.team()).color());
        if (verb == null) return line.append(Msg.SCRIMS.prose("forfeited."));
        if (winner == null) return line.append(Msg.SCRIMS.prose(verb + "."));
        return line.append(Msg.SCRIMS.prose(verb + " "))
                .append(Component.text(winnerName(winner, tool), Match.teamColor(game, winner)))
                .append(Msg.SCRIMS.prose("."));
    }
}
