package test.commands;

import test.Msg;
import test.bridge.ShardBridge;
import test.lobby.DemoLights;

import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.archipelago.storage.polar.PolarShardCodec;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.archipelago.world.WorldCommands;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Weather;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static test.commands.Ops.label;
import static test.commands.Ops.offClock;

/** {@code /shard}: create/join/layer/fork/flatten, saves (single + tree + polar exchange), relight, views, domains. */
public final class ShardCommands {

    // the entity recipe: a shard save IS just a diff - descriptors ride the DataKey user-data section
    public static final DataKey<BinaryTag> SAVED_ENTITIES = new DataKey<>("demo:entities", Codec.NBT);

    private ShardCommands() {}

    public static void register(ShardManager shards, InstanceContainer world) {
        Command shard = new Command("shard");
        var create = ArgumentType.Literal("create");
        var join = ArgumentType.Literal("join");
        var leave = ArgumentType.Literal("leave");
        var reset = ArgumentType.Literal("reset");
        var destroy = ArgumentType.Literal("destroy");
        var list = ArgumentType.Literal("list");
        var spectate = ArgumentType.Literal("spectate");
        var sky = ArgumentType.Literal("sky");
        Argument<String> name = ArgumentType.Word("name");
        Argument<String> skyValue = ArgumentType.Word("value").from("day", "night", "rain", "clear");

        shard.setDefaultExecutor((sender, ctx) -> Msg.usage(sender, "/shard - isolated game worlds over one map", new String[][]{
                {"/shard create <name>", "new game on its own clock"},
                {"/shard child <parent> <new>", "layer over a game (relative diff)"},
                {"/shard join <name>", "play: same map, your own diff"},
                {"/shard leave", "back to the base map"},
                {"/shard fork <name> <new>", "independent copy (blocks + sky + entities)"},
                {"/shard flatten <top> [bottom] <new>", "collapse a chain (or merge two)"},
                {"/shard save|load|savetree|loadtree|export|import", "the .shard/.polar round-trips"},
                {"/shard commit <name>", "land the diff on the real map"},
                {"/shard reset|destroy <name>", "drop the diff / the whole game"},
                {"/shard list | tree | tps", "what exists, the layer tree, the clocks"},
                {"/shard relight <name> <on|off|disable|custom>", "per-shard lighting demos"},
                {"/shard spectate <name|off> [game|+main]", "watch without playing"},
                {"/shard sky <name> <day|night|rain|clear>", "per-shard time + weather"},
                {"/shard tab <name> <world|tree|instance>", "what members list in tab (names + completion)"},
                {"/shard stall|spin <name> <ms>", "hurt one clock, watch siblings play on"},
        }));

        shard.addSyntax((sender, ctx) -> {
            String n = ctx.get(name);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            // over the map the sender is standing on: the playground, or an open Bloom arena (its layers
            // are ordinary shards). Only the playground seeds base entities - an arena's are session furniture
            Instance target = sender instanceof Player p && p.getInstance() != null ? p.getInstance() : world;
            if (target == world) shards.create(world, n, ExecutionDomain.create(), Shard::copyEntity);
            else shards.create(target, n, ExecutionDomain.create());
            Msg.shard(sender, "created ", Msg.v(n), " on its own clock - ", Msg.run("/shard join " + n));
        }, create, name);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(p, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            g.join(p);
            Msg.shard(p, "joined ", Msg.v(ctx.get(name)), " - same map, your own diff");
        }, join, name);

        var fork = ArgumentType.Literal("fork");
        Argument<String> forkName = ArgumentType.Word("newName");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            offClock(() -> {
                shards.fork(g, n, Shard::copyEntity); // diff + sky + entities (mechanics stamps + archipelago's default cloner)
                Msg.shard(sender, "forked ", Msg.v(ctx.get(name)), " -> ", Msg.v(n), " - ", Msg.run("/shard join " + n));
            });
        }, fork, name, forkName);

        // layers: relative diffs on the parent's clock; /shard join between layers = the combat-continuous switch
        var child = ArgumentType.Literal("child");
        shard.addSyntax((sender, ctx) -> {
            Shard parent = shards.byName(ctx.get(name));
            if (parent == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            shards.child(parent, n);
            Msg.shard(sender, "layer ", Msg.v(n), " over ", Msg.v(ctx.get(name)), " (same clock) - ", Msg.run("/shard join " + n), " switches layers");
        }, child, name, forkName);

        var flatten = ArgumentType.Literal("flatten");
        Argument<String> bottomName = ArgumentType.Word("bottomName");
        shard.addSyntax((sender, ctx) -> {
            Shard top = shards.byName(ctx.get(name));
            Shard bottom = shards.byName(ctx.get(bottomName));
            if (top == null || bottom == null) { Msg.err(sender, "shard", "both shards must exist"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            offClock(() -> {
                shards.flatten(top, bottom, n, Shard::copyEntity);
                Msg.shard(sender, Msg.v(ctx.get(name)), " onto ", Msg.v(ctx.get(bottomName)), " -> ", Msg.v(n), " (top wins) - ", Msg.run("/shard join " + n));
            });
        }, flatten, name, bottomName, forkName);

        // chain flatten: the whole layer chain collapsed into one independent shard (branches never merge)
        shard.addSyntax((sender, ctx) -> {
            Shard top = shards.byName(ctx.get(name));
            if (top == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            offClock(() -> {
                shards.flatten(top, n, Shard::copyEntity);
                Msg.shard(sender, "chain of ", Msg.v(ctx.get(name)), " -> ", Msg.v(n), " - ", Msg.run("/shard join " + n));
            });
        }, flatten, name, forkName);

        // save/load = the .shard round-trip: blocks (NBT included) + sky natively, entities via the DataKey
        // recipe; "blocks" mode = map template, no entity payload. Dirs resolve through Storage
        Path saveDir = Storage.dir("save-dir", "shard-saves");
        var save = ArgumentType.Literal("save");
        Argument<String> saveMode = ArgumentType.Word("mode").from("blocks");
        CommandExecutor doSave = (sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            boolean entities = !ctx.has(saveMode);
            offClock(() -> {
                try {
                    Files.createDirectories(saveDir);
                    Path file = saveDir.resolve(ctx.get(name) + ".shard");
                    ShardFile.save(g, file, entities ? w -> w.put(SAVED_ENTITIES, EntityDescriptors.write(g)) : null);
                    Msg.shard(sender, "saved ", Msg.v(ctx.get(name)), " -> " + file + " (", Msg.v(Files.size(file)),
                            " bytes, diff ", Msg.v(g.overlaySize()), entities ? ")" : ", blocks only)");
                } catch (IOException e) {
                    Msg.err(sender, "shard", "save failed: " + e.getMessage());
                }
            });
        };
        shard.addSyntax(doSave, save, name);
        shard.addSyntax(doSave, save, name, saveMode);

        // one file = the whole layer tree; loadtree rebuilds it, re-registered under the saved NAME hints
        var savetree = ArgumentType.Literal("savetree");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            offClock(() -> {
                try {
                    Files.createDirectories(saveDir);
                    Path file = saveDir.resolve(ctx.get(name) + ".shard");
                    Files.write(file, ShardFile.saveTree(g, shards::nameOf,
                            (s, w) -> w.put(SAVED_ENTITIES, EntityDescriptors.write(s))));
                    Msg.shard(sender, "saved the tree of ", Msg.v(ctx.get(name)), " -> " + file + " (",
                            Msg.v(Files.size(file)), " bytes)");
                } catch (IOException e) {
                    Msg.err(sender, "shard", "savetree failed: " + e.getMessage());
                }
            });
        }, savetree, name);

        var loadtree = ArgumentType.Literal("loadtree");
        shard.addSyntax((sender, ctx) -> {
            Path file = saveDir.resolve(ctx.get(name) + ".shard");
            if (!Files.exists(file)) { Msg.err(sender, "shard", "no save named ", Msg.v(ctx.get(name))); return; }
            String prefix = ctx.get(forkName);
            offClock(() -> {
                try {
                    List<ShardFile.TreeLayer> layers = ShardFile.loadTree(world, Files.readAllBytes(file));
                    ExecutionDomain domain = ExecutionDomain.create(); // one tree, one clock
                    StringBuilder named = new StringBuilder();
                    for (int i = 0; i < layers.size(); i++) {
                        ShardFile.TreeLayer layer = layers.get(i);
                        String saved = layer.data().get(ShardFile.NAME);
                        String n = prefix + "-" + (saved != null ? saved : i);
                        shards.register(layer.shard(), n);
                        layer.shard().domain(domain);
                        if (layer.data().get(SAVED_ENTITIES) instanceof ListBinaryTag descs) {
                            EntityDescriptors.read(layer.shard(), descs);
                        }
                        named.append(named.isEmpty() ? "" : ", ").append(n);
                    }
                    Msg.shard(sender, "tree loaded: ", Msg.v(named), " - ", Msg.run("/shard join " + prefix + "-"), "...");
                } catch (Exception e) {
                    Msg.err(sender, "shard", "loadtree failed: " + e.getMessage());
                }
            });
        }, loadtree, name, forkName);

        var load = ArgumentType.Literal("load");
        shard.addSyntax((sender, ctx) -> {
            Path file = saveDir.resolve(ctx.get(name) + ".shard");
            if (!Files.exists(file)) { Msg.err(sender, "shard", "no save named ", Msg.v(ctx.get(name))); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { Msg.err(sender, "shard", Msg.v(n), " already exists"); return; }
            offClock(() -> {
                Shard g = shards.create(world, n, ExecutionDomain.create());
                try {
                    UserData.View view = ShardFile.load(g, Files.readAllBytes(file));
                    if (view.get(SAVED_ENTITIES) instanceof ListBinaryTag descs) EntityDescriptors.read(g, descs);
                    Msg.shard(sender, "loaded ", Msg.v(ctx.get(name)), " -> ", Msg.v(n), " (diff ",
                            Msg.v(g.overlaySize()), ") - ", Msg.run("/shard join " + n));
                } catch (Exception e) {
                    shards.destroy(g);
                    Msg.err(sender, "shard", "load failed: " + e.getMessage());
                }
            });
        }, load, name, forkName);

        // the exchange adapter: pure byte transcode .shard <-> .polar for polar tooling, no live shard involved
        var export = ArgumentType.Literal("export");
        shard.addSyntax((sender, ctx) -> {
            try {
                Path in = saveDir.resolve(ctx.get(name) + ".shard");
                Path out = saveDir.resolve(ctx.get(name) + ".polar");
                byte[] source = Files.readAllBytes(in);
                Files.write(out, PolarShardCodec.fromShardFile(source));
                Msg.shard(sender, Msg.v(in.getFileName()), " -> ", Msg.v(out.getFileName()), " (", Msg.v(Files.size(out)),
                        " bytes, ", PolarShardCodec.transcodesLosslessly(source)
                                ? Msg.good("verified lossless") : Msg.err0("LOSSY"), ")");
            } catch (IOException e) {
                Msg.err(sender, "shard", "export failed: " + e.getMessage());
            }
        }, export, name);
        var doImport = ArgumentType.Literal("import");
        shard.addSyntax((sender, ctx) -> {
            try {
                Path in = saveDir.resolve(ctx.get(name) + ".polar");
                Path out = saveDir.resolve(ctx.get(name) + ".shard");
                byte[] source = Files.readAllBytes(in);
                Files.write(out, PolarShardCodec.toShardFile(source));
                Msg.shard(sender, Msg.v(in.getFileName()), " -> ", Msg.v(out.getFileName()), " (", Msg.v(Files.size(out)),
                        " bytes, ", PolarShardCodec.transcodesLosslessly(source) ? Msg.good("verified lossless")
                        : Msg.err0("LOSSY - polar-only data (biomes/light/chunk extras) dropped"), ")");
            } catch (IOException e) {
                Msg.err(sender, "shard", "import failed: " + e.getMessage());
            }
        }, doImport, name);

        // the winner's build lands on the real map (base writes belong on the server thread)
        var commit = ArgumentType.Literal("commit");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                g.commitToBase();
                Msg.shard(sender, Msg.v(ctx.get(name)), " committed to the base map (diff now ", Msg.v(g.overlaySize()), ")");
            });
        }, commit, name);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (MechanicsWorld.of(p) instanceof Shard g) {
                g.leave(p);
                Msg.shard(p, "left - back on the main map");
            } else Msg.err(p, "shard", "not in a shard");
        }, leave);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            offClock(() -> {
                g.reset();
                Msg.shard(sender, Msg.v(ctx.get(name)), " diff dropped");
            });
        }, reset, name);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            shards.destroy(g);
            Msg.shard(sender, Msg.v(ctx.get(name)), " destroyed");
        }, destroy, name);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> Msg.shard(sender,
                Msg.v(label(shards, g)), " members=", Msg.v(g.members().size()),
                " observers=", Msg.v(g.observers().size()), " diff=", Msg.v(g.overlaySize()))), list);

        // chain-effective light as per-viewer UpdateLight overlays (modern clients; 1.8 self-relights).
        // 'disable' = NO system (client-local relight is all you see); 'off' = a demo system that FORCES
        // darkness (the visible proof of server light authority); 'custom' = a stub narrating its hooks
        var relight = ArgumentType.Literal("relight");
        Argument<String> relightState = ArgumentType.Word("state").from("on", "off", "disable", "custom");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            String n = ctx.get(name);
            switch (ctx.get(relightState)) {
                case "on" -> {
                    g.relight(true);
                    Msg.shard(sender, "relight ", Msg.v("ON"), " for ", Msg.v(n),
                            " - place a torch/glowstone or roof something in and watch the light (modern client)");
                }
                case "off" -> {
                    g.relight(new DemoLights.DarkLight(g));
                    Msg.shard(sender, "relight ", Msg.v("OFF (forced dark)"), " for ", Msg.v(n),
                            " - place a torch: it glows for ~1s (your client's local light), then the server snuffs it");
                }
                case "disable" -> {
                    g.relight(false);
                    Msg.shard(sender, "relight ", Msg.v("DISABLED"), " for ", Msg.v(n),
                            " - no server light for shard edits; a torch still glows because your client relights"
                            + " locally around block changes it sees");
                }
                case "custom" -> {
                    g.relight(new DemoLights.NarratingLight(g, n));
                    Msg.shard(sender, "relight ", Msg.v("CUSTOM"), " for ", Msg.v(n),
                            " - place blocks and watch the hook narration; ", Msg.run("/shard relight " + n + " disable"), " removes it");
                }
            }
        }, relight, name, relightState);

        var tree = ArgumentType.Literal("tree");
        shard.addSyntax((sender, ctx) -> {
            var all = shards.shards();
            if (all.isEmpty()) { Msg.shard(sender, "none"); return; }
            for (Shard s : all) {
                if (s.parent() == null || !all.contains(s.parent())) printTree(sender, shards, s, 0);
            }
        }, tree);

        // the truly-multithreaded demo kit: stall one shard, keep playing in another; watch /shard tps
        var stall = ArgumentType.Literal("stall");
        var spin = ArgumentType.Literal("spin");
        var tps = ArgumentType.Literal("tps");
        Argument<Integer> millis = ArgumentType.Integer("ms");

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null || g.domain() == null) { Msg.err(sender, "shard", "no domain shard named ", Msg.v(ctx.get(name))); return; }
            int ms = ctx.get(millis);
            g.domain().execute(() -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) { // the watchdog freeing a "hung" demo stall
                }
            });
            Msg.shard(sender, Msg.v(ctx.get(name)), " stalls ", Msg.v(ms + "ms"), " (blocking) - siblings play on; >5s the watchdog interrupts");
        }, stall, name, millis);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null || g.domain() == null) { Msg.err(sender, "shard", "no domain shard named ", Msg.v(ctx.get(name))); return; }
            int ms = ctx.get(millis);
            g.domain().execute(() -> {
                long end = System.nanoTime() + ms * 1_000_000L;
                while (System.nanoTime() < end) Thread.onSpinWait(); // uninterruptible: captures a carrier
            });
            Msg.shard(sender, Msg.v(ctx.get(name)), " spins ", Msg.v(ms + "ms"), " (CPU) - >15s it FAILS: crash report + shard destroyed, members back to the base map");
        }, spin, name, millis);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> {
            var d = g.domain();
            if (d == null) {
                Msg.shard(sender, Msg.v(label(shards, g)), " server-ticked, members=", Msg.v(g.members().size()));
                return;
            }
            var m = d.metrics();
            Msg.shard(sender, Msg.v(label(shards, g)), " ticks=", Msg.v(m.ticks()), " lag=", Msg.v(m.lag()),
                    " mspt=", Msg.v(String.format("%.2f", m.msptEwma())),
                    " on " + m.threadName() + " members=", Msg.v(g.members().size()));
        }), tps);

        // views compose: of(g) = the game only; add withBaseEntities/withEntities/withAllEntities for more layers
        Argument<String> spectateMode = ArgumentType.Word("mode").from("game", "+main");
        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String n = ctx.get(name);
            if (n.equals("off")) { ShardView.set(p, null); Msg.shard(p, "view reset"); return; }
            Shard g = shards.byName(n);
            if (g == null) { p.sendMessage("[shard] no shard named '" + n + "'"); return; }
            ShardView.set(p, ShardView.of(g));
            Msg.shard(p, "spectating ", Msg.v(n), " - watching, not playing (", Msg.run("/shard spectate off"), ")");
        }, spectate, name);
        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(p, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            ShardView view = ShardView.of(g);
            if (ctx.get(spectateMode).equals("+main")) view = view.withBaseEntities(true);
            ShardView.set(p, view);
            Msg.shard(p, "spectating ", Msg.v(ctx.get(name)), " [" + ctx.get(spectateMode) + "]");
        }, spectate, name, spectateMode);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            switch (ctx.get(skyValue)) {
                case "day" -> g.setTime(1000);
                case "night" -> g.setTime(18000);
                case "rain" -> g.setWeather(new Weather(1f, 0f));
                case "clear" -> g.setWeather(Weather.CLEAR);
            }
            Msg.shard(sender, Msg.v(ctx.get(name)), " sky -> ", Msg.v(ctx.get(skyValue)));
        }, sky, name, skyValue);

        // tab scoping: the viewer-side list (and the client's name completion) per shard
        var tab = ArgumentType.Literal("tab");
        Argument<String> tabValue = ArgumentType.Word("scope").from("world", "tree", "instance");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "shard", "no shard named ", Msg.v(ctx.get(name))); return; }
            ShardTabList.TabScope scope = ShardTabList.TabScope.valueOf(ctx.get(tabValue).toUpperCase());
            g.tabScope(scope);
            Msg.shard(sender, Msg.v(ctx.get(name)), " tab scope -> ", Msg.v(scope), " (members re-synced)");
        }, tab, name, tabValue);

        MinecraftServer.getCommandManager().register(shard);

        // world-scoped command demo: /wave exists ONLY inside a shard - the client's command list and tab
        // completion follow membership (WorldCommands refreshes them on join/leave)
        Command wave = new Command("wave");
        wave.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) Msg.shard(p, Msg.v(p.getUsername()), " waves at the game");
        });
        WorldCommands.register(wave, w -> w instanceof Shard);
    }

    private static void printTree(CommandSender sender, ShardManager shards, Shard shard, int depth) {
        var d = shard.domain();
        Msg.shard(sender, "  ".repeat(depth) + "- ", Msg.v(label(shards, shard)), " diff=", Msg.v(shard.overlaySize()),
                " members=", Msg.v(shard.members().size()),
                d == null ? " server-ticked" : " on " + d.metrics().threadName());
        for (Shard child : shard.children()) printTree(sender, shards, child, depth + 1);
    }
}
