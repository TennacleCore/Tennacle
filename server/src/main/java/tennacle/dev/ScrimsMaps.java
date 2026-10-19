package tennacle.dev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.term4.barnacle.ArenaBuilder;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Markers;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Bridge Scrims maps as Bridge arenas: each world and marker file {@code research/scrims/scrims_map.py} wrote from
 * a capture. The worlds are Scrims'; each arena names them as its author.
 * <p>Args: the folder of {@code <map>.zip} and {@code <map>.json} pairs, then the output folder
 * ({@code arenas/scrims} by default, out of the bridge rotation).
 * {@code gradlew scrimsMaps -Pmaps=... [-Pout=...]}
 */
public final class ScrimsMaps {

    // the ordinals' own colors
    private static final List<String> TEAMS = List.of("red", "blue");

    private ScrimsMaps() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ScrimsMaps <maps dir> <out dir>");
            System.exit(2);
        }
        MinecraftServer.init();
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        int written = 0;
        try (Stream<Path> files = Files.list(Path.of(args[0]))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject map;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    map = new Gson().fromJson(reader, JsonObject.class);
                }
                Path zip = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.json$", ".zip"));
                String name = map.get("map").getAsString();
                String id = "scrims-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                byte[] arena = build(id, map, LegacyZip.toPolar(zip, instance -> {}));
                Files.write(out.resolve(id + ".bncl"), arena);
                written++;
                System.out.println(name + ": " + id + ".bncl, " + arena.length / 1024 + " KB, from " + map.get("capture").getAsString());
            }
        }
        System.out.println(written + " arenas in " + out.toAbsolutePath());
        System.exit(0);
    }

    private static byte[] build(String id, JsonObject map, byte[] world) {
        JsonArray spawn = map.getAsJsonArray("spawn");
        ArenaBuilder builder = new ArenaBuilder(id).name(map.get("map").getAsString()).authors("Scrims")
                .tags("bridge")
                .spawn(new Pos(spawn.get(0).getAsDouble(), spawn.get(1).getAsDouble(), spawn.get(2).getAsDouble()))
                .world(world)
                .user(CompoundBinaryTag.builder().put("scrims", CompoundBinaryTag.builder()
                        .putString("capture", map.get("capture").getAsString())
                        .putString("symmetry", map.get("symmetry").getAsString()).build()).build());
        for (var element : map.getAsJsonArray("sides")) {
            JsonObject side = element.getAsJsonObject();
            String group = String.valueOf(TEAMS.indexOf(side.get("team").getAsString()) + 1);
            if (group.equals("0")) throw new IllegalArgumentException("a side on team " + side.get("team"));
            builder.marker(new PosMarker("bridge:cage", group, "", pos(side.getAsJsonArray("cage"))));
            builder.marker(new PosMarker("bridge:respawn", group, "", pos(side.getAsJsonArray("respawn"))));
            builder.marker(region("bridge:goal", group, side.getAsJsonObject("goal")));
            builder.marker(region("section", group, side.getAsJsonObject("section")));
        }
        builder.marker(region("bridge:build", "", map.getAsJsonObject("build")));
        builder.marker(region("center", "", map.getAsJsonObject("center")));
        builder.marker(region(Markers.BOUNDS.id(), "", map.getAsJsonObject("bounds")));
        builder.marker(new LimitMarker("bridge:height", "pinned", "", null, map.get("height").getAsDouble(), null));
        builder.marker(new LimitMarker("bridge:void", "", "", null, map.get("void").getAsDouble(), null));
        builder.marker(new DataMarker("bloom:starter", "", "", CompoundBinaryTag.builder().putString("s", "bridge").build(), 1));
        return builder.build();
    }

    private static Pos pos(JsonArray v) {
        return new Pos(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble(), v.get(3).getAsFloat(), 0);
    }

    private static RegionMarker region(String key, String group, JsonObject box) {
        return new RegionMarker(key, group, "", block(box.getAsJsonArray("min")), block(box.getAsJsonArray("max")));
    }

    private static BlockVec block(JsonArray v) {
        return new BlockVec(v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt());
    }
}
