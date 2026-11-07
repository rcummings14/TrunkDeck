package p25monitor.lan;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;

/**
 * The home-network password, stored in settings as a salted hash ({@code lan.pass}). The password
 * itself is never written and never logged. The caller wipes nothing: these methods clear the
 * character array they are given.
 */
public final class LanSecret {
    public static final String KEY = "lan.pass";
    /** Settings keys the switch also keeps. The password verifier is {@link #KEY}. */
    public static final String ENABLED = "lan.enabled", PORT = "lan.port";
    public static final int DEFAULT_PORT = 8787;
    static final int ITER = 100_000, SALT = 16, BITS = 256;
    private static final SecureRandom RAND = new SecureRandom();

    private LanSecret() {}

    public static boolean isSet(Properties p) {
        String v = p == null ? "" : p.getProperty(KEY, "");
        return v.startsWith("pbkdf2.");
    }

    /** Saves a verifier for a password of 4 to 128 characters. Returns false if it is not usable. */
    public static boolean set(Properties p, char[] password) {
        try {
            if (p == null || password == null || password.length < 4 || password.length > 128) return false;
            byte[] salt = new byte[SALT];
            RAND.nextBytes(salt);
            byte[] hash = hash(password, salt, ITER);
            p.setProperty(KEY, "pbkdf2." + ITER + "." + hex(salt) + "." + hex(hash));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public static boolean verify(Properties p, char[] password) {
        try {
            if (password == null || p == null) return false;
            String[] parts = p.getProperty(KEY, "").split("\\.");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            int iter = Integer.parseInt(parts[1]);
            if (iter < 1_000 || iter > 5_000_000) return false;
            byte[] salt = unhex(parts[2]), expect = unhex(parts[3]);
            if (salt.length < 8 || expect.length < 16) return false;
            return MessageDigest.isEqual(expect, hash(password, salt, iter));
        } catch (Exception e) {
            return false;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    static byte[] hash(char[] password, byte[] salt, int iter) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iter, BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte v : b) s.append(String.format("%02x", v & 0xff));
        return s.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) throw new IllegalArgumentException();
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
