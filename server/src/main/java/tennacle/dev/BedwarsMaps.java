package tennacle.dev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.term4.barnacle.ArenaBuilder;
import io.github.term4.barnacle.format.ArenaContainer;
import io.github.term4.barnacle.format.ArenaManifest;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.Marker;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.LegacyAnvil;
import io.github.term4.bloom.mapmaking.Teams;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Markers;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Hypixel's archived BedWars worlds as arenas: each world through Bloom's pre-1.13 reader, with the markers
 * {@code research/bedwars/map_markers.py} wrote for it. The worlds are Hypixel's and each arena names them as its
 * author; we claim no part of them.
 * <p>Args: the archive folder, a folder of marker files, the output folder ({@code arenas/bedwars} by default),
 * then optionally map names to limit the run to, comma separated.
 * {@code gradlew bedwarsMaps -Parchive=... -Pmarkers=... [-Pout=...] [-Pmaps=...]}
 */
public final class BedwarsMaps {

    /** Hypixel's order round the map; the group is the ordinal, as on every arena here. */
    private static final List<String> TEAMS = List.of("Red", "Blue", "Green", "Yellow", "Aqua", "White", "Pink", "Gray");
    private static final Map<String, String> COLOR = Map.of("Red", "red", "Blue", "blue", "Green", "green",
            "Yellow", "yellow", "Aqua", "aqua", "White", "white", "Pink", "light_purple", "Gray", "dark_gray");
    private static final Map<Integer, List<String>> TAGS = Map.of(
            8, List.of("bedwars", "bedwars-solo", "bedwars-doubles"),
            4, List.of("bedwars", "bedwars-3v3v3v3", "bedwars-4v4v4v4"),
            2, List.of("bedwars", "bedwars-4v4"));

    private BedwarsMaps() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--reface")) {
            MinecraftServer.init();
            System.out.println(reface(Path.of(args[1])) + " arenas re-aimed in " + Path.of(args[1]).toAbsolutePath());
            System.exit(0);
        }
        if (args.length < 3) {
            System.err.println("usage: BedwarsMaps <archive dir> <marker dir> <out dir> [map,map,...]");
            System.exit(2);
        }
        Set<String> only = args.length > 3 && !args[3].isBlank() ? Set.of(args[3].split(",")) : null;
        MinecraftServer.init();
        int written = convert(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), only);
        System.out.println(written + " arenas in " + Path.of(args[2]).toAbsolutePath());
        System.exit(0);
    }

    /** Every marker file's map that the archive holds, or only those named; needs an initialized server. */
    static int convert(Path archive, Path markers, Path out, @Nullable Set<String> only) throws IOException {
        Files.createDirectories(out);
        int written = 0;
        try (Stream<Path> files = Files.walk(markers)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject map;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    map = new Gson().fromJson(reader, JsonObject.class);
                }
                String name = map.get("map").getAsString();
                if (only != null && !only.contains(name)) continue;
                String zipName = (map.has("archive") ? map.get("archive").getAsString() : name) + ".zip";
                Path zip = find(archive, zipName);
                if (zip == null) {
                    System.out.println(name + ": no " + zipName + " under " + archive);
                    continue;
                }
                String id = "bedwars-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                byte[] arena = build(id, map, world(zip, map));
                Files.write(out.resolve(id + ".bncl"), arena);
                written++;
                System.out.println(name + ": " + id + ".bncl, " + arena.length / 1024 + " KB, " + provenance(map));
            }
        }
        return written;
    }

    // the room is the rooms arena's to lay (games.map.Rooms): the map ships the marker alone
    private static RegionMarker.@Nullable Cuboid room(JsonObject map) {
        JsonObject lobby = object(map, "lobby");
        return lobby == null ? null : new RegionMarker.Cuboid(block(lobby.getAsJsonArray("min")), block(lobby.getAsJsonArray("max")));
    }

    private static void clear(Instance instance, RegionMarker.@Nullable Cuboid room) {
        if (room == null) return;
        for (int x = room.min().blockX(); x <= room.max().blockX(); x++) {
            for (int z = room.min().blockZ(); z <= room.max().blockZ(); z++) {
                if (!instance.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = room.min().blockY(); y <= room.max().blockY(); y++) instance.setBlock(x, y, z, Block.AIR);
            }
        }
    }

    // a bed Hypixel moved inside a base the archive keeps as it plays (Swashbuckle's stand a block forward)
    private static void moveBeds(Instance instance, JsonObject map) {
        for (JsonElement element : map.getAsJsonArray("bases")) {
            JsonObject base = element.getAsJsonObject();
            JsonObject from = object(base, "bed archive");
            if (from == null) continue;
            JsonObject to = base.getAsJsonObject("bed");
            BlockVec oldHead = block(from.getAsJsonArray("head")), oldFoot = block(from.getAsJsonArray("foot"));
            Block head = instance.getBlock(oldHead), foot = instance.getBlock(oldFoot);
            instance.setBlock(oldHead, Block.AIR);
            instance.setBlock(oldFoot, Block.AIR);
            instance.setBlock(block(to.getAsJsonArray("foot")), foot);
            instance.setBlock(block(to.getAsJsonArray("head")), head);
        }
    }

    // blocks Hypixel added since the archive, where captures saw them (Swashbuckle's barriers)
    private static void liveBlocks(Instance instance, JsonObject map) {
        if (!map.has("live blocks")) return;
        for (JsonElement element : map.getAsJsonArray("live blocks")) {
            JsonArray cell = element.getAsJsonArray();
            Block block = Block.fromKey("minecraft:" + cell.get(3).getAsString());
            if (block == null) throw new IllegalArgumentException("no block " + cell.get(3).getAsString());
            instance.setBlock(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt(), block);
        }
    }

    private static byte[] world(Path zip, JsonObject map) throws IOException {
        Path temp = Files.createTempDirectory("bedwars-map");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            String root = null;
            for (ZipEntry entry : archive.stream().toList()) {
                int at = entry.getName().indexOf("region/");
                if (at >= 0) {
                    root = entry.getName().substring(0, at);
                    break;
                }
            }
            if (root == null) throw new IOException(zip + " holds no region/ folder");
            for (ZipEntry entry : archive.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().startsWith(root)) continue;
                Path target = temp.resolve(entry.getName().substring(root.length())).normalize();
                if (!target.startsWith(temp)) throw new IOException("entry outside the world: " + entry.getName());
                Files.createDirectories(target.getParent());
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
            if (!LegacyAnvil.isLegacy(temp)) throw new IOException(zip + " is not a pre-1.13 world");
            return LegacyAnvil.toPolar(temp, instance -> {
                clear(instance, room(map));
                moveBeds(instance, map);
                liveBlocks(instance, map);
            });
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static byte[] build(String id, JsonObject map, byte[] world) {
        JsonObject lobby = object(map, "lobby");
        // a map no capture recorded is a guess throughout, tagged so a rotation can leave it out
        boolean guessed = !map.has("status") || !map.get("status").getAsString().equals("captured");
        List<String> tags = new ArrayList<>(TAGS.getOrDefault(map.get("teams").getAsInt(), List.of("bedwars")));
        if (guessed) tags.add("bedwars-guessed");
        ListBinaryTag.Builder<StringBinaryTag> needs = ListBinaryTag.builder(BinaryTagTypes.STRING);
        if (map.has("needs capture")) {
            for (JsonElement need : map.getAsJsonArray("needs capture")) needs.add(StringBinaryTag.stringBinaryTag(need.getAsString()));
        }
        // the archive keeps some maps' live builds under another name (Orchestra v2 plays as Orchestra)
        String name = (map.has("played as") ? map.get("played as") : map.get("map")).getAsString();
        ArenaBuilder builder = new ArenaBuilder(id).name(name).authors("Hypixel")
                .tags(tags.toArray(String[]::new))
                .time(6000).world(world).user(CompoundBinaryTag.builder().put("bedwars", CompoundBinaryTag.builder()
                        .putString("source", provenance(map)).putString("status", guessed ? "guessed" : "captured")
                        .put("needs capture", needs.build()).build()).build());
        if (lobby != null) {
            Vec spawn = vec(lobby.getAsJsonArray("spawn"));
            builder.spawn(new Pos(spawn.x(), spawn.y(), spawn.z(), 180, 0));
            builder.marker(new RegionMarker("bedwars:lobby", "", "", block(lobby.getAsJsonArray("min")), block(lobby.getAsJsonArray("max"))));
        }
        for (Marker marker : markers(map)) builder.marker(marker);
        return builder.build();
    }

    /**
     * The yaw a base's players come in with: straight away from its forge, as every captured Hypixel start teleport
     * does (334 of 350 to the tenth of a degree, 2026-10-18); toward the bed where a base has no forge.
     */
    static float facing(Vec spawn, @Nullable Vec forge, Vec bed) {
        if (forge != null) return yawToward(spawn, forge) + 180;
        return yawToward(spawn, bed);
    }

    // a stored bed is head and foot as one box: its middle stands for the head the converter had
    private static Vec middle(RegionMarker.Cuboid box) {
        return new Vec((box.min().blockX() + box.max().blockX() + 1) / 2.0, box.min().blockY(), (box.min().blockZ() + box.max().blockZ() + 1) / 2.0);
    }

    private static float yawToward(Vec from, Vec to) {
        return (float) Math.toDegrees(-Math.atan2(to.x() - from.x(), to.z() - from.z()));
    }

    /** Re-aims the spawns of the arenas already in {@code dir} by {@link #facing}; their worlds are left as they are. */
    static int reface(Path dir) throws IOException {
        int changed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".bncl")).sorted().toList()) {
                Map<String, byte[]> entries = new java.util.LinkedHashMap<>(ArenaContainer.unpack(Files.readAllBytes(file)));
                ArenaManifest manifest = ArenaManifest.fromBytes(entries.get(ArenaContainer.MANIFEST));
                Map<String, Vec> forges = new java.util.HashMap<>();
                Map<String, Vec> beds = new java.util.HashMap<>();
                for (Marker marker : manifest.markers()) {
                    if (marker instanceof PointMarker point && point.key().equals("bedwars:forge")) forges.put(point.group(), point.point());
                    if (marker instanceof RegionMarker bed && bed.key().equals("bedwars:bed")) beds.put(bed.group(), middle(bed.bounds()));
                }
                List<Marker> markers = new ArrayList<>();
                boolean moved = false;
                for (Marker marker : manifest.markers()) {
                    if (marker instanceof PosMarker spawn && spawn.key().equals(Markers.SPAWN.id()) && beds.containsKey(spawn.group())) {
                        Vec at = spawn.pos().asVec();
                        float yaw = facing(at, forges.get(spawn.group()), beds.get(spawn.group()));
                        moved |= Math.abs(yaw - spawn.pos().yaw()) % 360 > 0.01f;
                        markers.add(new PosMarker(spawn.key(), spawn.group(), spawn.layer(), spawn.pos().withYaw(yaw),
                                spawn.payload(), spawn.payloadVersion()));
                    } else {
                        markers.add(marker);
                    }
                }
                if (!moved) continue;
                entries.put(ArenaContainer.MANIFEST, new ArenaManifest(manifest.meta(), manifest.bounds(), markers,
                        manifest.layers(), manifest.variants()).toBytes());
                Files.write(file, ArenaContainer.pack(entries));
                changed++;
            }
        }
        return changed;
    }

    private static List<Marker> markers(JsonObject map) {
        List<Marker> out = new ArrayList<>();
        JsonObject box = object(map, "base box");
        for (JsonElement element : map.getAsJsonArray("bases")) {
            JsonObject base = element.getAsJsonObject();
            if (base.get("team").isJsonNull()) continue;
            String team = base.get("team").getAsString();
            String group = String.valueOf(TEAMS.indexOf(team) + 1);
            JsonObject bed = base.getAsJsonObject("bed");
            BlockVec head = block(bed.getAsJsonArray("head")), foot = block(bed.getAsJsonArray("foot"));
            out.add(new RegionMarker("bedwars:bed", group, "", head, foot));
            out.add(new DataMarker(Teams.KEY, group, "", teamLook(team), 1));
            Vec spawn = base.has("spawn") ? vec(base.getAsJsonArray("spawn")) : null;
            Vec forge = base.has("forge") ? vec(base.getAsJsonArray("forge")) : null;
            if (spawn != null) {
                float yaw = facing(spawn, forge, head.add(0.5, 0, 0.5));
                out.add(new PosMarker(Markers.SPAWN.id(), group, "", new Pos(spawn.x(), spawn.y(), spawn.z(), yaw, 0)));
            }
            if (forge != null) out.add(new PointMarker("bedwars:forge", group, "", forge));
            if (base.has("forge iron")) {
                for (JsonElement point : base.getAsJsonArray("forge iron")) {
                    JsonObject iron = point.getAsJsonObject();
                    out.add(new PointMarker("bedwars:forge-iron", group, "", vec(iron.getAsJsonArray("at")),
                            spawners(iron.getAsJsonObject("spawners")), 1));
                }
            }
            if (base.has("item shop")) out.add(new PointMarker("bedwars:shop", group, "", standing(vec(base.getAsJsonArray("item shop")))));
            if (base.has("upgrades")) out.add(new PointMarker("bedwars:upgrades", group, "", standing(vec(base.getAsJsonArray("upgrades")))));
            if (box != null && spawn != null && forge != null) out.add(baseBox(group, spawn, forge, box));
        }
        JsonObject generators = map.getAsJsonObject("generator points");
        for (String kind : List.of("diamond", "emerald")) {
            for (JsonElement element : generators.getAsJsonArray(kind)) {
                out.add(new PointMarker("bedwars:" + kind, "", "", vec(element.getAsJsonArray())));
            }
        }
        JsonObject build = object(map, "build");
        if (build != null) {
            // maxBuild is the first height refused
            if (build.has("maxBuild")) out.add(new LimitMarker("bedwars:height", "", "", null, build.get("maxBuild").getAsDouble() - 1, null));
            if (build.has("minBuild")) out.add(new LimitMarker("bedwars:floor", "", "", null, build.get("minBuild").getAsDouble(), null));
            // a circle round 0,0 through block coordinates: a block goes in while its distance is under it
            if (build.has("buildRadius")) {
                out.add(new DataMarker("bedwars:radius", "", "", CompoundBinaryTag.builder()
                        .putDouble("radius", build.get("buildRadius").getAsDouble()).build(), 1));
            }
        }
        out.add(new LimitMarker("bedwars:void", "", "", null, 0.0, null));
        return out;
    }

    // centered on the spawn block, reach back and front along the forge's line, left and right across it
    private static RegionMarker baseBox(String group, Vec spawn, Vec forge, JsonObject box) {
        int along = box.get("back and front").getAsInt(), across = box.get("left and right").getAsInt();
        int up = box.get("down and up").getAsInt();
        boolean alongX = Math.abs(forge.x() - spawn.x()) >= Math.abs(forge.z() - spawn.z());
        int rx = alongX ? along : across, rz = alongX ? across : along;
        BlockVec center = new BlockVec(spawn.blockX(), spawn.blockY(), spawn.blockZ());
        return new RegionMarker("bedwars:base", group, "", center.sub(rx, up, rz), center.add(rx, up, rz));
    }

    // how many of each mode's spawners drop iron at the point, keyed as the arena's bedwars-<mode> tags
    private static CompoundBinaryTag spawners(JsonObject modes) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (Map.Entry<String, JsonElement> mode : modes.entrySet()) {
            out.putInt(mode.getKey().toLowerCase(Locale.ROOT), mode.getValue().getAsInt());
        }
        return out.build();
    }

    // base, not display: every role a kit reads (armor, wool) follows the team, never the ordinal default
    private static CompoundBinaryTag teamLook(String team) {
        String color = COLOR.get(team);
        Tint.parse(color); // a name Canvas would refuse fails the run, not the first match
        return CompoundBinaryTag.builder().putString(Livery.BASE, color).build();
    }

    // the shop anchors were read off the name holograms, a fraction above the shopkeeper's feet
    // the marker is the villager's feet: a carpet's 1/16 stays (Lucky Rush's keepers stand at 65.0625)
    private static Vec standing(Vec feet) {
        return new Vec(feet.x(), Math.round(feet.y() * 32) / 32.0, feet.z());
    }

    private static String provenance(JsonObject map) {
        String bases = map.getAsJsonArray("bases").asList().stream()
                .map(b -> b.getAsJsonObject().get("source").getAsString().split("[:,]")[0]).distinct().sorted()
                .reduce((a, b) -> a + "/" + b).orElse("none");
        JsonObject generators = map.getAsJsonObject("generator source");
        String status = map.has("status") ? map.get("status").getAsString() : "guessed";
        String needs = map.has("needs capture") ? map.getAsJsonArray("needs capture").asList().stream()
                .map(JsonElement::getAsString).reduce((a, b) -> a + ", " + b).orElse("") : "everything";
        return status.toUpperCase(Locale.ROOT) + (needs.isEmpty() ? "" : ", needs a capture: " + needs)
                + "; bases " + bases + "; teams " + map.get("team source").getAsString().split(":")[0]
                + "; diamond " + generators.get("diamond").getAsString() + "; emerald " + generators.get("emerald").getAsString()
                + (object(map, "base box") != null ? "; base box captured" : "; no base box");
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsJsonObject();
    }

    private static Vec vec(JsonArray values) {
        return new Vec(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    private static BlockVec block(JsonArray values) {
        return new BlockVec(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt());
    }

    private static Path find(Path dir, String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        }
    }
}
