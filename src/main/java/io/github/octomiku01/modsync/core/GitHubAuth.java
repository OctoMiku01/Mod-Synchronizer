package io.github.octomiku01.modsync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The player's GitHub login, through GitHub's device flow: the player enters a short code on github.com, and no
 * password or secret ever passes through the game. The token is kept per user, not per game folder, so it does not
 * end up in exported instances, and on Windows encrypted (see {@link TokenStore}); a personal access token written
 * into the same file by hand works as well. A GitHub App with expiring tokens hands out ones that last 8 hours, with a
 * refresh token that lasts 6 months: they are renewed shortly before they run out, without a client secret, which
 * GitHub does not ask for with tokens from the device flow.
 */
public final class GitHubAuth {
    /**
     * Reading private repos takes the "repo" scope from an OAuth app, which has no narrower one for it; a GitHub App
     * ignores scopes and grants the permissions it was set up with instead
     */
    private static final String SCOPE = "repo";

    /** What the player has to enter, and where */
    public record DeviceCode(String deviceCode, String userCode, URI verificationUri, int interval, int expiresIn) {
    }

    /** GitHub refused an anonymous request: the repo is private, or the anonymous rate limit is used up */
    public static final class LoginRequired extends IOException {
        public LoginRequired(String message) {
            super(message);
        }
    }

    /** Renewed this long before it runs out, so that no request goes out with a token about to expire */
    private static final long RENEW_BEFORE_SECONDS = 300;

    private final Http http;
    private final Path tokenFile;
    private final Supplier<String> clientId;
    private volatile TokenStore.@Nullable Credential credential;

    /** @param clientId the app the login goes through, also for renewing it */
    public GitHubAuth(Http http, Path tokenFile, Supplier<String> clientId) {
        this.http = http;
        this.tokenFile = tokenFile;
        this.clientId = clientId;
        this.credential = TokenStore.read(tokenFile);
    }

    /** Where the token is kept; an access code written there by hand works as well */
    public Path tokenFile() {
        return tokenFile;
    }

    public boolean loggedIn() {
        return credential != null;
    }

    /** The headers that authorize a request to the GitHub API, none while logged out; renews the token first if due */
    public synchronized Map<String, String> headers() {
        renewIfDue();
        TokenStore.@Nullable Credential current = credential;
        return current == null ? Map.of() : Map.of("Authorization", "Bearer " + current.token());
    }

    private void renewIfDue() {
        TokenStore.@Nullable Credential current = credential;
        long now = System.currentTimeMillis() / 1000;
        if (current == null || current.refreshToken() == null || current.expiresAt() == 0 || now < current.expiresAt() - RENEW_BEFORE_SECONDS) {
            return;
        }
        if (current.refreshExpiresAt() != 0 && now >= current.refreshExpiresAt()) {
            // Not used for half a year: a new login it is
            forget();
            return;
        }
        try {
            JsonObject answer = JsonParser.parseString(http.postForm(URI.create("https://github.com/login/oauth/access_token"),
                    Map.of("client_id", clientId.get(), "grant_type", "refresh_token", "refresh_token", current.refreshToken()))).getAsJsonObject();
            if (answer.has("access_token")) {
                save(credential(answer));
            } else if (answer.has("error")) {
                // Withdrawn on github.com, or used up: a new login it is
                forget();
            }
        } catch (IOException | RuntimeException e) {
            // GitHub not reachable right now: the token may still last a little, else the request says so
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void forget() {
        credential = null;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            // Read again, and found expired again, at the next start
        }
    }

    /** A token, and if it expires its refresh token and when both run out, from GitHub's answer */
    private static TokenStore.Credential credential(JsonObject answer) {
        long now = System.currentTimeMillis() / 1000;
        JsonElement refresh = answer.get("refresh_token");
        JsonElement expiresIn = answer.get("expires_in");
        JsonElement refreshExpiresIn = answer.get("refresh_token_expires_in");
        return new TokenStore.Credential(answer.get("access_token").getAsString(), refresh == null ? null : refresh.getAsString(),
                expiresIn == null ? 0 : now + expiresIn.getAsLong(), refreshExpiresIn == null ? 0 : now + refreshExpiresIn.getAsLong());
    }

    public DeviceCode start(String clientId) throws IOException, InterruptedException {
        JsonObject answer;
        try {
            answer = JsonParser.parseString(http.postForm(URI.create("https://github.com/login/device/code"),
                    Map.of("client_id", clientId, "scope", SCOPE))).getAsJsonObject();
        } catch (Http.StatusException e) {
            throw new IOException("GitHub does not know the OAuth app " + clientId + ", or device flow is not enabled for it", e);
        }
        if (answer.has("error")) {
            throw new IOException("GitHub refused the login: " + describe(answer));
        }
        return new DeviceCode(answer.get("device_code").getAsString(), answer.get("user_code").getAsString(),
                URI.create(answer.get("verification_uri").getAsString()), answer.get("interval").getAsInt(), answer.get("expires_in").getAsInt());
    }

    /** Waits until the player has entered the code, then keeps the token */
    public void await(String clientId, DeviceCode code) throws IOException, InterruptedException {
        int interval = code.interval();
        long deadline = System.nanoTime() + code.expiresIn() * 1_000_000_000L;
        while (System.nanoTime() - deadline < 0) {
            Thread.sleep(interval * 1000L);
            JsonObject answer = JsonParser.parseString(http.postForm(URI.create("https://github.com/login/oauth/access_token"),
                    Map.of("client_id", clientId, "device_code", code.deviceCode(), "grant_type", "urn:ietf:params:oauth:grant-type:device_code")))
                    .getAsJsonObject();
            if (answer.has("access_token")) {
                save(credential(answer));
                return;
            }
            switch (answer.has("error") ? answer.get("error").getAsString() : "") {
                case "authorization_pending" -> {
                }
                case "slow_down" -> interval = answer.has("interval") ? answer.get("interval").getAsInt() : interval + 5;
                default -> throw new IOException("GitHub refused the login: " + describe(answer));
            }
        }
        throw new IOException("The code was not entered in time");
    }

    /**
     * Keeps a token the player entered instead of logging in, like a personal access token with read access to the
     * repos handed out as an access code to players without a GitHub account; only once GitHub accepts it
     */
    public void useToken(String newToken) throws IOException, InterruptedException {
        try {
            http.get(URI.create("https://api.github.com/rate_limit"),
                    Map.of("Authorization", "Bearer " + newToken, "Accept", "application/vnd.github+json"));
        } catch (Http.StatusException e) {
            throw new IOException("GitHub does not accept the access code (" + e.status() + ")", e);
        }
        save(TokenStore.Credential.lasting(newToken));
    }

    public void logout() throws IOException {
        credential = null;
        Files.deleteIfExists(tokenFile);
    }

    private void save(TokenStore.Credential newCredential) throws IOException {
        TokenStore.write(tokenFile, newCredential);
        credential = newCredential;
    }

    private static String describe(JsonObject answer) {
        JsonElement description = answer.get("error_description");
        return description != null ? description.getAsString() : answer.get("error").getAsString();
    }
}
