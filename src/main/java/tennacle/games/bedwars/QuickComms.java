package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.shop.ItemCurrency;
import tennacle.looks.Palette;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quick Communications (captured 2026-09-27): a message a click, told to the team; an attack or resource one first
 * asks which. Most also point the sender's compass, at their base, the team or the resource.
 */
final class QuickComms {

    private enum Aim { NONE, BASE, TEAM, RESOURCE }

    private enum Say {
        HELLO(10, Material.BOOK, "Hello ( ﾟ◡ﾟ)/!", Aim.NONE, ""),
        BACK(11, Material.BOOK, "I'm coming back to base!", Aim.BASE, ""),
        DEFENDING(12, Material.IRON_BARS, "I'm defending!", Aim.BASE, ""),
        ATTACKING(13, Material.IRON_SWORD, "I'm attacking!", Aim.TEAM, "I'm attacking "),
        COLLECTING(14, Material.DIAMOND, "I'm collecting resources!", Aim.RESOURCE, "I'm collecting "),
        HAVE(15, Material.CHEST, "I have resources!", Aim.RESOURCE, "I have "),
        THANKS(20, Material.BOOK, "Thank You!", Aim.NONE, ""),
        GET_BACK(21, Material.BOOK, "Get back to base!", Aim.BASE, ""),
        PLEASE_DEFEND(22, Material.IRON_BARS, "Please defend!", Aim.BASE, ""),
        LETS_ATTACK(23, Material.IRON_SWORD, "Let's attack!", Aim.TEAM, "Let's attack "),
        NEED(24, Material.DIAMOND, "We need resources!", Aim.RESOURCE, "We need "),
        INCOMING(25, Material.FEATHER, "Player incoming!!", Aim.NONE, "");

        final int slot;
        final Material icon;
        final String title;
        final Aim aim;
        /** What the message says before the team or resource picked. */
        final String lead;

        Say(int slot, Material icon, String title, Aim aim, String lead) {
            this.slot = slot;
            this.icon = icon;
            this.title = title;
            this.aim = aim;
            this.lead = lead;
        }
    }

    private record Resource(int slot, ItemCurrency currency) {}

    private static final List<Resource> RESOURCES = List.of(new Resource(10, Resources.DIAMOND),
            new Resource(12, Resources.IRON), new Resource(14, Resources.GOLD), new Resource(16, Resources.EMERALD));

    private QuickComms() {}

    static void open(Game game, Player viewer) {
        Screen.Builder window = Screen.rows(5).title(Component.text("Quick Communications"));
        for (Say say : Say.values()) window.button(say.slot, icon(say), (v, click) -> pick(game, v, say, click));
        window.button(40, TrackerShop.back("To Tracker & Communication"), (v, click) -> Compass.menu(game, v));
        Screens.open(viewer, window.build());
    }

    private static ItemStack icon(Say say) {
        List<Component> lore = new ArrayList<>();
        if (say.aim == Aim.TEAM) lore.add(ItemShop.gray("You will be able to select the Team."));
        if (say.aim == Aim.RESOURCE) lore.addAll(ItemShop.gray("You will be able to select the", "Resource."));
        lore.add(Component.empty());
        lore.add(Component.text("Click to send!", NamedTextColor.YELLOW));
        return Items.bare(Items.lore(Items.named(say.icon, Component.text(say.title, NamedTextColor.GREEN)), lore));
    }

    private static void pick(Game game, Player viewer, Say say, Click click) {
        if (click instanceof Click.Double) return;
        switch (say.aim) {
            case TEAM -> teams(game, viewer, say);
            case RESOURCE -> resources(game, viewer, say);
            default -> {
                viewer.closeInventory();
                // captured: the name less one trailing "!"
                Component message = Lines.said(game, say.title.substring(0, say.title.length() - 1), Component.empty());
                boolean base = say.aim == Aim.BASE;
                send(game, viewer, message, base ? Compass.base(game, Match.teamOf(game, viewer)) : null,
                        base ? Lines.said(game, "your base ", Component.empty()) : null);
            }
        }
    }

    private static void teams(Game game, Player viewer, Say say) {
        Screen.Builder window = Screen.rows(4).title(Component.text("Select an option: "));
        int slot = 10;
        for (Team team : Compass.enemies(game, viewer)) {
            Component name = Component.text(Palette.name(team), team.color(), TextDecoration.BOLD);
            ItemStack wool = ItemStack.of(team.livery().get(Wares.WOOL).material());
            window.button(slot++, option(wool, say.lead, name), (v, click) -> {
                if (click instanceof Click.Double) return;
                v.closeInventory();
                send(game, v, Lines.said(game, say.lead, name), Compass.base(game, team.id()),
                        Lines.said(game, Palette.titled(team) + " ", Component.empty()));
            });
        }
        window.button(31, TrackerShop.back("To Quick Communications"), (v, click) -> open(game, v));
        Screens.open(viewer, window.build());
    }

    private static void resources(Game game, Player viewer, Say say) {
        Screen.Builder window = Screen.rows(4).title(Component.text("Select an option: "));
        for (Resource resource : RESOURCES) {
            ItemCurrency currency = resource.currency();
            Component name = Component.text(currency.singular().toUpperCase(Locale.ROOT), currency.color(), TextDecoration.BOLD);
            window.button(resource.slot(), option(ItemStack.of(currency.material()), say.lead, name), (v, click) -> {
                if (click instanceof Click.Double) return;
                v.closeInventory();
                send(game, v, Lines.said(game, say.lead, name), at(game, v, currency),
                        Component.text(currency.singular() + " ", currency.color(), TextDecoration.BOLD));
            });
        }
        window.button(31, TrackerShop.back("To Quick Communications"), (v, click) -> open(game, v));
        Screens.open(viewer, window.build());
    }

    // captured, less Hypixel's closing quote taking the resource's color
    private static ItemStack option(ItemStack icon, String lead, Component what) {
        Component message = Component.text(lead, NamedTextColor.GREEN).append(what);
        List<Component> lore = List.of(
                ItemShop.gray("Click to send the message: \"").append(message).append(ItemShop.gray("\"")),
                ItemShop.gray("to your teammates!"),
                Component.empty(),
                Component.text("Click to send!", NamedTextColor.YELLOW));
        return Items.bare(Items.lore(Items.named(icon, message), lore));
    }

    // captured: the sender reads where the compass went just before the team hears the message
    private static void send(Game game, Player sender, Component message, @Nullable Pos at, @Nullable Component what) {
        Compass compass = game.module(Compass.class);
        if (at != null && what != null && compass != null && compass.pin(sender, at)) {
            sender.sendMessage(Lines.targeting(game, what));
            sender.playSound(Lines.ORB);
        }
        Component line = Lines.teamSays(game, sender.getUsername(), message);
        String team = Match.teamOf(game, sender);
        for (Player member : game.members()) {
            if (!team.equals(Match.teamOf(game, member))) continue;
            member.sendMessage(line);
            member.playSound(Lines.ORB);
        }
    }

    // iron and gold at the side's own forge, diamonds and emeralds at the nearest generator
    private static @Nullable Pos at(Game game, Player player, ItemCurrency currency) {
        String team = Match.teamOf(game, player);
        if (currency == Resources.IRON || currency == Resources.GOLD) {
            PointMarker forge = currency == Resources.IRON ? game.arena().marker(BedWars.FORGE_IRON, team) : null;
            if (forge == null) forge = game.arena().marker(BedWars.FORGE, team);
            return forge != null ? new Pos(forge.point()) : null;
        }
        MarkerKey<PointMarker> kind = currency == Resources.DIAMOND ? BedWars.DIAMOND : BedWars.EMERALD;
        PointMarker nearest = null;
        for (PointMarker marker : game.arena().markers(kind)) {
            if (nearest == null || marker.point().distanceSquared(player.getPosition())
                    < nearest.point().distanceSquared(player.getPosition())) nearest = marker;
        }
        return nearest != null ? new Pos(nearest.point()) : null;
    }
}
