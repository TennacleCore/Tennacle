package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Match;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.KillCredit;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.modules.Respawns;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Goals, the build band, the quiver, and the hit gates bridge adds (bow boost, friendly fire). Deaths and falls
 * go to {@code Respawns}, credit to {@code KillCredit}, the void line to {@code Limits}, the outcome to
 * {@code Score} and {@code TimeLimit}. Shoves never hurt behind protection.
 */
public final class Rules implements GameModule {

    /** Shot-to-return time for the one quiver arrow; 3.5s is the termbridge/hypixel return. */
    public static final StateKey<Long> ARROW_TICKS = StateKey.of("bridge:arrow-ticks", Codec.LONG, 70L);
    /** Everyone re-kits (fresh body, quiver reset) after a goal. */
    public static final StateKey<Boolean> REFIT_ON_GOAL = StateKey.of("bridge:refit-on-goal", Codec.BOOLEAN, true);

    static final String REFIT_ALARM = "bridge:refit";
    private static final String QUIVER_ALARM = "bridge:quiver";
    private static final Sound ARROW_BACK =
            Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1f);

    private long goalCooldownUntil;
    private final Map<Player, Long> arrowDue = new HashMap<>();
    private final LongSet placed = new LongOpenHashSet();
    private String look;
    private long arrowTicks;
    private int minBuildY;
    private int maxBuildY;
    private List<RegionMarker> goals;
    private Respawns respawns;
    private KillCredit credit;
    private Kits kits;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(ARROW_TICKS, REFIT_ON_GOAL, Bridge.BOW_BOOST, Bridge.FRIENDLY_FIRE);
    }

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        respawns = game.module(Respawns.class);
        credit = game.module(KillCredit.class);
        kits = game.module(Kits.class);
        if (respawns == null || credit == null || kits == null) {
            throw new IllegalStateException("bridge Rules needs Respawns, KillCredit and Kits installed before it");
        }
        RegionMarker build = game.arena().markers(Bridge.BUILD).getFirst();
        int floor = Integer.MAX_VALUE;
        for (var key : List.of(Bridge.SECTION, Bridge.CENTER)) {
            for (RegionMarker part : game.arena().markers(key)) {
                for (RegionMarker.Cuboid box : part.boxes()) {
                    floor = Math.min(floor, box.min().blockY());
                }
            }
        }
        minBuildY = floor; // never beneath the bridge's underside
        maxBuildY = (int) Math.floor(plane(game, Bridge.HEIGHT));
        goals = game.arena().markers(Bridge.GOAL); // fixed for the map: never re-queried per move packet
        look = tennacle.games.Matches.lookNameOf(game); // stamped before modules install, never changes
        arrowTicks = game.rules().resolve(Scope.GAME, ARROW_TICKS);
        boolean bowBoost = game.rules().resolve(Scope.GAME, Bridge.BOW_BOOST);
        boolean friendlyFire = game.rules().resolve(Scope.GAME, Bridge.FRIENDLY_FIRE);
        respawns.onRespawn((player, seat) -> refit(game, player));
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Slot seat = game.roster().slotOf(victim);
            if (seat == null) return;
            // arrows resolve source to the shooter (snapshot contract), so this credits bow kills too
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) { // a bow boost: never a "hit" - no credit, no protection forfeit
                if (!bowBoost || !Match.live(game)) e.amount(0);
                return;
            }
            if (source != null) {
                Slot from = game.roster().slotOf(source);
                if (!friendlyFire && from != null && from.team().equals(seat.team())) {
                    e.amount(0); // gamerule: teammates never hurt each other
                    return;
                }
                respawns.unprotect(source); // swinging forfeits your own protection
            }
            if (respawns.isProtected(victim) || !Match.live(game)) e.amount(0);
        });
        game.on(FatalDamageEvent.class, FatalDamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            if (game.roster().slotOf(victim) == null) return;
            e.cancel(); // a kill respawns behind protection - never the death screen
            killed(game, victim, e.source() instanceof Player by && by != victim ? by : null);
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
            if (respawns.isProtected(victim)) e.setCancelled(true);
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
            placed.add(packed(pos));
            respawns.unprotect(player);
        });
        game.on(PlayerBlockBreakEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean own = placed.remove(packed(pos));
            if (!own && !inBridge(game, pos)) {
                e.setCancelled(true);
                Msg.err(player, "bridge", "you can't break that");
                return;
            }
            respawns.unprotect(player);
        });
        game.on(PlayerMoveEvent.class, e -> {
            Player player = e.getPlayer();
            Slot seat = game.roster().slotOf(player);
            if (seat == null || !Match.live(game) || st.tick() < goalCooldownUntil) return;
            Pos to = e.getNewPosition();
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
        st.alarms().register(REFIT_ALARM, (tick, payload) -> game.forEachMember(member -> {
            if (game.roster().slotOf(member) != null) {
                game.body().fresh(member);
                refit(game, member);
            }
        }));
    }

    /** A member dropped under the void plane ({@code Limits}): credit the shove, respawn behind protection. */
    void fell(@NotNull Player player) {
        Game game = Game.of(player);
        if (game == null || game.roster().slotOf(player) == null) return;
        announceFall(game, player);
        respawns.queue(player);
    }

    // one arrow in the quiver: shooting starts the return, the XP bar drains it down
    private void startQuiver(Game game, Player shooter) {
        boolean idle = arrowDue.isEmpty();
        arrowDue.put(shooter, game.state().tick() + arrowTicks);
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
            owner.setExp(left / (float) arrowTicks);
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

    /** The kit back on a fresh body, the quiver reset - after a respawn and after a goal. */
    private void refit(Game game, Player player) {
        arrowDue.remove(player);
        player.setExp(0f);
        player.setLevel(0);
        player.getInventory().clear();
        kits.apply(player);
    }

    private static double plane(Game game, MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    private void killed(Game game, Player victim, @Nullable Player by) {
        credit.forget(victim);
        respawns.queue(victim);
        if (by != null) {
            Match.addSeat(game, by, Bridge.KILLS);
            if ("minemen".equals(look)) by.sendMessage(Banners.kill(game, victim));
        }
        Component line = Banners.killedLine(game, victim, by);
        Match.each(game, p -> p.sendMessage(line));
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
        if (!Match.live(game)) return;
        Player by = credit.creditFor(fallen);
        if (by != null) {
            Match.addSeat(game, by, Bridge.KILLS);
            if ("minemen".equals(look)) by.sendMessage(Banners.kill(game, fallen));
        }
        Component line = Banners.fallLine(game, fallen, by);
        Match.each(game, p -> p.sendMessage(line));
    }

    private void score(Game game, Player scorer, Slot seat) {
        GameState st = game.state();
        int goals = st.get(Scope.team(seat.team()), Bridge.GOALS) + 1;
        st.set(Scope.team(seat.team()), Bridge.GOALS, goals); // Score decides at the target
        var color = game.roster().team(seat.team()).color();
        Component banner = switch (look) {
            case "minemen" -> null; // captured: minemen announces goals on the board and title only
            case "hypixel" -> Banners.hypixel(game, scorer, color, goals);
            default -> Banners.scrims(game, scorer, color);
        };
        if (banner != null) Match.each(game, p -> p.sendMessage(banner));
        if (game.decided() != null) return; // the winning goal: no re-cage, no held title
        if (!game.rules().resolve(Scope.GAME, Cages.ENABLED)) { // no breaks between goals: play on
            if (game.rules().resolve(Scope.GAME, REFIT_ON_GOAL)) st.alarms().arm(REFIT_ALARM, st.tick() + 1);
            return;
        }
        // the held title + firework beats take over from the re-cage count one tick later (captured)
        Title scored = Title.title(Banners.scoredTitle(game, scorer, color), Component.empty(),
                Match.times(game, 0, 40, 0));
        game.forEachMember(member -> {
            member.showTitle(scored);
            Slot memberSeat = game.roster().slotOf(member);
            if (memberSeat != null) member.teleport(spawnOf(game, memberSeat));
        });
        st.alarms().arm(Cages.RECAGE_ALARM, st.tick() + 1, CompoundBinaryTag.builder()
                .putString("scorer", scorer.getUsername()).putString("team", seat.team()).build());
    }

    private static Pos spawnOf(Game game, Slot seat) {
        return seat.spawn() != null ? seat.spawn() : game.arena().meta().spawn();
    }

    // vanilla's BlockPos.asLong packing: one primitive per placed block, no key object per place or break
    private static long packed(Point pos) {
        return ((long) pos.blockX() & 0x3FFFFFFL) << 38 | ((long) pos.blockZ() & 0x3FFFFFFL) << 12 | (pos.blockY() & 0xFFFL);
    }
}
