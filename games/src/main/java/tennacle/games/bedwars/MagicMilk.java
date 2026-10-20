package tennacle.games.bedwars;

import tennacle.games.stats.Tally;
import tennacle.games.lives.Lives;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.consume.ConsumeEvent;
import io.github.term4.polyp.mechanics.consumable.ConsumableBehavior;
import io.github.term4.polyp.mechanics.consumable.ConsumableConfigResolver.ConsumableContext;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Magic Milk (captured 2026-09-25): for a while after the drink no trap takes the drinker. The drinker alone is told
 * when it wears off, and an intruder still inside then sets the next trap off at once; a death ends it. It washes
 * off what traps inflict and keeps every other effect, as the docs say and the capture's Mining Fatigue agrees.
 */
final class MagicMilk implements GameModule {

    /** From the end of the drink. */
    static final StateKey<Long> DURATION = StateKey.of("magic-milk.duration", Codec.LONG, 600L);

    private static final String WORN = "magic-milk:worn";

    private Game game;
    private Traps traps;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(DURATION);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        traps = game.module(Traps.class);
        Lives lives = game.module(Lives.class);
        if (traps == null || lives == null) throw new IllegalStateException("MagicMilk needs Traps and Lives installed before it");
        ConsumableBehavior milk = new ConsumableBehavior() {
            @Override
            public void onFinish(ConsumableContext ctx) {
                drank(ctx.user());
            }
        };
        game.on(ConsumeEvent.class, ConsumeEvent::user, e -> {
            if (e.item().material() == Material.MILK_BUCKET) e.behavior(milk);
        });
        game.state().alarms().register(WORN, (tick, payload) -> worn(payload.getInt("slot"), payload.getLong("until")));
        lives.onDeath(death -> game.state().set(Scope.slot(death.seat().index()), Traps.IMMUNE, 0L));
    }

    void drank(Player player) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null) return;
        Tally.add(game, player, "magic-milk-drunk");
        for (PotionEffect effect : Traps.INFLICTS) player.removeEffect(effect);
        long until = game.tick() + game.rules().resolve(player, DURATION);
        game.state().set(Scope.slot(seat.index()), Traps.IMMUNE, until);
        game.state().alarms().arm(WORN, until, CompoundBinaryTag.builder()
                .putInt("slot", seat.index()).putLong("until", until).build());
    }

    // a later drink or a death moved the tick, and this one says nothing
    private void worn(int index, long until) {
        if (game.state().get(Scope.slot(index), Traps.IMMUNE) != until) return;
        Slot seat = game.roster().slot(index);
        String owner = seat != null ? game.roster().ownerOf(seat) : null;
        Player player = owner != null ? game.memberNamed(owner) : null;
        if (player == null) return;
        player.sendMessage(Lines.milkWoreOff(game));
        player.playSound(Lines.MILK_WORE_OFF);
        traps.recheck(player);
    }
}
