package tennacle.commands;

import tennacle.Mechanics;
import tennacle.Msg;
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
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.Argument;
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

/**
 * The polyp example server's dev/test toolset plus the shard-aware {@code /boom}: lag simulation, profile
 * switches, explosions, enchants, effects, blockable swords, velocity-cap A/B.
 */
public final class DevCommands {

    private DevCommands() {}

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

    public static void register(Polyp polyp) {
        var commands = MinecraftServer.getCommandManager();

        // what the tab is ACTUALLY being sent for this viewer - the tab differs per client generation
        Command tabdump = new Command("tabdump");
        tabdump.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player viewer)) return;
            for (String line : ShardTabList.describe(viewer)) viewer.sendMessage(Msg.v(line));
        });
        commands.register(tabdump);

        Command gm = new Command("gm");
        Argument<GameMode> mode = ArgumentType.Enum("mode", GameMode.class);
        gm.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) p.setGameMode(ctx.get(mode));
        }, mode);
        commands.register(gm);

        Command gmc = new Command("gmc");
        gmc.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) { p.setGameMode(GameMode.CREATIVE); Msg.tag(p, "gm", "gamemode: ", Msg.v("creative")); }
        });
        commands.register(gmc);

        Command gms = new Command("gms");
        gms.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) { p.setGameMode(GameMode.SURVIVAL); Msg.tag(p, "gm", "gamemode: ", Msg.v("survival")); }
        });
        commands.register(gms);

        // /fly: toggle survival flight (double-jump to lift); while flying, fall damage is exempt. A gamemode
        // switch clears allowFlying (Minestom stamps it from the mode), so re-run /fly after /gms.
        Command fly = new Command("fly");
        fly.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            boolean on = !p.isAllowFlying();
            p.setAllowFlying(on);
            if (!on) p.setFlying(false);
            Msg.tag(p, "fly", "flight: ", Msg.v(on ? "ON" : "OFF"));
        });
        commands.register(fly);

        // /boom resolves the SHOOTER's world: a shard blast only rocks that game, a main-map one never reaches it
        Command boom = new Command("boom");
        boom.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Mechanics.explosions.explode(MechanicsWorld.of(p), p.getPosition(), 4.0f, p, null);
        });
        commands.register(boom);

        // test-only inbound-lag simulator (delays a player's movement packets so the server perceives their
        // position/onGround late, like a high-ping client). Purely test-side - mechanics is untouched.
        final LagSimulator lag = new LagSimulator();
        lag.install();
        Command lagCmd = new Command("lag");
        Argument<Integer> ticksArg = ArgumentType.Integer("ticks");
        lagCmd.setDefaultExecutor((sender, ctx) -> Msg.usage(sender, "/lag - simulate inbound latency", new String[][]{
                {"/lag <ticks>", "delay your own movement packets (0 = off)"}}));
        lagCmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            int ticks = ctx.get(ticksArg);
            lag.setDelay(p, ticks);
            Msg.tag(p, "sim-lag", "inbound packet latency = ", Msg.v(ticks), " ticks");
        }, ticksArg);
        commands.register(lagCmd);

        // /compat: toggle this player between the global 1.8 profile and a per-player modern override (mode switch demo)
        Command compatCmd = new Command("compat");
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
        commands.register(compatCmd);

        // /ns: A/B the two client sprint/swim gates. "food" clamps the wire food to 6 (steady 3-shank bar, nothing
        // else); "blind [duration]" holds a hidden blindness (fog lerps out at duration/20, but the sky-block and
        // darkness ride effect PRESENCE - short durations strobe under jitter, longer ones block the sky steadily)
        Command ns = new Command("ns");
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
        commands.register(ns);
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
        Command shortsCmd = new Command("shorts");
        shortsCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof OptimizedPlayer op)) return;
            CompatConfig current = polyp.profiles().resolve(op, MechanicsKeys.COMPAT);
            boolean on = !(current != null && Boolean.TRUE.equals(current.nativeShortVelocity));
            polyp.profiles().setPlayer(op, MechanicsProfile.builder()
                    .set(MechanicsKeys.COMPAT, Compat18.config().toBuilder().nativeShortVelocity(on).build())
                    .build());
            Msg.tag(op, "shorts", "shorts velocity: ", Msg.v(on ? "ON" : "OFF"), " (take a >2 b/t knockback to compare)");
        });
        commands.register(shortsCmd);

        // /suffocate: drops a stone block into your head to test suffocation damage (be in survival - /gms)
        Command suffocate = new Command("suffocate");
        suffocate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p) || p.getInstance() == null) return;
            p.getInstance().setBlock(p.getPosition().add(0, p.getEyeHeight(), 0), Block.STONE);
            Msg.tag(p, "suffocate", "stone placed in your head (survival = 1/tick suffocation)");
        });
        commands.register(suffocate);

        // /explode [power]: explosion at your feet (no source, so you feel the falloff knockback + take the damage)
        ExplosionSystem explosions = Mechanics.explosions;
        Command explode = new Command("explode");
        Argument<Integer> explodePower = ArgumentType.Integer("power");
        explode.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition(), 4.0f);
        });
        explode.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition(), (float) (int) ctx.get(explodePower));
        }, explodePower);
        commands.register(explode);

        // /explodeat <dx> <dy> <dz> [power]: detonate at your feet + offset (power 2 = fireball) - decouples
        // detonation height from distance for the head-gate model test
        Command explodeAt = new Command("explodeat");
        Argument<Double> exAtX = ArgumentType.Double("dx");
        Argument<Double> exAtY = ArgumentType.Double("dy");
        Argument<Double> exAtZ = ArgumentType.Double("dz");
        Argument<Double> exAtPower = ArgumentType.Double("power");
        explodeAt.setDefaultExecutor((sender, ctx) -> Msg.usage(sender, "/explodeat - detonate at an offset", new String[][]{
                {"/explodeat <dx> <dy> <dz> [power]", "power default 2 = fireball"}}));
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), 2.0f);
        }, exAtX, exAtY, exAtZ);
        explodeAt.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p && p.getInstance() != null)
                explosions.explode(p.getInstance(), p.getPosition().add(ctx.get(exAtX), ctx.get(exAtY), ctx.get(exAtZ)), ctx.get(exAtPower).floatValue());
        }, exAtX, exAtY, exAtZ, exAtPower);
        commands.register(explodeAt);

        // /sword: a blockable diamond sword (right-click + hold to block; 1.8 = (1+f)*0.5 damage, pre-armor)
        Command sword = new Command("sword");
        sword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.item(Material.DIAMOND_SWORD));
                Msg.tag(p, "sword", "blockable diamond sword given (hold right-click to block)");
            }
        });
        commands.register(sword);

        // /nbsword: a non-blockable diamond sword (opted out) - should NOT reduce damage while right-click held
        Command nbsword = new Command("nbsword");
        nbsword.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) {
                p.getInventory().addItemStack(VanillaBlocking.nonBlocking(ItemStack.of(Material.DIAMOND_SWORD)));
                Msg.tag(p, "nbsword", "non-blockable diamond sword given (holding right-click should not block)");
            }
        });
        commands.register(nbsword);

        // /enchant <enchantment> [level]: enchant the held item (e.g. /enchant fire_aspect 2)
        Command enchant = new Command("enchant");
        Argument<String> enchName = ArgumentType.String("enchantment");
        Argument<Integer> enchLevel = ArgumentType.Integer("level");
        enchant.setDefaultExecutor((sender, ctx) -> Msg.usage(sender, "/enchant - enchant the held item", new String[][]{
                {"/enchant <enchantment> [level]", "e.g. /enchant fire_aspect 2"}}));
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), 1);
        }, enchName);
        enchant.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEnchant(p, ctx.get(enchName), ctx.get(enchLevel));
        }, enchName, enchLevel);
        commands.register(enchant);

        // /effect <effect> [strength] [seconds]  |  /effect clear
        Command effectCmd = new Command("effect");
        Argument<String> effName = ArgumentType.String("effect");
        Argument<Integer> effStrength = ArgumentType.Integer("strength");
        Argument<Integer> effSeconds = ArgumentType.Integer("seconds");
        effectCmd.setDefaultExecutor((sender, ctx) -> Msg.usage(sender, "/effect - apply a potion effect", new String[][]{
                {"/effect <effect> [strength] [seconds]", "e.g. /effect strength 2"},
                {"/effect clear", "clear all effects"}}));
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), 1, 30);
        }, effName);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), 30);
        }, effName, effStrength);
        effectCmd.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) applyEffect(p, ctx.get(effName), ctx.get(effStrength), ctx.get(effSeconds));
        }, effName, effStrength, effSeconds);
        commands.register(effectCmd);

        // /velcap (LpVec3 snap) vs /velcapbridge (exact 1.8 short): A/B for the LegacyVelocityBridge knockback path
        EventNode<@NotNull PlayerEvent> playerNode = EventNode.type("polyp:test-player", EventFilter.PLAYER);
        VelocityCapCommands.install(playerNode);
        MinecraftServer.getGlobalEventHandler().addChild(playerNode);

        Command velCap = new Command("velcap");
        velCap.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapCommands.applyNormalCap(p);
        });
        commands.register(velCap);

        Command velCapBridge = new Command("velcapbridge");
        velCapBridge.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) VelocityCapCommands.applyBridgeCap(p);
        });
        commands.register(velCapBridge);
    }

    /** Applies (or clears) a potion effect on the sender. {@code strength} is the level (1 = level I); duration in seconds. */
    private static void applyEffect(Player p, String name, int strength, int seconds) {
        if (name.equalsIgnoreCase("clear")) { p.clearEffects(); Msg.tag(p, "effect", "cleared effects"); return; }
        PotionEffect effect = PotionEffect.fromKey(name.contains(":") ? name : "minecraft:" + name);
        if (effect == null) { Msg.err(p, "effect", "unknown effect: ", Msg.v(name)); return; }
        int amplifier = Math.max(0, strength - 1); // strength 1 -> amplifier 0 (level I)
        p.addEffect(new Potion(effect, amplifier, seconds * 20));
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
