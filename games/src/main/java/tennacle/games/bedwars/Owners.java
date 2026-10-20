package tennacle.games.bedwars;

import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import net.minestom.server.instance.block.Block;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Markers;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Generators;
import tennacle.games.Match;
import tennacle.games.stats.Haul;
import tennacle.games.stats.Tally;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whose generator a pickup came from. A forge is its base's. A diamond generator is its nearest side's, shared with
 * the next nearest while that one is under 1.5 times as far: two sides to a generator on most maps, one where the
 * generators sit in front of the bases (Terminal, Sky Festival). On a two-sided map each is its own side's alone,
 * unless both sides are as near (Gardens). Emeralds are nobody's.
 */
final class Owners implements Haul.Origins {

    private static final double SHARED = 1.5;
    // two sides this close to even are one generator between them
    private static final double EVEN = 1.05;
    // a generator this near a marker is that marker's
    private static final double AT = 3;

    private enum Kind { FORGE, DIAMOND, EMERALD }

    private record Owner(Kind kind, Set<String> teams) {}

    private final Map<Generators.Generator, Owner> owners = new ConcurrentHashMap<>();

    @Override
    public void picked(@NotNull Game game, @NotNull Player picker, @NotNull Generators.Generator generator,
                       @NotNull String resource, int amount) {
        Owner owner = owners.computeIfAbsent(generator, g -> owner(game, g.at()));
        if (owner == null) return;
        var id = picker.getUuid();
        boolean own = owner.teams().contains(Match.teamOf(game, picker));
        switch (owner.kind()) {
            case FORGE -> {
                Tally.add(game, id, resource + (own ? "-from-own-forge" : "-from-other-forges"), amount);
                if (!own) owner.teams().forEach(team -> Tally.pair(game, id, team, resource + "-from-forge", amount));
            }
            case DIAMOND -> {
                Tally.add(game, id, resource + (own ? "-from-own-diamond-generators" : "-from-other-diamond-generators"), amount);
                if (!own) owner.teams().forEach(team -> Tally.pair(game, id, team, resource + "-from-their-diamond-generators", amount));
            }
            case EMERALD -> Tally.add(game, id, resource + "-from-emerald-generators", amount);
        }
    }

    @Override
    public void destroyed(@NotNull Game game, @NotNull Player by, @NotNull Generators.Generator generator,
                          @NotNull String resource, int amount) {
        Owner owner = owners.computeIfAbsent(generator, g -> owner(game, g.at()));
        if (owner == null || owner.kind() == Kind.EMERALD) return;
        var id = by.getUuid();
        boolean own = owner.teams().contains(Match.teamOf(game, by));
        Tally.add(game, id, resource + (own ? "-destroyed-at-own-generators" : "-destroyed-at-other-generators"), amount);
        if (!own) owner.teams().forEach(team -> Tally.pair(game, id, team, resource + "-destroyed-at-their-generators", amount));
    }

    // a viewer's own (the ender chest), their base's, or anyone else's
    @Override
    public @Nullable Haul.Chest chest(@NotNull Game game, @NotNull Player visitor, @NotNull Point pos) {
        if (game.shard().getBlock(pos).compare(Block.ENDER_CHEST)) return new Haul.Chest("ender-chest", null);
        String owner = null;
        for (RegionMarker base : game.arena().markers(BedWars.BASE)) {
            if (base.contains(pos)) owner = base.group();
        }
        if (owner != null && owner.equals(Match.teamOf(game, visitor))) return new Haul.Chest("team-chest", null);
        return new Haul.Chest("other-chests", owner);
    }

    private static @Nullable Owner owner(Game game, Point at) {
        for (var key : List.of(BedWars.FORGE, BedWars.FORGE_IRON)) {
            for (PointMarker forge : game.arena().markers(key)) {
                if (flat(forge.point(), at) <= AT) return new Owner(Kind.FORGE, Set.of(forge.group()));
            }
        }
        for (PointMarker diamond : game.arena().markers(BedWars.DIAMOND)) {
            if (flat(diamond.point(), at) <= AT) return new Owner(Kind.DIAMOND, nearest(game, diamond.point()));
        }
        for (PointMarker emerald : game.arena().markers(BedWars.EMERALD)) {
            if (flat(emerald.point(), at) <= AT) return new Owner(Kind.EMERALD, Set.of());
        }
        return null;
    }

    static Set<String> nearest(Game game, Point generator) {
        Map<String, Double> sides = new HashMap<>();
        for (PosMarker spawn : game.arena().markers(Markers.SPAWN)) {
            sides.merge(spawn.group(), flat(spawn.pos(), generator), Math::min);
        }
        List<Map.Entry<String, Double>> byDistance = new ArrayList<>(sides.entrySet());
        byDistance.sort(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        if (byDistance.isEmpty()) return Set.of();
        Map.Entry<String, Double> first = byDistance.getFirst();
        double shared = byDistance.size() <= 2 ? EVEN : SHARED;
        if (byDistance.size() > 1 && byDistance.get(1).getValue() < first.getValue() * shared) {
            return Set.of(first.getKey(), byDistance.get(1).getKey());
        }
        return Set.of(first.getKey());
    }

    private static double flat(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
