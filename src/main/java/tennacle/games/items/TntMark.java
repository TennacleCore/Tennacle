package tennacle.games.items;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.platform.compatibility.via.ViaBridgeRpc;
import net.minestom.server.codec.Codec;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import org.jetbrains.annotations.NotNull;
import tennacle.boot.Mechanics;
import tennacle.games.Match;

import java.util.Set;

/**
 * The red dust over a TNT carrier's head, for everyone but them. Captured (Hypixel, 2026-09-29, 1356 particles): one
 * a tick, a block over the eye, a 1.8 colored dust of pure red, sent far; teammates see it too, the carrier never.
 */
public final class TntMark implements GameModule {

    public static final StateKey<Boolean> MARK = StateKey.of("tnt.mark", Codec.BOOLEAN, true);

    private static final String TICK = "tnt-mark:tick";
    private static final int REDDUST_1_8 = 30;
    private static final Particle RED = Particle.DUST.withProperties(new Color(255, 0, 0), 1f);
    private static final double OVER_EYE = 1.0;

    private Game game;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(MARK);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.state().alarms().register(TICK, (tick, payload) -> tick());
        game.state().alarms().arm(TICK, game.tick() + 1);
    }

    private void tick() {
        if (Match.live(game) && game.rules().resolve(Scope.GAME, MARK)) {
            for (Player carrier : game.members()) {
                if (carrier.getItemInMainHand().material() != Material.TNT) continue;
                Pos p = carrier.getPosition();
                double y = p.y() + carrier.getEyeHeight() + OVER_EYE;
                for (Player viewer : carrier.getViewers()) {
                    if (viewer == carrier) continue;
                    if (Mechanics.polyp.clientInfo().isLegacy(viewer) && ViaBridgeRpc.isInstalled() && ViaBridgeRpc.available(viewer)) {
                        ViaBridgeRpc.get().sendLevelParticles18(viewer, REDDUST_1_8, true, (float) p.x(), (float) y, (float) p.z(),
                                1f, 0f, 0f, 1f, 0); // 1.8: count 0 reads the offsets as the color
                    } else {
                        viewer.sendPacket(new ParticlePacket(RED, false, true, p.x(), y, p.z(), 0f, 0f, 0f, 0f, 1));
                    }
                }
            }
        }
        game.state().alarms().arm(TICK, game.tick() + 1);
    }
}
