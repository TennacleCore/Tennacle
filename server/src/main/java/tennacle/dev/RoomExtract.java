package tennacle.dev;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.ArenaBuilder;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Arenas;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.world.DimensionType;
import tennacle.games.map.Chunks;
import tennacle.games.map.Prefab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cuts a room out of an arena into the rooms arena: the blocks of its region, at the same coordinates, under a
 * {@code room} region whose group names the variant.
 * {@code gradlew roomExtract -Psource=<arena.bncl> [-Pkey=bedwars:lobby] [-Pvariant=bedwars] [-Pout=arenas/rooms.bncl]}
 */
public final class RoomExtract {

    private RoomExtract() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: RoomExtract <source arena> <region key> <variant> <out arena>");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        String key = args[1], variant = args[2];
        Path out = Path.of(args[3]);
        MinecraftServer.init();
        Arena arena = Arena.read(Files.readAllBytes(source));
        List<RegionMarker> regions = arena.markers(MarkerKey.region(key));
        if (regions.isEmpty()) throw new IOException(source + " has no " + key + " region");
        RegionMarker region = regions.getFirst();
        RegionMarker.Cuboid box = region.boxes().getFirst();
        Instance from = arena.instantiate();
        List<Prefab.Cell> cells = Prefab.read(from, region, box.min());
        arena.unload();

        InstanceContainer into = new InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD, ChunkLoader.noop());
        into.setChunkSupplier(LightingChunk::new);
        MinecraftServer.getInstanceManager().registerInstance(into);
        Chunks.load(into, box.min().blockX() >> 4, box.max().blockX() >> 4, box.min().blockZ() >> 4, box.max().blockZ() >> 4);
        Map<Integer, Integer> layers = new TreeMap<>();
        for (Prefab.Cell cell : cells) {
            into.setBlock(box.min().blockX() + cell.offset().blockX(), box.min().blockY() + cell.offset().blockY(),
                    box.min().blockZ() + cell.offset().blockZ(), cell.block());
            layers.merge(cell.offset().blockY(), 1, Integer::sum);
        }
        List<Chunk> chunks = new ArrayList<>(into.getChunks());
        LightingChunk.relight(into, chunks);
        byte[] bytes = new ArenaBuilder("rooms").name("Rooms").authors("Hypixel").tags("rooms").time(6000)
                .world(Arenas.world(chunks))
                .marker(new RegionMarker("room", variant, "", box.min(), box.max()))
                .build();
        Arenas.write(out, bytes);
        System.out.println("room " + variant + ": " + cells.size() + " blocks from " + box.min() + " to " + box.max()
                + ", by layer " + layers + " -> " + out.toAbsolutePath() + " (" + bytes.length / 1024 + " KB)");
        System.exit(0);
    }
}
