package tennacle.lobby;

import tennacle.Arrivals;
import tennacle.Msg;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/** What an arrival reads first: a greeting and where the commands are. The copy lives here and nowhere else. */
public final class Welcome {

    static final String TITLE = "Tennacle";

    private Welcome() {}

    public static void install() {
        Arrivals.add(Arrivals.WELCOME, Welcome::send); // the title's format (gradient vs flat) depends on the protocol
    }

    public static void send(@NotNull Player to) {
        Msg.say(to, "Welcome to ", Msg.THEME.title(to, TITLE), "! Type ", Msg.run("/help"), " to see what you can do.");
    }
}
