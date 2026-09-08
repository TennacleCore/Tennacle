package tennacle.games.watch;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import java.util.function.Supplier;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.roster.Slot;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.Material;
import java.util.ArrayList;
import java.util.List;
import tennacle.games.Matches;
import tennacle.games.Mode;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.polyp.platform.SharedTeam;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import io.github.term4.polyp.mechanics.projectile.entities.arrow.StuckArrows;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import io.github.term4.canvas.screen.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.item.ItemStack;
import io.github.term4.archipelago.shard.ShardPolicy;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerSpectateEntityEvent;
import net.minestom.server.entity.attribute.Attribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;

/**
 * A viewer enters as a {@link SpectatorMode.Mode#GHOST}: adventure, hovering, landed inside the player they asked
 * for, the stat bars off a 1.8 screen. NOT invisible - the shard scoping already hides an unbound viewer from the
 * game's members, so the spectator still sees their own body, the scrims way. In the game's tab they list grayed,
 * to the members and the other spectators; Grotto keeps their hands off the map. {@link #mode} switches them into
 * the client's TRUE spectator mode and back; a true spectator rides the camera of a member they attack-click.
 */
public final class Spectators {

    private Spectators() {}

    public static void install() {
        Lineup.install();
        // a watcher is in nobody's fight: their fists reach no one and no one's reach them. Binding cannot say
        // it - a watcher is bound to nothing, and so is every other watcher standing beside them. Extends
        // ShardPolicy, never WorldPolicy: a bare one reverts sight to binding equality and blinds them all
        WorldPolicy.set(new ShardPolicy() {
            @Override
            public boolean affects(@NotNull net.minestom.server.entity.Entity actor,
                                   @NotNull net.minestom.server.entity.Entity target) {
                return !watches(actor) && !watches(target) && ShardPolicy.super.affects(actor, target);
            }
        });
        // the compass: the picker of the players alive; Grotto refuses a spectator's use, so listen past the cancel
        MinecraftServer.getGlobalEventHandler().addListener(EventListener.builder(PlayerUseItemEvent.class)
                .ignoreCancelled(false).handler(e -> righted(e.getPlayer())).build());
        MinecraftServer.getGlobalEventHandler().addListener(EventListener.builder(PlayerBlockInteractEvent.class)
                .ignoreCancelled(false).handler(e -> righted(e.getPlayer())).build());
        MinecraftServer.getGlobalEventHandler().addListener(InventoryPreClickEvent.class, e -> {
            Player who = e.getPlayer();
            if ((Matches.grotto().spectating(who) != null || ghosting(who) != null) && Screens.screen(who) == null) e.setCancelled(true);
        });
        // a spectator's click is the 26.x spectate-entity packet, never an attack: ViaBackwards rewrites a legacy
        // spectator's attack into it too (EntityPacketRewriter26_1, by the game mode it tracks)
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpectateEntityEvent.class, e -> ride(e.getPlayer(), e.getTarget()));
        MinecraftServer.getGlobalEventHandler().addListener(EntityAttackEvent.class, e -> {
            if (e.getEntity() instanceof Player viewer) ride(viewer, e.getTarget());
        });
        // a spectator lists only in the game they watch (to its members and its other spectators), grayed
        ShardTabList.rule((viewer, subject) -> {
            Game watched = Matches.grotto().spectating(subject);
            if (watched == null) return null; // not a spectator: the viewer's scope decides
            if (Game.of(viewer) == watched) return Spectating.listed(watched); // spectate:listed - the members' view
            return Matches.grotto().spectating(viewer) == watched;
        });
    }

    /** Whether {@code who} is watching rather than playing - a game's crowd, or a replay's. */
    public static boolean watches(@NotNull net.minestom.server.entity.Entity who) {
        return who instanceof Player p && (Matches.grotto().spectating(p) != null || Replays.watching(p) != null);
    }

    // a true spectator rides the camera of a player in the game they watch; anyone else is not a target
    private static void ride(Player viewer, net.minestom.server.entity.Entity clicked) {
        if (SpectatorMode.mode(viewer) != SpectatorMode.Mode.TRUE || !(clicked instanceof Player target)) return;
        Game watched = Matches.grotto().spectating(viewer);
        if (watched != null && (Game.of(target) == watched || watched.isGhost(target))) SpectatorMode.camera(viewer, target);
    }

    /**
     * Puts {@code viewer} on {@code game}'s main world as a ghost: at {@code target}'s position, or the map's landing,
     * holding nothing but the players' compass.
     */
    public static void enter(@NotNull Game game, @NotNull Player viewer, @Nullable Player target) {
        SpectatorMode.enter(viewer, SpectatorMode.Mode.GHOST);
        freshen(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, true); // landed inside a player: no push, either way
        game.spectate(viewer, target != null ? target.getPosition() : null); // Nametags greys the tab name
        viewer.getInventory().clear();
        viewer.getInventory().setItemStack(COMPASS_SLOT, Lineup.compass());
        viewer.getInventory().setItemStack(FEATHER_SLOT, feather());
        // the compass starts on whom they came to watch; a spectator's own aim carries through to theirs
        Supplier<Pos> inherited = target != null ? Lineup.aimOf(target) : null;
        if (inherited != null) Lineup.aim(viewer, Lineup.following(target), inherited);
        else if (target != null) Lineup.aim(viewer, target.getUsername(),
                () -> target.isOnline() && Game.of(target) == game ? target.getPosition() : null);
    }

    /**
     * Puts a watcher who left the arena back on it: where the side they follow respawns, so they come back where
     * the play is, else the map's own watching spot.
     */
    public static void recall(@NotNull Game game, @NotNull Player viewer) {
        Slot seat = followed(game, viewer);
        Respawns respawns = game.module(Respawns.class);
        Pos at = seat == null ? null : respawns != null ? respawns.spawnOf(seat) : seat.spawn();
        if (at == null) at = game.spectatorLanding();
        if (at != null) viewer.teleport(at.withPitch(0), Vec.ZERO); // level: a marker authored looking down would tilt it
    }

    // down the chain of aims: whoever a watcher follows may be watching someone themselves
    private static @Nullable Slot followed(Game game, Player viewer) {
        Player who = viewer;
        for (int hop = 0; hop < 8; hop++) { // a pair aimed at each other would otherwise circle
            String name = Lineup.following(who);
            if (name == null) return null;
            Player member = game.memberNamed(name);
            if (member != null) return game.roster().slotOf(member);
            who = null;
            for (Player watcher : game.spectators()) {
                if (watcher.getUsername().equals(name)) who = watcher;
            }
            if (who == null) return null;
        }
        return null;
    }

    /** Whom {@code /spectate} can name: the seats, ghosts and viewers of every running game that takes them -
     *  none of whom a lobby tab lists, so the completion cannot come off the viewer's own scope. */
    public static @NotNull List<String> watchable() {
        List<String> names = new ArrayList<>();
        for (Game game : Matches.grotto().games()) {
            if (game.status() != Game.Status.OPEN || !Spectating.allowed(game)) continue;
            for (Player p : game.members()) names.add(p.getUsername());
            for (Player p : game.spectators()) names.add(p.getUsername());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Drops the view - a game's or a replay's - and gives the body back; {@code false} = was watching nothing. */
    public static boolean exit(@NotNull Player viewer) {
        // every route out calls this first, and a theater's dressing would otherwise ride along to wherever
        // they land: a tab row still flagged spectator draws them INVISIBLE to every other client
        boolean theater = Replays.leave(viewer);
        Game watched = Matches.grotto().spectating(viewer);
        boolean wore = SpectatorMode.exit(viewer); // the camera home and the body back before the world changes
        if (watched == null && !wore) return theater;
        if (watched != null) {
            watched.unspectate(viewer);
            viewer.getInventory().clear(); // the lobby dresses the arrival
            Lineup.unaim(viewer);
        }
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, false);
        return true;
    }

    /** The hotbar slots a watcher carries. */
    public static final int COMPASS_SLOT = 0, FEATHER_SLOT = 1;

    // a right click, whatever it landed on: each tool reads its own slot, and a left click reaches neither
    private static void righted(Player viewer) {
        compass(viewer);
        feather(viewer);
    }

    /** The feather a watcher carries: a right click swaps their body between the two spectator modes. */
    public static @NotNull ItemStack feather() {
        return Items.named(Material.FEATHER, Component.text("Spectator mode", NamedTextColor.AQUA));
    }

    // only a watcher of a game carries one: a seat ghosting out a respawn has no mode to swap
    private static void feather(Player viewer) {
        if (Matches.grotto().spectating(viewer) == null || viewer.getHeldSlot() != FEATHER_SLOT
                || viewer.getItemInMainHand().material() != Material.FEATHER) return;
        mode(viewer, SpectatorMode.mode(viewer) == SpectatorMode.Mode.TRUE
                ? SpectatorMode.Mode.GHOST : SpectatorMode.Mode.TRUE);
    }

    // the compass in hand: the seated, living members of the watched game, a click landing the viewer at one
    private static void compass(Player viewer) {
        Game watched = Matches.grotto().spectating(viewer);
        if (watched == null) watched = ghosting(viewer);
        if (watched == null || viewer.getHeldSlot() != COMPASS_SLOT
                || viewer.getItemInMainHand().material() != Material.COMPASS) return;
        Lineup.open(viewer, alive(watched));
    }

    // the game whose dead seat this is, waiting on a respawn: unbound, so the shard tag cannot say
    private static @Nullable Game ghosting(Player p) {
        for (Game game : Matches.grotto().games()) {
            if (game.isGhost(p)) return game;
        }
        return null;
    }

    /** A watcher's body as a fresh one: on its feet, full bars, nothing on it, nothing in it. */
    public static void freshen(@NotNull Player p) {
        io.github.term4.grotto.Bodies.revive(p); // a corpse watches invisibly: the hide outlives the death
        p.setHealth((float) p.getAttributeValue(Attribute.MAX_HEALTH));
        p.setFood(20);
        p.setFoodSaturation(5f);
        p.clearEffects();
        p.setExp(0f);
        p.setLevel(0);
        p.setFireTicks(0);
        p.setAdditionalHearts(0f);
        StuckArrows.clear(p);
    }

    /** A dead seat waiting on its respawn: nothing in hand but the players' compass; the respawn re-kits. */
    public static void ghostKit(@NotNull Player p) {
        p.getInventory().clear();
        p.getInventory().setItemStack(0, Lineup.compass());
    }

    /** The seated members of {@code game} still on their feet, as the compass lists them. */
    public static @NotNull List<Lineup.Entry> alive(@NotNull Game game) {
        Respawns respawns = game.module(Respawns.class);
        List<Lineup.Entry> out = new ArrayList<>();
        for (Player member : game.members()) {
            Slot seat = game.roster().slotOf(member);
            if (seat == null || game.isGhost(member) || (respawns != null && respawns.isDead(member))) continue;
            out.add(new Lineup.Entry(member.getUsername(), game.roster().team(seat.team()).color(), member.getSkin(),
                    () -> member.isOnline() && Game.of(member) == game ? member.getPosition() : null));
        }
        return out;
    }

    /** Switches a spectating {@code viewer}'s body, telling them; {@code false} = not spectating. */
    public static boolean mode(@NotNull Player viewer, @NotNull SpectatorMode.Mode mode) {
        if (Matches.grotto().spectating(viewer) == null) return false;
        if (!SpectatorMode.enter(viewer, mode)) {
            Msg.fail(viewer, "Your client has no spectator mode (1.7).");
        } else if (mode == SpectatorMode.Mode.TRUE) {
            Msg.say(viewer, "Spectator mode: click a player to ride their camera, sneak to leave it. ",
                    Msg.run("/spec mode ghost"), " for the ghost body.");
        } else {
            Msg.say(viewer, "Ghost body. ", Msg.run("/spec mode true"), " for spectator mode.");
        }
        return true;
    }

    /**
     * A game-mode command from a spectator: {@code spectator} and {@code adventure} are the two bodies, anything
     * else is refused - the ghost's spoof would swallow it silently. {@code false} = not spectating, set it as asked.
     */
    public static boolean gamemode(@NotNull Player viewer, @NotNull GameMode asked) {
        if (Matches.grotto().spectating(viewer) == null) return false;
        switch (asked) {
            case SPECTATOR -> mode(viewer, SpectatorMode.Mode.TRUE);
            case ADVENTURE -> mode(viewer, SpectatorMode.Mode.GHOST);
            default -> Msg.fail(viewer, "You're spectating: ", Msg.run("/spec mode true"), " or ",
                    Msg.run("/spec mode ghost"), ", or ", Msg.run("/l"), " first.");
        }
        return true;
    }
}
