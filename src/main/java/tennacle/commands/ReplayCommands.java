package tennacle.commands;

import tennacle.games.watch.Replays;
import tennacle.storage.SafeName;
import tennacle.Msg;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.instance.InstanceContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static tennacle.commands.Ops.offClock;

/** {@code /replay}: record a shard, play it back into a fresh theater, seek/reverse/rewind/commit. */
public final class ReplayCommands {

    // demo keeps one recording/playback at a time (the lib supports many); volatile - stop events for a domain
    // theater fire on its clock thread
    private static volatile Recording recording;
    private static volatile Playback demo;

    private ReplayCommands() {}

    /** The playback a timeline command drives: the game replay {@code sender} is watching, else the demo's. */
    private static @Nullable Playback current(net.minestom.server.command.CommandSender sender) {
        Playback watched = sender instanceof Player p ? Replays.playbackOf(p) : null;
        return watched != null ? watched : demo;
    }

    // a command word becomes a path here: ArgumentType.Word rejects only spaces, so "../x" escapes the directory
    private static @org.jetbrains.annotations.Nullable Path named(net.minestom.server.command.CommandSender to,
                                                                  Path dir, String name, String suffix) {
        Path file = SafeName.resolve(dir, name, suffix);
        if (file == null) Msg.err(to, "replay", "bad name ", Msg.v(name), " - " + SafeName.rule());
        return file;
    }

    public static void register(ShardManager shards, InstanceContainer world) {
        Path replayDir = Storage.dir("replay-dir", "replays");
        AppCommand replay = Commands.dev("replay", "Record and replay a shard.")
                .usage("/replay record <shard> [solo]", "start recording (solo = no child layers)")
                .usage("/replay stop <name>", "stop + write replays/<name>.shard")
                .usage("/replay play <name> <newShard> [visual]", "play a file into a fresh theater")
                .usage("/replay seek <tick> | rate <r>", "jump / speed (negative = reverse)")
                .usage("/replay reverse | forward | pause | resume", "timeline control")
                .usage("/replay rewind <ticks>", "hold the live game N ticks in the past")
                .usage("/replay commit", "land the rewound past on the live game")
                .usage("/replay bookmark <name> | bookmarks | goto <name>", "mark + jump")
                .usage("/replay state <actor>", "the folded player state at this tick")
                .usage("/replay halt", "end the playback")
                .usage("/replay watch <game>", "watch a recorded game by its id or board code");
        Argument<String> name = ArgumentType.Word("name");
        Argument<String> replayName = ArgumentType.Word("replayName");
        Argument<String> newName = ArgumentType.Word("newName");
        Argument<Long> seekTick = ArgumentType.Long("tick");
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            playback.reverse(true);
            Msg.replay(sender, "rewinding from tick ", Msg.v(playback.tick()));
        }, ArgumentType.Literal("reverse"));
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            playback.reverse(false);
            Msg.replay(sender, "playing forward from tick ", Msg.v(playback.tick()));
        }, ArgumentType.Literal("forward"));
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            playback.pause(true);
            Msg.replay(sender, "paused at tick ", Msg.v(playback.tick()));
        }, ArgumentType.Literal("pause"));
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            playback.pause(false);
            Msg.replay(sender, "resumed at tick ", Msg.v(playback.tick()));
        }, ArgumentType.Literal("resume"));
        replay.addSyntax((sender, ctx) -> {
            if (recording != null) { Msg.err(sender, "replay", "already recording - ", Msg.run("/replay stop <name>"), " first"); return; }
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            offClock(() -> {
                recording = Replay.record(g); // the t0 snapshot relays
                Msg.replay(sender, "recording ", Msg.v(ctx.get(name)), " - build/move, then ", Msg.run("/replay stop <name>"));
            });
        }, ArgumentType.Literal("record"), name);
        // solo = the layer filter demo: just this shard, none of its child layers
        replay.addSyntax((sender, ctx) -> {
            if (recording != null) { Msg.err(sender, "replay", "already recording - ", Msg.run("/replay stop <name>"), " first"); return; }
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            offClock(() -> {
                recording = Replay.record(g, layer -> false);
                Msg.replay(sender, "recording ", Msg.v(ctx.get(name)), " ", Msg.v("SOLO"), " (child layers excluded)");
            });
        }, ArgumentType.Literal("record"), name, ArgumentType.Literal("solo"));
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { Msg.err(sender, "replay", "nothing is recording"); return; }
            Recording rec = recording;
            recording = null;
            byte[] file = rec.stop();
            Path target = named(sender, replayDir, ctx.get(replayName), ".shard");
            if (target == null) return;
            try {
                Files.createDirectories(replayDir);
                tennacle.storage.Atomic.write(target, file);
            } catch (IOException e) {
                Msg.err(sender, "replay", "write failed: " + e.getMessage());
                return;
            }
            Msg.replay(sender, Msg.v(ctx.get(replayName)), " saved: ", Msg.v(rec.tick()), " ticks, "
                    + rec.counts() + " (", Msg.v(file.length), " bytes)");
        }, ArgumentType.Literal("stop"), replayName);
        // "visual" = stand-ins only, ignoring registered revivers - the mechanics-less look, for comparison
        CommandExecutor doPlay = (sender, ctx) -> {
            if (demo != null) { Msg.err(sender, "replay", "one playback at a time (tick ", Msg.v(demo.tick()), ")"); return; }
            byte[] file;
            Path source = named(sender, replayDir, ctx.get(replayName), ".shard");
            if (source == null) return;
            try {
                file = Files.readAllBytes(source);
            } catch (IOException e) {
                Msg.err(sender, "replay", "no replay named ", Msg.v(ctx.get(replayName)));
                return;
            }
            String n = ctx.get(newName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            offClock(() -> {
                Shard target = shards.create(world, n, ExecutionDomain.create());
                Playback playback;
                try {
                    playback = demo = Replay.play(target, file);
                } catch (IllegalArgumentException e) {
                    shards.destroy(target);
                    Msg.err(sender, "replay", e.getMessage());
                    return;
                }
                String skipped = playback.skipped().isEmpty() ? "" : " (skipped channels: " + playback.skipped() + ")";
                Msg.replay(sender, "playing ", Msg.v(ctx.get(replayName)), ": ", Msg.v(playback.length()),
                        " ticks, " + playback.counts() + skipped + " - ", Msg.run("/shard spectate " + n), " to watch");
            });
        };
        replay.addSyntax(doPlay, ArgumentType.Literal("play"), replayName, newName);
        // a game's own replay, by its id or the code its board showed
        Argument<String> game = ArgumentType.Word("game");
        replay.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) Replays.watch(p, ctx.get(game));
        }, ArgumentType.Literal("watch"), game);
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            long target = ctx.get(seekTick);
            offClock(() -> {
                try {
                    playback.seek(target); // relays onto the theater's clock
                    Msg.replay(sender, "at tick ", Msg.v(playback.tick()), "/", Msg.v(playback.length()));
                } catch (RuntimeException e) {
                    Msg.err(sender, "replay", e.getMessage());
                }
            });
        }, ArgumentType.Literal("seek"), seekTick);
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            playback.halt(); // the stop event clears the field
            Msg.replay(sender, "halted");
        }, ArgumentType.Literal("halt"));
        // the time-rewind recipe: stop the running recording, raise a theater CHILD of the live shard,
        // play the recording IN MEMORY (no file), jump back; /replay commit lands the past for real
        Argument<Long> backTicks = ArgumentType.Long("ticks");
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { Msg.err(sender, "replay", "nothing is recording - ", Msg.run("/replay record <shard>"), " first"); return; }
            if (demo != null) { Msg.err(sender, "replay", "one playback at a time"); return; }
            Recording rec = recording;
            recording = null;
            rec.stop();
            long back = ctx.get(backTicks);
            offClock(() -> {
                Shard live = rec.shard();
                String n = shards.nameOf(live) + "-rewind";
                if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
                Shard theater = new Shard(live);
                shards.register(theater, n);
                Playback playback = demo = Replay.play(theater, rec);
                playback.pause(true); // hold the rewound moment
                playback.seek(Math.min(rec.tick(), Math.max(0, rec.tick() - back)));
                Msg.replay(sender, Msg.v(n), " shows ", Msg.v(back), " ticks ago (tick ", Msg.v(playback.tick()),
                        "/", Msg.v(playback.length()), ") - ", Msg.run("/shard spectate " + n),
                        ", then ", Msg.run("/replay commit"), " or ", Msg.run("/replay halt"));
            });
        }, ArgumentType.Literal("rewind"), backTicks);
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            Shard theater = playback.shard();
            Shard live = theater.parent();
            if (live == null) { Msg.err(sender, "replay", "not a rewind theater (no parent to land on)"); return; }
            offClock(() -> {
                playback.halt(); // ghosts and twins clear; the rewound blocks stay
                live.setBlocks(theater.diff());
                shards.destroy(theater);
                Msg.replay(sender, "the past landed on ", Msg.v(shards.nameOf(live)));
            });
        }, ArgumentType.Literal("commit"));
        // arg id must differ from the literal's ("rate"): same-id nodes collide in the command tree
        Argument<Double> playRate = ArgumentType.Double("speed");
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            try {
                playback.rate(ctx.get(playRate));
                Msg.replay(sender, "rate ", Msg.v(playback.rate() + "x"), playback.reversed() ? " (reversed)" : "");
            } catch (IllegalArgumentException e) {
                Msg.err(sender, "replay", e.getMessage());
            }
        }, ArgumentType.Literal("rate"), playRate);
        Argument<String> markName = ArgumentType.Word("markName");
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { Msg.err(sender, "replay", "nothing is recording"); return; }
            recording.bookmark(ctx.get(markName));
            Msg.replay(sender, "bookmarked ", Msg.v(ctx.get(markName)), " @ tick ", Msg.v(recording.tick()));
        }, ArgumentType.Literal("bookmark"), markName);
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            if (playback.bookmarks().isEmpty()) { Msg.err(sender, "replay", "no bookmarks"); return; }
            playback.bookmarks().forEach(b -> Msg.replay(sender, Msg.v(b.name()), " @ tick ", Msg.v(b.tick())));
        }, ArgumentType.Literal("bookmarks"));
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            var mark = playback.bookmarks().stream().filter(b -> b.name().equals(ctx.get(markName))).findFirst();
            if (mark.isEmpty()) { Msg.err(sender, "replay", "no bookmark ", Msg.v(ctx.get(markName))); return; }
            offClock(() -> {
                playback.seek(mark.get().tick());
                Msg.replay(sender, "at ", Msg.v(mark.get().name()), " (tick ", Msg.v(playback.tick()), ")");
            });
        }, ArgumentType.Literal("goto"), markName);
        // the state channels' read seam: the folded player state at the playback's current tick
        Argument<String> actorName = ArgumentType.Word("actor");
        replay.addSyntax((sender, ctx) -> {
            Playback playback = current(sender);
            if (playback == null) { Msg.err(sender, "replay", "nothing is playing"); return; }
            String actor = ctx.get(actorName);
            var inv = StateChannels.inventory(playback, actor);
            Msg.replay(sender, Msg.v(actor), " @ tick ", Msg.v(playback.tick()),
                    ": health=", Msg.v(StateChannels.health(playback, actor)),
                    " hunger=", Msg.v(StateChannels.hunger(playback, actor)),
                    " xp=", Msg.v(StateChannels.xp(playback, actor)),
                    " inventory=", Msg.v(inv.size()), " known slots");
        }, ArgumentType.Literal("state"), actorName);
        MinecraftServer.getGlobalEventHandler().addListener(PlaybackStopEvent.class, e -> {
            if (e.playback() == demo) {
                e.playback().shard().forEachWatcher(w -> Msg.replay(w, "finished"));
                demo = null;
            }
        });
        Commands.register(replay);
    }
}
