package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the GitHub login in a file of the user's own. On Windows its content is encrypted with DPAPI, the protection
 * the Windows credential manager uses as well: only this Windows account can read it. Elsewhere it is plain JSON that
 * only its owner may read. A token written into the file by hand, like an access code on a server, is read as well.
 */
final class TokenStore {
    private static final String DPAPI_PREFIX = "dpapi:";

    /**
     * @param token            what authorizes the requests
     * @param refreshToken     what gets a new token once this one runs out; none for tokens that do not expire
     * @param expiresAt        when the token runs out, in seconds since 1970; 0 if it does not
     * @param refreshExpiresAt when the refresh token runs out, in seconds since 1970; 0 if it does not
     */
    record Credential(String token, @Nullable String refreshToken, long expiresAt, long refreshExpiresAt) {
        static Credential lasting(String token) {
            return new Credential(token, null, 0, 0);
        }
    }

    private TokenStore() {
    }

    /** The login kept in the file, none if there is none or it cannot be read */
    static @Nullable Credential read(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            boolean encrypted = content.startsWith(DPAPI_PREFIX);
            if (encrypted) {
                if (!Dpapi.available()) {
                    return null;
                }
                content = new String(Dpapi.unprotect(Base64.getDecoder().decode(content.substring(DPAPI_PREFIX.length()))), StandardCharsets.UTF_8);
            }
            if (content.isEmpty()) {
                return null;
            }
            Credential credential = content.startsWith("{") ? Manifest.GSON.fromJson(content, Credential.class) : Credential.lasting(content);
            if (credential == null || credential.token() == null || credential.token().isBlank()) {
                return null;
            }
            if (!encrypted && Dpapi.available()) {
                // A file from before, or one written by hand: from now on encrypted
                write(file, credential);
            }
            return credential;
        } catch (IOException | RuntimeException e) {
            // Unreadable, or encrypted for another Windows account: as if logged out
            return null;
        }
    }

    static void write(Path file, Credential credential) throws IOException {
        Files.createDirectories(file.getParent());
        String json = Manifest.GSON.toJson(credential);
        if (Dpapi.available()) {
            Files.writeString(file, DPAPI_PREFIX + Base64.getEncoder().encodeToString(Dpapi.protect(json.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
            return;
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** DPAPI through JNA, which Minecraft brings along; looked up so that a missing JNA just means no encryption */
    private static final class Dpapi {
        private static final boolean AVAILABLE = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && present("com.sun.jna.platform.win32.Crypt32Util");

        static boolean available() {
            return AVAILABLE;
        }

        static byte[] protect(byte[] data) {
            return com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data);
        }

        static byte[] unprotect(byte[] data) {
            return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data);
        }

        private static boolean present(String name) {
            try {
                Class.forName(name, false, TokenStore.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
    }
}
