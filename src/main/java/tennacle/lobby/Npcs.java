package tennacle.lobby;

import io.github.term4.canvas.hologram.Hologram;
import io.github.term4.polyp.world.WorldPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;
import tennacle.Press;
import tennacle.games.KitLayouts;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Play;
import tennacle.lobby.menus.Icons;
import tennacle.lobby.menus.PlayMenu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The people who start games: one per kind of game a lobby hosts, a line over each naming it, its sizes and how many
 * play it. A click opens the game's menu, or plays at once when there is only one size. Until the lobbies have maps
 * of their own they stand in a row before the spawn.
 */
public final class Npcs {

    /** One NPC: the name over it, and the modes it starts. */
    public record Spec(@NotNull String name, @NotNull Predicate<Mode> hosts) {}

    private record Standing(Spec spec, Hologram label) {}

    private static final Map<Entity, Standing> STANDING = new IdentityHashMap<>();

    private Npcs() {}

    public static void install() {
        for (Lobbies.Lobby lobby : Lobbies.all()) {
            List<Spec> specs = specs(lobby);
            for (int i = 0; i < specs.size(); i++) {
                double x = lobby.spawn().x() + (i - (specs.size() - 1) / 2.0) * 3;
                stand(lobby, specs.get(i), new Pos(x, lobby.spawn().y(), lobby.spawn().z() + 6, 180, 0));
            }
        }
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerEntityInteractEvent.class, e -> {
            if (e.getHand() == PlayerHand.MAIN) use(e.getPlayer(), e.getTarget());
        });
        events.addListener(EntityAttackEvent.class, e -> {
            if (e.getEntity() instanceof Player player) use(player, e.getTarget());
        });
        Counts.onMove(Npcs::refresh);
    }

    // a spec per banner the lobby hosts, in the order the modes signed up
    private static List<Spec> specs(Lobbies.Lobby lobby) {
        Map<String, List<Mode>> byBanner = lobby.modes().stream()
                .collect(Collectors.groupingBy(Mode::banner, java.util.LinkedHashMap::new, Collectors.toList()));
        List<Spec> out = new ArrayList<>();
        byBanner.forEach((banner, modes) -> out.add(new Spec(banner, mode -> lobby.hosts(mode) && mode.banner().equals(banner))));
        return out;
    }

    private static void stand(Lobbies.Lobby lobby, Spec spec, Pos at) {
        LivingEntity body = new LivingEntity(EntityType.VILLAGER);
        body.setTag(WorldPolicy.INERT, true);
        body.setNoGravity(true);
        body.setInvulnerable(true);
        body.setInstance(lobby.world(), at);
        Hologram label = Hologram.at(at, (stand, where) -> {
            stand.setTag(WorldPolicy.INERT, true);
            stand.setInstance(lobby.world(), where);
        });
        label.line(2.55, Msg.THEME.value(spec.name().toUpperCase(java.util.Locale.ROOT)).decorate(TextDecoration.BOLD));
        label.line(2.3, Msg.THEME.prose(sizes(spec)));
        label.line(2.05, playing(spec));
        label.line(1.8, Msg.THEME.dim("» ").append(Msg.THEME.accent("Click to play")));
        synchronized (STANDING) {
            STANDING.put(body, new Standing(spec, label));
        }
    }

    private static void use(Player player, Entity target) {
        Standing standing;
        synchronized (STANDING) {
            standing = STANDING.get(target);
        }
        if (standing == null || KitLayouts.editing(player) || !Press.once(player)) return;
        Spec spec = standing.spec();
        String only = PlayMenu.only(spec.hosts());
        if (only == null) {
            PlayMenu.open(player, spec.name(), spec.hosts(), Icons::of);
            return;
        }
        String refused = Play.start(player, only);
        if (refused != null) Msg.fail(player, refused);
    }

    private static void refresh() {
        synchronized (STANDING) {
            STANDING.values().forEach(standing -> standing.label().text(2, playing(standing.spec())));
        }
    }

    private static Component playing(Spec spec) {
        int sum = 0;
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode != null && spec.hosts().test(mode)) sum += Counts.playing(mode);
        }
        return Msg.THEME.value(sum).append(Msg.THEME.prose(" playing"));
    }

    private static String sizes(Spec spec) {
        List<String> out = new ArrayList<>();
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode == null || !spec.hosts().test(mode)) continue;
            boolean named = mode.title().startsWith(spec.name() + " ");
            mode.plays().forEach((id, size) -> out.add(named ? mode.title().substring(spec.name().length() + 1) : size + "v" + size));
        }
        return String.join(" · ", out);
    }
}
