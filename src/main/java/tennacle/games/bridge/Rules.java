package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Duels;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Goals, the void line, respawn protection, and the build band. Shoves never hurt. */
public final class Rules implements GameModule {

    private static final String TIME_UP_ALARM = "bridge:time-up";
    /** A shove credits the void kill for this long. */
    private static final long CREDIT_TICKS = 100;

    private record Hit(Player by, long tick) {}

    private long goalCooldownUntil;
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Set<Point> placed = new HashSet<>();
    private int minBuildY;
    private int maxBuildY;

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        RegionMarker bounds = game.arena().markers(Markers.BOUNDS).getFirst();
        LimitMarker voidLine = game.arena().markers(Markers.VOID).getFirst();
        double voidY = voidLine.y() != null ? voidLine.y() : Double.NEGATIVE_INFINITY;
        RegionMarker build = game.arena().markers(Bridge.BUILD).getFirst();
        int floor = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        for (var key : List.of(Bridge.SECTION, Bridge.CENTER)) {
            for (RegionMarker part : game.arena().markers(key)) {
                for (RegionMarker.Cuboid box : part.boxes()) {
                    floor = Math.min(floor, box.min().blockY());
                    top = Math.max(top, box.max().blockY());
                }
            }
        }
        minBuildY = floor; // never beneath the bridge's underside
        maxBuildY = top + game.rules().resolve(Scope.GAME, Bridge.BUILD_HEIGHT) - 1;

        game.on(DamageEvent.class, DamageEvent::target, e -> {
            e.amount(0);
            if (e.target() instanceof Player victim && e.source() instanceof Player attacker && attacker != victim) {
                protectedUntil.remove(attacker.getUuid()); // swinging forfeits your own protection
                lastHit.put(victim.getUuid(), new Hit(attacker, st.tick()));
            }
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Long until = protectedUntil.get(victim.getUuid());
            if (until != null && st.tick() < until) e.setCancelled(true);
        });
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            if (!buildable(build, pos) || onASpawn(game, pos)) {
                e.setCancelled(true);
                Msg.err(player, "bridge", "you can't build there");
                return;
            }
            placed.add(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            protectedUntil.remove(player.getUuid());
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean own = placed.remove(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            if (!own && !inBridge(game, pos)) {
                e.setCancelled(true);
                Msg.err(player, "bridge", "you can't break that");
                return;
            }
            protectedUntil.remove(player.getUuid());
        });
        game.on(PlayerMoveEvent.class, e -> {
            Player player = e.getPlayer();
            Slot seat = game.roster().slotOf(player);
            if (seat == null) return;
            Pos to = e.getNewPosition();
            if (to.y() < voidY || !bounds.contains(to)) {
                died(game, player, seat);
                return;
            }
            if (!"fighting".equals(st.get(Scope.GAME, Duels.PHASE))) return;
            if (st.tick() < goalCooldownUntil) return;
            for (RegionMarker goal : game.arena().markers(Bridge.GOAL)) {
                if (goal.group().equals(seat.team()) || !goal.contains(to)) continue;
                goalCooldownUntil = st.tick() + 40;
                score(game, player, seat);
                return;
            }
        });
        st.alarms().register(TIME_UP_ALARM, (tick, payload) -> timeUp(game));
        st.alarms().arm(TIME_UP_ALARM, st.tick() + game.rules().resolve(Scope.GAME, Bridge.LIMIT));
    }

    private void died(Game game, Player player, Slot seat) {
        PosMarker back = game.arena().marker(Bridge.RESPAWN, seat.team());
        player.teleport(back != null ? back.pos() : spawnOf(game, seat));
        protectedUntil.put(player.getUuid(),
                game.state().tick() + game.rules().resolve(Scope.GAME, Bridge.PROTECT));
        announceFall(game, player);
    }

    private boolean buildable(RegionMarker build, Point pos) {
        if (pos.blockY() < minBuildY || pos.blockY() > maxBuildY) return false;
        for (RegionMarker.Cuboid box : build.boxes()) { // slices gate X/Z only - Y is the band above
            if (pos.blockX() >= box.min().blockX() && pos.blockX() <= box.max().blockX()
                    && pos.blockZ() >= box.min().blockZ() && pos.blockZ() <= box.max().blockZ()) return true;
        }
        return false;
    }

    private static boolean onASpawn(Game game, Point pos) {
        for (PosMarker at : game.arena().markers(Markers.SPAWN)) if (spawnBlock(at, pos)) return true;
        for (PosMarker at : game.arena().markers(Bridge.RESPAWN)) if (spawnBlock(at, pos)) return true;
        return false;
    }

    private static boolean spawnBlock(PosMarker at, Point pos) {
        return pos.blockX() == at.pos().blockX() && pos.blockZ() == at.pos().blockZ()
                && (pos.blockY() == at.pos().blockY() || pos.blockY() == at.pos().blockY() + 1);
    }

    private static boolean inBridge(Game game, Point pos) {
        for (RegionMarker part : game.arena().markers(Bridge.SECTION)) if (part.contains(pos)) return true;
        for (RegionMarker part : game.arena().markers(Bridge.CENTER)) if (part.contains(pos)) return true;
        return false;
    }

    private void announceFall(Game game, Player fallen) {
        if (!"fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) return;
        Hit hit = lastHit.remove(fallen.getUuid());
        Player by = hit != null && game.state().tick() - hit.tick() <= CREDIT_TICKS ? hit.by() : null;
        if (by != null) {
            addSeat(game, by, Bridge.KILLS);
            if ("minemen".equals(Duels.lookNameOf(game))) by.sendMessage(Banners.kill(game, fallen));
        }
        Component line = Banners.fallLine(game, fallen, by);
        Duels.each(game, p -> p.sendMessage(line));
    }

    private static void addSeat(Game game, Player player, StateKey<Integer> key) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null) return;
        Scope scope = Scope.slot(seat.index());
        game.state().set(scope, key, game.state().get(scope, key) + 1);
    }

    private static void timeUp(Game game) {
        GameState st = game.state();
        if (!"fighting".equals(st.get(Scope.GAME, Duels.PHASE))) return;
        String leader = null;
        int best = -1;
        boolean tie = false;
        for (Team team : game.roster().teams()) {
            int goals = st.get(Scope.team(team.id()), Bridge.GOALS);
            if (goals > best) { best = goals; leader = team.id(); tie = false; }
            else if (goals == best) tie = true;
        }
        String winning = leader;
        Player loser = tie ? null : game.members().stream()
                .filter(m -> {
                    Slot seat = game.roster().slotOf(m);
                    return seat != null && !seat.team().equals(winning);
                }).findFirst().orElse(null);
        if (loser != null) {
            Duels.settle(game, loser);
            return;
        }
        st.set(Scope.GAME, Duels.PHASE, "done");
        Duels.each(game, p -> p.sendMessage(Msg.SCRIMS.prose("Time's up - draw.")));
        game.decide(Outcome.draw(), 60);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player quitter, @NotNull Game.Departure departure) {
        if (!departure.reserved()) Duels.settle(game, quitter, null); // a held seat waits out its window
    }

    private void score(Game game, Player scorer, Slot seat) {
        GameState st = game.state();
        int goals = st.get(Scope.team(seat.team()), Bridge.GOALS) + 1;
        st.set(Scope.team(seat.team()), Bridge.GOALS, goals);
        var color = game.roster().team(seat.team()).color();
        Component banner = switch (Duels.lookNameOf(game)) {
            case "minemen" -> null; // captured: minemen announces goals on the board and title only
            case "hypixel" -> Banners.hypixel(game, scorer, color, goals);
            default -> Banners.scrims(game, scorer, color);
        };
        if (banner != null) Duels.each(game, p -> p.sendMessage(banner));
        if (goals >= game.rules().resolve(Scope.GAME, Bridge.TARGET)) {
            Player loser = game.members().stream()
                    .filter(m -> game.roster().slotOf(m) != null
                            && !seat.team().equals(game.roster().slotOf(m).team()))
                    .findFirst().orElse(null);
            if (loser != null) Duels.settle(game, loser);
            return;
        }
        Title scored = Title.title(Banners.scoredTitle(game, scorer, color), Component.empty(),
                Duels.times(game, 0, 30, 10));
        Duels.cue(game, SoundSet.SCORED);
        for (Player member : game.members()) {
            member.showTitle(scored);
            Slot memberSeat = game.roster().slotOf(member);
            if (memberSeat != null) member.teleport(spawnOf(game, memberSeat));
        }
        st.alarms().arm(Cages.RECAGE_ALARM, st.tick() + 1);
    }

    private static Pos spawnOf(Game game, Slot seat) {
        return seat.spawn() != null ? seat.spawn() : game.arena().meta().spawn();
    }
}
