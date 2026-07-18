package test;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.ticking.ExecutionDomain;

/** Shared command plumbing. */
final class Ops {

    private Ops() {}

    // a domain player's commands run on THEIR shard's clock; ops that relay into another live shard
    // (snapshots: fork/flatten/save/load/reset, replay record/play/seek) must hop off it first
    static void offClock(Runnable body) {
        if (ExecutionDomain.onClockThread()) Thread.startVirtualThread(body);
        else body.run();
    }

    static String label(ShardManager shards, Shard shard) {
        String name = shards.nameOf(shard);
        return name != null ? name : "(unnamed)";
    }
}
