package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Where the shared list is, and with it the other files that go with it: they lie in a "files" folder next to the
 * list, under their path in the game folder.
 */
public sealed interface ListLocation {
    String GITHUB_PREFIX = "github:";

    /** A list in a GitHub repo, private ones too: {@code github:owner/repo/path/to/list.json}, optionally with {@code @branch} */
    record GitHub(String repo, String path, @Nullable String ref) implements ListLocation {
        @Override
        public String read(Http http, GitHubSource gitHub) throws IOException, InterruptedException {
            return gitHub.file(repo, path, ref);
        }

        @Override
        public ModSource.Download fileDownload(Http http, GitHubSource gitHub, String file) throws IOException {
            int slash = path.lastIndexOf('/');
            return gitHub.fileDownload(repo, (slash >= 0 ? path.substring(0, slash + 1) : "") + "files/" + file, ref);
        }
    }

    /** A list anywhere else on the web */
    record Url(URI uri) implements ListLocation {
        @Override
        public String read(Http http, GitHubSource gitHub) throws IOException, InterruptedException {
            return http.get(uri, Map.of());
        }

        @Override
        public ModSource.Download fileDownload(Http http, GitHubSource gitHub, String file) {
            String encoded = Stream.of(file.split("/"))
                    .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                    .collect(Collectors.joining("/"));
            return new ModSource.Download(uri.resolve("files/" + encoded), Map.of());
        }
    }

    String read(Http http, GitHubSource gitHub) throws IOException, InterruptedException;

    /** Where a file that goes with the list is downloaded from, by its path in the game folder */
    ModSource.Download fileDownload(Http http, GitHubSource gitHub, String file) throws IOException;

    static ListLocation parse(String location) throws IOException {
        String trimmed = location.strip();
        if (!trimmed.startsWith(GITHUB_PREFIX)) {
            try {
                return new Url(URI.create(trimmed));
            } catch (IllegalArgumentException e) {
                throw new IOException("Not a link to a list: " + trimmed, e);
            }
        }
        String rest = trimmed.substring(GITHUB_PREFIX.length());
        @Nullable String ref = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            ref = rest.substring(at + 1);
            rest = rest.substring(0, at);
        }
        String[] parts = rest.split("/", 3);
        if (parts.length < 3) {
            throw new IOException("Not a list in a GitHub repo, expected github:owner/repo/path: " + trimmed);
        }
        return new GitHub(parts[0] + "/" + parts[1], parts[2], ref);
    }
}
