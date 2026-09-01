package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Duels;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
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

    static final String TIME_UP_ALARM = "bridge:time-up"; // armed by Cages at the first GO
    /** A shove credits the void kill for this long. */
    private static final long CREDIT_TICKS = 100;

    private record Hit(Player by, long tick) {}

    static final String REFIT_ALARM = "bridge:refit";
    private static final String QUIVER_ALARM = "bridge:quiver";
    private static final int ARROW_TICKS = 70; // 3.5s, the termbridge/hypixel return time
    private static final Sound ARROW_BACK =
            Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1f);

    private long goalCooldownUntil;
    private final Map<Player, Long> arrowDue = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Set<Point> placed = new HashSet<>();
    private int minBuildY;
    private int maxBuildY;
    private List<RegionMarker> goals;

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        RegionMarker build = game.arena().markers(Bridge.BUILD).getFirst();
        int floor = Integer.MAX_VALUE;
        for (var key : List.of(Bridge.SECTION, Bridge.CENTER)) {
            for (RegionMarker part : game.arena().markers(key)) {
                for (RegionMarker.Cuboid box : part.boxes()) {
                    floor = Math.min(floor, box.min().blockY());
                }
            }
        }
        // both planes ride the map - the starter ships the standard ones, the type expects them
        double voidY = plane(game, Bridge.VOID);
        minBuildY = floor; // never beneath the bridge's underside
        maxBuildY = (int) Math.floor(plane(game, Bridge.HEIGHT));
        goals = game.arena().markers(Bridge.GOAL); // fixed for the map: never re-queried per move packet

        boolean bowBoost = game.rules().resolve(Scope.GAME, Bridge.BOW_BOOST);
        boolean friendlyFire = game.rules().resolve(Scope.GAME, Bridge.FRIENDLY_FIRE);
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Slot seat = game.roster().slotOf(victim);
            if (seat == null) return;
            // arrows resolve source to the shooter (snapshot contract), so this credits bow kills too
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) { // a bow boost: never a "hit" - no credit, no protection forfeit
                if (!bowBoost || !"fighting".equals(st.get(Scope.GAME, Duels.PHASE))) e.amount(0);
                return;
            }
            if (source != null) {
                Slot from = game.roster().slotOf(source);
                if (!friendlyFire && from != null && from.team().equals(seat.team())) {
                    e.amount(0); // gamerule: teammates never hurt each other
                    return;
                }
                protectedUntil.remove(source.getUuid()); // swinging forfeits your own protection
                lastHit.put(victim.getUuid(), new Hit(source, st.tick()));
            }
            Long shield = protectedUntil.get(victim.getUuid());
            if (shield != null && st.tick() < shield) {
                e.amount(0);
                return;
            }
            if (!"fighting".equals(st.get(Scope.GAME, Duels.PHASE))) e.amount(0);
        });
        game.on(FatalDamageEvent.class, FatalDamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Slot seat = game.roster().slotOf(victim);
            if (seat == null) return;
            e.cancel(); // a kill respawns behind protection - never the death screen
            killed(game, victim, seat, e.source() instanceof Player by && by != victim ? by : null);
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) {
                if (!bowBoost) e.setCancelled(true); // boosting off = self arrows push nothing
                return; // protection never blocks your own boost
            }
            if (source != null && !friendlyFire) {
                Slot a = game.roster().slotOf(source);
                Slot b = game.roster().slotOf(victim);
                if (a != null && b != null && a.team().equals(b.team())) {
                    e.setCancelled(true);
                    return;
                }
            }
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
            if (to.y() < voidY) {
                died(game, player, seat);
                return;
            }
            if (!"fighting".equals(st.get(Scope.GAME, Duels.PHASE))) return;
            if (st.tick() < goalCooldownUntil) return;
            for (RegionMarker goal : goals) {
                if (goal.group().equals(seat.team()) || !goal.contains(to)) continue;
                goalCooldownUntil = st.tick() + 40;
                score(game, player, seat);
                return;
            }
        });
        game.on(net.minestom.server.event.item.ItemDropEvent.class, e -> {
            if (game.roster().slotOf(e.getPlayer()) == null) return;
            e.setCancelled(true);
            e.getPlayer().getInventory().update(); // 1.8 predicted the toss - resync the slots
        });
        game.on(net.minestom.server.event.inventory.InventoryPreClickEvent.class, e -> {
            if (game.roster().slotOf(e.getPlayer()) == null) return;
            var click = e.getClick();
            // an in-screen toss is a CLICK - Minestom clears the slot on the click result, so the drop
            // event alone can't keep the item
            if (click instanceof net.minestom.server.inventory.click.Click.DropSlot
                    || click instanceof net.minestom.server.inventory.click.Click.DropCursor) {
                e.setCancelled(true);
                return;
            }
            Material picked = e.getClickedItem().material();
            // armor only ever exists worn here - a click that grabs a leather piece is an unequip
            if (picked == Material.LEATHER_CHESTPLATE || picked == Material.LEATHER_LEGGINGS
                    || picked == Material.LEATHER_BOOTS) e.setCancelled(true);
        });
        game.on(ProjectileLaunchEvent.class, e -> e.snapshot().shooter(), e -> {
            if (!(e.snapshot().type() instanceof Arrow) || !(e.snapshot().shooter() instanceof Player shooter)) return;
            if (game.roster().slotOf(shooter) == null) return;
            startQuiver(game, shooter);
        });
        st.alarms().register(QUIVER_ALARM, (tick, payload) -> quiverTick(game));
        st.alarms().register(REFIT_ALARM, (tick, payload) -> {
            for (Player member : game.members()) {
                Slot ms = game.roster().slotOf(member);
                if (ms != null) refit(game, member, ms);
            }
        });
        st.alarms().register(TIME_UP_ALARM, (tick, payload) -> timeUp(game));
    }

    // one arrow in the quiver: shooting starts the 3.5s return, the XP bar drains it down
    private void startQuiver(Game game, Player shooter) {
        boolean idle = arrowDue.isEmpty();
        arrowDue.put(shooter, game.state().tick() + ARROW_TICKS);
        shooter.setLevel(4);
        shooter.setExp(1f);
        if (idle) game.state().alarms().arm(QUIVER_ALARM, game.state().tick() + 1);
    }

    private void quiverTick(Game game) {
        long now = game.state().tick();
        var pending = arrowDue.entrySet().iterator();
        while (pending.hasNext()) {
            var entry = pending.next();
            Player owner = entry.getKey();
            if (Game.of(owner) != game) { // left mid-flight
                pending.remove();
                continue;
            }
            long left = entry.getValue() - now;
            if (left <= 0) {
                giveArrow(owner);
                owner.playSound(ARROW_BACK);
                owner.setExp(0f);
                owner.setLevel(0);
                pending.remove();
                continue;
            }
            owner.setExp(left / (float) ARROW_TICKS);
            owner.setLevel((int) Math.ceil(left / 20.0));
        }
        if (!arrowDue.isEmpty()) game.state().alarms().arm(QUIVER_ALARM, now + 1);
    }

    // regen stacks onto an existing arrow first; a fresh one lands where the player's layout puts it
    private static void giveArrow(Player owner) {
        var inv = owner.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack in = inv.getItemStack(slot);
            if (in.material() == Material.ARROW) {
                inv.setItemStack(slot, in.withAmount(in.amount() + 1));
                return;
            }
        }
        Map<String, Integer> layout = tennacle.games.KitLayouts.of(owner, "bridge");
        int fallback = Bridge.KIT.defaultSlots().getOrDefault("arrow", 9);
        inv.setItemStack(layout != null ? layout.getOrDefault("arrow", fallback) : fallback,
                ItemStack.of(Material.ARROW));
    }

    /** Round-fresh: full health and food, effects gone, kit re-applied, the quiver reset. */
    private void refit(Game game, Player player, Slot seat) {
        arrowDue.remove(player);
        player.setExp(0f);
        player.setLevel(0);
        player.setHealth((float) player.getAttributeValue(Attribute.MAX_HEALTH));
        player.setFood(20);
        player.clearEffects();
        player.getInventory().clear();
        Bridge.KIT.apply(game, player, seat);
    }

    private static double plane(Game game, MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    private void killed(Game game, Player victim, Slot seat, @org.jetbrains.annotations.Nullable Player by) {
        PosMarker back = game.arena().marker(Bridge.RESPAWN, seat.team());
        victim.teleport(back != null ? back.pos() : spawnOf(game, seat));
        refit(game, victim, seat);
        protectedUntil.put(victim.getUuid(),
                game.state().tick() + game.rules().resolve(Scope.GAME, Bridge.PROTECT));
        lastHit.remove(victim.getUuid());
        if (by != null) {
            Duels.addSeat(game, by, Bridge.KILLS);
            if ("minemen".equals(Duels.lookNameOf(game))) by.sendMessage(Banners.kill(game, victim));
        }
        Component line = Banners.killedLine(game, victim, by);
        Duels.each(game, p -> p.sendMessage(line));
    }

    private void died(Game game, Player player, Slot seat) {
        PosMarker back = game.arena().marker(Bridge.RESPAWN, seat.team());
        player.teleport(back != null ? back.pos() : spawnOf(game, seat));
        refit(game, player, seat);
        protectedUntil.put(player.getUuid(),
                game.state().tick() + game.rules().resolve(Scope.GAME, Bridge.PROTECT));
        announceFall(game, player);
    }

    // a flat (1-tall) box is an X/Z slice under the global band; a taller box is its own 3D volume,
    // for maps whose buildable ceiling varies along the bridge
    private boolean buildable(RegionMarker build, Point pos) {
        for (RegionMarker.Cuboid box : build.boxes()) {
            if (pos.blockX() < box.min().blockX() || pos.blockX() > box.max().blockX()
                    || pos.blockZ() < box.min().blockZ() || pos.blockZ() > box.max().blockZ()) continue;
            boolean flat = box.min().blockY() == box.max().blockY();
            int lo = flat ? minBuildY : box.min().blockY();
            int hi = flat ? maxBuildY : box.max().blockY();
            if (pos.blockY() >= lo && pos.blockY() <= hi) return true;
        }
        return false;
    }

    private static boolean onASpawn(Game game, Point pos) {
        for (PosMarker at : game.arena().markers(Bridge.CAGE)) if (spawnBlock(at, pos)) return true;
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
            Duels.addSeat(game, by, Bridge.KILLS);
            if ("minemen".equals(Duels.lookNameOf(game))) by.sendMessage(Banners.kill(game, fallen));
        }
        Component line = Banners.fallLine(game, fallen, by);
        Duels.each(game, p -> p.sendMessage(line));
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
        Duels.draw(game);
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        player.setGameMode(GameMode.SURVIVAL); // bridge digs and builds - the duel default is adventure
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player quitter, @NotNull Game.Departure departure) {
        Duels.settleOnLeave(game, quitter, departure);
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
        // the held title + firework beats take over from the re-cage count one tick later (captured)
        Title scored = Title.title(Banners.scoredTitle(game, scorer, color), Component.empty(),
                Duels.times(game, 0, 40, 0));
        for (Player member : game.members()) {
            member.showTitle(scored);
            Slot memberSeat = game.roster().slotOf(member);
            if (memberSeat != null) member.teleport(spawnOf(game, memberSeat));
        }
        st.alarms().arm(Cages.RECAGE_ALARM, st.tick() + 1, CompoundBinaryTag.builder()
                .putString("scorer", scorer.getUsername()).putString("team", seat.team()).build());
    }

    private static Pos spawnOf(Game game, Slot seat) {
        return seat.spawn() != null ? seat.spawn() : game.arena().meta().spawn();
    }
}
