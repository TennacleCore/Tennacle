package tennacle.commands;

import tennacle.games.watch.Spectators;
import io.github.term4.polyp.mechanics.attribute.catalog.VanillaPotions;
import tennacle.boot.Mechanics;
import tennacle.Msg;
import tennacle.dev.ItemWire;
import tennacle.dev.Arsenal;
import tennacle.dev.LagSimulator;

import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.blocking.catalog.VanillaBlocking;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import io.github.term4.polyp.platform.compatibility.Compat18;
import io.github.term4.polyp.platform.compatibility.CompatConfig;
import io.github.term4.polyp.platform.player.OptimizedPlayer;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.polyp.world.MechanicsWorld;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentEnum;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.network.packet.server.play.EntityEffectPacket;
import net.minestom.server.network.packet.server.play.RemoveEntityEffectPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * The polyp example server's dev/test toolset plus the shard-aware {@code /boom}: lag simulation, profile
 * switches, explosions, enchants, effects, blockable swords, velocity-cap A/B.
 */
public final class DevCommands {

    private DevCommands() {}

    private static void gamemode(Player p, GameMode mode) {
        if (Spectators.gamemode(p, mode)) return; // a spectator's bodies, or a refusal
        p.setGameMode(mode);
        Msg.tag(p, "gm", "gamemode: ", Msg.v(mode.name().toLowerCase()));
    }

    private static final Tag<String> NS_MODE = Tag.String("test:ns-mode");
    private static final Tag<Integer> NS_BLIND_TICKS = Tag.Integer("test:ns-blind-ticks");

    /** {@code mode} null = off: restore the real food + drop the effect. */
    private static void setNsMode(Player p, String mode, int blindTicks) {
        String previous = p.getTag(NS_MODE);
        if (mode == null) {
            p.removeTag(NS_MODE);
            if ("blind".equals(previous)) p.sendPacket(new RemoveEntityEffectPacket(p.getEntityId(), PotionEffect.BLINDNESS));
            if ("food".equals(previous)) p.sendPacket(new UpdateHealthPacket(p.getHealth(), p.getFood(), p.getFoodSaturation()));
            Msg.tag(p, "ns", "client sprint block ", Msg.v("off"));
            return;
        }
        if ("blind".equals(previous) && !mode.equals("blind")) {
            p.sendPacket(new RemoveEntityEffectPacket(p.getEntityId(), PotionEffect.BLINDNESS));
        }
        if ("food".equals(previous) && !mode.equals("food")) {
            p.sendPacket(new UpdateHealthPacket(p.getHealth(), p.getFood(), p.getFoodSaturation()));
        }
        p.setTag(NS_MODE, mode);
        if (mode.equals("blind")) p.setTag(NS_BLIND_TICKS, Math.max(2, blindTicks));
        Msg.tag(p, "ns", "client sprint block ", Msg.v(mode.equals("blind") ? "blind (" + Math.max(2, blindTicks) + "t)" : mode));
    }

    private static void reportLag(LagSimulator lag, Player p) {
        long inbound = lag.inboundTicks(p);
        Msg.tag(p, "sim-lag", "ping ", Msg.v(lag.simulatedPing(p)), "ms = ",
                Msg.v(inbound * MinecraftServer.TICK_MS), "ms in (", Msg.v(inbound), "t) + ",
                Msg.v(lag.outboundMillis(p)), "ms out");
        // the keep-alive round trip is 10s apart, so this trails a change by up to that long
        Msg.tag(p, "sim-lag", "measured ", Msg.v(p.getLatency()), "ms, last keep-alive ",
                Msg.v(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - p.getLastKeepAlive())), "ms ago");
        Msg.tag(p, "sim-lag", "that round trip was really held ", Msg.v(lag.lastKeepAliveOutHold(p)),
                "ms out + ", Msg.v(lag.lastKeepAliveInHold(p)), "ms in (-1 = never held)");
    }

    public static void register(Polyp polyp) {

        // what the tab is ACTUALLY being sent for this viewer - the tab differs per client generation
        AppCommand tabdump = Commands.dev("tabdump", "What the tab list is sending you.");
        tabdump.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player viewer)) return;
            for (String line : ShardTabList.describe(viewer)) viewer.sendMessage(Msg.v(line));
        });
        Commands.register(tabdump);
        // the SEQUENCE of tab packets that reached this client - what a snapshot cannot show
        AppCommand tablog = Commands.dev("tablog", "The tab packets you received, in order.");
        tablog.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player viewer)) return;
            for (String line : ShardTabList.history(viewer)) viewer.sendMessage(Msg.v(line));
        });
        Commands.register(tablog);

        // the inventory wire both ways, for reading a legacy pick block: a middle-click that found the stack
        // reports a held-item change, one that did not reports nothing at all
        AppCommand itemwire = Commands.dev("itemwire", "Record the inventory wire, both directions.")
                .usage("/itemwire", "start recording, or read it back")
                .usage("/itemwire clear", "drop what was recorded and keep going")
                .usage("/itemwire off", "stop");
        itemwire.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player viewer)) return;
            if (!ItemWire.armed(viewer)) {
                ItemWire.arm(viewer);
                Msg.ok(viewer, "Recording the inventory wire. Middle-click, then run /itemwire again.");
                return;
            }
            java.util.List<String> lines = ItemWire.read(viewer);
            if (lines.isEmpty()) {
                Msg.fail(viewer, "Nothing on the wire since it was armed.");
                return;
            }
            for (String line : lines) Msg.say(viewer, Msg.v(line));
        });
        itemwire.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player viewer)) return;
            String what = ctx.get("what");
            if (what.equals("off")) {
                ItemWire.disarm(viewer);
                Msg.ok(viewer, "Stopped.");
            } else {
                ItemWire.clear(viewer);
                Msg.ok(viewer, "Cleared. Middle-click now.");
            }
        }, ArgumentType.Word("what").from("clear", "off"));
        Commands.register(itemwire);

        AppCommand gm = Commands.dev("gm", "Your gamemode.")
                .usage("/gm <mode>", "survival, creative, adventure or spectator");
        Argument<GameMode> mode = ArgumentType.Enum("mode", GameMode.class).setFormat(ArgumentEnum.Format.LOWER_CASED);
        gm.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) gamemode(p, ctx.get(mode));
        }, mode);
        Commands.register(gm);

        AppCommand gmc = Commands.dev("gmc", "Creative mode.");
        gmc.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) gamemode(p, GameMode.CREATIVE);
        });
        Commands.register(gmc);

        AppCommand gms = Commands.dev("gms", "Survival mode.");
        gms.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) gamemode(p, GameMode.SURVIVAL);
        });
        Commands.register(gms);

        // /fly: toggle survival flight (double-jump to lift); while flying, fall damage is exempt. A gamemode
        // switch clears allowFlying (Minestom stamps it from the mode), so re-run /fly after /gms.
        AppCommand fly = Commands.dev("fly", "Toggle flight.");
        fly.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            boolean on = !p.isAllowFlying();
            p.setAllowFlying(on);
            if (!on) p.setFlying(false);
            Msg.tag(p, "fly", "flight: ", Msg.v(on ? "ON" : "OFF"));
        });
        Commands.register(fly);

        // /boom resolves the SHOOTER's world: a shard blast only rocks that game, a main-map one never reaches it
        AppCommand boom = Commands.dev("boom", "A blast where you stand, in your own world.");
        boom.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Mechanics.explosions.explode(MechanicsWorld.of(p), p.getPosition(), 4.0f, p, null);
        });
        Commands.register(boom);

        // test-only latency simulator. Purely test-side - mechanics is untouched.
        final LagSimulator lag = new LagSimulator();
        lag.install();
        AppCommand lagCmd = Commands.dev("lag", "Give yourself a simulated ping.")
                .usage("/lag", "what is set, and what the keep-alive actually measured")
                .usage("/lag <ms>", "simulated round trip, split over both directions (0 = off)")
                .usage("/lag in <ticks> out <ms>", "set each direction yourself");
        lagCmd.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) reportLag(lag, p);
        });
        Argument<Integer> pingArg = ArgumentType.Integer("ms");
        Argument<Integer> inArg = ArgumentType.Integer("ticks");
        Argument<Integer> outArg = ArgumentType.Integer("ms");
        lagCmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            int asked = ctx.get(pingArg);
            if (lag.setPing(p, asked) < asked) {
                Msg.tag(p, "sim-lag", "capped at ", Msg.v(LagSimulator.MAX_PING_MILLIS),
                        "ms: past that the keep-alive drops you");
            }
            reportLag(lag, p);
        }, pingArg);
        lagCmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            lag.setInboundTicks(p, Math.max(0, ctx.get(inArg)));
            lag.setOutboundMillis(p, Math.max(0, ctx.get(outArg)));
            reportLag(lag, p);
        }, ArgumentType.Literal("in"), inArg, ArgumentType.Literal("out"), outArg);
        Commands.register(lagCmd);

        // /compat: toggle this player between the global 1.8 profile and a per-player modern override (mode switch demo)
        AppCommand compatCmd = Commands.dev("compat", "Toggle your mechanics between 1.8 and the latest version.");
        compatCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof OptimizedPlayer op)) return;
            boolean turningOff = polyp.profiles().player(op) == null; // no per-player override = currently on the global profile
            if (turningOff) {
                polyp.profiles().setPlayer(op, MechanicsProfile.builder().set(MechanicsKeys.COMPAT, Compat18.off()).build());
                Msg.tag(op, "compat", "mechanics set to ", Msg.v("latest version"));
            } else {
                polyp.profiles().setPlayer(op, null);
                Msg.tag(op, "compat", "mechanics set to ", Msg.v("1.8"));
            }
        });
        Commands.register(compatCmd);

        // /ns: A/B the two client sprint/swim gates. "food" clamps the wire food to 6 (steady 3-shank bar, nothing
        // else); "blind [duration]" holds a hidden blindness (fog lerps out at duration/20, but the sky-block and
        // darkness ride effect PRESENCE - short durations strobe under jitter, longer ones block the sky steadily)
        AppCommand ns = Commands.dev("ns", "A/B the client's two sprint gates.")
                .usage("/ns", "toggle the food clamp")
                .usage("/ns food | blind [ticks] | off", "pick a gate");
        Argument<Integer> blindTicks = ArgumentType.Integer("duration").setDefaultValue(5);
        ns.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            setNsMode(p, p.getTag(NS_MODE) == null ? "food" : null, 0);
        });
        ns.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) setNsMode(p, "food", 0);
        }, ArgumentType.Literal("food"));
        ns.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) setNsMode(p, "blind", ctx.get(blindTicks));
        }, ArgumentType.Literal("blind"), blindTicks);
        ns.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) setNsMode(p, null, 0);
        }, ArgumentType.Literal("off"));
        Commands.register(ns);
        MinecraftServer.getSchedulerManager().scheduleTask(() -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                String nsMode = p.getTag(NS_MODE);
                if (nsMode == null) continue;
                if (nsMode.equals("food")) {
                    p.sendPacket(new UpdateHealthPacket(p.getHealth(), Math.min(p.getFood(), 6), p.getFoodSaturation()));
                } else {
                    Integer d = p.getTag(NS_BLIND_TICKS);
                    p.sendPacket(new EntityEffectPacket(p.getEntityId(),
                            new Potion(PotionEffect.BLINDNESS, 0, d != null ? d : 5, Potion.AMBIENT_FLAG)));
                }
            }
        }, net.minestom.server.timer.TaskSchedule.immediate(), net.minestom.server.timer.TaskSchedule.tick(1));

        // /shorts: A/B the byte-exact Animatium velocity vs the lossy LpVec3 (take a >2 b/t knockback to compare)
        AppCommand shortsCmd = Commands.dev("shorts", "A/B byte-exact velocity shorts against the lossy vector.");
        shortsCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof OptimizedPlayer op)) return;
            CompatConfig current = polyp.profiles().resolve(op, MechanicsKeys.COMPAT);
            boolean on = !(current != null && Boolean.TRUE.equals(current.nativeShortVelocity));
            polyp.profiles().setPlayer(op, MechanicsProfile.builder()
                    .set(MechanicsKeys.COMPAT, Compat18.config().toBuilder().nativeShortVelocity(on).build())
                    .build());
            Msg.tag(op, "shorts", "shorts velocity: ", Msg.v(on ? "ON" : "OFF"), " (take a >2 b/t knockback to compare)");
        });
        Commands.register(shortsCmd);

        // /suffocate: drops a stone block into your head to test suffocation damage (be in survival - /gms)
        AppCommand suffocate = Commands.dev("suffocate", "A stone block in your head.");
        suffocate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p) || p.getInstance() == null) return;
            p.getInstance().setBlock(p.getPosition().add(0, p.getEyeHeight(), 0), Block.STONE);
            Msg.tag(p, "suffocate", "stone placed in your head (survival = 1/tick suffocation)");
        });
        Commands.register(suffocate);

        // /explode [power]: explosion at your feet (no source, so you feel the falloff knockback + take the damage)
        ExplosionSystem explosions = Mechanics.explosions;
        AppCommand explode = Commands.dev("explode", "An explosion at your feet.")
                .usage("/explode [power]", "power 4 by default");
        Argument<Integer> explodePower = ArgumentType.Integer("power");
        explode.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition(), 4.0f);
        });
        explode.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition(), (float) (int) ctx.get(explodePower));
        }, explodePower);
        Commands.register(explode);

        // /explodeat <dx> <dy> <dz> [power]: detonate at your feet + offset (power 2 = fireball) - decouples
        // detonation height from distance for the head-gate model test
        AppCommand explodeAt = Commands.dev("explodeat", "Detonate at an offset from your feet.")
                .usage("/explodeat <dx> <dy> <dz> [power]", "power default 2 = fireball");
        Argument<Double> exAtX = ArgumentType.Double("dx");
        Argument<Double> exAtY = ArgumentType.Double("dy");
        Argument<Double> exAtZ = ArgumentType.Double("dz");
        Argument<Double> exAtPower = ArgumentType.Double("power");
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), 2.0f);
        }, exAtX, exAtY, exAtZ);
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), ctx.get(exAtPower).floatValue());
        }, exAtX, exAtY, exAtZ, exAtPower);
        Commands.register(explodeAt);

        // /sword: a blockable diamond sword (right-click + hold to block; 1.8 = (1+f)*0.5 damage, pre-armor)
        AppCommand sword = Commands.dev("sword", "A blockable diamond sword.");
        sword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.item(Material.DIAMOND_SWORD));
                Msg.tag(p, "sword", "blockable diamond sword given (hold right-click to block)");
            }
        });
        Commands.register(sword);

        // /arsenal: the mechanics test rig's body - what the lobby handed every arrival before it was a lobby
        AppCommand arsenal = Commands.dev("arsenal", "The test rig's body: survival, diamond, one of everything.");
        arsenal.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                Arsenal.give(p);
                Msg.tag(p, "arsenal", "survival, diamond, one of everything");
            }
        });
        Commands.register(arsenal);

        // /nbsword: a non-blockable diamond sword (opted out) - should NOT reduce damage while right-click held
        AppCommand nbsword = Commands.dev("nbsword", "A diamond sword that never blocks.");
        nbsword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.nonBlocking(ItemStack.of(Material.DIAMOND_SWORD)));
                Msg.tag(p, "nbsword", "non-blockable diamond sword given (holding right-click should not block)");
            }
        });
        Commands.register(nbsword);

        // /enchant <enchantment> [level]: enchant the held item (e.g. /enchant fire_aspect 2)
        AppCommand enchant = Commands.dev("enchant", "Enchant the held item.")
                .usage("/enchant <enchantment> [level]", "e.g. /enchant fire_aspect 2");
        Argument<String> enchName = ArgumentType.Word("enchantment");
        enchName.setSuggestionCallback(tennacle.commands.Completions.of(() ->
                MinecraftServer.getEnchantmentRegistry().keys().stream().map(key -> key.key().value()).sorted().toList()));
        Argument<Integer> enchLevel = ArgumentType.Integer("level");
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), 1);
        }, enchName);
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), ctx.get(enchLevel));
        }, enchName, enchLevel);
        Commands.register(enchant);

        // /effect <effect> [strength] [seconds]  |  /effect clear
        AppCommand effectCmd = Commands.dev("effect", "Apply a potion effect.")
                .usage("/effect <effect> [strength] [seconds]", "e.g. /effect strength 2")
                .usage("/effect clear", "clear all effects");
        Argument<String> effName = ArgumentType.Word("effect");
        effName.setSuggestionCallback(tennacle.commands.Completions.of(() ->
                PotionEffect.values().stream().map(effect -> effect.key().value()).sorted().toList()));
        Argument<Integer> effStrength = ArgumentType.Integer("strength");
        Argument<Integer> effSeconds = ArgumentType.Integer("seconds");
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), 1, 30);
        }, effName);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), 30);
        }, effName, effStrength);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), ctx.get(effSeconds));
        }, effName, effStrength, effSeconds);
        Commands.register(effectCmd);

        // /velcap (LpVec3 snap) vs /velcapbridge (exact 1.8 short): A/B for the LegacyVelocityBridge knockback path
        EventNode<@NotNull PlayerEvent> playerNode = EventNode.type("polyp:test-player", EventFilter.PLAYER);
        VelocityCapCommands.install(playerNode);
        MinecraftServer.getGlobalEventHandler().addChild(playerNode);

        AppCommand velCap = Commands.dev("velcap", "Velocity cap A/B: the snapped vector.");
        velCap.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapCommands.applyNormalCap(p);
        });
        Commands.register(velCap);

        AppCommand velCapBridge = Commands.dev("velcapbridge", "Velocity cap A/B: the exact 1.8 short over ViaBridge.");
        velCapBridge.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapCommands.applyBridgeCap(p);
        });
        Commands.register(velCapBridge);
    }

    /** Applies (or clears) a potion effect on the sender. {@code strength} is the level (1 = level I); duration in seconds. */
    private static void applyEffect(Player p, String name, int strength, int seconds) {
        if (name.equalsIgnoreCase("clear")) { p.clearEffects(); Msg.tag(p, "effect", "cleared effects"); return; }
        PotionEffect effect = PotionEffect.fromKey(name.contains(":") ? name : "minecraft:" + name);
        if (effect == null) { Msg.err(p, "effect", "unknown effect: ", Msg.v(name)); return; }
        int amplifier = Math.max(0, strength - 1); // strength 1 -> amplifier 0 (level I)
        // the lifecycle the game runs: attribute effects land, the particles show
        VanillaPotions.addEffect(p, new Potion(effect, amplifier, seconds * 20, Potion.PARTICLES_FLAG | Potion.ICON_FLAG));
        Msg.tag(p, "effect", "applied ", Msg.v(effect.key().asString()), " ", Msg.v(strength), " for ", Msg.v(seconds + "s"));
    }

    /** Adds {@code name} (defaulting the namespace to minecraft:) at {@code level} to the held item's enchantments. */
    private static void applyEnchant(Player p, String name, int level) {
        ItemStack held = p.getItemInMainHand();
        if (held.isAir()) { Msg.err(p, "enchant", "hold an item first"); return; }
        Key key = Key.key(name.contains(":") ? name : "minecraft:" + name);
        // an unknown key still builds a component; it fails in every packet that carries the item, and the
        // failing write kicks each connection it reaches
        RegistryKey<Enchantment> ench = MinecraftServer.getEnchantmentRegistry().getKey(key);
        if (ench == null) { Msg.err(p, "enchant", "unknown enchantment: ", Msg.v(key.asString())); return; }
        EnchantmentList existing = held.get(DataComponents.ENCHANTMENTS);
        EnchantmentList updated = (existing != null ? existing : EnchantmentList.EMPTY).with(ench, level);
        p.setItemInMainHand(held.with(DataComponents.ENCHANTMENTS, updated));
        Msg.tag(p, "enchant", "enchanted held item with ", Msg.v(key.asString()), " ", Msg.v(level));
    }
}
