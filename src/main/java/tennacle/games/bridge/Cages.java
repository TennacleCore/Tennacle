package tennacle.games.bridge;

import io.github.term4.bloom.Bloom;
import io.github.term4.barnacle.marker.DataMarker;
import java.util.concurrent.ThreadLocalRandom;
import net.minestom.server.coordinate.Pos;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.canvas.theme.Role;
import tennacle.games.HotArena;
import tennacle.games.Chunks;
import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.Marker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.barnacle.template.Facing;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bridge opener, and the re-cage after a goal. Cages are authored as real blocks in the {@code cages}
 * arena - per variant (marker group): a {@code cage} region over the volume and a {@code cage} pos standing
 * where the player stands, facing the FRONT. {@code accent}/{@code accent.<family>} regions mark cells whose
 * authored RED blocks follow the team's {@code cage} value. Players pick a variant ({@code /cage}, vault-backed);
 * a side's one cage is drawn from its members' picks, rotated so the front faces the map center. The count is the captured per-network
 * voice; GO advances the {@link Phases}, the freeze is the {@code Freeze} module's.
 */
public final class Cages implements GameModule {

    /** Seconds on the cage count; captured: 3 on hypixel, 5 on scrims. */
    public static final StateKey<Integer> SECONDS = StateKey.of("cage:seconds", Codec.INT, 3);
    /** Off: no cage blocks. The break after a goal is {@link #REOPEN}'s - a cage is a look, not the break itself. */
    public static final StateKey<Boolean> ENABLED = StateKey.of("cage:enabled", Codec.BOOLEAN, true);
    /** Whether a goal reopens the count; off, the goal plays straight on with nobody re-seated. */
    public static final StateKey<Boolean> REOPEN = StateKey.of("cage:reopen", Codec.BOOLEAN, true);

    /** Held at the seat: {@link #HOLD_PINNED}, {@link #HOLD_BOUNDED} or {@link #HOLD_NONE}. */
    public static final StateKey<String> HOLD = StateKey.of("cage:hold", Codec.STRING, "bounded");
    /** Nobody moves. */
    public static final String HOLD_PINNED = "pinned";
    /** Free inside {@link #BOUNDS}, nothing past it - the walls a cage draws are a look, this is the boundary. */
    public static final String HOLD_BOUNDED = "bounded";
    /** No hold at all. */
    public static final String HOLD_NONE = "none";
    /**
     * Blocks from the seat a bounded player may reach: the box is that far on x and z, and from that far below
     * the seat to one block further above it, so a jump lands inside. Every cage gets the same box, whatever it
     * looks like, so an unlocked cage is never room to move.
     */
    public static final StateKey<Integer> BOUNDS = StateKey.of("cage:bounds", Codec.INT, 3);
    /**
     * Where a side is held for the opening: {@link #SEAT_CAGE} or {@link #SEAT_RESPAWN}. The stamp, the hold and
     * the teleport after a goal all read it, so the cage is never somewhere the players are not.
     */
    public static final StateKey<String> SEAT = StateKey.of("cage:seat", Codec.STRING, "cage");
    /** The team's {@code bridge:cage} marker - the seat the roster hands out. */
    public static final String SEAT_CAGE = "cage";
    /** The team's {@code bridge:respawn} marker, for a mode that opens where it respawns. */
    public static final String SEAT_RESPAWN = "respawn";

    /** The only cages a game allows; empty = all of them. */
    public static final StateKey<List<String>> WHITELIST = StateKey.of("cage:whitelist", Codec.STRING.list(), List.of());
    /** Cages a game refuses, whitelisted or not. */
    public static final StateKey<List<String>> BLACKLIST = StateKey.of("cage:blacklist", Codec.STRING.list(), List.of());

    static final String RECAGE_ALARM = "bridge:recage";
    private static final String COUNT_ALARM = "bridge:cage-count";
    private static final String STAMP_ALARM = "bridge:cage-stamp";
    // the authoring arena: /arena new cages, build, /marker region cage + /marker pos cage my, /arena save
    private static final HotArena CAGES = HotArena.of("cages");
    private static final MarkerKey<PosMarker> CAGE_SPOT = MarkerKey.pos("cage");
    private static final MarkerKey<RegionMarker> CAGE_BOX = MarkerKey.region("cage");
    /** The blocks a VARIANT carries per team: {@code /marker data cage.fill tall 1=redstone_ore 2=lapis_ore}. */
    private static final MarkerKey<DataMarker> CAGE_FILL_SET = MarkerKey.data("cage.fill");
    /** What a cage's accented RED_* blocks become: a tint keeps their material, a block replaces them. */
    static final Role<Role.Fill> CAGE_FILL = Role.block("cage");
    // captured hypixel: note.hat vol 1 each beat, note.harp at GO; scrims/minemen cues UNCAPTURED - pling
    private static final Sound HAT = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 1f, 1f);
    private static final Sound HARP = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HARP, Sound.Source.MASTER, 1f, 1f);
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);

    private record Cell(BlockVec offset, Block block, boolean accented) {}
    private record CageDef(Facing front, List<Cell> cells, Map<String, Role.Fill> fills) {}

    private static Arena cageSource;
    private static Map<String, CageDef> cageDefs = Map.of();
    private static final tennacle.PlayerPrefs.Entry<String, String> PICK =
            tennacle.PlayerPrefs.register(Bridge.CAGE_CHOICE);

    private final Map<Point, Block> replaced = new HashMap<>();
    private String look;
    private Phases phases;
    private Component held;
    private boolean introShown;
    private boolean caged;
    private int countFrom;      // the length the running count was armed with
    private int remaining = -1; // the beat last shown; -1 = no count running
    private long nextBeat = -1;

    /** So a bad fill is refused where it is authored rather than at the next stamp. */
    static void editable() {
        Bloom.payload(CAGE_FILL_SET.id(), (team, value) -> {
            if (!team.matches("[0-9]+")) throw new IllegalArgumentException("a cage fill is per team ordinal: 1=<value>");
            Role.Fill.parse(value);
        });
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(SECONDS, ENABLED, REOPEN, HOLD, BOUNDS, SEAT, WHITELIST, BLACKLIST,
                Countdown.GRACE, Countdown.INTERVAL);
    }

    /** Where a seat opens, per {@link #SEAT}: its own marker, or the team's respawn spot. */
    static @Nullable Pos seat(Game game, Slot seat) {
        return seatOf(game, seat.team(), seat.spawn());
    }

    private static @Nullable Pos seatOf(Game game, String team, @Nullable Pos own) {
        if (!SEAT_RESPAWN.equals(game.rules().resolve(Scope.GAME, SEAT))) return own;
        PosMarker respawn = game.arena().marker(Bridge.RESPAWN, team);
        return respawn != null ? respawn.pos() : own;
    }

    /** The hold {@code Freeze} asks: pinned to the seat, free inside the boundary, or nothing. */
    static Pos hold(Game game, Pos anchor, Pos to) {
        String mode = game.rules().resolve(Scope.GAME, HOLD);
        if (HOLD_NONE.equals(mode)) return to;
        if (HOLD_PINNED.equals(mode)) return anchor;
        int b = Math.max(0, game.rules().resolve(Scope.GAME, BOUNDS));
        double x = clamp(to.x(), anchor.x() - b, anchor.x() + b);
        double y = clamp(to.y(), anchor.y() - b, anchor.y() + b + 1); // the extra block is jump room
        double z = clamp(to.z(), anchor.z() - b, anchor.z() + b);
        // every move inside the box comes through here: hand back the move itself rather than a copy
        return x == to.x() && y == to.y() && z == to.z() ? to : new Pos(x, y, z);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    /** The pick a game will honor: theirs when it is allowed here, else the first cage that is. */
    static String allowed(Game game, String pick) {
        List<String> white = game.rules().resolve(Scope.GAME, WHITELIST);
        List<String> black = game.rules().resolve(Scope.GAME, BLACKLIST);
        if (permits(white, black, pick)) return pick;
        if (permits(white, black, "")) return ""; // the default cage, when the rules leave it standing
        return variants().stream().filter(v -> permits(white, black, v)).sorted().findFirst().orElse("");
    }

    private static boolean permits(List<String> white, List<String> black, String variant) {
        String name = variant.isEmpty() ? "default" : variant;
        if (black.contains(name) || black.contains(variant)) return false;
        return white.isEmpty() || white.contains(name) || white.contains(variant);
    }

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        look = Matches.lookNameOf(game); // stamped before modules install, never changes
        phases = game.module(Phases.class);
        if (phases == null) throw new IllegalStateException("Cages needs Phases installed before it");
        st.alarms().register(COUNT_ALARM, (tick, payload) -> beat(game, payload.getInt("n")));
        st.alarms().register(STAMP_ALARM, (tick, payload) -> {
            if (!caged) {
                caged = true;
                stamp(game); // seats are filled by now - picks resolve, players wait inside the cage
            }
        });
        st.alarms().register(RECAGE_ALARM, (tick, payload) -> recage(game, payload));
        st.alarms().arm(STAMP_ALARM, st.tick() + 2);
        armCount(game, st.tick() + game.rules().resolve(Scope.GAME, Countdown.GRACE));
        game.rules().watch(SECONDS, (scope, value) -> recount(game));
    }

    private void armCount(Game game, long at) {
        countFrom = seconds(game);
        remaining = countFrom + 1; // nothing shown yet
        nextBeat = at;
        game.state().alarms().arm(COUNT_ALARM, at, CompoundBinaryTag.builder().putInt("n", countFrom).build());
    }

    // mid-count: the beats already shown stay shown, the rest re-count from the new length at the same cadence
    private void recount(Game game) {
        if (remaining < 0 || nextBeat < 0 || !Match.opening(game)) return;
        int shown = countFrom - remaining + 1;
        int next = Math.max(0, seconds(game) - shown);
        countFrom = seconds(game);
        remaining = next + 1;
        game.state().alarms().cancel(COUNT_ALARM);
        game.state().alarms().arm(COUNT_ALARM, Math.max(game.state().tick() + 1, nextBeat),
                CompoundBinaryTag.builder().putInt("n", next).build());
    }

    private static int seconds(Game game) {
        return game.rules().resolve(Scope.GAME, SECONDS);
    }

    private void beat(Game game, int n) {
        GameState st = game.state();
        if (!Match.opening(game)) return;
        remaining = n;
        nextBeat = -1;
        boolean hypixel = "hypixel".equals(look);
        if (!caged) {
            caged = true;
            stamp(game);
        }
        if (!introShown) {
            introShown = true;
            if (hypixel) { // captured: the instruction block lands right before the first cage beat
                Match.each(game, p -> {
                    Component started = Matches.lookOf(game).count().started().apply(game, p);
                    if (started != null) p.sendMessage(started);
                });
            }
        }
        if (n > 0) {
            Title beat = Title.title(held != null ? held : Component.empty(),
                    Banners.cageCount(game, n), Match.times(game, 0, 40, 0));
            Component chat = "scrims".equals(look) ? Banners.cageCount(game, n) : null;
            Match.each(game, p -> {
                p.showTitle(beat);
                if (chat != null) p.sendMessage(chat); // captured: scrims mirrors the count into chat
                p.playSound(hypixel ? HAT : TICK);
            });
            if (held != null) Matches.cue(game, SoundSet.SCORED); // captured: the goal firework rides every beat
            nextBeat = st.tick() + game.rules().resolve(Scope.GAME, Countdown.INTERVAL);
            st.alarms().arm(COUNT_ALARM, nextBeat, CompoundBinaryTag.builder().putInt("n", n - 1).build());
            return;
        }
        remaining = -1;
        open(game);
        held = null;
        phases.advance(); // LIVE; a seat nobody filled aborts the game right here
        if (game.decided() != null) return;
        Title fight = Title.title(Component.empty(), Banners.fight(game), Match.times(game, 0, 20, 10));
        Match.each(game, p -> {
            p.showTitle(fight);
            p.playSound(hypixel ? HARP : GO);
        });
    }

    private void recage(Game game, CompoundBinaryTag payload) {
        String scorer = payload.getString("scorer");
        held = scorer.isEmpty() ? null
                : Banners.scoredHeld(game, scorer, game.roster().team(payload.getString("team")).color());
        phases.enter(Match.OPENING);
        caged = true;
        stamp(game);
        if (game.rules().resolve(Scope.GAME, Kits.RESET_ON_ROUND)) {
            game.state().alarms().arm(Rules.REFIT_ALARM, game.state().tick() + 1);
        }
        armCount(game, game.state().tick() + 1);
    }

    /** The authored cages by variant; re-extracted when /arena save swaps the arena. */
    private static synchronized Map<String, CageDef> cages() {
        Arena arena;
        try {
            arena = CAGES.get();
        } catch (IllegalStateException missing) {
            return Map.of(); // no cages arena yet - open-air freeze
        }
        if (arena == cageSource) return cageDefs;
        cageSource = arena;
        Map<String, CageDef> defs = new HashMap<>();
        var source = arena.instantiate();
        for (PosMarker spot : arena.markers(CAGE_SPOT)) {
            RegionMarker box = arena.marker(CAGE_BOX, spot.group());
            if (box == null) continue;
            List<RegionMarker> accents = new ArrayList<>();
            for (Marker marker : arena.markers()) {
                if (marker instanceof RegionMarker region && region.group().equals(spot.group())
                        && (region.key().equals("accent") || region.key().startsWith("accent."))) {
                    accents.add(region);
                }
            }
            BlockVec anchor = new BlockVec(spot.pos().blockX(), spot.pos().blockY(), spot.pos().blockZ());
            List<Cell> cells = new ArrayList<>();
            for (RegionMarker.Cuboid part : box.boxes()) {
                Chunks.load(source, part.min().blockX() >> 4, part.max().blockX() >> 4, part.min().blockZ() >> 4, part.max().blockZ() >> 4);
                for (int x = part.min().blockX(); x <= part.max().blockX(); x++) {
                    for (int z = part.min().blockZ(); z <= part.max().blockZ(); z++) {
                        for (int y = part.min().blockY(); y <= part.max().blockY(); y++) {
                            Block block = source.getBlock(x, y, z);
                            if (block.isAir()) continue;
                            cells.add(new Cell(
                                    new BlockVec(x - anchor.blockX(), y - anchor.blockY(), z - anchor.blockZ()),
                                    block, accented(block, new BlockVec(x, y, z), accents)));
                        }
                    }
                }
            }
            defs.put(spot.group(), new CageDef(Facing.fromYaw(spot.pos().yaw()), List.copyOf(cells),
                    fills(arena.marker(CAGE_FILL_SET, spot.group()))));
        }
        arena.unload();
        if (defs.isEmpty()) System.err.println("[cages] the cages arena has no cage region+pos pair");
        cageDefs = Map.copyOf(defs);
        return cageDefs;
    }

    // authored with the cage, so an ore cage stays ore whatever hue a map hands that team
    static Map<String, Role.Fill> fills(@Nullable DataMarker marker) {
        CompoundBinaryTag payload = marker == null ? null : marker.payload();
        if (payload == null) return Map.of();
        Map<String, Role.Fill> fills = new HashMap<>();
        for (String team : payload.keySet()) {
            try {
                fills.put(team, Role.Fill.parse(payload.getString(team)));
            } catch (IllegalArgumentException bad) {
                System.err.println("[cages] " + marker.group() + " fill " + team + ": " + bad.getMessage());
            }
        }
        return Map.copyOf(fills);
    }

    // authored RED_* inside a matching accent region follows the team
    private static boolean accented(Block block, BlockVec at, List<RegionMarker> accents) {
        String name = block.key().value().toUpperCase(Locale.ROOT);
        if (!name.startsWith("RED_")) return false;
        for (RegionMarker accent : accents) {
            if (!accent.contains(at)) continue;
            String family = accent.key().equals("accent") ? "" : accent.key().substring("accent.".length());
            if (familyMatches(name, family)) return true;
        }
        return false;
    }

    // an accent's suffix narrows what it takes: a dyed family, or one block by name (accent.red_wool)
    private static boolean familyMatches(String name, String family) {
        return switch (family) {
            case "" -> true;
            case "wool" -> name.endsWith("_WOOL");
            case "glass" -> name.contains("STAINED_GLASS");
            case "terracotta" -> name.endsWith("TERRACOTTA");
            case "concrete" -> name.contains("CONCRETE");
            case "carpet" -> name.endsWith("_CARPET");
            default -> name.equals(family.toUpperCase(Locale.ROOT));
        };
    }

    /**
     * A side's one cage: a random member's pick, re-drawn every time it is stamped. One cage holds the whole
     * team, so teammates who picked differently cannot each have theirs - the draw is how they settle it, and
     * it weights by how many of them wanted it (termbridge's rule).
     */
    private static String choiceFor(Game game, String team) {
        List<String> picks = new ArrayList<>();
        game.forEachMember(member -> {
            Slot seat = game.roster().slotOf(member);
            if (seat != null && seat.team().equals(team)) picks.add(choiceOf(member));
        });
        if (picks.isEmpty()) return allowed(game, "");
        return allowed(game, picks.get(ThreadLocalRandom.current().nextInt(picks.size())));
    }

    static String choiceOf(Player player) {
        String pick = PICK.of(player.getUuid());
        return pick != null ? pick : ""; // null = the login fetch has not landed - default cage
    }

    static void choose(Player player, String variant) {
        PICK.set(player, variant);
    }

    static Set<String> variants() {
        return cages().keySet();
    }

    // all writes ride the game's shard - the arena container is shared by concurrent games
    private void stamp(Game game) {
        if (!game.rules().resolve(Scope.GAME, ENABLED)) return; // read here, so /rules reaches the next recage
        Map<String, CageDef> defs = cages();
        if (defs.isEmpty()) return;
        for (PosMarker spawn : game.arena().markers(Bridge.CAGE)) {
            CageDef cage = defs.getOrDefault(choiceFor(game, spawn.group()), defs.get(""));
            if (cage == null) cage = defs.values().iterator().next();
            var at = seatOf(game, spawn.group(), spawn.pos());
            if (at == null) continue;
            // the map marker already faces the center - rotate the authored front onto it
            int turns = cage.front().quarterTurnsTo(Facing.fromYaw(at.yaw()));
            Role.Fill own = cage.fills().get(spawn.group());
            Role.Fill fill = game.roster().team(spawn.group()).livery().get(CAGE_FILL);
            if (own != null) fill = Role.Fill.merge(own, fill); // the cage's own set wins over the map's team look
            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
            for (Cell cell : cage.cells()) {
                BlockVec turned = Facing.rotate(cell.offset(), turns);
                int cx = (at.blockX() + turned.blockX()) >> 4, cz = (at.blockZ() + turned.blockZ()) >> 4;
                minCx = Math.min(minCx, cx); maxCx = Math.max(maxCx, cx);
                minCz = Math.min(minCz, cz); maxCz = Math.max(maxCz, cz);
            }
            if (minCx <= maxCx) Chunks.load(game.instance(), minCx, maxCx, minCz, maxCz);
            for (Cell cell : cage.cells()) {
                BlockVec turned = Facing.rotate(cell.offset(), turns);
                BlockVec pos = new BlockVec(at.blockX() + turned.blockX(), at.blockY() + turned.blockY(),
                        at.blockZ() + turned.blockZ());
                Block block = cell.block();
                if (cell.accented()) {
                    Block themed = fill.over(block);
                    if (themed != null) block = themed;
                }
                replaced.putIfAbsent(pos, game.shard().getBlock(pos.blockX(), pos.blockY(), pos.blockZ()));
                game.shard().setBlock(pos, Facing.rotate(block, turns));
            }
        }
    }

    private void open(Game game) {
        replaced.forEach((pos, block) -> game.shard().setBlock(pos, block));
        replaced.clear();
    }

}
