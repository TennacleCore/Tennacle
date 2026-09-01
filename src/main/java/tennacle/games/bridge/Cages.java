package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Duels;
import tennacle.games.HotArena;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.Marker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.barnacle.template.Facing;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bridge round. Cages are authored as real blocks in the {@code cages} arena - per variant (marker
 * group): a {@code cage} region over the volume and a {@code cage} pos standing where the player stands,
 * facing the FRONT. {@code accent}/{@code accent.<family>} regions mark cells whose authored RED blocks
 * recolor to the seat's team. Players pick a variant ({@code /cage}, vault-backed); each spot stamps its
 * seat's pick rotated so the front faces the map center. The countdown is the captured per-network voice;
 * bridge never runs the shared duel countdown, so the freeze lives here too.
 */
public final class Cages implements GameModule {

    static final String RECAGE_ALARM = "bridge:recage";
    private static final String COUNT_ALARM = "bridge:cage-count";
    private static final String STAMP_ALARM = "bridge:cage-stamp";

    // the authoring arena: /arena new cages, build, /marker region cage + /marker pos cage my, /arena save
    private static final HotArena CAGES = HotArena.of("cages");
    private static final MarkerKey<PosMarker> CAGE_SPOT = MarkerKey.pos("cage");
    private static final MarkerKey<RegionMarker> CAGE_BOX = MarkerKey.region("cage");
    private static final Map<String, String> TEAM_DYE = Map.of(
            "red", "RED", "blue", "BLUE", "green", "LIME", "yellow", "YELLOW");

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
    private Component held;
    private boolean introShown;
    private boolean caged;

    @Override
    public void install(@NotNull Game game) {
        GameState st = game.state();
        st.alarms().register("duel:ghost", (tick, payload) -> { // the shared settle path arms it by name
            UUID uuid = UUID.fromString(payload.getString("uuid"));
            for (Player p : game.members()) {
                if (p.getUuid().equals(uuid)) {
                    game.ghost(p);
                    return;
                }
            }
        });
        st.alarms().register(COUNT_ALARM, (tick, payload) -> beat(game, payload.getInt("n")));
        st.alarms().register(STAMP_ALARM, (tick, payload) -> {
            if (!caged) {
                caged = true;
                stamp(game); // seats are filled by now - picks resolve, players wait inside the cage
            }
        });
        st.alarms().register(RECAGE_ALARM, (tick, payload) -> recage(game, payload));

        // cancelling would resync with ABSOLUTE view (the client's look rewinds mid-flick); instead let
        // the packet land, then pin back to the seat with yaw/pitch as zero RELATIVE deltas
        Set<UUID> pinning = new HashSet<>();
        game.on(PlayerMoveEvent.class, e -> {
            if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
            Player p = e.getPlayer();
            Pos from = p.getPosition();
            Pos to = e.getNewPosition();
            if (from.x() == to.x() && from.y() == to.y() && from.z() == to.z()) return;
            Slot seat = game.roster().slotOf(p);
            Pos anchor = seat != null && seat.spawn() != null ? seat.spawn() : from;
            if (!pinning.add(p.getUuid())) return;
            MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                pinning.remove(p.getUuid());
                if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
                p.teleport(anchor.withView(0, 0), null, RelativeFlags.VIEW);
            });
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            String phase = st.get(Scope.GAME, Duels.PHASE);
            if ("fighting".equals(phase)) return;
            // the settling tick's KB is the final hit - it lands; anything later in "done" is swallowed
            if ("done".equals(phase) && st.tick() == st.get(Scope.GAME, Duels.DONE_AT)) return;
            e.setCancelled(true);
        });

        st.alarms().arm(STAMP_ALARM, st.tick() + 2);
        st.alarms().arm(COUNT_ALARM, st.tick() + Duels.START_GRACE_TICKS,
                CompoundBinaryTag.builder().putInt("n", seconds(game)).build());
    }

    /** Captured: 3s on hypixel, 5s on scrims; minemen uncaptured (their bridge doesn't exist) - 3s. */
    private static int seconds(Game game) {
        return "scrims".equals(Duels.lookNameOf(game)) ? 5 : 3;
    }

    private void beat(Game game, int n) {
        GameState st = game.state();
        if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
        boolean hypixel = "hypixel".equals(Duels.lookNameOf(game));
        if (!caged) {
            caged = true;
            stamp(game);
        }
        // the clock starts with the first beat (captured: Time Left ticks through the cage count)
        if (st.get(Scope.GAME, Duels.STARTED_AT) < 0) {
            st.set(Scope.GAME, Duels.STARTED_AT, st.tick());
            st.alarms().arm(Rules.TIME_UP_ALARM, st.tick() + game.rules().resolve(Scope.GAME, Bridge.LIMIT));
        }
        if (!introShown) {
            introShown = true;
            if (hypixel) { // captured: the instruction block lands right before the first cage beat
                Duels.each(game, p -> {
                    Component started = Duels.lookOf(game).count().started().apply(game, p);
                    if (started != null) p.sendMessage(started);
                });
            }
        }
        if (n > 0) {
            Title beat = Title.title(held != null ? held : Component.empty(),
                    Banners.cageCount(game, n), Duels.times(game, 0, 40, 0));
            Component chat = "scrims".equals(Duels.lookNameOf(game)) ? Banners.cageCount(game, n) : null;
            Duels.each(game, p -> {
                p.showTitle(beat);
                if (chat != null) p.sendMessage(chat); // captured: scrims mirrors the count into chat
                p.playSound(hypixel ? HAT : TICK);
            });
            if (held != null) Duels.cue(game, SoundSet.SCORED); // captured: the goal firework rides every beat
            st.alarms().arm(COUNT_ALARM, st.tick() + 20, CompoundBinaryTag.builder().putInt("n", n - 1).build());
            return;
        }
        if (game.shard().members().size() < 2) { // a duelist never arrived (logged out mid-transfer)
            st.set(Scope.GAME, Duels.PHASE, "done");
            Duels.each(game, p -> Msg.tag(p, "duel", "duel cancelled - your opponent never showed"));
            game.decide(Outcome.draw(), 1);
            return;
        }
        open(game);
        held = null;
        st.set(Scope.GAME, Duels.PHASE, "fighting");
        Title fight = Title.title(Component.empty(), Banners.fight(game), Duels.times(game, 0, 20, 10));
        Duels.each(game, p -> {
            p.showTitle(fight);
            p.playSound(hypixel ? HARP : GO);
        });
    }

    private void recage(Game game, CompoundBinaryTag payload) {
        String scorer = payload.getString("scorer");
        held = scorer.isEmpty() ? null
                : Banners.scoredHeld(game, scorer, game.roster().team(payload.getString("team")).color());
        game.state().set(Scope.GAME, Duels.PHASE, "countdown");
        caged = true;
        stamp(game);
        game.state().alarms().arm(Rules.REFIT_ALARM, game.state().tick() + 1);
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
                for (int x = part.min().blockX(); x <= part.max().blockX(); x++) {
                    for (int z = part.min().blockZ(); z <= part.max().blockZ(); z++) {
                        if (!source.isChunkLoaded(x >> 4, z >> 4)) source.loadChunk(x >> 4, z >> 4).join();
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
        for (Player member : game.members()) {
            Slot seat = game.roster().slotOf(member);
            if (seat != null && seat.team().equals(team)) return choiceOf(member);
        }
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
        Map<String, CageDef> defs = cages();
        if (defs.isEmpty()) return;
        for (PosMarker spawn : game.arena().markers(Bridge.CAGE)) {
            CageDef cage = defs.getOrDefault(choiceFor(game, spawn.group()), defs.get(""));
            if (cage == null) cage = defs.values().iterator().next();
            var at = spawn.pos();
            // the map marker already faces the center - rotate the authored front onto it
            int turns = cage.front().quarterTurnsTo(Facing.fromYaw(at.yaw()));
            String dye = TEAM_DYE.getOrDefault(spawn.group(), "RED");
            for (Cell cell : cage.cells()) {
                BlockVec turned = Facing.rotate(cell.offset(), turns);
                BlockVec pos = new BlockVec(at.blockX() + turned.blockX(), at.blockY() + turned.blockY(),
                        at.blockZ() + turned.blockZ());
                if (!game.instance().isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) {
                    game.instance().loadChunk(pos.blockX() >> 4, pos.blockZ() >> 4).join();
                }
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
}
