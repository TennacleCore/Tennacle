package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.shop.Price;
import tennacle.games.beds.Beds;
import tennacle.games.map.Building;
import tennacle.looks.Look;
import tennacle.looks.Palette;

import io.github.term4.canvas.hologram.Hologram;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.sound.SoundEvent;

import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Bed Wars' chat lines, titles and sounds, all captured on Hypixel: the parts the look's theme names (good, bad,
 * prose, accent) through it, the colors no role carries (a purchase's gold, a generator's aqua) as captured.
 */
final class Lines {

    private static final String[] NUMERALS = {"I", "II", "III"};

    // captured: volume 8, the pling at the 1.8 client's highest pitch and the portal at its lowest
    static final Sound BOUGHT = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 8f, 2f);
    static final Sound REFUSED = Sound.sound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 8f, 0.5f);
    /** Captured at the depositor's feet, not the chest. */
    static final Sound DEPOSIT = Sound.sound(SoundEvent.BLOCK_CHEST_CLOSE, Sound.Source.BLOCK, 1f, 1f);
    static final Sound ORB = Sound.sound(SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.PLAYER, 1f, 1f);
    /** Captured: 1.8's {@code liquid.water}. */
    static final Sound MILK_WORE_OFF = Sound.sound(SoundEvent.BLOCK_WATER_AMBIENT, Sound.Source.PLAYER, 0.7f, 2f);
    /** 1.8 mob.chicken.plop at each laid block. */
    static final Sound DRAGON_GROWL = Sound.sound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1f, 1f);

    private Lines() {}

    private static Theme theme(Game game) {
        return Matches.lookOf(game).theme();
    }

    /** Captured: aqua or dark green {@code <Kind> Generators }, yellow {@code have been upgraded to Tier }, a red numeral. */
    static void tierUp(Game game, Mid.Kind kind, int tier) {
        boolean diamond = kind == Mid.Kind.DIAMOND;
        Match.say(game, Component.text(diamond ? "Diamond Generators " : "Emerald Generators ",
                        diamond ? NamedTextColor.AQUA : NamedTextColor.DARK_GREEN)
                .append(theme(game).accent("have been upgraded to Tier "))
                .append(theme(game).bad(NUMERALS[Math.max(1, Math.min(3, tier)) - 1])));
    }

    // captured: 2.475 over the full-size stands Hypixel puts 0.4375, 0.15625 and -0.09375 over the keeper
    static List<Hologram.Line> shopLabels(Game game) {
        return List.of(new Hologram.Line(2.63125, Component.text("ITEM SHOP", NamedTextColor.AQUA)),
                new Hologram.Line(2.38125, rightClick(game)));
    }

    static List<Hologram.Line> upgradeLabels(Game game) {
        boolean solo = game.rules().resolve(Scope.GAME, Roster.TEAM_SIZE) == 1;
        return List.of(new Hologram.Line(2.9125, Component.text(solo ? "SOLO" : "TEAM", NamedTextColor.AQUA)),
                new Hologram.Line(2.63125, Component.text("UPGRADES", NamedTextColor.AQUA)),
                new Hologram.Line(2.38125, rightClick(game)));
    }

    private static Component rightClick(Game game) {
        return theme(game).accent("RIGHT CLICK").decorate(TextDecoration.BOLD);
    }

    /** Captured over a generator: yellow, then a red numeral. */
    static Component tier(Game game, int tier) {
        return theme(game).accent("Tier ").append(theme(game).bad(NUMERALS[Math.max(1, Math.min(3, tier)) - 1]));
    }

    static Component generator(Mid.Kind kind) {
        return kind == Mid.Kind.DIAMOND ? Component.text("Diamond", NamedTextColor.AQUA, TextDecoration.BOLD)
                : Component.text("Emerald", NamedTextColor.DARK_GREEN, TextDecoration.BOLD);
    }

    static Component spawnsIn(Game game, long seconds) {
        return theme(game).accent("Spawns in ").append(theme(game).bad(String.valueOf(seconds)))
                .append(theme(game).accent(seconds == 1 ? " second" : " seconds"));
    }

    /** Captured: red, in chat alone, five minutes before every bed goes. */
    static void bedsWarning(Game game) {
        Match.say(game, theme(game).bad("All beds will be destroyed in 5 minutes!"));
    }

    /** Captured: red SUDDEN DEATH:, gold +, aqua 1, the side's dragon in its color. */
    static Component suddenDeathDragon(Game game, Team team) {
        return theme(game).bad("SUDDEN DEATH: ")
                .append(Component.text("+", NamedTextColor.GOLD))
                .append(Component.text("1 ", NamedTextColor.AQUA))
                .append(Component.text(Palette.name(team) + " Dragon!", team.color()));
    }

    static Component moreDragons(Game game) {
        return Component.text("MORE DRAGONS HAVE SPAWNED!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);
    }

    /** Captured: red {@code Sudden Death} as a title, 10/30/10, no subtitle; the dragons are Creatures'. */
    static void suddenDeath(Game game) {
        Match.title(game, Title.title(theme(game).bad("Sudden Death"), Component.empty(), Match.times(game, 10, 30, 10)));
    }

    static Component purchased(Game game, String item) {
        return theme(game).good("You purchased ").append(Component.text(item, NamedTextColor.GOLD));
    }

    /** The team's line when one of them buys an upgrade or a trap. */
    static Component teamPurchased(Game game, String buyer, String item) {
        return theme(game).good(buyer + " purchased ").append(Component.text(item, NamedTextColor.GOLD));
    }

    static Component shortOf(Game game, Price price, int missing) {
        return theme(game).bad("You don't have enough " + price.currency().name(price.amount()) + "! Need " + missing + " more!");
    }

    static Component full(Game game) {
        return theme(game).bad("Purchase Failed! Your inventory is full!");
    }

    static Component owned(Game game) {
        return theme(game).bad("You've already purchased this item!");
    }

    static Component beyond(Game game) {
        return theme(game).bad("You already have a higher tier item.");
    }

    static List<Component> emptyQuickBuySlot(Game game) {
        return List.of(theme(game).bad("This is an empty Quick Buy slot!"),
                Component.text("Shift Click on an item in the rest of the shop to fill it!", NamedTextColor.AQUA));
    }

    static Component upgradeShort(Game game) {
        return theme(game).bad("You don't have enough resources to unlock that upgrade!");
    }

    static Component maxed(Game game) {
        return theme(game).bad("You have already maxed out this upgrade!");
    }

    static Component queueFull(Game game) {
        return theme(game).bad("Trap queue full!");
    }

    static Component noBed(Game game) {
        return theme(game).bad("Can't be purchased without a bed!");
    }

    static Component tooFast(Game game) {
        return theme(game).bad("Clicking too fast!");
    }

    static Component trapRemoved(Game game, String trap) {
        return theme(game).bad("Removed ").append(Component.text(trap, NamedTextColor.GOLD)).append(theme(game).bad(" from the queue!"));
    }

    /** Captured: the owners' title, a plain subtitle, 5/35/10. */
    static Title trapTriggered(Game game, String trap) {
        return Title.title(theme(game).bad("TRAP TRIGGERED!"), Component.text("Your " + trap + " has been set off!"),
                Match.times(game, 5, 35, 10));
    }

    static Component trapSetOff(Game game, String trap) {
        return theme(game).bad(trap + " was set off!").decorate(TextDecoration.BOLD);
    }

    /** Captured: the Reveal trap's title names the intruder's team in its color. */
    static Title alarm(Game game, String team, TextColor color) {
        return Title.title(theme(game).bad("ALARM!!!").decorate(TextDecoration.BOLD),
                Component.text("Reveal trap set off by ").append(Component.text(team + " ", color)).append(Component.text("team!")),
                Match.times(game, 5, 35, 10));
    }

    /** Captured: the zone lines, the floor's, and a map block's; the height line is the string catalog's, uncaptured. */
    static Component refusal(Game game, Player player, Refusals.Kind kind, @Nullable String reason) {
        return switch (kind) {
            case PLACE -> theme(game).bad(switch (reason == null ? "" : reason) {
                case Building.REASON_FLOOR -> "You cannot build any further down!";
                case Building.REASON_HEIGHT -> "Build height limit reached!";
                default -> "You can't place blocks here!";
            });
            case BREAK -> theme(game).bad(Beds.OWN.equals(reason) ? "You can't destroy your own bed!"
                    : Building.REASON_MAP.equals(reason) ? "You can only break blocks placed by a player!" : "You can't break blocks here!");
            case PROTECTED, HIT -> Look.refusal(game, player, kind, reason);
        };
    }

    static Component chestLocked(Game game, Team owner) {
        return lock(game, "You cannot open this Chest as the ", owner);
    }

    static Component depositLocked(Game game, Team owner) {
        return lock(game, "You cannot deposit into this chest as the ", owner);
    }

    private static Component lock(Game game, String lead, Team owner) {
        return theme(game).bad(lead).append(Component.text(Palette.titled(owner) + " Team", owner.color()))
                .append(theme(game).bad(" has not been eliminated!"));
    }

    static Component cantDeposit(Game game) {
        return theme(game).bad("You can't deposit this item!");
    }

    static Component needTwo(Game game) {
        return theme(game).bad("You need at least 2 items of this type to deposit!");
    }

    static Component noSpace(Game game) {
        return theme(game).bad("No space available to deposit items.");
    }

    /** Captured: the item in its color, the chest aqua or light purple, then the chest's count of it. */
    static Component deposited(Game game, ItemStack item, int amount, boolean team, int total) {
        Theme theme = theme(game);
        TextColor color = switch (item.material().key().value()) {
            case "gold_ingot" -> NamedTextColor.GOLD;
            case "diamond" -> NamedTextColor.AQUA;
            case "emerald" -> NamedTextColor.GREEN;
            default -> NamedTextColor.WHITE;
        };
        return theme.prose("Deposited ")
                .append(Component.text("x" + amount + " ", color).append(name(item)).append(Component.text(" ")))
                .append(theme.prose("into "))
                .append(team ? Component.text("Team Chest", NamedTextColor.AQUA) : Component.text("Ender Chest", NamedTextColor.LIGHT_PURPLE))
                .append(theme.prose("! "))
                .append(theme.dim("(" + total + " Total)"));
    }

    // each client's own name for it, 1.8's through Via
    private static Component name(ItemStack item) {
        Component custom = item.get(DataComponents.CUSTOM_NAME);
        return custom != null ? Component.text(PlainTextComponentSerializer.plainText().serialize(custom))
                : Component.translatable(item.material().translationKey());
    }

    static Component enderDropped(Game game, String player) {
        return theme(game).good("Contents of " + player + "'s Ender Chest have been dropped into their fountain.");
    }

    static Component milkWoreOff(Game game) {
        return theme(game).bad("Your Magic Milk wore off!");
    }

    static Component trackerLocked(Game game) {
        return theme(game).bad("Not all enemy beds are destroyed yet!");
    }

    /** Captured: red, in the tick of the enemy's hit. */
    static Component invisibilityLost(Game game) {
        return theme(game).bad("You took damage and lost your invisibility!");
    }

    /** Our copy: Hypixel's line for a tracker on the team already tracked is not captured. */
    static Component trackingAlready(Game game) {
        return theme(game).bad("You are already tracking that team!");
    }

    static Component notEnough(Game game, Price price) {
        return theme(game).bad("You don't have enough " + price.currency().name(price.amount()) + "!");
    }

    static Component trackerLost(Game game) {
        return theme(game).bad("You will lose ability to track this team when you die!");
    }

    /** Captured over the hotbar: the tracked player bold in their team's color, the meters green and bold. */
    static Component tracking(String player, TextColor color, int meters) {
        return Component.text("Tracking: ", NamedTextColor.WHITE)
                .append(Component.text(player, color, TextDecoration.BOLD))
                .append(Component.text(" - Distance: ", NamedTextColor.WHITE))
                .append(Component.text(meters + "m", NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    /** Captured: the team chat's own prefix; Hypixel's rank-colored name is a rankless gray one here. */
    static Component teamSays(Game game, String sender, Component message) {
        Theme theme = theme(game);
        return Component.text()
                .append(theme.good("TEAM > ").decorate(TextDecoration.BOLD))
                .append(theme.prose(sender))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(message)
                .build();
    }

    /** A quick message: its words green, {@code what} (a team, a resource) as it is styled. */
    static Component said(Game game, String lead, Component what) {
        return theme(game).good(lead).append(what);
    }

    /** {@code what} carries its own trailing space. */
    static Component targeting(Game game, Component what) {
        return theme(game).good("Targeting ").append(what).append(theme(game).good("on the compass now!"));
    }

    static Component alarmLine(Game game, String intruder, String team, TextColor color) {
        Theme theme = theme(game);
        return theme.bad("Reveal trap set off by ").decorate(TextDecoration.BOLD)
                .append(theme.prose(intruder).decorate(TextDecoration.BOLD))
                .append(theme.bad(" from ").decorate(TextDecoration.BOLD))
                .append(Component.text(team, color, TextDecoration.BOLD))
                .append(theme.bad(" team!").decorate(TextDecoration.BOLD));
    }
}
