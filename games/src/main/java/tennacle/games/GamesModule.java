package tennacle.games;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.vault.Vault;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import org.jetbrains.annotations.NotNull;
import tennacle.GameIds;
import tennacle.audit.Audit;
import tennacle.audit.AuditCommands;
import tennacle.games.bedfight.BedFight;
import tennacle.games.bedwars.BedWars;
import tennacle.games.boxing.Boxing;
import tennacle.games.bridge.Bridge;
import tennacle.games.matchmaking.Duels;
import tennacle.games.matchmaking.Play;
import tennacle.games.matchmaking.Queues;
import tennacle.games.sumo.Sumo;
import tennacle.games.watch.ReplayCommands;
import tennacle.games.watch.Replays;
import tennacle.games.watch.SpectateCommands;
import tennacle.games.watch.Spectators;
import tennacle.looks.GameSounds;
import tennacle.looks.TabStats;
import tennacle.social.party.Parties;
import tennacle.storage.MatchLog;

import java.nio.file.Path;

/**
 * The games module: the match layer over Grotto, matchmaking, spectating, replays, the looks and every mode. A game
 * sends its players to {@code home} until something else takes the route ({@link Matches#home}).
 */
public final class GamesModule {

    /** Where the games keep what they write, and the secret behind their ids. */
    public record Data(@NotNull Vault vault, @NotNull Path matchLog, @NotNull Path replays, @NotNull String gameSecret) {}

    private GamesModule() {}

    public static void install(@NotNull InstanceContainer home, @NotNull Pos homeSpawn, @NotNull ShardManager shards,
                               @NotNull Data data) {
        ReplayCommands.register(shards, home);
        Matches.install(home, homeSpawn, shards);
        Matches.grotto().vault(data.vault());
        MatchLog.install(data.matchLog());
        Matches.grotto().onGameEnd(MatchLog::ended);
        Replays.install(data.replays(), shards); // every game, spooled as it runs, kept as a file under its mode
        Duels.install(); // /duel <player> [mode] [platform]: 1v1s over Grotto
        Queues.install(); // /play's duels: the first parties that fill both teams start
        Play.install(); // /play <game> [platform]: Hypixel's ids
        SpectateCommands.register();
        Spectators.install();
        Rooms.install(); // who hears whom: game, spectators, party, shard tree
        RulesCommands.register(); // /rules: live modifiers over the running game
        MapCommand.register(); // /map: the map you play or watch
        tennacle.games.imports.HypixelLayouts.install(); // /kit import hypixel: a player's layouts from their profile
        tennacle.games.imports.LayoutVisibility.install(); // who sees a player's layouts: everyone, friends or nobody
        TeamCommands.register(); // /team: which ordinal is which color, and who is on it
        TabStats.register(); // /tabstats: which seat counters the house tab lists
        GameSounds.register(); // /sounds: which cues a player hears
        // id convention; the secret is the derivation capability (rotate = old screenshots stop deriving)
        GameIds.install(Matches.grotto(), data.gameSecret());
        Audit.install(shards);
        AuditCommands.register(); // /audit: the server's worlds (instances, games, shard trees, players)
        GrottoCommands.register(); // /grotto: spectate, and a game id from a board code
        LobbyCommands.register(); // /spawn|/lobby|/l - a seated player departs their game first
        TeamChat.register(); // /shout: past team chat, the whole game
        Careers.register(); // /stats: every mode's career line
        Sumo.install(); // ring-out over a void plane; the platform picks the knockback
        Boxing.install(); // speed 2, first to 10 hits
        Bridge.install(); // blue vs red, first to five goals
        BedFight.install(); // break the bed, then eliminate
        BedWars.install(); // Hypixel's, on its maps: /play bedwars_eight_one
        Screens.cues(GameSounds::lobby);
        Parties.cues(GameSounds::lobby);
    }
}
