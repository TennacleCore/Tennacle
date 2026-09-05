package tennacle.games.bridge;

import tennacle.games.Deaths;
import java.util.Set;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import tennacle.Msg;
import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.flow.Rounds;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.Goals;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.gear.Restock;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.api.event.damage.FatalDamageEvent;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The build band, protection, kills and falls, and the goal ceremony. Goals themselves are {@code Goals},
 * teammates {@code FriendlyFire}, the quiver {@code Restock}, a self shot {@code projectiles/minecraft:arrow/selfHit};
 * deaths and falls go to {@code Respawns}, credit to {@code KillCredit}, the void line to {@code Limits}, the
 * outcome to {@code Score} and {@code TimeLimit}. Shoves never hurt behind protection.
 */
public final class Rules implements GameModule {

    static final String REFIT_ALARM = "bridge:refit";
    private static final Sound ARROW_BACK =
            Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1f);

    private final LongSet placed = new LongOpenHashSet();
    private String look;
    private int minBuildY;
    private int maxBuildY;
    /** Absolute max build Y; {@code -1} keeps the {@code bridge:height} marker. */
    public static final StateKey<Integer> HEIGHT = StateKey.of("space.build.height", Codec.INT, -1);
    /** May place outside the {@code bridge:build} slices - still under the height and above the floor. */
    public static final StateKey<Boolean> OUTSIDE = StateKey.of("space.build.outside", Codec.BOOLEAN, false);
    /** A block a member put there. */
    public static final String PLACED = "placed";
    /** The map's own. */
    public static final String MAP = "map";
    /** What may be broken outside the bridge sections; a kind nobody names stays standing out there. */
    public static final StateKey<List<String>> BREAKABLE_OUTSIDE =
            StateKey.of("space.build.breakable-outside", Codec.STRING.list(), List.of(PLACED));

    private Game game;
    private Respawns respawns;
    private KillCredit credit;
    private Kits kits;
    private Restock restock;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(HEIGHT, OUTSIDE, BREAKABLE_OUTSIDE);
    }

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        this.game = game;
        respawns = game.module(Respawns.class);
        credit = game.module(KillCredit.class);
        kits = game.module(Kits.class);
        restock = game.module(Restock.class);
        Goals goals = game.module(Goals.class);
        if (respawns == null || credit == null || kits == null || restock == null || goals == null) {
            throw new IllegalStateException("bridge Rules needs Respawns, KillCredit, Kits, Restock and Goals installed before it");
        }
        goals.onGoal((scorer, seat, n) -> score(game, scorer, seat, n));
        restock.onRestock((owner, item) -> owner.playSound(ARROW_BACK));
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
        look = tennacle.games.Matches.lookNameOf(game); // stamped before modules install, never changes
        respawns.onRespawn((player, seat) -> refit(game, player));
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Slot seat = game.roster().slotOf(victim);
            if (seat == null) return;
            // arrows resolve source to the shooter (snapshot contract), so this credits bow kills too
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) return; // a bow boost: never a "hit" - no credit, no protection forfeit
            if (source != null) respawns.unprotect(source); // swinging forfeits your own protection
            if (respawns.isProtected(victim) || game.decided() != null) e.cancel(); // no flash, no sound, no i-frame
        });
        game.on(FatalDamageEvent.class, FatalDamageEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            if (game.roster().slotOf(victim) == null) return;
            e.cancel(); // a kill respawns behind protection - never the death screen
            killed(game, victim, e.source() instanceof Player by && by != victim ? by : null, shotRange(e));
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            if (!(e.target() instanceof Player victim)) return;
            Player source = e.source() instanceof Player p ? p : null;
            if (source == victim) return; // protection never blocks your own boost
            if (respawns.isProtected(victim)) e.setCancelled(true);
        });
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (game.roster().slotOf(player) == null) return;
            Point pos = e.getBlockPosition();
            boolean allowed = buildable(build, pos)
                    || (game.rules().resolve(Scope.GAME, OUTSIDE) && inBand(pos)); // off the slices, still in the band
            if (!allowed || onASpawn(game, pos)) {
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
            boolean own = placed.contains(packed(pos));
            if (!inBridge(game, pos) && !game.rules().resolve(player, BREAKABLE_OUTSIDE).contains(own ? PLACED : MAP)) {
                e.setCancelled(true);
                Msg.err(player, "bridge", "you can't break that");
                return;
            }
            placed.remove(packed(pos)); // after the refusal: a block that stays is still the one who placed it
            respawns.unprotect(player);
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
        st.alarms().register(REFIT_ALARM, (tick, payload) -> {
            game.forEachMember(member -> {
                if (game.roster().slotOf(member) == null) return;
                if (Rounds.resets(game, member, Rounds.BODY)) game.body().fresh(member);
                if (Rounds.resets(game, member, Rounds.KIT)) refit(game, member);
            });
        });
    }

    /** A member dropped under the void plane ({@code Limits}): credit the shove, respawn behind protection. */
    void fell(@NotNull Player player) {
        Game game = Game.of(player);
        if (game == null || game.roster().slotOf(player) == null) return;
        announceFall(game, player);
        respawns.queue(player);
    }

    /** The tiebreaker: the next goal settles it ({@link Score#NEXT_WINS}) and the drawn score stands, so the
     *  board keeps its meter; the look calls it, and {@code time:tiebreaker-reset} says how the players go back -
     *  not at all, to their spawns, or to cages. */
    static void tiebreaker(@NotNull Game game) {
        game.state().set(Scope.GAME, Score.NEXT_WINS, true);
        game.forEachAudience(viewer -> Matches.lookOf(game).tiebreak().show(game, viewer));
        switch (game.rules().resolve(Scope.GAME, TimeLimit.TIEBREAKER_RESET)) {
            case TimeLimit.RESET_CAGES -> game.state().alarms().arm(Cages.RECAGE_ALARM, game.state().tick() + 1,
                    CompoundBinaryTag.builder().putString("scorer", "").putString("team", "").build());
            case TimeLimit.RESET_SPAWN -> {
                game.forEachMember(member -> {
                    Slot seat = game.roster().slotOf(member);
                    if (seat != null) member.teleport(spawnOf(game, seat), Vec.ZERO);
                });
                game.state().alarms().arm(REFIT_ALARM, game.state().tick() + 1);
            }
            default -> { } // RESET_NONE: play on from where everyone stands
        }
    }

    /** The kit back on a fresh body, the quiver reset - after a respawn and after a goal. */
    private void refit(Game game, Player player) {
        restock.reset(player);
        kits.refit(player); // targeted: only the slots that changed, never a full inventory resend
    }

    private static double plane(Game game, MarkerKey<LimitMarker> key) {
        Double y = game.arena().markers(key).getFirst().y();
        if (y == null) throw new IllegalStateException(key.id() + " must pin y (/marker limit " + key.id() + " y=<n>)");
        return y;
    }

    /** Horizontal blocks between where a projectile was loosed and where it struck; {@code -1} if it was not one. */
    private static double shotRange(FatalDamageEvent e) {
        if (!(e.detail() instanceof ProjectileEntity projectile)) return -1;
        Pos from = projectile.getSpawnPosition();
        Point hit = e.finalSnap().point();
        if (from == null || hit == null) return -1;
        return Math.hypot(hit.x() - from.x(), hit.z() - from.z());
    }

    private void killed(Game game, Player victim, @Nullable Player by, double range) {
        credit.forget(victim);
        respawns.queue(victim);
        Deaths.cue(game, victim, by);
        if (by != null) {
            Match.addSeat(game, by, Bridge.KILLS);
        }
        Component line = Banners.killedLine(game, victim, by, range);
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
            int hi = flat ? ceiling() : box.max().blockY();
            if (pos.blockY() >= lo && pos.blockY() <= hi) return true;
        }
        return false;
    }

    // the global band: the bridge's underside up to the marker, or the rule when one is set
    private int ceiling() {
        int rule = game.rules().resolve(Scope.GAME, HEIGHT);
        return rule >= 0 ? rule : maxBuildY;
    }

    private boolean inBand(Point pos) {
        return pos.blockY() >= minBuildY && pos.blockY() <= ceiling();
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
        Deaths.cue(game, fallen, by);
        if (by != null) {
            Match.addSeat(game, by, Bridge.KILLS);
        }
        Component line = Banners.fallLine(game, fallen, by);
        Match.each(game, p -> p.sendMessage(line));
    }

    // Goals wrote the counter (Score decides at the target); this is the ceremony
    private void score(Game game, Player scorer, Slot seat, int goals) {
        GameState st = game.state();
        var color = game.roster().team(seat.team()).color();
        Component banner = switch (look) {
            case "minemen" -> null; // captured: minemen announces goals on the board and title only
            case "hypixel" -> Banners.hypixel(game, scorer, color, goals);
            default -> Banners.scrims(game, scorer, color);
        };
        if (banner != null) Match.each(game, p -> p.sendMessage(banner));
        if (game.decided() != null) return; // the winning goal: no re-cage, no held title
        if (!Rounds.reopens(game)) { // no break between goals: play on
            st.alarms().arm(REFIT_ALARM, st.tick() + 1); // the alarm reads both halves; neither is this call's business
            return;
        }
        // the held title + firework beats take over from the re-cage count one tick later (captured)
        Title scored = Title.title(Banners.scoredTitle(game, scorer, color), Component.empty(),
                Match.times(game, 0, 40, 0));
        Match.each(game, p -> p.showTitle(scored));
        game.forEachMember(member -> {
            Slot memberSeat = game.roster().slotOf(member);
            if (memberSeat != null) member.teleport(spawnOf(game, memberSeat), Vec.ZERO);
        });
        st.alarms().arm(Cages.RECAGE_ALARM, st.tick() + 1, CompoundBinaryTag.builder()
                .putString("scorer", scorer.getUsername()).putString("team", seat.team()).build());
    }

    private static Pos spawnOf(Game game, Slot seat) {
        Pos at = Cages.seat(game, seat);
        return at != null ? at : game.arena().meta().spawn();
    }

    // vanilla's BlockPos.asLong packing: one primitive per placed block, no key object per place or break
    private static long packed(Point pos) {
        return ((long) pos.blockX() & 0x3FFFFFFL) << 38 | ((long) pos.blockZ() & 0x3FFFFFFL) << 12 | (pos.blockY() & 0xFFFL);
    }
}
