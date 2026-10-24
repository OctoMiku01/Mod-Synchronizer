package io.github.octomiku01.modsync.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Release assets on GitHub, from public and private repos. Entries name the repo as "owner/repo", the release by
 * its tag and the asset by its file name.
 */
public final class GitHubSource implements ModSource {
    public static final String ID = "github";
    private static final String API = "https://api.github.com";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern REPO_LINK = Pattern.compile("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/#?].*)?$");

    private final Http http;
    private final GitHubAuth auth;
    /** The releases per repo, while identifying jars for an export */
    private final Map<String, JsonArray> releases = new HashMap<>();

    public GitHubSource(Http http, GitHubAuth auth) {
        this.http = http;
        this.auth = auth;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Download resolve(Manifest.Entry entry) throws IOException, InterruptedException {
        JsonObject release = getJson("/repos/" + repo(entry.project()) + "/releases/tags/" + encode(entry.version())).getAsJsonObject();
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (asset.get("name").getAsString().equals(entry.file())) {
                // The API link of the asset, not browser_download_url: only the API one works for private repos
                Map<String, String> headers = new HashMap<>(auth.headers());
                headers.put("Accept", "application/octet-stream");
                return new Download(URI.create(asset.get("url").getAsString()), headers);
            }
        }
        throw new IOException("Release " + entry.version() + " of " + entry.project() + " has no file " + entry.file());
    }

    /** A file from a repo, like the shared list itself; {@code ref} is a branch, tag or commit, the default branch when null */
    public String file(String repo, String path, @Nullable String ref) throws IOException, InterruptedException {
        return get(contents(repo, path, ref), "application/vnd.github.raw+json");
    }

    /** Where a file from a repo is downloaded from, like a config that goes with the list; up to 100 MB */
    public Download fileDownload(String repo, String path, @Nullable String ref) throws IOException {
        Map<String, String> headers = new HashMap<>(auth.headers());
        headers.put("Accept", "application/vnd.github.raw+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return new Download(URI.create(API + contents(repo, path, ref)), headers);
    }

    private static String contents(String repo, String path, @Nullable String ref) throws IOException {
        StringBuilder url = new StringBuilder("/repos/").append(repo(repo)).append("/contents");
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                url.append('/').append(encode(part));
            }
        }
        if (ref != null) {
            url.append("?ref=").append(encode(ref));
        }
        return url.toString();
    }

    /**
     * The entry for a jar whose fabric.mod.json links a GitHub repo that has the jar as a release asset, found by the
     * SHA-256 checksum GitHub states for every asset.
     */
    public @Nullable Manifest.Entry identify(LocalMods.LocalMod mod) throws IOException, InterruptedException {
        @Nullable String repo = linkedRepo(mod.file());
        String sha256 = mod.hashes().get("sha256");
        if (repo == null || sha256 == null) {
            return null;
        }
        JsonArray all = releases.get(repo);
        if (all == null) {
            try {
                all = getJson("/repos/" + repo + "/releases?per_page=100").getAsJsonArray();
            } catch (Http.StatusException | GitHubAuth.LoginRequired e) {
                // A link to a repo that is gone, or that this login cannot see
                all = new JsonArray();
            }
            releases.put(repo, all);
        }
        for (JsonElement releaseElement : all) {
            JsonObject release = releaseElement.getAsJsonObject();
            for (JsonElement assetElement : release.getAsJsonArray("assets")) {
                JsonObject asset = assetElement.getAsJsonObject();
                JsonElement digest = asset.get("digest");
                if (digest != null && !digest.isJsonNull() && digest.getAsString().equalsIgnoreCase("sha256:" + sha256)) {
                    return new Manifest.Entry(ID, repo, release.get("tag_name").getAsString(), asset.get("name").getAsString(),
                            new HashMap<>(mod.hashes()), null, mod.modId());
                }
            }
        }
        return null;
    }

    /**
     * The "owner/repo" of a mod: {@code custom.modsync.github} in its fabric.mod.json if set (for forks whose contact
     * links rightly point to the original), else the GitHub repo its sources, homepage or issues link
     */
    static @Nullable String linkedRepo(Path jar) {
        @Nullable JsonObject metadata = LocalMods.fabricModJson(jar);
        if (metadata == null) {
            return null;
        }
        @Nullable JsonElement own = child(child(child(metadata, "custom"), "modsync"), "github");
        if (own != null && own.isJsonPrimitive()) {
            try {
                return repo(own.getAsString());
            } catch (IOException e) {
                return null;
            }
        }
        @Nullable JsonElement contact = child(metadata, "contact");
        for (String key : new String[]{"sources", "homepage", "issues"}) {
            @Nullable JsonElement link = child(contact, key);
            if (link != null && link.isJsonPrimitive()) {
                Matcher matcher = REPO_LINK.matcher(link.getAsString());
                if (matcher.find()) {
                    return matcher.group(1) + "/" + matcher.group(2);
                }
            }
        }
        return null;
    }

    private static @Nullable JsonElement child(@Nullable JsonElement parent, String key) {
        return parent != null && parent.isJsonObject() ? parent.getAsJsonObject().get(key) : null;
    }

    private JsonElement getJson(String path) throws IOException, InterruptedException {
        return JsonParser.parseString(get(path, "application/vnd.github+json"));
    }

    private String get(String path, String accept) throws IOException, InterruptedException {
        Map<String, String> headers = new HashMap<>(auth.headers());
        headers.put("Accept", accept);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        try {
            return http.get(URI.create(API + path), headers);
        } catch (Http.StatusException e) {
            // GitHub hides private repos behind a 404; 401 and 403 are a missing login or the anonymous rate limit
            if (!auth.loggedIn() && (e.status() == 401 || e.status() == 403 || e.status() == 404)) {
                throw new GitHubAuth.LoginRequired("GitHub needs a login for " + path);
            }
            throw e;
        }
    }

    /** Checks the "owner/repo" so that nothing else can slip into the API path */
    private static String repo(String repo) throws IOException {
        if (!REPO.matcher(repo).matches() || Arrays.stream(repo.split("/")).anyMatch(part -> part.matches("\\.+"))) {
            throw new IOException("Not a GitHub repo: " + repo);
        }
        return repo;
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
