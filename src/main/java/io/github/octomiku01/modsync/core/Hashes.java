package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** File hashes as lowercase hex, keyed by the names Modrinth uses: "sha1", "sha256", "sha512" */
public final class Hashes {
    /** Strongest first: a file is checked against the strongest hash both sides know */
    public static final List<String> ALGORITHMS = List.of("sha512", "sha256", "sha1");

    private Hashes() {
    }

    /** All hashes of the file, read once */
    public static Map<String, String> of(Path file) throws IOException {
        Map<String, MessageDigest> digests = new LinkedHashMap<>();
        for (String algorithm : ALGORITHMS) {
            digests.put(algorithm, digest(algorithm));
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) {
                for (MessageDigest digest : digests.values()) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        digests.forEach((algorithm, digest) -> hashes.put(algorithm, HexFormat.of().formatHex(digest.digest())));
        return hashes;
    }

    /**
     * Whether two sets of hashes describe the same file, by the strongest hash both have. Without one in common they
     * do not match: a file is never trusted without a hash to check.
     */
    public static boolean match(Map<String, String> expected, Map<String, String> actual) {
        for (String algorithm : ALGORITHMS) {
            String a = expected.get(algorithm);
            String b = actual.get(algorithm);
            if (a != null && b != null) {
                return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(switch (algorithm) {
                case "sha1" -> "SHA-1";
                case "sha256" -> "SHA-256";
                case "sha512" -> "SHA-512";
                default -> throw new IllegalArgumentException(algorithm);
            });
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
