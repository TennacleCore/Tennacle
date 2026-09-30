package tennacle.lobby;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;
import tennacle.games.matchmaking.Parties;

import java.util.List;
import java.util.Locale;

/** What a player chose in the lobby's settings, kept with their preferences. */
public final class Settings {

    /** Who a setting lets through. */
    public enum Audience {
        EVERYONE("Everyone"), PARTY("Your party"), NOBODY("Nobody");

        private final String label;

        Audience(String label) {
            this.label = label;
        }

        public @NotNull String label() {
            return label;
        }

        /** Whether {@code from} gets through to {@code to} under this setting. */
        public boolean admits(@NotNull Player from, @NotNull Player to) {
            return switch (this) {
                case EVERYONE -> true;
                case PARTY -> Parties.together(from, to);
                case NOBODY -> false;
            };
        }
    }

    /** One choice among {@code options}, stored by name; anything unreadable is the first option. */
    public static final class Choice {
        private final List<Audience> options;
        private final PlayerPrefs.Entry<String, Audience> stored;

        private Choice(String key, List<Audience> options) {
            this.options = List.copyOf(options);
            this.stored = PlayerPrefs.register(VaultKey.of(key, Codec.STRING, options.getFirst().name()),
                    name -> read(name, options), audience -> audience.name());
        }

        public @NotNull List<Audience> options() {
            return options;
        }

        public @NotNull Audience of(@NotNull Player player) {
            Audience held = stored.of(player.getUuid());
            return held != null ? held : options.getFirst();
        }

        public void set(@NotNull Player player, @NotNull Audience audience) {
            stored.set(player, audience);
        }

        /** The option {@code step} along from the current one, wrapping. */
        public @NotNull Audience step(@NotNull Player player, int step) {
            int at = options.indexOf(of(player));
            Audience next = options.get(Math.floorMod(at + step, options.size()));
            set(player, next);
            return next;
        }

        private static Audience read(String name, List<Audience> options) {
            try {
                Audience audience = Audience.valueOf(name.toUpperCase(Locale.ROOT));
                return options.contains(audience) ? audience : options.getFirst();
            } catch (IllegalArgumentException unreadable) {
                return options.getFirst();
            }
        }
    }

    public static final Choice LOBBY_CHAT = new Choice("lobby:chat", List.of(Audience.EVERYONE, Audience.PARTY, Audience.NOBODY));
    public static final Choice PARTY_INVITES = new Choice("lobby:party-invites", List.of(Audience.EVERYONE, Audience.NOBODY));
    public static final Choice DUEL_REQUESTS = new Choice("lobby:duel-requests", List.of(Audience.EVERYONE, Audience.PARTY, Audience.NOBODY));

    private static final PlayerPrefs.Entry<String, Boolean> PLAYERS = PlayerPrefs.register(
            VaultKey.of("lobby:players", Codec.STRING, "shown"), "shown"::equals, shown -> shown ? "shown" : "hidden");

    private Settings() {}

    /** Loads the class: its preferences have to register before the first login to be fetched with it. */
    public static void install() {}

    public static boolean playersShown(@NotNull Player player) {
        Boolean shown = PLAYERS.of(player.getUuid());
        return shown == null || shown;
    }

    public static void playersShown(@NotNull Player player, boolean shown) {
        PLAYERS.set(player, shown);
    }
}
