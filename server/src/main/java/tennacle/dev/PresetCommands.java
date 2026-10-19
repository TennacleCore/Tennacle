package tennacle.dev;

import tennacle.boot.Mechanics;
import tennacle.Msg;

import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.presets.Preset;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentEnum;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;

import java.util.Map;
import java.util.WeakHashMap;

import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
/**
 * {@code /preset} - the mechanics preset at each scope, live: the runtime form of the {@code Mechanics.PRESET}
 * line. Scopes resolve player -&gt; shard -&gt; instance -&gt; global, so a running game's own rules still sit on
 * top of the lobby's, and every key that game leaves unset falls through to whatever is set here.
 */
public final class PresetCommands {

    private PresetCommands() {}

    /** What this command last applied per scope, by the profile it installed: a scope someone else overwrote reads as theirs, not ours. */
    private record Applied(Preset preset, MechanicsProfile profile) {}

    private static final Map<Object, Applied> APPLIED = new WeakHashMap<>();
    private static final Object GLOBAL = new Object();

    public static void register(Polyp polyp, InstanceContainer lobby) {
        AppCommand cmd = Commands.staff("preset", "The mechanics preset at each scope, live.")
                .usage("/preset lobby <preset>", "the lobby map - a game's own rules still win")
                .usage("/preset global <preset>", "the whole server, as if Mechanics.PRESET said so")
                .usage("/preset self <preset>", "only you, for an A/B against the player beside you")
                .usage("/preset lobby|self reset", "the lobby back to its own, you back to your scope")
                .usage("/preset show", "what each scope holds, and which one answers for you");
        var lobbyArg = ArgumentType.Literal("lobby");
        var globalArg = ArgumentType.Literal("global");
        var selfArg = ArgumentType.Literal("self");
        var reset = ArgumentType.Literal("reset");
        var show = ArgumentType.Literal("show");
        Argument<Preset> preset = ArgumentType.Enum("preset", Preset.class).setFormat(ArgumentEnum.Format.LOWER_CASED);

        cmd.addSyntax((sender, ctx) -> {
            apply(polyp, lobby, ctx.get(preset));
            Msg.tag(sender, "preset", "lobby -> ", Msg.v(name(ctx.get(preset))),
                    " (a running game's rules still sit on top)");
        }, lobbyArg, preset);

        cmd.addSyntax((sender, ctx) -> {
            polyp.profiles().setInstance(lobby, tennacle.lobby.Playground.profile());
            APPLIED.remove(lobby);
            Msg.tag(sender, "preset", "lobby -> ", Msg.v("the lobby's own"), " (no fights, hunger or drops)");
        }, lobbyArg, reset);

        cmd.addSyntax((sender, ctx) -> {
            MechanicsProfile built = Mechanics.profileOf(ctx.get(preset));
            polyp.profiles().setGlobal(built);
            APPLIED.put(GLOBAL, new Applied(ctx.get(preset), built));
            Msg.tag(sender, "preset", "global -> ", Msg.v(name(ctx.get(preset))));
        }, globalArg, preset);

        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            apply(polyp, p, ctx.get(preset));
            Msg.tag(sender, "preset", "you -> ", Msg.v(name(ctx.get(preset))));
        }, selfArg, preset);

        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            polyp.profiles().setPlayer(p, null);
            APPLIED.remove(p);
            Msg.tag(sender, "preset", "you -> ", Msg.v("your scope"), " (override dropped)");
        }, selfArg, reset);

        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Msg.tag(p, "preset", "you: ", Msg.v(held(p, polyp.profiles().player(p))));
            Msg.tag(p, "preset", "lobby: ", Msg.v(held(lobby, polyp.profiles().instance(lobby))));
            Msg.tag(p, "preset", "global: ", Msg.v(held(GLOBAL, polyp.profiles().global())));
            Msg.tag(p, "preset", "answering for you: ", Msg.v(answering(polyp, p, lobby)));
        }, show);

        Commands.register(cmd);
    }

    private static void apply(Polyp polyp, Object scope, Preset preset) {
        MechanicsProfile built = Mechanics.profileOf(preset);
        if (scope instanceof Player p) polyp.profiles().setPlayer(p, built);
        else polyp.profiles().setInstance((InstanceContainer) scope, built);
        APPLIED.put(scope, new Applied(preset, built));
    }

    /** The preset name only when the scope still holds the profile we installed - anything else is someone else's. */
    private static String held(Object scope, MechanicsProfile current) {
        if (current == null) return "none";
        Applied applied = APPLIED.get(scope);
        return applied != null && applied.profile() == current ? name(applied.preset()) : "set elsewhere";
    }

    /** The first scope in the chain with anything in it - which is the one a key falls to unless a nearer scope sets it. */
    private static String answering(Polyp polyp, Player p, InstanceContainer lobby) {
        if (polyp.profiles().player(p) != null) return "you";
        var shard = io.github.term4.archipelago.shard.Shard.of(p);
        if (shard != null && polyp.profiles().world(shard) != null) return "your game";
        if (p.getInstance() == lobby && polyp.profiles().instance(lobby) != null) return "lobby";
        return "global";
    }

    private static String name(Preset preset) {
        return preset.name().toLowerCase();
    }
}
