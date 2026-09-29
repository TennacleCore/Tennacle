package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.beds.Beds;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.space.Regions;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static tennacle.games.bedwars.Resources.DIAMOND;

/**
 * The trap queue (captured 2026-09-24 and 25): up to three a team, first in first out, the next costing more the
 * more are queued. The first enemy into the ball around a standing bed sets off the head of the queue; the next
 * goes off no sooner than the cooldown after, on whoever is inside by then.
 */
final class Traps implements GameModule {

    enum Kind {
        BLINDNESS("blindness-trap", "Blindness Trap", 14, Material.TRIPWIRE_HOOK,
                List.of("Inflicts Blindness and Slowness for", "8 seconds.")),
        COUNTER("counter-offensive-trap", "Counter-Offensive Trap", 15, Material.FEATHER,
                List.of("Grants Speed II and Jump Boost II", "for 15 seconds to allied players", "near your base.")),
        REVEAL("reveal-trap", "Reveal Trap", 16, Material.REDSTONE_TORCH,
                List.of("Reveals invisible players as well as", "their name and team.")),
        MINER("miner-fatigue-trap", "Miner Fatigue Trap", 23, Material.IRON_PICKAXE,
                List.of("Inflict Mining Fatigue for 8 seconds."));

        final String id;
        final String title;
        final int slot;
        final Material icon;
        final List<String> description;

        Kind(String id, String title, int slot, Material icon, List<String> description) {
            this.id = id;
            this.title = title;
            this.slot = slot;
            this.icon = icon;
            this.description = description;
        }
    }

    /** One queued trap and who bought it. */
    record Queued(@NotNull Kind kind, @NotNull String by) {}

    /** Each team's queue, head first, as {@code kind|buyer}. */
    static final StateKey<List<String>> QUEUE = StateKey.of("ledger.bedwars.traps", Codec.STRING.list(), List.of(), true);
    private static final StateKey<Long> READY = StateKey.of("ledger.bedwars.trap-ready", Codec.LONG, 0L, true);
    /** A seat's Magic Milk: no trap takes it before this tick. */
    static final StateKey<Long> IMMUNE = StateKey.of("ledger.bedwars.trap-immune", Codec.LONG, 0L, true);
    /** The next trap's price by how many are queued. */
    static final StateKey<List<Price>> PRICES = StateKey.of("shop.trap", Price.CODEC.list(),
            List.of(Price.of(1, DIAMOND), Price.of(2, DIAMOND), Price.of(4, DIAMOND)));
    static final StateKey<Long> COOLDOWN = StateKey.of("traps.cooldown", Codec.LONG, 600L);
    /** Read as the game starts. */
    static final StateKey<Double> RADIUS = StateKey.of("traps.radius", Codec.DOUBLE, 12.0);
    static final int MAX = 3;
    /** What a trap does to an intruder, and what Magic Milk washes off. */
    static final Set<PotionEffect> INFLICTS = Set.of(PotionEffect.BLINDNESS, PotionEffect.SLOWNESS, PotionEffect.MINING_FATIGUE);

    static final Refusal QUEUE_FULL = new Refusal("queue-full");
    static final Refusal NO_BED = new Refusal("no-bed");

    private static final String ALARM = "traps:";

    private final Map<String, Regions.Area> areas = new HashMap<>();
    private final Map<String, Point> beds = new HashMap<>();
    private Game game;
    private Regions regions;

    static @NotNull List<Offer> offers() {
        List<Offer> out = new ArrayList<>();
        for (Kind kind : Kind.values()) out.add(new TrapOffer(kind));
        return out;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(COOLDOWN, RADIUS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        regions = game.module(Regions.class);
        if (regions == null) throw new IllegalStateException("Traps needs Regions installed before it");
        double radius = game.rules().resolve(Scope.GAME, RADIUS);
        for (Team team : game.roster().teams()) {
            Point head = Beds.head(game, game.arena().marker(BedWars.BED, team.id()));
            if (head == null) continue;
            Regions.Area area = Regions.Area.ball(head, radius);
            areas.put(team.id(), area);
            beds.put(team.id(), head);
            regions.watch(area, player -> entered(team.id(), player), null);
            game.state().alarms().register(ALARM + team.id(), (tick, payload) -> again(team.id()));
            game.state().alarms().register(ALARM + "bell:" + team.id(), (tick, payload) -> game.forEachAudience(viewer ->
                    viewer.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f,
                            payload.getFloat("pitch")), head.x(), head.y(), head.z())));
        }
    }

    static @NotNull List<Queued> queue(@NotNull Game game, @NotNull String team) {
        List<Queued> out = new ArrayList<>();
        for (String entry : game.state().get(Scope.team(team), QUEUE)) {
            int bar = entry.indexOf('|');
            if (bar < 0) continue;
            try {
                out.add(new Queued(Kind.valueOf(entry.substring(0, bar)), entry.substring(bar + 1)));
            } catch (IllegalArgumentException ignored) {
                // a kind no longer sold
            }
        }
        return out;
    }

    private static void store(Game game, String team, List<Queued> queue) {
        game.state().set(Scope.team(team), QUEUE, queue.stream().map(q -> q.kind().name() + "|" + q.by()).toList());
    }

    /** Takes {@code player}'s own trap at {@code index} out of their team's queue; false when it is not theirs. */
    static boolean remove(@NotNull Game game, @NotNull Player player, int index) {
        String team = Match.teamOf(game, player);
        List<Queued> queue = new ArrayList<>(queue(game, team));
        if (index >= queue.size() || !queue.get(index).by().equals(player.getUsername())) return false;
        Queued gone = queue.remove(index);
        store(game, team, queue);
        player.sendMessage(Lines.trapRemoved(game, gone.kind().title));
        return true;
    }

    /** {@code player} as if they just walked in wherever they stand. */
    void recheck(@NotNull Player player) {
        areas.forEach((team, area) -> {
            if (regions.inside(area).contains(player)) entered(team, player);
        });
    }

    private void entered(String team, Player player) {
        if (!intruder(team, player) || queue(game, team).isEmpty()) return;
        long ready = game.state().get(Scope.team(team), READY);
        if (game.tick() < ready) game.state().alarms().arm(ALARM + team, ready);
        else fire(team, player);
    }

    // off cooldown with someone still inside: the one nearest the bed
    private void again(String team) {
        if (queue(game, team).isEmpty() || game.tick() < game.state().get(Scope.team(team), READY)) return;
        Point bed = beds.get(team);
        Player nearest = null;
        for (Player player : regions.inside(areas.get(team))) {
            if (!intruder(team, player)) continue;
            if (nearest == null || player.getPosition().distanceSquared(bed) < nearest.getPosition().distanceSquared(bed)) {
                nearest = player;
            }
        }
        if (nearest != null) fire(team, nearest);
    }

    private boolean intruder(String team, Player player) {
        Slot seat = game.roster().slotOf(player);
        return seat != null && !seat.team().equals(team) && !game.isGhost(player)
                && player.getGameMode() != GameMode.SPECTATOR && Beds.stands(game, team)
                && game.tick() >= game.state().get(Scope.slot(seat.index()), IMMUNE);
    }

    private void fire(String team, Player intruder) {
        List<Queued> queue = new ArrayList<>(queue(game, team));
        if (queue.isEmpty()) return;
        Queued trap = queue.removeFirst();
        store(game, team, queue);
        long ready = game.tick() + game.rules().resolve(Scope.GAME, COOLDOWN);
        game.state().set(Scope.team(team), READY, ready);
        if (!queue.isEmpty()) game.state().alarms().arm(ALARM + team, ready); // captured: whoever stayed in takes the next
        switch (trap.kind()) {
            // captured
            case BLINDNESS -> {
                intruder.addEffect(new Potion(PotionEffect.BLINDNESS, 1, 160));
                intruder.addEffect(new Potion(PotionEffect.SLOWNESS, 0, 100));
            }
            case MINER -> intruder.addEffect(new Potion(PotionEffect.MINING_FATIGUE, 0, 160));
            case COUNTER -> {
                for (Player ally : regions.inside(areas.get(team))) {
                    if (!team.equals(Match.teamOf(game, ally)) || game.isGhost(ally)) continue;
                    ally.addEffect(new Potion(PotionEffect.SPEED, 1, 300));
                    ally.addEffect(new Potion(PotionEffect.JUMP_BOOST, 1, 300));
                }
            }
            case REVEAL -> intruder.removeEffect(PotionEffect.INVISIBILITY);
        }
        if (trap.kind() == Kind.REVEAL) alarm(team, intruder);
        else setOff(team, trap.kind());
    }

    // captured: the owners read it, the intruder only feels it
    private void setOff(String team, Kind kind) {
        Title title = Lines.trapTriggered(game, kind.title);
        Component line = Lines.trapSetOff(game, kind.title);
        forTeam(team, member -> {
            member.showTitle(title);
            member.sendMessage(line);
            member.playSound(Lines.REFUSED);
        });
    }

    // captured: the ALARM title and line name the intruder's team, then a bell rings at the bed for 5 s
    private void alarm(String team, Player intruder) {
        Team theirs = game.roster().team(Match.teamOf(game, intruder));
        String name = theirs != null ? tennacle.looks.Palette.titled(theirs) : "";
        TextColor color = theirs != null ? theirs.color() : NamedTextColor.WHITE;
        Title title = Lines.alarm(game, name, color);
        Component line = Lines.alarmLine(game, intruder.getUsername(), name, color);
        forTeam(team, member -> {
            member.showTitle(title);
            member.sendMessage(line);
            member.playSound(Lines.REFUSED);
        });
        for (int note = 0; note <= 50; note++) {
            float pitch = (note % 2 == 0 ? 107 : 113) / 63f; // captured pitch bytes
            game.state().alarms().arm(ALARM + "bell:" + team, game.tick() + 2L * note + 1,
                    CompoundBinaryTag.builder().putFloat("pitch", pitch).build());
        }
    }

    private void forTeam(String team, java.util.function.Consumer<Player> action) {
        for (Player member : game.members()) {
            if (team.equals(Match.teamOf(game, member))) action.accept(member);
        }
    }

    /** A trap bought into the buyer's team's queue. */
    record TrapOffer(@NotNull Kind kind) implements Offer {

        @Override
        public @NotNull String id() {
            return kind.id;
        }

        @Override
        public @NotNull Set<StateKey<?>> rules() {
            return Set.of(PRICES);
        }

        @Override
        public @NotNull Price price(@NotNull Game game, @NotNull Player buyer) {
            List<Price> prices = game.rules().resolve(buyer, PRICES);
            int queued = queue(game, Match.teamOf(game, buyer)).size();
            return prices.get(Math.min(queued, prices.size() - 1));
        }

        @Override
        public @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer) {
            String team = Match.teamOf(game, buyer);
            if (!Beds.stands(game, team)) return NO_BED;
            return queue(game, team).size() >= MAX ? QUEUE_FULL : null;
        }

        @Override
        public void grant(@NotNull Game game, @NotNull Player buyer) {
            String team = Match.teamOf(game, buyer);
            List<Queued> queue = new ArrayList<>(queue(game, team));
            queue.add(new Queued(kind, buyer.getUsername()));
            store(game, team, queue);
            Traps traps = game.module(Traps.class);
            if (traps != null) traps.again(team); // bought with an intruder inside: it goes off at once (captured)
        }
    }
}
