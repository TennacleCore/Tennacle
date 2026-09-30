package tennacle.party;

/** The party module: parties, their invites, and who may send one. */
public final class PartyModule {

    private PartyModule() {}

    public static void install() {
        Parties.install(); // /party: a side that duels and queues together
    }
}
