package tennacle.dev;

import io.github.term4.barnacle.ArenaMeta;
import io.github.term4.barnacle.format.ArenaContainer;
import io.github.term4.barnacle.format.ArenaManifest;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Each arena's icon for the map menus: the block that most sets its world apart from the others in the folder (a
 * block's share of this map, weighted by how few maps share it, as search engines weigh words), filler aside (air,
 * fluids, bedrock, and the team-colored wool, clay and glass every base is built from). Written to the arena's
 * {@code icon}; an icon already there stays, so a hand-picked one is never overwritten.
 */
public final class ArenaIcons {

    public static final String ICON = "icon";

    private static final List<String> FILLER = List.of("air", "water", "lava", "bedrock", "barrier", "light",
            "structure_void", "wool", "terracotta", "stained_glass", "glass_pane", "glass", "bed", "carpet");
    // a block under this share of a map is a detail, not its look; a map "has" a block from this share on
    private static final double NOTICED = 0.02, PRESENT = 0.005;

    private record Read(Path file, Map<String, byte[]> entries, ArenaManifest manifest, Map<String, Double> shares) {}

    private ArenaIcons() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: ArenaIcons <arena dir> [--write]");
            System.exit(2);
        }
        MinecraftServer.init();
        boolean write = args.length > 1 && args[1].equals("--write");
        List<Read> arenas;
        try (Stream<Path> files = Files.list(Path.of(args[0]))) {
            arenas = files.filter(f -> f.toString().endsWith(".bncl")).sorted().map(ArenaIcons::read).toList();
        }
        Map<String, Integer> present = new HashMap<>();
        for (Read arena : arenas) arena.shares().forEach((block, share) -> {
            if (share >= PRESENT) present.merge(block, 1, Integer::sum);
        });
        for (Read arena : arenas) {
            ArenaMeta meta = arena.manifest().meta();
            String held = meta.user().getString(ICON);
            String icon = !held.isEmpty() ? held : signature(arena.shares(), present, arenas.size());
            System.out.println(meta.name() + " -> " + icon + (!held.isEmpty() ? " (kept)" : ""));
            if (!write || icon == null || !held.isEmpty()) continue;
            ArenaMeta named = new ArenaMeta(meta.id(), meta.version(), meta.name(), meta.authors(), meta.tags(), meta.dimension(),
                    meta.dataVersion(), meta.spawn(), meta.time(), meta.weather(), meta.user().putString(ICON, icon));
            Map<String, byte[]> entries = new LinkedHashMap<>(arena.entries());
            entries.put(ArenaContainer.MANIFEST, new ArenaManifest(named, arena.manifest().bounds(), arena.manifest().markers(),
                    arena.manifest().layers(), arena.manifest().variants()).toBytes());
            Files.write(arena.file(), ArenaContainer.pack(entries));
        }
        System.exit(0);
    }

    private static Read read(Path file) {
        try {
            Map<String, byte[]> entries = ArenaContainer.unpack(Files.readAllBytes(file));
            return new Read(file, entries, ArenaManifest.fromBytes(entries.get(ArenaContainer.MANIFEST)),
                    shares(PolarReader.read(entries.get(ArenaContainer.WORLD))));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Each block's share of {@code world}, filler and blocks with no item aside. */
    static Map<String, Double> shares(PolarWorld world) {
        Map<String, Long> counts = new HashMap<>();
        for (PolarChunk chunk : world.chunks()) {
            for (PolarSection section : chunk.sections()) {
                String[] palette = section.blockPalette();
                if (palette.length == 1) {
                    counts.merge(block(palette[0]), 4096L, Long::sum);
                    continue;
                }
                long[] perEntry = new long[palette.length];
                for (int index : section.blockData()) perEntry[index]++;
                for (int i = 0; i < palette.length; i++) counts.merge(block(palette[i]), perEntry[i], Long::sum);
            }
        }
        counts.keySet().removeIf(block -> filler(block) || Material.fromKey(block) == null);
        double total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Double> shares = new HashMap<>();
        if (total > 0) counts.forEach((block, n) -> shares.put(block, n / total));
        return shares;
    }

    static @Nullable String signature(Map<String, Double> shares, Map<String, Integer> present, int maps) {
        return shares.entrySet().stream().filter(e -> e.getValue() >= NOTICED)
                .max(java.util.Comparator.comparingDouble(e -> e.getValue() * Math.log((double) maps / present.getOrDefault(e.getKey(), 1))))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static String block(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static boolean filler(String block) {
        String name = block.substring(block.indexOf(':') + 1);
        for (String part : FILLER) if (name.equals(part) || name.endsWith("_" + part)) return true;
        return false;
    }
}
