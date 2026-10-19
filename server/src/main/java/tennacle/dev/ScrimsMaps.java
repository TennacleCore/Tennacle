package tennacle.dev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.term4.barnacle.ArenaBuilder;
import io.github.term4.barnacle.format.ArenaContainer;
import io.github.term4.barnacle.format.ArenaManifest;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Markers;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Bridge Scrims maps as Bridge arenas: each world and marker file {@code research/scrims/scrims_map.py} wrote from
 * a capture. The worlds are Scrims'; each arena names them as its author. Scrims is the source of truth: a map
 * replaces the bridge arena of its name.
 * <p>Args: the folder of {@code <map>.zip} and {@code <map>.json} pairs, then the output folder
 * ({@code arenas} by default).
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
                String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                Path target = out.resolve(id + ".bncl");
                if (Files.exists(target) && !bridge(target)) {
                    System.out.println(name + ": " + target + " is not a bridge arena, left alone");
                    continue;
                }
                byte[] arena = build(id, map, LegacyZip.toPolar(zip, instance -> {}));
                Files.write(target, arena);
                written++;
                System.out.println(name + ": " + id + ".bncl, " + arena.length / 1024 + " KB, from " + map.get("capture").getAsString()
                        + (map.has("guessed") && !map.getAsJsonArray("guessed").isEmpty() ? "; GUESSED " + map.get("guessed") : ""));
            }
        }
        System.out.println(written + " arenas in " + out.toAbsolutePath());
        System.exit(0);
    }

    private static boolean bridge(Path arena) throws IOException {
        var manifest = ArenaManifest.fromBytes(ArenaContainer.unpack(Files.readAllBytes(arena)).get(ArenaContainer.MANIFEST));
        return manifest.meta().tags().contains("bridge");
    }

    private static byte[] build(String id, JsonObject map, byte[] world) {
        JsonArray spawn = map.getAsJsonArray("spawn");
        ArenaBuilder builder = new ArenaBuilder(id).name(map.get("map").getAsString()).authors(authors(map))
                .tags("bridge")
                .spawn(new Pos(spawn.get(0).getAsDouble(), spawn.get(1).getAsDouble(), spawn.get(2).getAsDouble()))
                .world(world)
                .user(CompoundBinaryTag.builder().put("scrims", CompoundBinaryTag.builder()
                        .putString("capture", map.get("capture").getAsString())
                        .putString("symmetry", map.get("symmetry").getAsString())
                        .put("guessed", guessed(map)).build()).build());
        for (var element : map.getAsJsonArray("sides")) {
            JsonObject side = element.getAsJsonObject();
            String group = String.valueOf(TEAMS.indexOf(side.get("team").getAsString()) + 1);
            if (group.equals("0")) throw new IllegalArgumentException("a side on team " + side.get("team"));
            builder.marker(new PosMarker("bridge:cage", group, "", pos(side.getAsJsonArray("cage"))));
            builder.marker(new PosMarker("bridge:respawn", group, "", pos(side.getAsJsonArray("respawn"))));
            builder.marker(region("bridge:goal", group, side.getAsJsonObject("goal")));
            builder.marker(region("section", group, side.getAsJsonObject("section")));
        }
        List<RegionMarker.Cuboid> build = new ArrayList<>(); // flat slices: the band gates y
        for (var box : map.getAsJsonArray("build")) build.add(cuboid(box.getAsJsonObject()));
        builder.marker(new RegionMarker("bridge:build", "", "", build, null, 0));
        builder.marker(region("center", "", map.getAsJsonObject("center")));
        builder.marker(region(Markers.BOUNDS.id(), "", map.getAsJsonObject("bounds")));
        builder.marker(new LimitMarker("bridge:height", "pinned", "", null, map.get("height").getAsDouble(), null));
        builder.marker(new LimitMarker("bridge:void", "", "", null, map.get("void").getAsDouble(), null));
        builder.marker(new DataMarker("bloom:starter", "", "", CompoundBinaryTag.builder().putString("s", "bridge").build(), 1));
        return builder.build();
    }

    // what no capture showed, so a later duel knows to probe it
    private static ListBinaryTag guessed(JsonObject map) {
        ListBinaryTag.Builder<StringBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.STRING);
        if (map.has("guessed")) {
            for (var need : map.getAsJsonArray("guessed")) out.add(StringBinaryTag.stringBinaryTag(need.getAsString()));
        }
        return out.build();
    }

    // the builders a map name credits, then Scrims
    private static String[] authors(JsonObject map) {
        List<String> out = new ArrayList<>();
        if (map.has("authors")) for (var a : map.getAsJsonArray("authors")) out.add(a.getAsString());
        out.add("Scrims");
        return out.toArray(String[]::new);
    }

    private static Pos pos(JsonArray v) {
        return new Pos(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble(), v.get(3).getAsFloat(), 0);
    }

    private static RegionMarker region(String key, String group, JsonObject box) {
        return new RegionMarker(key, group, "", List.of(cuboid(box)), null, 0);
    }

    private static RegionMarker.Cuboid cuboid(JsonObject box) {
        return new RegionMarker.Cuboid(block(box.getAsJsonArray("min")), block(box.getAsJsonArray("max")));
    }

    private static BlockVec block(JsonArray v) {
        return new BlockVec(v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt());
    }
}
