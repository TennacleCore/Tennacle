package tennacle.dev;

import tennacle.Msg;

import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.util.tick.TickScalingConfig;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;

import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
/**
 * {@code /simtps} - time dilation at each {@code MechanicsProfile} scope. The server keeps its real tick rate;
 * the scope's {@code clientTps}/{@code referenceTps} re-rate its physics and durations, so below 20 is slow
 * motion and above it is fast. Anyone SIMULATING in a dilated scope also gets a client tick-rate packet (modern
 * only); a legacy client can watch a dilated shard but never play in one.
 */
public final class SimTpsCommands {

    private SimTpsCommands() {}

    public static void register(Polyp polyp, ShardManager shards, InstanceContainer world) {
        AppCommand cmd = Commands.dev("simtps", "A simulated tick rate: slow motion or fast.")
                .usage("/simtps self <1-100|native>", "dilate YOUR player scope")
                .usage("/simtps shard <name> <1-100|native>", "dilate one shard (its members only)")
                .usage("/simtps instance <1-100|native>", "dilate the base map")
                .usage("/simtps global <1-100|native>", "dilate the whole server")
                .usage("/simtps show", "the rate resolved for you, and why");
        var global = ArgumentType.Literal("global");
        var shard = ArgumentType.Literal("shard");
        var instance = ArgumentType.Literal("instance");
        var self = ArgumentType.Literal("self");
        var show = ArgumentType.Literal("show");
        Argument<String> name = ArgumentType.Word("name"); // NOT "shard" - the literal already owns that id (collectArguments keys by id)
        // "native" clears the scope's baseline (back to literal ticks); anything else must parse as a rate
        Argument<String> rate = ArgumentType.Word("rate");


        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            TickScalingConfig cfg = parse(sender, ctx.get(rate));
            if (cfg == NO_CHANGE) return;
            polyp.profiles().setPlayer(p, MechanicsKeys.TICK_SCALING, cfg);
            report(sender, "self", ctx.get(rate));
        }, self, rate);

        cmd.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { Msg.err(sender, "simtps", "no shard named ", Msg.v(ctx.get(name))); return; }
            TickScalingConfig cfg = parse(sender, ctx.get(rate));
            if (cfg == NO_CHANGE) return;
            // world scope = the shard itself, so ONLY its members dilate - a spectator stays unbound and native
            polyp.profiles().setWorld(g, MechanicsKeys.TICK_SCALING, cfg);
            report(sender, "shard " + ctx.get(name), ctx.get(rate));
        }, shard, name, rate);

        cmd.addSyntax((sender, ctx) -> {
            TickScalingConfig cfg = parse(sender, ctx.get(rate));
            if (cfg == NO_CHANGE) return;
            polyp.profiles().setInstance(world, MechanicsKeys.TICK_SCALING, cfg);
            report(sender, "instance", ctx.get(rate));
        }, instance, rate);

        cmd.addSyntax((sender, ctx) -> {
            TickScalingConfig cfg = parse(sender, ctx.get(rate));
            if (cfg == NO_CHANGE) return;
            polyp.profiles().setGlobal(MechanicsKeys.TICK_SCALING, cfg);
            report(sender, "global", ctx.get(rate));
        }, global, rate);

        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            TickScalingConfig resolved = polyp.profiles().resolve(p, MechanicsKeys.TICK_SCALING);
            int tps = resolved == null ? TickScalingConfig.SERVER_TPS : resolved.clientTps();
            // dilation follows the BINDING, not the view: an unbound spectator resolves native and moves normally
            Shard bound = Shard.of(p);
            Msg.tag(p, "simtps", "resolved ",
                    Msg.v(tps == TickScalingConfig.SERVER_TPS ? "native (no scaling)" : tps + " tps"),
                    " | bound to ", Msg.v(bound != null ? shards.nameOf(bound) : "the base map"),
                    " | client wire ", Msg.v(polyp.clientInfo().isLegacy(p) ? "legacy - spectate only" : "modern - can simulate"));
        }, show);

        Commands.register(cmd);
    }

    /** Sentinel for "the input was bad and the sender was told" - distinct from a real null (= clear the scope). */
    private static final TickScalingConfig NO_CHANGE = TickScalingConfig.builder().build();

    private static TickScalingConfig parse(net.minestom.server.command.CommandSender sender, String raw) {
        if (raw.equalsIgnoreCase("native")) return null; // clears just this member
        try {
            return TickScalingConfig.simulated(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            Msg.err(sender, "simtps", Msg.v(raw), " is not a rate - use 1-",
                    Msg.v(TickScalingConfig.MAX_SIMULATED_TPS), " or ", Msg.v("native"));
            return NO_CHANGE;
        } catch (IllegalArgumentException e) {
            Msg.err(sender, "simtps", e.getMessage());
            return NO_CHANGE;
        }
    }

    private static void report(net.minestom.server.command.CommandSender sender, String scope, String raw) {
        if (raw.equalsIgnoreCase("native")) {
            Msg.tag(sender, "simtps", Msg.v(scope), " -> ", Msg.v("native"), " (literal ticks)");
            return;
        }
        int tps = Integer.parseInt(raw);
        String feel = tps < TickScalingConfig.VANILLA_CLIENT_TPS ? "slow motion"
                : tps > TickScalingConfig.VANILLA_CLIENT_TPS ? "fast motion" : "1:1";
        Msg.tag(sender, "simtps", Msg.v(scope), " -> ", Msg.v(tps + " tps"), " (", Msg.v(feel),
                ") - throw something to see it");
    }
}
