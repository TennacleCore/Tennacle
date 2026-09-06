package tennacle.audit;

import tennacle.Mechanics;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.matchmaking.Parties;
import tennacle.games.watch.Replays;
import tennacle.games.watch.Spectators;
import tennacle.games.Mode;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.bloom.Bloom;
import io.github.term4.bloom.Session;
import io.github.term4.canvas.Canvas;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Grotto;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.barnacle.Arena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The server's worlds as an admin reads them: every instance, the games and shard trees over it, and where each
 * player stands. The chat rows link into two screens that follow the live map ({@link AuditViews}): a tree, one
 * node and its children, and a shard's players as heads. A shard is addressed by its manager name, or {@code #}
 * and its identity in hex when it has none; a game by {@code type#code}.
 */
public final class Audit {

    /** Names in a hover past this many read as a count: the screen lists them. */
    public static final int HOVER_NAMES = 10;
    /** One level of the readout's tree; a chat space is four pixels, so two read as nothing. */
    private static final String INDENT = "    ";

    private static volatile ShardManager shards;

    private Audit() {}

    public static void install(@NotNull ShardManager manager) {
        shards = manager;
    }

    public static @NotNull ShardManager shards() {
        return Objects.requireNonNull(shards, "Audit.install first");
    }

    /** The handle a chat link or a screen names {@code shard} by. */
    public static @NotNull String handle(@NotNull Shard shard) {
        String name = shards().nameOf(shard);
        return name != null ? name : "#" + Integer.toHexString(System.identityHashCode(shard));
    }

    /** The shard {@code handle} names, or null: a manager name, a {@code #} identity, or a game's {@code type#code}. */
    public static @Nullable Shard shard(@NotNull String handle) {
        Shard named = shards().byName(handle);
        if (named != null) return named;
        for (Game game : Matches.grotto().games()) {
            if (gameName(game).equalsIgnoreCase(handle)) return game.shard();
        }
        for (Shard shard : shards().shards()) {
            if (handle(shard).equalsIgnoreCase(handle)) return shard;
        }
        return null;
    }

    /** Whether {@code shard} still stands in the manager. */
    public static boolean alive(@NotNull Shard shard) {
        return shards().shards().contains(shard);
    }

    public static @NotNull String gameName(@NotNull Game game) {
        return game.type().id() + "#" + game.code();
    }

    /** What an instance is to the app: the lobby, a map's world, the editor's, or a bare world. */
    public static @NotNull String label(@NotNull Instance instance) {
        if (instance == Matches.lobby()) return "lobby";
        for (Grotto.InstanceView row : Matches.grotto().instances()) {
            if (row.instance() == instance && row.arena() != null) return "map " + row.arena().meta().id();
        }
        for (String name : Matches.modes()) { // a map warmed for its next game, before any game names it
            Mode mode = Matches.mode(name);
            if (mode == null) continue;
            for (String map : mode.arena().names()) {
                Arena arena = mode.arena().named(map);
                if (arena != null && arena.instantiated() == instance) return "map " + map;
            }
        }
        Session editing = Bloom.session();
        if (editing != null && editing.instance() == instance) return "editor " + editing.id();
        return "world " + instance.getDimensionName() + " " + instance.getUuid().toString().substring(0, 6);
    }

    /** {@code tree <handle>} for a shard with children, {@code shard <handle>} for one without. */
    public static @NotNull String label(@NotNull Shard shard) {
        return (shard.children().isEmpty() ? "shard " : "tree ") + handle(shard);
    }

    /** Who a shard tree serves - a game, a replay theater, the editor - or null. */
    public static @Nullable String owner(@NotNull Shard shard) {
        for (Shard at = shard; at != null; at = at.parent()) {
            Game game = Game.of(at);
            if (game != null) return "game " + gameName(game);
            String replay = Replays.replayOf(at);
            if (replay != null) return "replay " + replay.substring(0, Math.min(6, replay.length()));
        }
        Session editing = Bloom.session();
        if (editing != null && editing.instance() == shard.instance()) return "editor " + editing.id();
        return null;
    }

    /** The shards under {@code root}, the whole subtree. */
    public static int under(@NotNull Shard root) {
        int n = 0;
        for (Shard child : root.children()) n += 1 + under(child);
        return n;
    }

    /** The lobby first, then by label. */
    public static @NotNull List<Instance> instances() {
        List<Instance> out = new ArrayList<>(MinecraftServer.getInstanceManager().getInstances());
        out.sort(Comparator.comparing((Instance i) -> i != Matches.lobby()).thenComparing(Audit::label));
        return out;
    }

    public static @NotNull List<Game> gamesIn(@NotNull Instance instance) {
        return Matches.grotto().games().stream().filter(g -> g.instance() == instance)
                .sorted(Comparator.comparing(Game::code)).toList();
    }

    /** The root shards over {@code instance}, by handle. */
    public static @NotNull List<Shard> rootsIn(@NotNull Instance instance) {
        return shards().shards().stream().filter(s -> s.parent() == null && s.instance() == instance)
                .sorted(Comparator.comparing(Audit::handle)).toList();
    }

    /** The players in {@code shard} - its members, then those viewing it from outside - by name. */
    public static @NotNull List<Player> playersIn(@NotNull Shard shard) {
        Set<Player> all = new LinkedHashSet<>(shard.members());
        all.addAll(shard.observers());
        return all.stream().sorted(Comparator.comparing(Player::getUsername)).toList();
    }

    /** Those viewing {@code shard} without standing in it: a replay's watchers, staff with a view set. */
    public static @NotNull List<Player> viewersOf(@NotNull Shard shard) {
        return shard.observers().stream().filter(p -> !shard.members().contains(p))
                .sorted(Comparator.comparing(Player::getUsername)).toList();
    }

    public static @NotNull String client(@NotNull Player p) {
        return Canvas.isLegacy(p) ? "1.8" : "modern";
    }

    /** The seat's team color when {@code p} holds one in {@code game}, gray for a watcher, white otherwise. */
    public static @NotNull TextColor color(@NotNull Player p, @Nullable Game game) {
        if (game == null) return NamedTextColor.WHITE;
        if (game.spectators().contains(p)) return NamedTextColor.GRAY;
        Slot slot = game.roster().slotOf(p);
        return slot != null ? game.roster().team(slot.team()).color() : NamedTextColor.WHITE;
    }

    /** Where {@code p} stands, in a phrase: the game and seat, what they watch, the editor, a shard, or the lobby. */
    public static @NotNull List<Object> where(@NotNull Player p) {
        List<Object> out = new ArrayList<>();
        Game game = Game.of(p);
        Game watched = Matches.grotto().spectating(p);
        String replay = Replays.watching(p);
        Session editing = Bloom.session();
        Shard shard = Shard.of(p);
        if (game != null) {
            Slot slot = game.roster().slotOf(p);
            out.add("in ");
            out.add(whoLink(gameName(game), game.shard()));
            if (slot != null) out.add(Component.text(" " + slot.team(), color(p, game)));
            else if (game.isGhost(p)) out.add(" as a ghost");
        } else if (watched != null) {
            out.add("watching ");
            out.add(whoLink(gameName(watched), watched.shard()));
        } else if (replay != null) {
            out.add("watching replay ");
            out.add(shard != null ? whoLink(replay.substring(0, Math.min(6, replay.length())), shard) : Msg.v(replay));
        } else if (editing != null && editing.instance() == p.getInstance()) {
            out.add("editing ");
            out.add(Msg.v(editing.id()));
        } else if (shard != null) {
            out.add("in ");
            out.add(whoLink(label(shard), shard));
        } else if (p.getInstance() != null) {
            out.add("in the ");
            out.add(Msg.v(label(p.getInstance())));
        }
        List<Player> party = Parties.of(p);
        if (party.size() > 1) {
            out.add(" · party of " + party.size() + (Parties.leads(p) ? " (leader)" : ""));
        }
        return out;
    }

    /** {@code text} that opens {@code shard}'s players as heads. */
    public static @NotNull Component whoLink(@NotNull String text, @NotNull Shard shard) {
        return Msg.link(text, "/audit who " + handle(shard), "click for who is in it");
    }

    /** {@code text} that opens {@code shard}'s tree. */
    public static @NotNull Component treeLink(@NotNull String text, @NotNull Shard shard) {
        return Msg.link(text, "/audit tree " + handle(shard), "click to expand");
    }

    /** A player count that hovers the names (up to {@link #HOVER_NAMES}) and opens {@code shard}'s heads. */
    public static @NotNull Component players(@NotNull List<Player> players, @Nullable Shard shard, @Nullable Game game) {
        int n = players.size();
        String text = n + (n == 1 ? " player" : " players");
        Component hover = n == 0 ? Component.text("nobody") : n > HOVER_NAMES
                ? Component.text(n + " players" + (shard != null ? " - click to list them" : ""))
                : names(players, game);
        Component out = Msg.v(text).hoverEvent(hover);
        return shard != null && n > 0 ? out.clickEvent(ClickEvent.runCommand("/audit who " + handle(shard))) : out;
    }

    private static Component names(List<Player> players, @Nullable Game game) {
        Component out = Component.empty();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (i > 0) out = out.append(Component.text(", ", NamedTextColor.GRAY));
            out = out.append(Component.text(p.getUsername(), color(p, game)));
        }
        return out;
    }

    // ---- the chat readouts

    public static void overview(@NotNull CommandSender to) {
        List<Instance> all = instances();
        int games = Matches.grotto().games().size();
        long roots = shards().shards().stream().filter(s -> s.parent() == null).count();
        int online = MinecraftServer.getConnectionManager().getOnlinePlayers().size();
        Msg.tag(to, "audit", Msg.v(all.size()), plural(all.size(), " instance"), " · ", Msg.v(games),
                plural(games, " game"), " · ", Msg.v(roots), plural((int) roots, " shard tree"), " · ",
                Msg.v(online), plural(online, " player"));
        for (Instance instance : all) {
            instanceRow(to, instance, INDENT);
            for (Game game : gamesIn(instance)) gameRow(to, game, INDENT + INDENT);
            for (Shard root : rootsIn(instance)) {
                if (Game.of(root) == null) treeRow(to, root, INDENT + INDENT);
            }
        }
    }

    public static void instances(@NotNull CommandSender to) {
        for (Instance instance : instances()) instanceRow(to, instance, "");
    }

    public static void minis(@NotNull CommandSender to) {
        List<Game> games = new ArrayList<>(Matches.grotto().games());
        if (games.isEmpty()) {
            Msg.tag(to, "audit", "no game is running");
            return;
        }
        for (Instance instance : instances()) {
            List<Game> here = gamesIn(instance);
            if (here.isEmpty()) continue;
            Msg.tag(to, "audit", Msg.v(label(instance)));
            for (Game game : here) gameRow(to, game, INDENT);
        }
    }

    public static void shards(@NotNull CommandSender to) {
        boolean any = false;
        for (Instance instance : instances()) {
            List<Shard> roots = rootsIn(instance);
            if (roots.isEmpty()) continue;
            any = true;
            Msg.tag(to, "audit", Msg.v(label(instance)));
            for (Shard root : roots) treeRow(to, root, INDENT);
        }
        if (!any) Msg.tag(to, "audit", "no shard stands anywhere");
    }

    public static void players(@NotNull CommandSender to) {
        List<Player> online = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .sorted(Comparator.comparing(Player::getUsername)).toList();
        if (online.isEmpty()) {
            Msg.tag(to, "audit", "nobody is online");
            return;
        }
        for (Player p : online) {
            List<Object> parts = new ArrayList<>();
            parts.add(Msg.link(p.getUsername(), "/audit player " + p.getUsername(), "click for the details"));
            parts.add(" · " + client(p) + " · ");
            parts.addAll(where(p));
            Msg.tag(to, "audit", parts.toArray());
        }
    }

    /** One player in detail, with the way to them. */
    public static void player(@NotNull CommandSender to, @NotNull Player p) {
        List<Object> head = new ArrayList<>();
        head.add(Msg.v(p.getUsername()));
        head.add(" · " + client(p) + " · ");
        head.addAll(where(p));
        Msg.tag(to, "audit", head.toArray());
        Instance instance = p.getInstance();
        Shard shard = Shard.of(p);
        Pos at = p.getPosition();
        Msg.tag(to, "audit", INDENT, Msg.v(instance != null ? label(instance) : "nowhere"),
                shard != null ? " · " : "", shard != null ? treeLink(label(shard), shard) : "",
                " · at " + Math.round(at.x()) + " " + Math.round(at.y()) + " " + Math.round(at.z()));
        Game game = Game.of(p);
        if (game != null) {
            Msg.tag(to, "audit", INDENT, Msg.v(gameName(game)), " · " + game.status().name().toLowerCase() + " · "
                    + Replays.clock(game.tick()) + " on ", Msg.v(game.arena().meta().name()));
        }
        if (to instanceof Player viewer && viewer != p) {
            Msg.tag(to, "audit", INDENT, Msg.link("[go there]", "/audit go " + p.getUsername(),
                    game != null ? "watch their game" : "stand at their side"));
        }
    }

    /** Takes {@code viewer} to {@code target}: into their game as a spectator, into their replay, or to their side. */
    public static void go(@NotNull Player viewer, @NotNull Player target) {
        if (viewer == target) {
            Msg.fail(viewer, "That's you.");
            return;
        }
        Game game = Game.of(target);
        if (game != null) {
            if (Game.of(viewer) != null) {
                Msg.fail(viewer, "You're in a game. ", Msg.run("/l"), " first.");
                return;
            }
            if (!Spectating.allowed(game)) {
                Msg.fail(viewer, "That game doesn't allow spectators.");
                return;
            }
            Spectators.exit(viewer);
            Spectators.enter(game, viewer, target);
            Msg.ok(viewer, "Watching ", Msg.v(target.getUsername()), ". ", Msg.run("/l"), " to leave.");
            return;
        }
        String replay = Replays.watching(target);
        if (replay != null) {
            Replays.watch(viewer, replay);
            return;
        }
        Instance instance = target.getInstance();
        if (instance == null) {
            Msg.fail(viewer, Msg.v(target.getUsername()), " is nowhere right now.");
            return;
        }
        Shard shard = Shard.of(target);
        Pos at = target.getPosition();
        if (viewer.getInstance() == instance) viewer.teleport(at);
        else viewer.setInstance(instance, at);
        if (shard != null && Shard.of(viewer) != shard) shard.join(viewer);
        Msg.ok(viewer, "At ", Msg.v(target.getUsername()), "'s side.");
    }

    private static void instanceRow(CommandSender to, Instance instance, String indent) {
        List<Player> here = new ArrayList<>(instance.getPlayers());
        here.sort(Comparator.comparing(Player::getUsername));
        long entities = instance.getEntities().stream().filter(e -> !(e instanceof Player)).count();
        List<Object> parts = new ArrayList<>();
        parts.add(indent);
        parts.add(Msg.v(label(instance)));
        parts.add(" · ");
        parts.add(players(here, null, null));
        parts.add(" · " + instance.getChunks().size() + " chunks · " + entities + plural((int) entities, " entity", " entities"));
        for (Grotto.InstanceView row : Matches.grotto().instances()) {
            if (row.instance() == instance && row.quietMillis() > 0) {
                long m = row.quietMillis() / 60000;
                parts.add(" · idle " + (m > 0 ? m + "m" : row.quietMillis() / 1000 + "s"));
            }
        }
        Msg.tag(to, "audit", parts.toArray());
    }

    private static void gameRow(CommandSender to, Game game, String indent) {
        List<Player> seated = game.members().stream().sorted(Comparator.comparing(Player::getUsername)).toList();
        List<Player> watching = game.spectators().stream().sorted(Comparator.comparing(Player::getUsername)).toList();
        List<Object> parts = new ArrayList<>();
        parts.add(indent);
        parts.add(Msg.v(gameName(game)).hoverEvent(Component.text("click to copy the game id"))
                .clickEvent(ClickEvent.copyToClipboard(game.id())));
        parts.add(" · " + game.status().name().toLowerCase() + " " + Replays.clock(game.tick()) + " on ");
        parts.add(Msg.v(game.arena().meta().name()));
        parts.add(" · ");
        parts.add(players(seated, game.shard(), game));
        if (!watching.isEmpty()) {
            parts.add(" · ");
            parts.add(Msg.v(watching.size() + " watching").hoverEvent(names(watching, game)));
        }
        parts.add(" · ");
        parts.add(treeLink(label(game.shard()), game.shard()));
        Msg.tag(to, "audit", parts.toArray());
    }

    private static void treeRow(CommandSender to, Shard root, String indent) {
        List<Object> parts = new ArrayList<>();
        parts.add(indent);
        parts.add(root.children().isEmpty() ? whoLink(label(root), root) : treeLink(label(root), root));
        String owner = owner(root);
        if (owner != null) parts.add(" · " + owner);
        parts.add(" · ");
        parts.add(players(new ArrayList<>(root.members()), root, Game.of(root)));
        List<Player> viewing = viewersOf(root);
        if (!viewing.isEmpty()) {
            parts.add(" · ");
            parts.add(Msg.v(viewing.size() + " viewing").hoverEvent(names(viewing, null)));
        }
        int under = under(root);
        if (under > 0) parts.add(" · " + under + " under it");
        Msg.tag(to, "audit", parts.toArray());
    }

    static String plural(int n, String one) {
        return n == 1 ? one : one + "s";
    }

    static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }

    /** The names of {@code players}, joined - for tests and plain surfaces. */
    public static @NotNull String list(@NotNull List<Player> players) {
        return players.stream().map(Player::getUsername).collect(Collectors.joining(", "));
    }
}
