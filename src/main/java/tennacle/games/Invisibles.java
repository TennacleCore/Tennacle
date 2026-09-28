package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.polyp.mechanics.attribute.defense.ProtectionCategory;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import java.util.HashSet;
import tennacle.boot.Mechanics;
import net.minestom.server.particle.Particle;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.coordinate.Point;
import net.kyori.adventure.key.Key;
import io.github.term4.polyp.platform.compatibility.via.ViaBridgeRpc;
import io.github.term4.polyp.fx.Fx;
import io.github.term4.polyp.api.event.fx.FxEvent;
import io.github.term4.polyp.platform.player.OptimizedPlayer;
import io.github.term4.polyp.platform.player.ViewRewrites;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityPotionAddEvent;
import net.minestom.server.event.entity.EntityPotionRemoveEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * An invisible member as Hypixel shows one (captured 2026-09-25): the armor gone from an enemy's view in the tick of
 * the drink and the nameplate with it, both back in the tick of the hit that breaks the effect; a teammate keeps
 * seeing the armor and the name, and a teammate's arrow keeps the effect. Held items stay in view.
 */
public final class Invisibles implements GameModule {

    public static final StateKey<Boolean> HIDES_ARMOR = StateKey.of("invisibility.hides-armor", Codec.BOOLEAN, true);
    public static final StateKey<Boolean> HIDES_NAME = StateKey.of("invisibility.hides-name", Codec.BOOLEAN, true);
    public static final StateKey<Boolean> BREAKS_ON_DAMAGE = StateKey.of("invisibility.breaks-on-damage", Codec.BOOLEAN, true);
    /** An invisible member leaves a footstep particle a step (captured: 1.8's {@code footstep}, mycelium on a modern client). */
    public static final StateKey<Boolean> FOOTPRINTS = StateKey.of("invisibility.footprints", Codec.BOOLEAN, true);

    private static final int FOOTSTEP_1_8 = 28; // EnumParticleTypes.FOOTSTEP: no newer protocol has it, so a 1.8 viewer takes it through ViaBridge
    private static final Particle MYCELIUM = Particle.fromKey(Key.key("minecraft:mycelium"));

    private static final Set<EquipmentSlot> WORN = EnumSet.of(EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE,
            EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS);
    private static final Tag<Boolean> UNSEEN = Tag.Transient("tennacle:unseen");
    private static boolean hooked;

    private final Function<Game, Component> lost;
    private Game game;

    private Invisibles(Function<Game, Component> lost) {
        this.lost = lost;
    }

    /** {@code lost} is the mode's line to a member whose hit broke the effect. */
    public static @NotNull Invisibles of(@NotNull Function<Game, Component> lost) {
        return new Invisibles(lost);
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(HIDES_ARMOR, HIDES_NAME, BREAKS_ON_DAMAGE, FOOTPRINTS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        hook();
        // both events fire before the effect list changes, so the viewers' copies read a mark set here, not the list
        game.on(EntityPotionAddEvent.class, EntityPotionAddEvent::getEntity, e -> {
            if (e.getPotion().effect() == PotionEffect.INVISIBILITY && e.getEntity() instanceof Player p) changed(p, true);
        });
        game.on(EntityPotionRemoveEvent.class, EntityPotionRemoveEvent::getEntity, e -> {
            if (e.getPotion().effect() == PotionEffect.INVISIBILITY && e.getEntity() instanceof Player p) changed(p, false);
        });
        // the user (2026-09-28): fire and an enemy's hit, blast or arrow break it; a fall, your own blast or a teammate's do not
        // captured: footstep x1 at the feet plus 0.1, spread 0.3/0/0.3, speed 1, long-distance, every step, the walker's own eyes too
        game.on(FxEvent.class, e -> e.context().source(), e -> {
            if (!Fx.STEP.equals(e.key()) || !(e.context().source() instanceof Player walker)) return;
            if (!Boolean.TRUE.equals(walker.getTag(UNSEEN)) || game.roster().slotOf(walker) == null) return;
            if (!game.rules().resolve(walker, FOOTPRINTS)) return;
            footprint(walker, e.context().position());
        });
        // damage dealt, not a swing: a hit in the hurt window or one armor eats whole leaves the effect (the user)
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || e.dealt() <= 0) return;
            if (!(e.target() instanceof Player victim) || game.roster().slotOf(victim) == null) return;
            if (!victim.hasEffect(PotionEffect.INVISIBILITY) || !game.rules().resolve(victim, BREAKS_ON_DAMAGE)) return;
            boolean fire = e.type() != null && e.type().protectionCategories().contains(ProtectionCategory.FIRE);
            Player by = FriendlyFire.attributed(e.source());
            boolean enemy = by != null && by != victim && !sameSide(game, by, victim);
            if (!fire && !enemy) return;
            victim.removeEffect(PotionEffect.INVISIBILITY);
            victim.sendMessage(lost.apply(game));
        });
    }

    // the viewers' copies of the armor go out again: the per-viewer view below decides what each sees
    private void changed(Player p, boolean invisible) {
        if (game.roster().slotOf(p) == null) return;
        p.setTag(UNSEEN, invisible);
        // the four worn slots alone (captured): the hands ride their own updates, and the drink's is still under way
        Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : WORN) worn.put(slot, p.getEquipment(slot));
        p.sendPacketToViewers(new EntityEquipmentPacket(p.getEntityId(), worn));
        Nametags tags = game.module(Nametags.class);
        if (tags != null && game.rules().resolve(p, HIDES_NAME)) tags.veil(p, invisible);
    }

    private static void footprint(Player walker, Point at) {
        Set<Player> eyes = new HashSet<>(walker.getViewers());
        eyes.add(walker);
        Vec spot = new Vec(at.x(), at.y() + 0.1, at.z());
        for (Player viewer : eyes) {
            if (Mechanics.polyp.clientInfo().isLegacy(viewer) && ViaBridgeRpc.isInstalled() && ViaBridgeRpc.available(viewer)) {
                ViaBridgeRpc.get().sendLevelParticles18(viewer, FOOTSTEP_1_8, true, (float) spot.x(), (float) spot.y(), (float) spot.z(),
                        0.3f, 0f, 0.3f, 1f, 1);
            } else {
                viewer.sendPacket(new ParticlePacket(MYCELIUM, true, true, spot, new Vec(0.3, 0, 0.3), 1f, 1));
            }
        }
    }

    private static boolean sameSide(Game game, Player a, Player b) {
        String side = Match.teamOf(game, a);
        return !side.isEmpty() && side.equals(Match.teamOf(game, b));
    }

    private static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ViewRewrites.register(Invisibles::armorForTheEnemy, EntityEquipmentPacket.class);
    }

    // a member's shard answers for no entity by id: the game's own instance does
    private static SendablePacket armorForTheEnemy(OptimizedPlayer viewer, SendablePacket packet) {
        if (!(packet instanceof EntityEquipmentPacket worn)) return packet;
        Game game = Game.of(viewer);
        if (game == null || game.module(Invisibles.class) == null) return packet;
        if (!(game.instance().getEntityById(worn.entityId()) instanceof Player subject) || subject == viewer) return packet;
        if (!Boolean.TRUE.equals(subject.getTag(UNSEEN)) || Game.of(subject) != game) return packet;
        if (!game.rules().resolve(subject, HIDES_ARMOR) || sameSide(game, viewer, subject)) return packet;
        Map<EquipmentSlot, ItemStack> shown = new EnumMap<>(EquipmentSlot.class);
        boolean stripped = false;
        for (Map.Entry<EquipmentSlot, ItemStack> entry : worn.equipments().entrySet()) {
            boolean hide = WORN.contains(entry.getKey()) && !entry.getValue().isAir();
            shown.put(entry.getKey(), hide ? ItemStack.AIR : entry.getValue());
            stripped |= hide;
        }
        return stripped ? new EntityEquipmentPacket(worn.entityId(), shown) : packet;
    }
}
