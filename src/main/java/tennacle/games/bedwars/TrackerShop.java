package tennacle.games.bedwars;

import tennacle.games.TeamBlocks;

import tennacle.games.Match;
import tennacle.games.beds.Beds;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;
import tennacle.games.shop.Sale;
import tennacle.games.shop.Shop;
import tennacle.looks.Palette;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Purchase Enemy Tracker (captured 2026-09-24 and 27): a wool per enemy side still in, sold once every enemy bed is
 * gone. Every click closes the window.
 */
final class TrackerShop {

    static final Refusal BEDS_STAND = new Refusal("beds-stand");
    /** One team at a time (the user, 2026-09-28); tracking several, or a cheaper swap, is for later. Hypixel sells the
     *  tracked team again and charges for it, a bug of theirs kept out (the user, 2026-09-29). */
    static final Refusal TRACKING = new Refusal("tracking");

    private TrackerShop() {}

    static void open(Game game, Player viewer, boolean fromCompass) {
        Screen.Builder window = Screen.rows(4).title(Component.text("Purchase Enemy Tracker"));
        int slot = 10;
        for (Team team : Compass.enemies(game, viewer)) {
            window.button(slot++, v -> icon(game, v, team), (v, click) -> buy(game, v, team, click));
        }
        window.button(31, back(fromCompass ? "To Tracker & Communication" : "To Quick Buy"), (v, click) -> {
            if (fromCompass) Compass.menu(game, v);
            else ItemShop.open(game, v, Wares.Tab.QUICK_BUY);
        });
        Screens.open(viewer, window.build());
    }

    static ItemStack back(String to) {
        return Items.bare(Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)),
                List.of(Items.gray(to))));
    }

    private static ItemStack icon(Game game, Player viewer, Team team) {
        Tracker tracker = new Tracker(team.id());
        Price price = tracker.price(game, viewer);
        boolean open = tracker.refusal(game, viewer) == null;
        boolean affordable = price.currency().held(viewer) >= price.amount();
        List<Component> lore = new ArrayList<>(Compass.trackerLore());
        lore.add(Component.empty());
        lore.add(Items.gray("Cost: ").append(Component.text(price.amount() + " " + price.currency().name(price.amount()),
                price.currency().color())));
        lore.add(Component.empty());
        lore.add(!open ? Component.text("Unlocks when all enemy beds are destroyed!", NamedTextColor.RED)
                : affordable ? Component.text("Click to purchase!", NamedTextColor.YELLOW)
                : Component.text("You don't have enough " + price.currency().name(price.amount()) + "!", NamedTextColor.RED));
        ItemStack wool = ItemStack.of(team.livery().get(TeamBlocks.WOOL).material());
        Component name = Component.text("Track Team " + Palette.titled(team), open && affordable ? NamedTextColor.GREEN : NamedTextColor.RED);
        return Items.bare(Items.lore(Items.named(wool, name), lore));
    }

    // captured: no sound either way
    private static void buy(Game game, Player viewer, Team team, Click click) {
        if (click instanceof Click.Double) return;
        viewer.closeInventory();
        Shop shop = game.module(Shop.class);
        if (shop == null) return;
        switch (shop.buy(viewer, new Tracker(team.id()))) {
            case Sale.Sold ignored -> {
                viewer.sendMessage(Lines.purchased(game, Palette.titled(team) + " Tracking"));
                viewer.sendMessage(Lines.trackerLost(game));
            }
            case Sale.Short missing -> viewer.sendMessage(Lines.notEnough(game, missing.price()));
            case Sale.Refused refused -> {
                if (refused.refusal() == BEDS_STAND) viewer.sendMessage(Lines.trackerLocked(game));
                if (refused.refusal() == TRACKING) viewer.sendMessage(Lines.trackingAlready(game));
            }
        }
    }

    /** The seat's compass on {@code team} until a death. */
    record Tracker(@NotNull String team) implements Offer {

        @Override
        public @NotNull String id() {
            return "tracker";
        }

        @Override
        public @NotNull Set<StateKey<?>> rules() {
            return Set.of(Compass.TRACKER_PRICE);
        }

        @Override
        public @NotNull Price price(@NotNull Game game, @NotNull Player buyer) {
            return game.rules().resolve(buyer, Compass.TRACKER_PRICE);
        }

        @Override
        public @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer) {
            String own = Match.teamOf(game, buyer);
            if (!Beds.standing(game).stream().allMatch(own::equals)) return BEDS_STAND;
            Slot seat = game.roster().slotOf(buyer);
            return seat != null && team.equals(game.state().get(Scope.slot(seat.index()), Compass.TRACKING)) ? TRACKING : null;
        }

        @Override
        public void grant(@NotNull Game game, @NotNull Player buyer) {
            Slot seat = game.roster().slotOf(buyer);
            Compass compass = game.module(Compass.class);
            if (seat == null || compass == null) return;
            game.state().set(Scope.slot(seat.index()), Compass.TRACKING, team);
            compass.aim(buyer);
        }
    }
}
