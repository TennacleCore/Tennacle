package tennacle.social;

import tennacle.Seen;
import tennacle.social.blocks.Blocks;
import tennacle.social.friends.Friends;
import tennacle.social.party.Parties;

/** The social module: parties, friends, blocks, and who may reach whom. */
public final class SocialModule {

    private SocialModule() {}

    public static void install() {
        Seen.install();
        Friends.install();
        Blocks.install();
        Parties.install(); // /party: a side that duels and queues together
    }
}
