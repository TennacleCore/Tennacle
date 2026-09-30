package tennacle.lobby;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;
import tennacle.party.Audience;
import tennacle.party.Choice;

/** What a player chose in the lobby's own settings, kept with their preferences. */
public final class Settings {

    public static final Choice LOBBY_CHAT = Choice.of("lobby:chat", Audience.EVERYONE, Audience.PARTY, Audience.NOBODY);

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
