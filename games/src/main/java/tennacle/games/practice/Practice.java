package tennacle.games.practice;

import io.github.term4.bloom.mapmaking.Teams;
import io.github.term4.grotto.Body;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.space.Daylight;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presets;
import tennacle.games.Ruleset;
import tennacle.games.map.HotArena;
import tennacle.looks.Palette;

import java.util.List;
import java.util.Map;

/**
 * Practice: one player against a bot on the boxing map, Boxing without its Speed, to drill hit selecting,
 * reducing and jump resets. Nothing deals damage. The bot is in {@link Trainer}, its settings in {@link Drill}.
 */
public final class Practice {

    static final Ruleset BASE = Ruleset.named("practice")
            .rule(Roster.RECLAIM_TICKS, 0L)
            .path("hunger/exhaustionScale", 0)
            .path("damage/enabledTypes", "only(minecraft:player_attack)");

    private static final Map<String, Ruleset> VARIANTS = Presets.variants(BASE);

    private static final ItemStack SWORD = Kit.unbreakable(ItemStack.of(Material.DIAMOND_SWORD)
            .with(DataComponents.CUSTOM_NAME, Component.text("Practice Sword", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false)));
    private static final Kit KIT = Kit.of("practice").item("sword", 0, SWORD)
            .item("start", 2, Lines.START).item("reset", 3, Lines.RESET).item("settings", 4, Lines.SETTINGS)
            .item("leave", 8, Lines.LEAVE);

    private static final Teams.Defaults DEFAULTS = new Teams.Defaults("practice",
            io.github.term4.canvas.theme.Livery.EMPTY, List.of(io.github.term4.grotto.roster.Team.DISPLAY));

    private Practice() {}

    public static void install() {
        Drill.register();
        Teams.register(DEFAULTS);
        GameType type = GameType.builder("practice")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .teams(a -> Palette.teams(a, Markers.SPAWN, DEFAULTS))
                .expect(Markers.SPAWN, 2)
                .body(Body.fresh().gamemode(GameMode.ADVENTURE))
                .modules(() -> Phases.of(Match.LIVE), Presets::new, Daylight::new, () -> Kits.of(KIT), Trainer::new,
                        Abandonment.endWhenDeserted(Outcome::aborted))
                .build();
        for (Ruleset ruleset : VARIANTS.values()) ruleset.check(Mechanics.polyp.profiles().global(), type);
        Matches.register(Mode.of("practice", type, HotArena.of("boxing"), MechanicsProfile::builder, BASE, VARIANTS)
                .solo()
                .play("practice", 1)
                .build());
    }
}
