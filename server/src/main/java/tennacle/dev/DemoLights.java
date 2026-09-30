package tennacle.dev;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardLighting;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The {@code /shard relight} demo {@link ShardLighting} implementations. */
public final class DemoLights {

    private DemoLights() {}

    /**
     * FORCES zero light (block and sky) in every chain-touched chunk. The snuff is delayed so the client's local
     * prediction shows first - torch glows, then the server kills it: the visible proof that server light
     * overrides client light.
     */
    public static final class DarkLight implements ShardLighting {
        private static final int SNUFF_DELAY = 20;
        private final Shard shard;
        private final Runnable unhook;
        private final Set<Long> lit = ConcurrentHashMap.newKeySet();
        private final Map<Long, Long> due = new ConcurrentHashMap<>();
        private volatile long clock;

        public DarkLight(Shard shard) {
            this.shard = shard;
            this.unhook = shard.onTick(t -> tick());
            markChunks(ShardLighting.chainChunks(shard)); // already-built areas snuff too
        }

        private UpdateLightPacket dark(int chunkX, int chunkZ) {
            var dim = shard.instance().getCachedDimensionType();
            int sections = (dim.maxY() >> 4) - (dim.minY() >> 4);
            BitSet empty = new BitSet();
            empty.set(0, sections + 2); // every section incl. the below/above padding
            return new UpdateLightPacket(chunkX, chunkZ,
                    new LightData(new BitSet(), new BitSet(), empty, empty, List.of(), List.of()));
        }

        private void tick() {
            long now = ++clock;
            for (var it = due.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() > now) continue;
                it.remove();
                long index = e.getKey();
                if (!candidateChunks().contains(index)) {
                    if (lit.remove(index)) ShardLighting.restoreBase(shard, index); // last diff broke: back to base
                    continue;
                }
                ShardLighting.broadcast(shard, dark(CoordConversion.chunkIndexGetX(index), CoordConversion.chunkIndexGetZ(index)));
                lit.add(index);
            }
        }

        @Override public void changed(Set<Long> sections) {
            for (long key : sections) {
                due.put(CoordConversion.chunkIndex(ShardLighting.sectionKeyX(key), ShardLighting.sectionKeyZ(key)),
                        clock + SNUFF_DELAY);
            }
        }
        private void markChunks(Set<Long> chunks) {
            for (long index : chunks) due.put(index, clock + SNUFF_DELAY);
        }
        @Override public void refreshLit() { markChunks(lit); }
        @Override public void sendTo(Player player, int chunkX, int chunkZ) {
            long index = CoordConversion.chunkIndex(chunkX, chunkZ);
            if (!candidateChunks().contains(index)) return;
            player.sendPacket(dark(chunkX, chunkZ));
            lit.add(index);
        }
        @Override public void close() {
            unhook.run();
            due.clear();
            for (long index : Set.copyOf(lit)) ShardLighting.restoreBase(shard, index);
            lit.clear();
        }
        @Override public SendablePacket packet(int chunkX, int chunkZ) {
            return candidateChunks().contains(CoordConversion.chunkIndex(chunkX, chunkZ)) ? dark(chunkX, chunkZ) : null;
        }
        @Override public Set<Long> litChunks() { return lit; }
        @Override public void markLit(long chunkIndex) { lit.add(chunkIndex); }
        @Override public Set<Long> candidateChunks() { return ShardLighting.chainChunks(shard); }
        @Override public int blockLight(int x, int y, int z) {
            return candidateChunks().contains(CoordConversion.chunkIndex(x >> 4, z >> 4)) ? 0 : -1;
        }
        @Override public int skyLight(int x, int y, int z) {
            return candidateChunks().contains(CoordConversion.chunkIndex(x >> 4, z >> 4)) ? 0 : -1;
        }
    }

    /** No packets, just chat narration of every hook the shard drives. */
    public record NarratingLight(Shard shard, String name) implements ShardLighting {
        private void say(String what) {
            for (Player p : shard.members()) p.sendMessage("[light:" + name + "] " + what);
        }
        @Override public void changed(Set<Long> chunks) { say("changed x" + chunks.size() + " chunk(s)"); }
        @Override public void refreshLit() { say("refreshLit (reset/ancestor churn)"); }
        @Override public void sendTo(Player player, int chunkX, int chunkZ) {
            player.sendMessage("[light:" + name + "] sendTo " + chunkX + "," + chunkZ);
        }
        @Override public void close() { say("closed - base light untouched, nothing to restore"); }
        @Override public SendablePacket packet(int chunkX, int chunkZ) { return null; }
        @Override public Set<Long> litChunks() { return Set.of(); }
        @Override public void markLit(long chunkIndex) {}
        @Override public Set<Long> candidateChunks() { return Set.of(); }
    }
}
