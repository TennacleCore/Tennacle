package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.looks.Frames;

import io.github.term4.canvas.theme.Theme;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Bridge's board rows per network; the chrome comes from {@link Frames}. */
final class Boards {

    private Boards() {}

    static Scoreboard scrims() {
        return Frames.scrims("Bridge", Boards::scrimsRows);
    }

    // the house board is scrims' rows in the house frame
    static Scoreboard tennacle() {
        return Frames.tennacle("Bridge", Boards::scrimsRows);
    }

    // captured: clock, badged goal meters, the players
    private static void scrimsRows(Scoreboard b) {
        b.line((g, me) -> Component.text("Time: ", NamedTextColor.WHITE)
                        .append(Component.text(clock(TimeLimit.left(g)), Frames.timeColor(g, TimeLimit.left(g)))))
                .blank()
                .line((g, me) -> meterLine(g, teamAt(g, 0)), Bridge.GOALS, Score.TARGET)
                .line((g, me) -> meterLine(g, teamAt(g, 1)), Bridge.GOALS, Score.TARGET)
                .blank()
                .rows(g -> names(g, teamAt(g, 0)))
                .rows(g -> names(g, teamAt(g, 1)));
    }

    // captured: [R]/[B] circle meters, Kills/Goals
    static Scoreboard hypixel() {
        return Frames.hypixel("BRIDGE", "Bridge Duel",
                g -> TimeLimit.left(g), b -> b
                .line((g, me) -> circles(g, teamAt(g, 0), Msg.HYPIXEL, false), Bridge.GOALS, Score.TARGET)
                .line((g, me) -> circles(g, teamAt(g, 1), Msg.HYPIXEL, false), Bridge.GOALS, Score.TARGET)
                .blank()
                .line((g, me) -> Frames.hypixelRow("Kills: ", mySeat(g, me, Bridge.KILLS)), Bridge.KILLS)
                .line((g, me) -> Frames.hypixelRow("Goals: ", myTeamGoals(g, me)), Bridge.GOALS));
    }

    // captured: bold [B]/[R] circle meters, Kills, Time Left
    static Scoreboard minemen() {
        return Frames.minemen("Bridge", b -> b
                .line((g, me) -> circles(g, teamAt(g, 1), Msg.MINEMEN, true), Bridge.GOALS, Score.TARGET)
                .line((g, me) -> circles(g, teamAt(g, 0), Msg.MINEMEN, true), Bridge.GOALS, Score.TARGET)
                .blank()
                .line((g, me) -> Frames.minemenRow("Kills: ", mySeat(g, me, Bridge.KILLS)), Bridge.KILLS)
                .blank()
                .line((g, me) -> Frames.minemenRow("Time Left: ", Frames.timeLeft(g))));
    }

    static Team teamAt(Game game, int index) {
        List<Team> teams = game.roster().teams();
        return index < teams.size() ? teams.get(index) : Team.undeclared("");
    }

    // heavy bold arrow + team badge square + the goal meter (siblings: bold must not bleed into the meter)
    private static Component meterLine(Game game, Team team) {
        return Component.empty()
                .append(Component.text("➜ ", team.color(), TextDecoration.BOLD))
                .append(Msg.SCRIMS.meter(game.state().get(Scope.team(team.id()), Bridge.GOALS),
                        game.rules().resolve(Scope.GAME, Score.TARGET), team.color()));
    }

    // captured: a row per seat held on the side, in seat order - the holder, or who a walked-out seat waits for
    private static List<Component> names(Game game, Team team) {
        List<Component> out = new ArrayList<>();
        for (Slot seat : game.roster().slots()) {
            if (!seat.team().equals(team.id())) continue;
            String name = game.roster().ownerOf(seat);
            if (name == null) name = game.roster().reservedFor(seat);
            if (name != null) out.add(Component.text(name, team.color()));
        }
        return out;
    }

    // captured on both networks: "[R] " team label + team-colored filled circles, the theme's empties
    private static Component circles(Game game, Team team, Theme theme, boolean boldLabel) {
        int goals = game.state().get(Scope.team(team.id()), Bridge.GOALS);
        int target = Math.max(goals, game.rules().resolve(Scope.GAME, Score.TARGET));
        String tag = "[" + tennacle.games.Palette.letter(team) + "] ";
        Component label = Component.text(tag, team.color());
        if (boldLabel) label = label.decorate(TextDecoration.BOLD);
        return Component.empty().append(label).append(theme.circles(goals, target, team.color()));
    }

    private static int mySeat(Game game, Player me, StateKey<Integer> key) {
        Slot seat = game.roster().slotOf(me);
        return seat != null ? game.state().get(Scope.slot(seat.index()), key) : 0;
    }

    // captured scrims clock: minutes unpadded
    private static String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + "%02d".formatted(seconds % 60);
    }

    private static int myTeamGoals(Game game, Player me) {
        Slot seat = game.roster().slotOf(me);
        return seat != null ? game.state().get(Scope.team(seat.team()), Bridge.GOALS) : 0;
    }

}
