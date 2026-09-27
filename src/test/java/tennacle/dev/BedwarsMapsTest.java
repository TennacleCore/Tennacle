package tennacle.dev;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Markers;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An archived 1.8 world and its marker file come out as one arena: flattened, lit, every marker in its team. */
class BedwarsMapsTest extends HeadlessServerTest {

    @TempDir
    Path dir;

    private static int index(int x, int y, int z) {
        return (y & 15) << 8 | z << 4 | x;
    }

    private static void nibble(byte[] array, int index, int value) {
        int shift = (index & 1) * 4;
        array[index >> 1] = (byte) ((array[index >> 1] & ~(15 << shift)) | (value << shift));
    }

    /** One 1.8 chunk: a stone floor at y 64, a diamond block in it, a bed on it with its head at 3,65,3. */
    private static byte[] region() throws IOException {
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) blocks[index(x, 64, z)] = 1;
        }
        blocks[index(8, 64, 8)] = 57;
        blocks[index(3, 65, 2)] = 26; // foot, facing south
        blocks[index(3, 65, 3)] = 26;
        nibble(data, index(3, 65, 3), 8); // head bit, facing south
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 4).putByteArray("Blocks", blocks).putByteArray("Data", data).build();
        CompoundBinaryTag chunk = CompoundBinaryTag.builder().put("Level", CompoundBinaryTag.builder()
                .putInt("xPos", 0).putInt("zPos", 0)
                .put("Sections", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(section).build()).build()).build();
        ByteArrayOutputStream nbt = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(chunk, nbt, BinaryTagIO.Compression.ZLIB);
        byte[] payload = nbt.toByteArray();
        int sectors = (payload.length + 5 + 4095) / 4096;
        ByteBuffer file = ByteBuffer.allocate(8192 + sectors * 4096);
        file.putInt(0, 2 << 8 | sectors);
        file.position(8192);
        file.putInt(payload.length + 1).put((byte) 2).put(payload);
        return file.array();
    }

    private static final String MARKERS = """
            {"map": "Test Map", "group": "Solo-Doubles Maps/Regular", "teams": 2, "team source": "captured", "notes": [],
             "lobby": {"min": [-2, 90, -2], "max": [2, 99, 2], "spawn": [0.5, 91, 0.5]},
             "build": {"pool": "BEDWARS_8TEAMS_FAST", "minBuild": 60, "maxBuild": 80, "buildRadius": 20},
             "forge spawners": 3,
             "base box": {"back and front": 3, "left and right": 2, "down and up": 1, "open sides": []},
             "bases": [
              {"team": "Red", "bed": {"head": [3, 65, 3], "foot": [3, 65, 2], "facing": "south"},
               "spawn": [3.5, 65, -3.5], "forge": [3.5, 65, -6.5], "item shop": [0.5, 65.16, -3.5],
               "upgrades": [6.5, 65.16, -3.5], "source": "captured",
               "forge iron": [{"at": [2.5, 65.5, -6.5], "spawners": {"4v4": 1}}, {"at": [3.5, 65, -6.5], "spawners": {"4v4": 3}}]},
              {"team": "Blue", "bed": {"head": [12, 65, 3], "foot": [12, 65, 2], "facing": "south"},
               "spawn": [12.5, 65, -3.5], "forge": [12.5, 65, -6.5], "item shop": [9.5, 65.16, -3.5],
               "upgrades": [15.5, 65.16, -3.5], "source": "captured",
               "forge iron": [{"at": [12.5, 65, -6.5], "spawners": {"4v4": 4}}]}],
             "generator points": {"diamond": [[8.5, 66.0, 8.5]], "emerald": []},
             "generator blocks": {"diamond": [[8, 64, 8]], "emerald": []},
             "generator source": {"diamond": "lone blocks", "emerald": "none found"}}
            """;

    @Test
    void worldAndMarkersConvert() throws IOException {
        Path archive = dir.resolve("archive"), markers = dir.resolve("markers"), out = dir.resolve("out");
        Files.createDirectories(archive);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive.resolve("Test Map.zip")))) {
            zip.putNextEntry(new ZipEntry("Test Map/region/r.0.0.mca"));
            zip.write(region());
            zip.closeEntry();
        }
        Files.createDirectories(markers);
        Files.writeString(markers.resolve("Test Map.json"), MARKERS, StandardCharsets.UTF_8);

        assertEquals(1, BedwarsMaps.convert(archive, markers, out, Set.of("Test Map")));
        Arena arena = Arena.read(out.resolve("bedwars-test-map.bncl"));
        assertEquals(List.of("bedwars", "bedwars-4v4"), arena.meta().tags(), "two teams play 4v4");

        assertEquals(Set.of("1", "2"), arena.groups(Markers.SPAWN));
        PosMarker red = arena.marker(Markers.SPAWN, "1");
        assertNotNull(red);
        assertEquals(0, red.pos().yaw(), 0.01, "a spawn faces its bed");
        RegionMarker bed = arena.marker(MarkerKey.region("bedwars:bed"), "1");
        assertNotNull(bed);
        assertTrue(bed.contains(new BlockVec(3, 65, 2)) && bed.contains(new BlockVec(3, 65, 3)));
        RegionMarker base = arena.marker(MarkerKey.region("bedwars:base"), "2");
        assertNotNull(base);
        assertEquals(new BlockVec(10, 64, -7), base.boxes().getFirst().min());
        assertEquals(new BlockVec(14, 66, -1), base.boxes().getFirst().max());
        DataMarker look = arena.marker(MarkerKey.data("team"), "2");
        assertNotNull(look);
        assertEquals("blue", look.payload().getString("display"));
        assertEquals(new Vec(0.5, 65, -3.5), arena.marker(MarkerKey.point("bedwars:shop"), "1").point());
        assertEquals(List.of(new Vec(8.5, 66, 8.5)),
                arena.markers(MarkerKey.point("bedwars:diamond")).stream().map(PointMarker::point).toList());
        List<PointMarker> iron = arena.markers(MarkerKey.point("bedwars:forge-iron")).stream()
                .filter(m -> m.group().equals("1")).toList();
        assertEquals(List.of(new Vec(2.5, 65.5, -6.5), new Vec(3.5, 65, -6.5)), iron.stream().map(PointMarker::point).toList());
        assertEquals(3, iron.get(1).payload().getInt("4v4"));
        assertEquals(79.0, arena.markers(MarkerKey.limit("bedwars:height")).getFirst().y());
        assertEquals(60.0, arena.markers(MarkerKey.limit("bedwars:floor")).getFirst().y());
        assertEquals(1, arena.markers(MarkerKey.limit("bedwars:void")).size());
        assertTrue(arena.marker(MarkerKey.region("bedwars:build"), "").contains(new BlockVec(19, 79, -19)));

        Instance world = arena.instantiate();
        assertEquals(Block.STONE, world.getBlock(0, 64, 0));
        assertEquals(Block.DIAMOND_BLOCK, world.getBlock(8, 64, 8));
        assertEquals("head", world.getBlock(3, 65, 3).getProperty("part"));
        Chunk chunk = world.getChunk(0, 0);
        assertNotNull(chunk);
        assertEquals(15, chunk.getSectionAt(66).skyLight().getLevel(5, 66 & 15, 5), "open sky over the floor is lit");
    }
}
