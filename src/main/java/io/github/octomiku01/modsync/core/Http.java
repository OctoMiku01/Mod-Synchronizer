package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One HTTP client for all sources; Modrinth asks every client to name itself in the User-Agent. Redirects are
 * followed by hand: a token for one host must not reach the next, GitHub sends release downloads on to a CDN that
 * rejects requests with one.
 */
public final class Http {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REDIRECTS = 5;

    /** An answer other than 200 */
    public static final class StatusException extends IOException {
        private final int status;

        StatusException(URI uri, int status) {
            super(uri.getHost() + " answered " + status + " for " + uri);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String userAgent;

    public Http(String version) {
        this.userAgent = "OctoMiku01/Mod-Synchronizer/" + version;
    }

    public String get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        return send(uri, headers, HttpRequest.BodyPublishers.noBody(), "GET");
    }

    public String postJson(URI uri, String json) throws IOException, InterruptedException {
        return send(uri, Map.of("Content-Type", "application/json"), HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8), "POST");
    }

    /** Posts a form and asks for JSON back, as GitHub's login endpoints want it */
    public String postForm(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(uri, Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8), "POST");
    }

    /** Downloads to a temporary file first: the target only ever holds a complete download */
    public void download(URI uri, Map<String, String> headers, Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = follow(uri, headers, HttpRequest.BodyPublishers.noBody(), "GET", HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new StatusException(response.uri(), response.statusCode());
            }
            Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private String send(URI uri, Map<String, String> headers, HttpRequest.BodyPublisher body, String method)
            throws IOException, InterruptedException {
        HttpResponse<String> response = follow(uri, headers, body, method, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new StatusException(response.uri(), response.statusCode());
        }
        return response.body();
    }

    private <T> HttpResponse<T> follow(URI uri, Map<String, String> headers, HttpRequest.BodyPublisher body, String method,
                                       HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        Map<String, String> current = new HashMap<>(headers);
        for (int redirects = 0; ; redirects++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", userAgent).method(method, body);
            current.forEach(request::header);
            HttpResponse<T> response = client.send(request.build(), handler);
            int status = response.statusCode();
            if (status < 300 || status >= 400 || status == 304) {
                return response;
            }
            if (response.body() instanceof InputStream stream) {
                stream.close();
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) {
                throw new IOException(uri.getHost() + " redirected without a target");
            }
            URI next = uri.resolve(location);
            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects from " + uri);
            }
            if (!next.getHost().equalsIgnoreCase(uri.getHost())) {
                current.remove("Authorization");
            }
            if (status != 307 && status != 308) {
                method = "GET";
                body = HttpRequest.BodyPublishers.noBody();
                current.remove("Content-Type");
            }
            uri = next;
        }
    }
}
