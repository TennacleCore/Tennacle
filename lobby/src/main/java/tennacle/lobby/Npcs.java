package tennacle.lobby;

import io.github.term4.canvas.npc.Actions;
import io.github.term4.canvas.npc.Npc;
import io.github.term4.canvas.npc.Trigger;
import io.github.term4.polyp.world.WorldPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import tennacle.games.KitLayouts;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.lobby.menus.Icons;
import tennacle.lobby.menus.PlayMenu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The people who start games: one per kind of game a lobby hosts, under its name and how many play it. A click
 * opens the game's menu, never a game. Until the lobbies have maps of their own they stand in a row before the spawn.
 */
public final class Npcs {

    /** One NPC: the name over it, and the modes it starts. */
    public record Spec(@NotNull String name, @NotNull Predicate<Mode> hosts) {}

    private static final Map<Npc, Spec> STANDING = new IdentityHashMap<>();

    private Npcs() {}

    public static void install() {
        for (Lobbies.Lobby lobby : Lobbies.all()) {
            List<Spec> specs = specs(lobby);
            for (int i = 0; i < specs.size(); i++) {
                double x = lobby.spawn().x() + (i - (specs.size() - 1) / 2.0) * 3;
                stand(lobby, specs.get(i), new Pos(x, lobby.spawn().y(), lobby.spawn().z() + 6, 180, 0));
            }
        }
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
        // over a villager's head, the gap Scrims leaves over a player's
        Npc npc = Npc.builder().body(() -> new LivingEntity(EntityType.VILLAGER))
                .line(3.0, Component.text("CLICK TO PLAY", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .line(2.7, Component.text(spec.name(), NamedTextColor.AQUA))
                .line(2.4, playing(spec))
                .on(Trigger.any(), Actions.run(player -> {
                    if (!KitLayouts.editing(player)) PlayMenu.open(player, spec.name(), spec.hosts(), Icons::of);
                }))
                .spawn(at, (entity, where) -> {
                    entity.setTag(WorldPolicy.INERT, true);
                    entity.setInstance(lobby.world(), where);
                });
        synchronized (STANDING) {
            STANDING.put(npc, spec);
        }
    }

    static Map<Npc, Spec> standing() {
        synchronized (STANDING) {
            return Map.copyOf(STANDING);
        }
    }

    private static void refresh() {
        synchronized (STANDING) {
            STANDING.forEach((npc, spec) -> npc.label(2, playing(spec)));
        }
    }

    private static Component playing(Spec spec) {
        int sum = 0;
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode != null && spec.hosts().test(mode)) sum += Counts.playing(mode);
        }
        return Component.text(sum + (sum == 1 ? " Player" : " Players"), NamedTextColor.YELLOW, TextDecoration.BOLD);
    }
}
