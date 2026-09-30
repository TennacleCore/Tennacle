package tennacle;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.Grotto;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The SERVER's game-id convention - Grotto just consumes the {@link Grotto.Identity}. Ids are UUID-shaped
 * HMACs of (launch minute, board code) under a server secret; codes pack the UTC minute-of-day with salt.
 * So the board's date + the code beside it re-derive the id - but only here, holding the secret.
 */
public final class GameIds {

    // Crockford base32 (no i/l/o/u): codes survive screenshots; derive() folds squinted glyphs back
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static volatile byte[] secret;
    private static volatile ZoneId zone = ZoneId.systemDefault();
    // same-minute mints differ only by salt - the one window dedup guards; the minute bits separate the rest
    private static final Set<String> issued = new HashSet<>();
    private static long issuedMinute;

    private GameIds() {}

    public static void install(@NotNull Grotto grotto, @NotNull String hmacSecret) {
        install(grotto, hmacSecret, ZoneId.systemDefault());
    }

    /** {@code boardZone} = the zone the boards print their date in (the server's, unless overridden). */
    public static void install(@NotNull Grotto grotto, @NotNull String hmacSecret, @NotNull ZoneId boardZone) {
        secret = hmacSecret.getBytes(StandardCharsets.UTF_8);
        zone = boardZone;
        grotto.gameIds((type, arena) -> {
            long minute = System.currentTimeMillis() / 60000;
            String code;
            synchronized (issued) {
                if (minute != issuedMinute) {
                    issued.clear();
                    issuedMinute = minute;
                }
                do {
                    // salt leads so codes read random; the low 11 bits carry the UTC minute-of-day for derive()
                    int packed = ThreadLocalRandom.current().nextInt(1 << 14) << 11 | (int) (minute % 1440);
                    code = encode(packed);
                    // letter-first: an all-digit roll reads like a timestamp (and the networks' codes lead with one)
                } while (Character.isDigit(code.charAt(0)) || live(grotto, code) || !issued.add(code));
            }
            return new Grotto.Identity(idFor(secret, minute, code), code);
        });
    }

    // a day-old game still running shares this minute's code bits - re-roll rather than show one code twice
    private static boolean live(Grotto grotto, String code) {
        for (Game game : grotto.games()) {
            if (game.code().equals(code)) return true;
        }
        return false;
    }

    /** Screenshot to id over the installed secret + zone; squinted glyphs fold, {@code null} = no fit. */
    public static @Nullable String derive(@NotNull LocalDate boardDate, @NotNull String code) {
        return derive(secret, zone, boardDate, code);
    }

    /**
     * The pure function - runs anywhere holding the secret (a discord bot, a website), no server involved.
     * The whole scheme, for a non-JVM port: code = 5 Crockford-base32 chars packing {@code salt << 11 |
     * utcMinuteOfDay}; id = first 16 bytes of {@code HMAC-SHA256(secret, unixMinute + ":" + code)} rendered
     * as a UUID. {@code boardZone} = the zone the board printed its date in.
     */
    public static @Nullable String derive(byte @NotNull [] secret, @NotNull ZoneId boardZone,
                                          @NotNull LocalDate boardDate, @NotNull String code) {
        if (code.length() != 5) return null; // a mistranscribed code must never derive a confident wrong id
        int packed = 0;
        for (int i = 0; i < code.length(); i++) packed = (packed << 5) | digit(code.charAt(i));
        String canonical = encode(packed);
        int minuteOfDay = packed & 2047;
        if (minuteOfDay >= 1440) return null;
        for (long utcDay = boardDate.toEpochDay() - 1; utcDay <= boardDate.toEpochDay() + 1; utcDay++) {
            long minute = utcDay * 1440 + minuteOfDay;
            if (Instant.ofEpochSecond(minute * 60).atZone(boardZone).toLocalDate().equals(boardDate)) {
                return idFor(secret, minute, canonical);
            }
        }
        return null;
    }

    private static String idFor(byte[] secret, long minute, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            ByteBuffer d = ByteBuffer.wrap(mac.doFinal((minute + ":" + code).getBytes(StandardCharsets.UTF_8)));
            return new UUID(d.getLong(), d.getLong()).toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(int packed) {
        char[] out = new char[5];
        for (int i = 4; i >= 0; i--) {
            out[i] = ALPHABET[packed & 31];
            packed >>>= 5;
        }
        return new String(out);
    }

    private static int digit(char reported) {
        char c = Character.toLowerCase(reported);
        if (c == 'i' || c == 'l') c = '1';
        if (c == 'o') c = '0';
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) return i;
        }
        throw new IllegalArgumentException("not a code character: " + reported);
    }
}
