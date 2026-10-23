package io.github.octomiku01.modsync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The player's GitHub login, through GitHub's device flow: the player enters a short code on github.com, and no
 * password or secret ever passes through the game. The token is kept per user, not per game folder, so it does not
 * end up in exported instances; a personal access token written into the same file by hand works as well.
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

    private final Http http;
    private final Path tokenFile;
    private volatile @Nullable String token;

    public GitHubAuth(Http http, Path tokenFile) {
        this.http = http;
        this.tokenFile = tokenFile;
        try {
            String stored = Files.exists(tokenFile) ? Files.readString(tokenFile, StandardCharsets.UTF_8).strip() : "";
            token = stored.isEmpty() ? null : stored;
        } catch (IOException e) {
            token = null;
        }
    }

    public boolean loggedIn() {
        return token != null;
    }

    /** The headers that authorize a request to the GitHub API, none while logged out */
    public Map<String, String> headers() {
        String current = token;
        return current == null ? Map.of() : Map.of("Authorization", "Bearer " + current);
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
            JsonElement accessToken = answer.get("access_token");
            if (accessToken != null) {
                save(accessToken.getAsString());
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

    public void logout() throws IOException {
        token = null;
        Files.deleteIfExists(tokenFile);
    }

    private void save(String newToken) throws IOException {
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, newToken, StandardCharsets.UTF_8);
        token = newToken;
    }

    private static String describe(JsonObject answer) {
        JsonElement description = answer.get("error_description");
        return description != null ? description.getAsString() : answer.get("error").getAsString();
    }
}
