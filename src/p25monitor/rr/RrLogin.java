package p25monitor.rr;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * The user's own RadioReference login. The username is remembered for convenience; the password only when
 * "Remember me" is ticked (off by default). A remembered password is stored scrambled (AES-GCM with a key derived from
 * this computer's user name and settings folder) in rr-login.properties, readable only by the user's account. That
 * keeps it out of plain sight; it is not strong protection against someone who can read the user's files.
 */
public final class RrLogin {
    public static final String FILE = "rr-login.properties";
    public String username = "", password = "";
    public boolean remember;

    public static RrLogin load(Path profile) {
        RrLogin l = new RrLogin();
        Path p = profile.resolve(FILE);
        if (!Files.isRegularFile(p)) return l;
        Properties pr = new Properties();
        try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { pr.load(r); } catch (Exception e) { return l; }
        l.username = pr.getProperty("username", "");
        l.remember = Boolean.parseBoolean(pr.getProperty("remember", "false"));
        if (l.remember) l.password = unscramble(pr.getProperty("password", ""), profile);
        return l;
    }

    /** Writes the username (and the password only if remember is on); remember off removes any stored password. */
    public void save(Path profile) throws java.io.IOException {
        Files.createDirectories(profile);
        Properties pr = new Properties();
        pr.setProperty("username", username == null ? "" : username.trim());
        pr.setProperty("remember", String.valueOf(remember));
        if (remember && password != null && !password.isEmpty()) pr.setProperty("password", scramble(password, profile));
        Path p = profile.resolve(FILE), tmp = profile.resolve(FILE + ".tmp");
        try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { pr.store(w, "RadioReference login (password only if Remember me is on; scrambled)"); }
        try { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Forgets the stored password (keeps the username). */
    public static void forget(Path profile) throws java.io.IOException {
        RrLogin l = load(profile); l.remember = false; l.password = ""; l.save(profile);
    }

    static SecretKey key(Path profile) throws GeneralSecurityException {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(("TrunkDeck-RR|" + System.getProperty("user.name", "") + "|" + profile.toAbsolutePath()).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(h, 16), "AES");
    }

    static String scramble(String pw, Path profile) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(profile), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(pw.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length]; System.arraycopy(iv, 0, all, 0, 12); System.arraycopy(ct, 0, all, 12, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static String unscramble(String s, Path profile) {
        try {
            if (!s.startsWith("v1:")) return "";
            byte[] all = Base64.getDecoder().decode(s.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(profile), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }   // moved to another computer / user: ask again
    }
}
