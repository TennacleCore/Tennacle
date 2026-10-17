package tennacle.social;

import tennacle.social.party.Parties;

/** The social module: parties, their invites, and who may send one. */
public final class SocialModule {

    private SocialModule() {}

    public static void install() {
        Parties.install(); // /party: a side that duels and queues together
    }
}
