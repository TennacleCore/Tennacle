package tennacle.games.bridge;

import tennacle.games.HotArena;
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
import io.github.term4.grotto.modules.Countdown;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.modules.Phases;
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
 * authored RED blocks recolor to the seat's team. Players pick a variant ({@code /cage}, vault-backed); each
 * spot stamps its seat's pick rotated so the front faces the map center. The count is the captured per-network
 * voice; GO advances the {@link Phases}, the freeze is the {@code Freeze} module's.
 */
public final class Cages implements GameModule {

    /** Seconds on the cage count; captured: 3 on hypixel, 5 on scrims. */
    public static final StateKey<Integer> SECONDS = StateKey.of("cages:seconds", Codec.INT, 3);
    /** Off: no cage blocks and no re-cage after a goal - the count still opens the match, goals play on. */
    public static final StateKey<Boolean> ENABLED = StateKey.of("cages:enabled", Codec.BOOLEAN, true);

    static final String RECAGE_ALARM = "bridge:recage";
    private static final String COUNT_ALARM = "bridge:cage-count";
    private static final String STAMP_ALARM = "bridge:cage-stamp";
    // the authoring arena: /arena new cages, build, /marker region cage + /marker pos cage my, /arena save
    private static final HotArena CAGES = HotArena.of("cages");
    private static final MarkerKey<PosMarker> CAGE_SPOT = MarkerKey.pos("cage");
    private static final MarkerKey<RegionMarker> CAGE_BOX = MarkerKey.region("cage");
    /** The role a cage's authored RED_* blocks take their hue from. */
    private static final String CAGE_TINT = "cage";
    // captured hypixel: note.hat vol 1 each beat, note.harp at GO; scrims/minemen cues UNCAPTURED - pling
    private static final Sound HAT = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 1f, 1f);
    private static final Sound HARP = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HARP, Sound.Source.MASTER, 1f, 1f);
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);

    private record Cell(BlockVec offset, Block block, @Nullable String recolor) {}
    private record CageDef(Facing front, List<Cell> cells) {}

    private static Arena cageSource;
    private static Map<String, CageDef> cageDefs = Map.of();
    private static final tennacle.PlayerPrefs.Entry<String, String> PICK =
            tennacle.PlayerPrefs.register(Bridge.CAGE_CHOICE);

    private final Map<Point, Block> replaced = new HashMap<>();
    private String look;
    private boolean enabled;
    private Phases phases;
    private Component held;
    private boolean introShown;
    private boolean caged;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(SECONDS, ENABLED, Countdown.GRACE, Countdown.INTERVAL);
    }

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        look = Matches.lookNameOf(game); // stamped before modules install, never changes
        enabled = game.rules().resolve(Scope.GAME, ENABLED);
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
        st.alarms().arm(COUNT_ALARM, st.tick() + game.rules().resolve(Scope.GAME, Countdown.GRACE),
                CompoundBinaryTag.builder().putInt("n", seconds(game)).build());
    }

    private static int seconds(Game game) {
        return game.rules().resolve(Scope.GAME, SECONDS);
    }

    private void beat(Game game, int n) {
        GameState st = game.state();
        if (!Match.opening(game)) return;
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
            st.alarms().arm(COUNT_ALARM, st.tick() + game.rules().resolve(Scope.GAME, Countdown.INTERVAL),
                    CompoundBinaryTag.builder().putInt("n", n - 1).build());
            return;
        }
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
        game.state().alarms().arm(COUNT_ALARM, game.state().tick() + 1,
                CompoundBinaryTag.builder().putInt("n", seconds(game)).build());
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
                preload(source, part.min().blockX() >> 4, part.max().blockX() >> 4, part.min().blockZ() >> 4, part.max().blockZ() >> 4);
                for (int x = part.min().blockX(); x <= part.max().blockX(); x++) {
                    for (int z = part.min().blockZ(); z <= part.max().blockZ(); z++) {
                        for (int y = part.min().blockY(); y <= part.max().blockY(); y++) {
                            Block block = source.getBlock(x, y, z);
                            if (block.isAir()) continue;
                            cells.add(new Cell(
                                    new BlockVec(x - anchor.blockX(), y - anchor.blockY(), z - anchor.blockZ()),
                                    block, recolor(block, new BlockVec(x, y, z), accents)));
                        }
                    }
                }
            }
            defs.put(spot.group(), new CageDef(Facing.fromYaw(spot.pos().yaw()), List.copyOf(cells)));
        }
        arena.unload();
        if (defs.isEmpty()) System.err.println("[cages] the cages arena has no cage region+pos pair");
        cageDefs = Map.copyOf(defs);
        return cageDefs;
    }

    // authored RED_* inside a matching accent region recolors per team; the returned tail rides the Cell
    private static @Nullable String recolor(Block block, BlockVec at, List<RegionMarker> accents) {
        String name = block.key().value().toUpperCase(Locale.ROOT);
        if (!name.startsWith("RED_")) return null;
        for (RegionMarker accent : accents) {
            if (!accent.contains(at)) continue;
            String family = accent.key().equals("accent") ? "" : accent.key().substring("accent.".length());
            if (familyMatches(name, family)) return name.substring("RED_".length());
        }
        return null;
    }

    private static boolean familyMatches(String name, String family) {
        return switch (family) {
            case "" -> true;
            case "wool" -> name.endsWith("_WOOL");
            case "glass" -> name.contains("STAINED_GLASS");
            case "terracotta" -> name.endsWith("TERRACOTTA");
            case "concrete" -> name.contains("CONCRETE");
            case "carpet" -> name.endsWith("_CARPET");
            default -> false;
        };
    }

    /** The seated player's pick for that team's spot ("" until their vault answer lands). */
    private static String choiceFor(Game game, String team) {
        Player seated = game.firstMember(member -> {
            Slot seat = game.roster().slotOf(member);
            return seat != null && seat.team().equals(team);
        });
        if (seated != null) return choiceOf(seated);
        return "";
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
        if (!enabled) return;
        Map<String, CageDef> defs = cages();
        if (defs.isEmpty()) return;
        for (PosMarker spawn : game.arena().markers(Bridge.CAGE)) {
            CageDef cage = defs.getOrDefault(choiceFor(game, spawn.group()), defs.get(""));
            if (cage == null) cage = defs.values().iterator().next();
            var at = spawn.pos();
            // the map marker already faces the center - rotate the authored front onto it
            int turns = cage.front().quarterTurnsTo(Facing.fromYaw(at.yaw()));
            String dye = game.roster().team(spawn.group()).livery().tint(CAGE_TINT).hue().name();
            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
            for (Cell cell : cage.cells()) {
                BlockVec turned = Facing.rotate(cell.offset(), turns);
                int cx = (at.blockX() + turned.blockX()) >> 4, cz = (at.blockZ() + turned.blockZ()) >> 4;
                minCx = Math.min(minCx, cx); maxCx = Math.max(maxCx, cx);
                minCz = Math.min(minCz, cz); maxCz = Math.max(maxCz, cz);
            }
            if (minCx <= maxCx) preload(game.instance(), minCx, maxCx, minCz, maxCz);
            for (Cell cell : cage.cells()) {
                BlockVec turned = Facing.rotate(cell.offset(), turns);
                BlockVec pos = new BlockVec(at.blockX() + turned.blockX(), at.blockY() + turned.blockY(),
                        at.blockZ() + turned.blockZ());
                Block block = cell.block();
                if (cell.recolor() != null) {
                    Block dyed = Block.fromKey("minecraft:" + (dye + "_" + cell.recolor()).toLowerCase(Locale.ROOT));
                    if (dyed != null) block = dyed;
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

    /** Every chunk in the range loaded with ONE wait - a re-cage after a goal must not stall the tick per cell. */
    private static void preload(net.minestom.server.instance.Instance instance, int minCx, int maxCx, int minCz, int maxCz) {
        List<java.util.concurrent.CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!instance.isChunkLoaded(cx, cz)) loads.add(instance.loadChunk(cx, cz));
            }
        }
        if (!loads.isEmpty()) java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }
}
