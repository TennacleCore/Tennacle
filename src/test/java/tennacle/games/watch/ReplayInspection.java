package tennacle.games.watch;

import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.codec.Transcoder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Not a test: a reader for the replay files on disk, run by hand with {@code REPLAY_INSPECT=<n>} (the newest n
 * files) - what each actor's channels say, so a ghost that never showed can be traced to the op that hid it.
 */
class ReplayInspection extends HeadlessServerTest {

    @Test
    void dump() throws IOException {
        String want = System.getenv("REPLAY_INSPECT");
        Assumptions.assumeTrue(want != null, "REPLAY_INSPECT names how many files to read");
        int newest = Integer.parseInt(want);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("replays"))) {
            files = walk.filter(p -> p.toString().endsWith(".shard"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .limit(newest).toList();
        }
        for (Path file : files) describe(file);
    }

    private static void describe(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        UserData.View data = ShardFile.userData(bytes);
        CompoundBinaryTag ops = data.get(Replay.OPS);
        System.out.println("=== " + file + " (" + bytes.length + " bytes) ticks=" + (ops != null ? ops.getLong("ticks") : -1)
                + " lineup=" + data.get(Replays.LINEUP) + " code=" + data.get(Replays.CODE));
        if (ops == null) return;
        CompoundBinaryTag channels = ops.getCompound("channels");
        Map<String, StringBuilder> actors = new LinkedHashMap<>();
        Map<String, int[]> moves = new LinkedHashMap<>(); // count, first tick, last tick
        for (Recorded<?> op : read(Channels.MOVES, channels)) {
            Channels.Move m = (Channels.Move) op.op();
            int[] tally = moves.computeIfAbsent(m.player(), k -> new int[]{0, (int) op.tick(), (int) op.tick()});
            tally[0]++;
            tally[2] = (int) op.tick();
        }
        for (Recorded<?> op : read(Channels.SKINS, channels)) {
            Channels.Skin s = (Channels.Skin) op.op();
            actors.computeIfAbsent(s.player(), k -> new StringBuilder()).append(" skin@").append(op.tick());
        }
        for (Recorded<?> op : read(Channels.ACTS, channels)) {
            Channels.Act a = (Channels.Act) op.op();
            String word = switch (a.kind()) {
                case Channels.HIDDEN -> "HIDDEN";
                case Channels.SHOWN -> "SHOWN";
                case Channels.GONE -> "GONE";
                case Channels.LAYER -> "LAYER" + a.value();
                case Channels.DEATH -> "DEATH";
                case Channels.RESPAWN -> "RESPAWN";
                default -> null;
            };
            if (word != null) actors.computeIfAbsent(a.player(), k -> new StringBuilder()).append(' ').append(word).append('@').append(op.tick());
        }
        Map<String, Integer> shots = new LinkedHashMap<>();
        for (Recorded<?> op : read(Channels.SPAWNS, channels)) {
            Channels.Spawn s = (Channels.Spawn) op.op();
            if (!s.shooter().isEmpty()) shots.merge(s.shooter(), 1, Integer::sum);
        }
        List<String> names = new ArrayList<>(moves.keySet());
        for (String name : actors.keySet()) if (!names.contains(name)) names.add(name);
        for (String name : shots.keySet()) if (!names.contains(name)) names.add(name);
        for (String name : names) {
            int[] tally = moves.get(name);
            System.out.println("  " + name + ": moves=" + (tally != null ? tally[0] + " [" + tally[1] + ".." + tally[2] + "]" : "NONE")
                    + " shots=" + shots.getOrDefault(name, 0) + " acts=" + actors.getOrDefault(name, new StringBuilder("(none)")));
        }
    }

    // Playback's own three shapes: spooled packed parts, one packed blob, per-op NBT
    private static List<Recorded<?>> read(Recordable<?> type, CompoundBinaryTag channels) {
        CompoundBinaryTag channel = channels.getCompound(type.id());
        List<Recorded<?>> out = new ArrayList<>();
        if (channel.keySet().isEmpty()) return out;
        if (channel.get("parts") instanceof ListBinaryTag parts) {
            for (BinaryTag part : parts) out.addAll(Objects.requireNonNull(type.pack()).decode(((ByteArrayBinaryTag) part).value()));
            return out;
        }
        if (channel.get("bin") != null) {
            out.addAll(Objects.requireNonNull(type.pack()).decode(channel.getByteArray("bin")));
            return out;
        }
        for (BinaryTag tag : channel.getList("ops", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            out.add(new Recorded<>(entry.getLong("t"), type.codec().decode(Transcoder.NBT, entry.get("op")).orElseThrow()));
        }
        return out;
    }
}
