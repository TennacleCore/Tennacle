package tennacle.games.bedwars;

import tennacle.games.Match;

import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.modules.space.Regions;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.TimedPotion;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Heal Pool (captured 2026-09-25): Regeneration I for a side's own players within a ball around its spawn, bed or
 * no bed, and green sparkles over it for everyone, while the side has the upgrade.
 */
final class HealPool implements GameModule {

    /** Read as the game starts. */
    static final StateKey<Double> RADIUS = StateKey.of("heal-pool.radius", Codec.DOUBLE, 21.0);

    private static final String BEAT = "heal-pool:beat";
    private static final Potion REGENERATION = new Potion(PotionEffect.REGENERATION, 0, Potion.INFINITE_DURATION);

    private final Map<String, Regions.Area> pools = new LinkedHashMap<>();
    private final Map<String, Point> centers = new LinkedHashMap<>();
    private Game game;
    private Regions regions;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(RADIUS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        regions = game.module(Regions.class);
        if (regions == null) throw new IllegalStateException("HealPool needs Regions installed before it");
        double radius = game.rules().resolve(Scope.GAME, RADIUS);
        for (Team team : game.roster().teams()) {
            PosMarker spawn = game.arena().marker(Markers.SPAWN, team.id());
            if (spawn == null) continue;
            Point center = spawn.pos();
            Regions.Area pool = Regions.Area.ball(center, radius);
            pools.put(team.id(), pool);
            centers.put(team.id(), center);
            regions.watch(pool, player -> heal(team.id(), player), this::leave);
        }
        game.rules().watch(Upgrades.HEAL_POOL, (scope, level) -> {
            if (scope instanceof Scope.Team team && pools.containsKey(team.id())) {
                for (Player player : regions.inside(pools.get(team.id()))) heal(team.id(), player);
            }
        });
        // captured: sparkles every 12 ticks; the same beat restores a Regeneration a golden apple's outran
        game.state().alarms().register(BEAT, (tick, payload) -> beat());
        game.state().alarms().arm(BEAT, game.tick() + 12);
    }

    private boolean has(String team) {
        return game.state().get(Scope.team(team), Upgrades.HEAL_POOL) > 0;
    }

    // a stronger one runs its course first; the pool's own is never sent twice
    private void heal(String team, Player player) {
        if (!has(team) || !team.equals(Match.teamOf(game, player)) || game.isGhost(player)) return;
        for (TimedPotion active : player.getActiveEffects()) {
            Potion potion = active.potion();
            if (potion.effect() == PotionEffect.REGENERATION
                    && (potion.amplifier() > 0 || potion.duration() == Potion.INFINITE_DURATION)) return;
        }
        player.addEffect(REGENERATION);
    }

    // only the pool's own: a golden apple's stays
    private void leave(Player player) {
        for (TimedPotion active : player.getActiveEffects()) {
            Potion potion = active.potion();
            if (potion.effect() == PotionEffect.REGENERATION && potion.amplifier() == 0
                    && potion.duration() == Potion.INFINITE_DURATION) {
                player.removeEffect(PotionEffect.REGENERATION);
                return;
            }
        }
    }

    private void beat() {
        game.state().alarms().arm(BEAT, game.tick() + 12);
        pools.forEach((team, pool) -> {
            if (!has(team)) return;
            ParticlePacket sparkles = new ParticlePacket(Particle.HAPPY_VILLAGER, false, true, centers.get(team),
                    new Vec(10.5, 10.5, 10.5), 0f, 39);
            game.forEachAudience(viewer -> viewer.sendPacket(sparkles));
            for (Player player : regions.inside(pool)) heal(team, player);
        });
    }
}
