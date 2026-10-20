package tennacle.games.beds;

import net.minestom.server.instance.block.BlockFace;
import tennacle.games.map.Building;
import io.github.term4.grotto.Markers;
import io.github.term4.barnacle.marker.PosMarker;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.coordinate.Pos;
import io.github.term4.polyp.api.event.mobs.MobBreakBlockEvent;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Match;
import tennacle.games.stats.Tally;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import tennacle.games.lives.Anchor;
import tennacle.games.lives.Lives;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.roster.Team;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.title.Title;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The beds: one a side, a real bed under the mode's bed region over both its blocks, in the owning team's
 * group. While a side's bed stands its seats respawn; breaking it takes that away, and the next death there
 * is the last. {@link #BREAK} says whose bed a seat may take. {@link #DESTROY} takes every bed left at once,
 * so a match nobody wins still ends. The elimination is {@code LastStanding}'s, which {@link Lives} calls
 * when a bedless seat falls.
 */
public final class Beds implements Anchor, Building.Watcher {

    /** Whether the team's bed still stands; team-scoped, so a board reads it a side at a time. */
    public static final StateKey<Boolean> STANDS = StateKey.of("ledger.beds.stands", Codec.BOOLEAN, true, true);
    /** Ticks of live play before every standing bed falls at once; negative = never. */
    public static final StateKey<Long> DESTROY = StateKey.of("beds.destroy", Codec.LONG, 10L * 60 * 20);
    /** The breaker's own side's bed. */
    public static final String OWN = "own";
    /** Any other side's. */
    public static final String ENEMY = "enemy";
    /** Whose bed a seat may break; a side nobody names sleeps safe. */
    public static final StateKey<List<String>> BREAK =
            StateKey.of("beds.break", Codec.STRING.list(), List.of(ENEMY));
    /** A dragon (any block-eating mob) takes a bed it flies through; off, the bed stands and slows it. */
    public static final StateKey<Boolean> DRAGONS = StateKey.of("beds.dragons", Codec.BOOLEAN, false);
    /** Ticks before the fall that {@link #warning} runs; {@code 0} = never. */
    public static final StateKey<Long> WARNING = StateKey.of("beds.warning", Codec.LONG, 5L * 60 * 20);
    /** Both bed sounds at the listener's own block (captured Hypixel Bed Wars); off, at the bed (bed fight, MineMen). */
    public static final StateKey<Boolean> SOUND_LISTENER = StateKey.of("beds.sound.listener", Codec.BOOLEAN, false);

    static {
        tennacle.commands.RulesCompletions.words(BREAK.id(), List.of(OWN, ENEMY));
    }
    private static final StateKey<Long> FALLS_AT = StateKey.of("ledger.beds.at", Codec.LONG, -1L);
    private static final StateKey<Long> STARTED = StateKey.of("ledger.beds.from", Codec.LONG, -1L);
    private static final String FALL_ALARM = "beds:destruction";
    private static final String WARN_ALARM = "beds:warning";
    private final List<Consumer<Game>> warnings = new ArrayList<>();

    private static final Sound BROKEN = Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1f);
    private static final Sound LOST_BED = Sound.sound(SoundEvent.ENTITY_WITHER_DEATH, Sound.Source.MASTER, 1f, 1f);
    private static final Component LOST = Component.text("BED DESTROYED!", NamedTextColor.RED);
    private static final Component NO_RESPAWN = Component.text("You will no longer respawn!", NamedTextColor.WHITE);
    private static final Component ALL_GONE = Component.text("All beds have been destroyed!", NamedTextColor.WHITE);

    private final MarkerKey<RegionMarker> key;
    private final StateKey<Integer> broken;
    private Game game;
    /** Every block of every bed, to the side that sleeps in it. */
    private final Map<BlockVec, String> cells = new HashMap<>();
    private final List<java.util.function.Consumer<String>> fell = new java.util.ArrayList<>();
    private final Map<String, UUID> breakers = new HashMap<>();
    private BedStats stats;

    /** The beds under {@code key}'s regions; a break counts on the breaker's {@code broken}. */
    public Beds(@NotNull MarkerKey<RegionMarker> key, @NotNull StateKey<Integer> broken) {
        this.key = key;
        this.broken = broken;
    }

    /** {@code team}'s bed while it stands, else null. */
    public @Nullable RegionMarker bed(@NotNull String team) {
        return stands(game, team) ? game.arena().marker(key, team) : null;
    }

    /** A side's base: its bed's corner while it stands, else its spawn. */
    public static @Nullable Pos base(@NotNull Game game, @NotNull String team) {
        Beds beds = game.module(Beds.class);
        RegionMarker bed = beds != null ? beds.bed(team) : null;
        if (bed != null) {
            BlockVec min = bed.bounds().min();
            return new Pos(min.x() + 0.5, min.y(), min.z() + 0.5);
        }
        PosMarker spawn = game.arena().marker(Markers.SPAWN, team);
        return spawn != null ? spawn.pos() : null;
    }

    /** The center of {@code bed}'s head block, or of its first cell when no block there says which; null for none. */
    public static @Nullable Point head(@NotNull Game game, @Nullable RegionMarker bed) {
        if (bed == null) return null;
        Point any = null;
        for (RegionMarker.Cuboid box : bed.boxes()) {
            for (int x = box.min().blockX(); x <= box.max().blockX(); x++) {
                for (int y = box.min().blockY(); y <= box.max().blockY(); y++) {
                    for (int z = box.min().blockZ(); z <= box.max().blockZ(); z++) {
                        Point cell = new BlockVec(x, y, z);
                        if (any == null) any = cell;
                        if ("head".equals(game.shard().getBlock(cell).getProperty("part"))) return new Vec(x + 0.5, y + 0.5, z + 0.5);
                    }
                }
            }
        }
        return any != null ? new Vec(any.x() + 0.5, any.y() + 0.5, any.z() + 0.5) : null;
    }

    /** Told the side whose bed just went, however it went - the clock or an enemy pick. */
    @Override
    public @NotNull Beds onFall(@NotNull java.util.function.Consumer<String> action) {
        fell.add(action);
        return this;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(DESTROY, BREAK, DRAGONS, SOUND_LISTENER, WARNING);
    }

    @Override
    public void install(@NotNull Game game) {
        stats = new BedStats(game, this::standingBedAt);
        this.game = game;
        for (Team team : game.roster().teams()) {
            game.state().set(Scope.team(team.id()), STANDS, true);
            RegionMarker bed = game.arena().marker(key, team.id());
            if (bed == null) continue;
            for (RegionMarker.Cuboid box : bed.boxes()) {
                for (int x = box.min().blockX(); x <= box.max().blockX(); x++) {
                    for (int y = box.min().blockY(); y <= box.max().blockY(); y++) {
                        for (int z = box.min().blockZ(); z <= box.max().blockZ(); z++) {
                            cells.put(new BlockVec(x, y, z), team.id());
                        }
                    }
                }
            }
        }
        game.on(PlayerBlockBreakEvent.class, e -> {
            Point pos = e.getBlockPosition();
            String owner = cells.get(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            if (owner == null) return; // a bed already taken leaves ordinary ground behind
            Player breaker = e.getPlayer();
            if (game.roster().slotOf(breaker) == null) return;
            e.setCancelled(true); // both halves go together, so a half is never left standing
            if (!Match.live(game)) return; // not in play before the start or after the end: Refusals says so
            boolean mine = owner.equals(Match.teamOf(game, breaker));
            if (!game.rules().resolve(breaker, BREAK).contains(mine ? OWN : ENEMY)) {
                Refusals.because(e, mine ? OWN : ENEMY); // Refusals says so, in the mode's words
                return;
            }
            Refusals.handled(e); // cancelled to take the whole bed at once, not to turn them away
            if (stands(game, owner)) broke(owner, breaker, pos);
        });
        game.onWorld(MobBreakBlockEvent.class, MobBreakBlockEvent::world, e -> {
            Point pos = e.position();
            String owner = cells.get(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
            if (owner == null) return;
            e.setCancelled(true); // the whole bed goes at once, or none of it
            if (!Match.live(game) || !stands(game, owner) || !game.rules().resolve(Scope.GAME, DRAGONS)) return;
            Component by = e.mob().getCustomName() != null ? e.mob().getCustomName()
                    : Component.text(e.mob().getEntityType().key().value());
            Object side = e.mob().sides().of(e.mob());
            broke(owner, by, side != null ? String.valueOf(side) : null, pos);
        });
        LastStanding standing = game.module(LastStanding.class);
        if (standing != null) {
            // a side nobody sat on at GO never had its bed in play: no fall to tell
            standing.onOut((g, seat, player, cause) -> {
                if (cause.kind() == LastStanding.Kind.NEVER_ARRIVED && stands(game, seat.team())) clear(seat.team());
            });
        }
        game.state().alarms().register(FALL_ALARM, (tick, payload) -> fall());
        game.state().alarms().register(WARN_ALARM, (tick, payload) -> {
            for (Consumer<Game> warning : warnings) warning.accept(game);
        });
        game.rules().watch(DESTROY, (scope, value) -> rearm());
        game.rules().watch(WARNING, (scope, value) -> rearm());
        Phases phases = game.module(Phases.class);
        if (phases == null || Phases.is(game, Match.LIVE)) start();
        else phases.onEnter(Match.LIVE, g -> start());
    }

    /** The tick every remaining bed falls on, {@code -1} where no clock runs. */
    public static long fallsAt(@NotNull Game game) {
        return game.state().get(Scope.GAME, FALLS_AT);
    }

    /** Ticks until the beds fall, {@code 0} once they have; {@code -1} where no clock runs. */
    public static long fallsIn(@NotNull Game game) {
        long at = fallsAt(game);
        return at < 0 ? -1 : Math.max(0, at - game.state().tick());
    }

    private void start() {
        game.state().set(Scope.GAME, STARTED, game.state().tick());
        rearm();
    }

    private void rearm() {
        long from = game.state().get(Scope.GAME, STARTED);
        if (from < 0 || standing(game).isEmpty()) return; // before the clock starts, or nothing left to take
        long ticks = game.rules().resolve(Scope.GAME, DESTROY);
        game.state().alarms().cancel(WARN_ALARM);
        if (ticks < 0) {
            game.state().set(Scope.GAME, FALLS_AT, -1L);
            game.state().alarms().cancel(FALL_ALARM);
            return;
        }
        long at = from + ticks;
        game.state().set(Scope.GAME, FALLS_AT, at);
        game.state().alarms().arm(FALL_ALARM, at);
        long warn = game.rules().resolve(Scope.GAME, WARNING);
        if (warn > 0 && at - warn > game.state().tick()) game.state().alarms().arm(WARN_ALARM, at - warn);
    }

    /** Runs {@code action} {@link #WARNING} ticks before the beds fall (Bed Wars' 25:00 line). */
    public @NotNull Beds warning(@NotNull Consumer<Game> action) {
        warnings.add(action);
        return this;
    }

    // every side at once, so nobody is the one who lost their bed to the clock
    private void fall() {
        List<String> left = standing(game);
        if (left.isEmpty()) return;
        for (String team : left) erase(team);
        boolean hypixel = Banners.hypixel(game);
        game.forEachAudience(viewer -> viewer.playSound(hypixel ? LOST_BED : BROKEN));
        if (hypixel) told(left::contains, ALL_GONE, Match.times(game, 5, 35, 10));
        else told(left::contains, NO_RESPAWN, Match.times(game, 0, 40, 20));
        Match.say(game, Banners.bedsFell(game));
    }

    private void told(Predicate<String> teams) {
        told(teams, NO_RESPAWN, Match.times(game, 0, 40, 20));
    }

    // the side that lost it hears it on their screen; the rest read the line
    private void told(Predicate<String> teams, Component under, Title.Times times) {
        Title title = Title.title(LOST, under, times);
        Match.each(game, p -> {
            String team = Match.teamOf(game, p);
            if (!team.isEmpty() && teams.test(team)) p.showTitle(title);
        });
    }

    /** Who took {@code team}'s bed by hand, while online; null for the clock or a creature. */
    public static @Nullable Player breaker(@NotNull Game game, @NotNull String team) {
        Beds beds = game.module(Beds.class);
        UUID id = beds != null ? beds.breakers.get(team) : null;
        return id != null ? MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id) : null;
    }

    @Override
    public boolean stands(@NotNull String team) {
        return stands(game, team);
    }

    @Override
    public @Nullable Player breaker(@NotNull String team) {
        return breaker(game, team);
    }

    /** Whether {@code team} still has a bed to respawn at. */
    public static boolean stands(@NotNull Game game, @NotNull String team) {
        return game.state().get(Scope.team(team), STANDS);
    }

    /** The sides whose bed still stands, in roster order. */
    public static @NotNull List<String> standing(@NotNull Game game) {
        return game.roster().teams().stream().map(Team::id).filter(id -> stands(game, id)).toList();
    }

    @Override
    public void placed(@NotNull Player placer, @NotNull Point pos) {
        stats.placed(placer, pos);
    }

    @Override
    public void broke(@NotNull Player breaker, @NotNull Point pos) {
        stats.broke(breaker, pos);
    }

    @Override
    public void died(@NotNull Lives.Death death) {
        stats.died(death);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        stats.left(player);
    }

    private @Nullable String standingBedAt(Point pos) {
        String team = cells.get(new BlockVec(pos.blockX(), pos.blockY(), pos.blockZ()));
        return team != null && stands(game, team) ? team : null;
    }

    private void erase(String team) {
        for (Player seated : game.members()) {
            var seat = game.roster().slotOf(seated);
            if (seat != null && seat.team().equals(team)) Tally.add(game, seated, "beds-lost");
        }
        clear(team);
        stats.fell(team);
        for (java.util.function.Consumer<String> action : fell) action.accept(team);
    }

    // the cells go with the bed: what stands there next is a placed block, nobody's objective
    private void clear(String team) {
        game.state().set(Scope.team(team), STANDS, false);
        cells.entrySet().removeIf(cell -> {
            if (!cell.getValue().equals(team)) return false;
            game.shard().setBlock(cell.getKey(), Block.AIR);
            game.shard().applyPhysics(cell.getKey());
            return true;
        });
    }

    private void broke(String owner, Player breaker, Point bed) {
        Match.addSeat(game, breaker, broken);
        Tally.add(game, breaker, "beds-broken");
        Tally.pair(game, breaker.getUuid(), owner, "beds-broken", 1);
        breakers.put(owner, breaker.getUuid()); // before the fall: its listeners ask who it was
        broke(owner, Component.text(breaker.getUsername(), Match.teamColor(game, breaker)), Match.teamOf(game, breaker), bed);
    }

    // a bed taken by a name: a player's, or a creature's (its side is its owner's)
    private void broke(String owner, Component by, @Nullable String byTeam, Point bed) {
        erase(owner);
        Team broken = game.roster().team(owner);
        // both networks: the side that lost it hears the wither, everyone else the dragon
        boolean atListener = game.rules().resolve(Scope.GAME, SOUND_LISTENER);
        game.forEachAudience(viewer -> {
            Sound sound = owner.equals(Match.teamOf(game, viewer)) ? LOST_BED : BROKEN;
            if (atListener) viewer.playSound(sound);
            else viewer.playSound(sound, bed.blockX() + 0.5, bed.blockY() + 0.5, bed.blockZ() + 0.5);
        });
        told(owner::equals);
        Match.say(game, Banners.bedBroken(game, broken, by));
        Team side = byTeam != null && !byTeam.isEmpty() ? game.roster().team(byTeam) : null;
        tennacle.games.watch.Replays.mark(game, "bed", net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                .putString("bed", tennacle.looks.Palette.titled(broken))
                .putString("bedTeam", broken.id())
                .putInt("bedColor", broken.color().value())
                .putString("breaker", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(by))
                .putString("breakerTeam", side != null ? side.id() : "")
                .putInt("breakerColor", (side != null ? side.color() : broken.color()).value())
                .build());
    }

}
