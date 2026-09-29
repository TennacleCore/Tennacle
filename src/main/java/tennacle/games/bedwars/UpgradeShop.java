package tennacle.games.bedwars;

import tennacle.games.Throttle;
import tennacle.games.Match;
import tennacle.games.bedwars.Upgrades.Upgrade;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Rank;
import tennacle.games.shop.Refusal;
import tennacle.games.shop.Sale;
import tennacle.games.shop.Shop;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * The Upgrades & Traps window (captured 2026-09-24 to 27): the upgrades and traps over a row of panes, the team's
 * trap queue under them. A purchase is told to the whole team and repaints the window; a right click on your own
 * queued trap takes it out, no refund.
 */
final class UpgradeShop {

    // captured: clicks on upgrades and traps 101 and 200 ms apart refused, 299 ms through
    private static final long GAP = 6;
    private static final StateKey<Long> CLICKED = StateKey.of("ledger.bedwars.upgrade-click", Codec.LONG, -100L, true);
    private static final int[] QUEUE = {39, 40, 41};
    private static final String[] ORDINAL = {"first", "second", "third"};

    private UpgradeShop() {}

    static void open(Game game, Player viewer) {
        Screen.Builder window = Screen.rows(6).title(Component.text("Upgrades & Traps"));
        for (Upgrade upgrade : Upgrades.ALL) {
            window.button(upgrade.slot(), v -> icon(game, v, upgrade),
                    (v, click) -> buy(game, v, upgrade.offer(), name(game, v, upgrade), click));
        }
        for (Traps.Kind kind : Traps.Kind.values()) {
            Offer offer = game.module(Shop.class).offer(kind.id);
            window.button(kind.slot, v -> icon(game, v, kind), (v, click) -> buy(game, v, offer, kind.title, click));
        }
        ItemStack pane = Items.lore(Items.named(Material.GRAY_STAINED_GLASS_PANE, Component.text("⬆ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Purchasable", NamedTextColor.GRAY))),
                List.of(Component.text("⬇ ", NamedTextColor.DARK_GRAY).append(Component.text("Traps Queue", NamedTextColor.GRAY))));
        for (int i = 27; i < 36; i++) window.item(i, pane);
        for (int i = 0; i < QUEUE.length; i++) {
            int index = i;
            window.button(QUEUE[i], v -> queued(game, v, index), (v, click) -> {
                if (click instanceof Click.Right && Traps.remove(game, v, index)) Screens.repaint(v);
            });
        }
        Screens.open(viewer, window.build());
    }

    // the tier on sale, or the last when all are bought
    private static String name(Game game, Player viewer, Upgrade upgrade) {
        Rank rank = upgrade.offer();
        int tier = Math.max(1, Math.min(rank.selling(game, viewer), upgrade.names().size()));
        return upgrade.names().get(tier - 1);
    }

    private static ItemStack icon(Game game, Player viewer, Upgrade upgrade) {
        Rank rank = upgrade.offer();
        int bought = rank.current(game, viewer);
        boolean maxed = rank.refusal(game, viewer) == Refusal.OWNED;
        Price price = rank.price(game, viewer);
        boolean affordable = held(viewer, price);
        List<Component> lore = new ArrayList<>(ItemShop.gray(upgrade.description().toArray(String[]::new)));
        lore.add(Component.empty());
        if (upgrade.single()) {
            lore.add(ItemShop.gray("Cost: ").append(diamonds(price)));
        } else {
            List<Price> prices = game.rules().resolve(viewer, rank.pricesRule());
            for (int tier = 1; tier <= upgrade.tiers().size(); tier++) {
                NamedTextColor done = tier <= bought ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                Price at = prices.get(Math.min(tier, prices.size()) - 1);
                lore.add(Component.text("Tier " + tier + ": " + upgrade.tiers().get(tier - 1), done)
                        .append(ItemShop.gray(", ")).append(diamonds(at)));
            }
        }
        lore.add(Component.empty());
        NamedTextColor color = maxed ? NamedTextColor.GREEN : affordable ? NamedTextColor.YELLOW : NamedTextColor.RED;
        lore.add(maxed ? Component.text("UNLOCKED", NamedTextColor.GREEN)
                : affordable ? Component.text("Click to purchase!", NamedTextColor.YELLOW)
                : Component.text("You don't have enough Diamonds!", NamedTextColor.RED));
        ItemStack stack = Items.counted(ItemStack.of(upgrade.icon()), bought);
        return Items.bare(Items.lore(Items.named(stack, Component.text(name(game, viewer, upgrade), color)), lore));
    }

    private static ItemStack icon(Game game, Player viewer, Traps.Kind kind) {
        Offer offer = game.module(Shop.class).offer(kind.id);
        Price price = offer.price(game, viewer);
        boolean bed = offer.refusal(game, viewer) != Traps.NO_BED;
        boolean affordable = held(viewer, price);
        List<Component> lore = new ArrayList<>(ItemShop.gray(kind.description.toArray(String[]::new)));
        lore.add(Component.empty());
        lore.add(ItemShop.gray("Cost: ").append(diamonds(price)));
        lore.add(Component.empty());
        lore.add(!bed ? Component.text("Can't be purchased without a bed!", NamedTextColor.RED)
                : affordable ? Component.text("Click to purchase!", NamedTextColor.YELLOW)
                : Component.text("You don't have enough Diamonds!", NamedTextColor.RED));
        NamedTextColor color = bed && affordable ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Items.bare(Items.lore(Items.named(kind.icon, Component.text(kind.title, color)), lore));
    }

    private static ItemStack queued(Game game, Player viewer, int index) {
        List<Traps.Queued> queue = Traps.queue(game, Match.teamOf(game, viewer));
        String first = ORDINAL[index];
        if (index < queue.size()) {
            Traps.Queued trap = queue.get(index);
            List<Component> lore = new ArrayList<>(ItemShop.gray(trap.kind().description.toArray(String[]::new)));
            lore.add(Component.empty());
            lore.add(ItemShop.gray("The " + first + " enemy to walk into your"));
            lore.add(ItemShop.gray("base will trigger this trap!"));
            lore.add(Component.empty());
            lore.add(ItemShop.gray("Queued by: ").append(Component.text(trap.by(), NamedTextColor.AQUA)));
            lore.add(Component.empty());
            lore.add(Component.text("Right Click to remove! ", NamedTextColor.YELLOW)
                    .append(Component.text("(No refund)", NamedTextColor.DARK_GRAY)));
            ItemStack stack = Items.counted(ItemStack.of(trap.kind().icon), index + 1);
            return Items.bare(Items.lore(Items.named(stack,
                    Component.text("Trap #" + (index + 1) + ": " + trap.kind().title, NamedTextColor.GREEN)), lore));
        }
        List<Price> prices = game.rules().resolve(viewer, Traps.PRICES);
        List<Component> lore = new ArrayList<>(ItemShop.gray("The " + first + " enemy to walk into your", "base will trigger this trap!"));
        lore.add(Component.empty());
        lore.addAll(ItemShop.gray("Purchasing a trap will queue it here.", "Its cost will scale based on the", "number of traps queued."));
        lore.add(Component.empty());
        lore.add(ItemShop.gray("Next trap: ").append(diamonds(prices.get(Math.min(queue.size(), prices.size() - 1)))));
        ItemStack stack = Items.counted(ItemStack.of(Material.LIGHT_GRAY_STAINED_GLASS), index + 1);
        return Items.lore(Items.named(stack, Component.text("Trap #" + (index + 1) + ": No Trap!", NamedTextColor.RED)), lore);
    }

    private static void buy(Game game, Player viewer, Offer offer, String name, Click click) {
        if (click instanceof Click.Double) return;
        Slot seat = game.roster().slotOf(viewer);
        if (seat == null) return;
        if (!Throttle.fixed(game, CLICKED, GAP).pass(viewer, left -> {
            viewer.sendMessage(Lines.tooFast(game));
            viewer.playSound(Lines.REFUSED);
            viewer.closeInventory();
        })) return;
        Sale sale = game.module(Shop.class).buy(viewer, offer);
        switch (sale) {
            case Sale.Sold sold -> {
                Component line = Lines.teamPurchased(game, viewer.getUsername(), name);
                String team = Match.teamOf(game, viewer);
                for (Player member : game.members()) {
                    if (team.equals(Match.teamOf(game, member))) member.sendMessage(line);
                }
                viewer.playSound(Lines.BOUGHT);
            }
            case Sale.Short ignored -> refuse(viewer, Lines.upgradeShort(game));
            case Sale.Refused refused -> {
                Refusal why = refused.refusal();
                if (why == Refusal.OWNED) refuse(viewer, Lines.maxed(game));
                else if (why == Traps.QUEUE_FULL) refuse(viewer, Lines.queueFull(game));
                else if (why == Traps.NO_BED) refuse(viewer, Lines.noBed(game));
            }
        }
        Screens.repaint(viewer);
    }

    private static void refuse(Player viewer, Component line) {
        viewer.sendMessage(line);
        viewer.playSound(Lines.REFUSED);
    }

    private static Component diamonds(Price price) {
        return Component.text(price.amount() + " " + price.currency().name(price.amount()), price.currency().color());
    }

    private static boolean held(Player viewer, Price price) {
        return price.currency().held(viewer) >= price.amount();
    }
}
