package tennacle.games.watch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Where finished games are listed: who played (as they were named then, on which team), who won, how long it took.
 * A database in production; until then, {@link #SAMPLES} makes up a believable history per player.
 */
public interface ReplayCatalog {

    record Seat(@NotNull UUID id, @NotNull String name) {}

    record Side(@NotNull String team, @NotNull List<Seat> seats) {}

    /** {@code winner} is a team id, null for a draw; {@code sample} = made up, nothing to watch. */
    record Record(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String map, long playedAt,
                  long lengthMillis, @NotNull List<Side> sides, @Nullable String winner, boolean sample) {}

    /** {@code player}'s games, newest first. */
    @NotNull List<Record> of(@NotNull UUID player, @NotNull String name);

    /** Thirty made-up games a player seems to have played, the same ones every time for the same player. */
    ReplayCatalog SAMPLES = new ReplayCatalog() {
        private static final String[][] GAMES = {
                {"bridge", "Bridge 1v1", "Urban", "1"}, {"bedwars-doubles", "Bed Wars Doubles", "Lighthouse", "2"},
                {"sumo", "Sumo", "Ring", "1"}, {"boxing", "Boxing", "Arena", "1"},
                {"bridge", "Bridge 2v2", "Sorcery", "2"}, {"bedfight", "Bed Fight", "Mesa", "1"},
                {"bedwars-solo", "Bed Wars Solo", "Airshow", "1"}};
        private static final String[] OTHERS = {"Kai", "Noor", "Rin", "Ezra", "Mika", "Jun", "Sami", "Theo", "Ivy", "Lior"};
        private static final String[][] TEAMS = {{"red", "blue"}, {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"}};

        @Override
        public @NotNull List<Record> of(@NotNull UUID player, @NotNull String name) {
            Random random = new Random(player.getMostSignificantBits() ^ player.getLeastSignificantBits());
            List<Record> out = new ArrayList<>();
            long at = System.currentTimeMillis() - 20 * 60_000L;
            for (int i = 0; i < 30; i++) {
                String[] game = GAMES[random.nextInt(GAMES.length)];
                int size = Integer.parseInt(game[3]);
                String[] teams = game[0].startsWith("bedwars") ? TEAMS[1] : TEAMS[0];
                List<Side> sides = new ArrayList<>();
                for (int t = 0; t < teams.length; t++) {
                    List<Seat> seats = new ArrayList<>();
                    for (int s = 0; s < size; s++) {
                        boolean me = t == 0 && s == 0;
                        String who = me ? name : OTHERS[random.nextInt(OTHERS.length)];
                        seats.add(new Seat(me ? player : UUID.nameUUIDFromBytes(who.getBytes()), who));
                    }
                    sides.add(new Side(teams[t], seats));
                }
                String winner = teams[random.nextInt(Math.min(teams.length, 3)) == 0 ? 0 : 1 + random.nextInt(teams.length - 1)];
                long length = (game[0].equals("sumo") ? 30 : game[0].startsWith("bedwars") ? 600 : 240) * 1000L
                        + random.nextInt(300_000);
                String code = Long.toString(Math.abs(random.nextLong()) % 2_176_782_336L, 36).toUpperCase();
                out.add(new Record("sample-" + i, code, game[1], game[2], at, length, sides, winner, true));
                at -= length + 60_000L + random.nextInt(3 * 3_600_000);
            }
            return out;
        }
    };
}
