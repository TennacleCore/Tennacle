package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.lives.Lives;
import tennacle.games.shop.Price;
import tennacle.games.watch.Lineup;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static tennacle.games.bedwars.Resources.EMERALD;

/**
 * The compass (captured 2026-09-27): it comes with the kit, opens the Tracker & Communication window, and points at
 * what the seat asked for last. A bought tracker follows the nearest living player of its team until a death; a
 * quick message's base, team or resource holds otherwise; with neither, the nearest teammate, or home in Solo.
 */
final class Compass implements GameModule {

    static final ItemStack ITEM = Items.named(Material.COMPASS, Component.text("Compass ", NamedTextColor.GREEN)
            .append(Component.text("(Right Click)", NamedTextColor.GRAY)));
    /** Captured: the main inventory's top right, every life. */
    static final int SLOT = 17;

    /** The team a seat's tracker follows, {@code ""} for none. */
    static final StateKey<String> TRACKING = StateKey.of("ledger.bedwars.tracking", Codec.STRING, "", true);
    static final StateKey<Price> TRACKER_PRICE = StateKey.of("shop.tracker", Price.CODEC, Price.of(2, EMERALD));

    private static final List<String> TRACKER_LORE = List.of("Purchase tracking upgrade for your",
            "compass which will track each player", "on a specific team until you die.");

    private static final String READOUT = "compass:readout";

    private final Map<Player, Pos> pinned = new ConcurrentHashMap<>();
    private Game game;
    private Respawns respawns;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(TRACKER_PRICE);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        Lives lives = game.module(Lives.class);
        Phases phases = game.module(Phases.class);
        if (respawns == null || lives == null || phases == null) {
            throw new IllegalStateException("Compass needs Phases, Respawns and Lives installed before it");
        }
        Lineup.install();
        game.on(PlayerUseItemEvent.class, e -> {
            Player player = e.getPlayer();
            if (e.getHand() != PlayerHand.MAIN || e.getItemStack().material() != Material.COMPASS
                    || game.roster().slotOf(player) == null || game.isGhost(player)) return;
            menu(game, player);
        });
        phases.onEnter(Match.LIVE, g -> g.members().forEach(this::aim));
        respawns.onRespawn((player, seat) -> aim(player)); // after Lives' own, which unaims
        lives.onDeath(death -> {
            game.state().set(Scope.slot(death.seat().index()), TRACKING, "");
            pinned.remove(death.victim());
        });
        game.state().alarms().register(READOUT, (tick, payload) -> {
            game.state().alarms().arm(READOUT, game.tick() + 5);
            for (Player member : game.members()) readout(member);
        });
        game.state().alarms().arm(READOUT, game.tick() + 5);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        pinned.remove(player);
        Lineup.unaim(player);
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        for (Player member : game.members()) Lineup.unaim(member);
        pinned.clear();
    }

    static void menu(Game game, Player viewer) {
        boolean teams = game.rules().resolve(Scope.GAME, Roster.TEAM_SIZE) > 1; // captured: by mode, not by who is on the team
        Screen.Builder window = Screen.rows(3).title(Component.text("Tracker & Communication"));
        if (teams) {
            window.button(11, Items.bare(Items.lore(Items.named(Material.EMERALD, Component.text("Quick Communications", NamedTextColor.GREEN)),
                    List.of(Items.gray("Send highlighted chat messages to"), Items.gray("your teammates!"), Component.empty(),
                            Component.text("Click to open!", NamedTextColor.YELLOW)))),
                    (v, click) -> QuickComms.open(game, v));
        }
        List<Component> lore = new ArrayList<>(trackerLore());
        lore.add(Component.empty());
        lore.add(Component.text("Click to open!", NamedTextColor.YELLOW));
        window.button(teams ? 15 : 13, Items.bare(Items.lore(Items.named(Material.COMPASS,
                Component.text("Tracker Shop", NamedTextColor.GREEN)), lore)), (v, click) -> TrackerShop.open(game, v, true));
        Screens.open(viewer, window.build());
    }

    static List<Component> trackerLore() {
        return Items.gray(TRACKER_LORE.toArray(String[]::new));
    }

    /** Points {@code player}'s compass by what their seat asked for last. */
    void aim(Player player) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null) return;
        String tracked = game.state().get(Scope.slot(seat.index()), TRACKING);
        if (!tracked.isEmpty()) {
            Lineup.aim(player, () -> at(nearest(player, tracked)));
            return;
        }
        Pos pin = pinned.get(player);
        if (pin != null) {
            Lineup.aim(player, () -> pin);
        } else if (game.rules().resolve(Scope.GAME, Roster.TEAM_SIZE) > 1) {
            Lineup.aim(player, () -> at(nearest(player, seat.team())));
        } else {
            Pos home = seat.spawn();
            Lineup.aim(player, () -> home);
        }
    }

    /** A quick message's point; false while a tracker holds the compass. */
    boolean pin(Player player, Pos at) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null || !game.state().get(Scope.slot(seat.index()), TRACKING).isEmpty()) return false;
        pinned.put(player, at);
        aim(player);
        return true;
    }

    private static @Nullable Pos at(@Nullable Player player) {
        return player != null ? player.getPosition() : null;
    }

    // living only, never from
    private @Nullable Player nearest(Player from, String team) {
        Player best = null;
        double closest = Double.MAX_VALUE;
        for (Player member : game.members()) {
            if (member == from || !team.equals(Match.teamOf(game, member)) || game.isGhost(member)
                    || respawns.isDead(member) || member.getInstance() != from.getInstance()) continue;
            double distance = member.getPosition().distanceSquared(from.getPosition());
            if (distance < closest) {
                best = member;
                closest = distance;
            }
        }
        return best;
    }

    // captured once a second; ours a quarter of one
    private void readout(Player player) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null || game.isGhost(player)) return;
        String tracked = game.state().get(Scope.slot(seat.index()), TRACKING);
        Player target = tracked.isEmpty() ? null : nearest(player, tracked);
        if (target == null) return;
        int meters = (int) player.getPosition().distance(target.getPosition());
        player.sendActionBar(Lines.tracking(target.getUsername(), game.roster().team(tracked).color(), meters));
    }

    /** The teams still in the game but {@code viewer}'s. */
    static List<Team> enemies(Game game, Player viewer) {
        String own = Match.teamOf(game, viewer);
        LastStanding standing = game.module(LastStanding.class);
        return game.roster().teams().stream()
                .filter(team -> !team.id().equals(own) && (standing == null || standing.stands(team.id())))
                .toList();
    }
}
