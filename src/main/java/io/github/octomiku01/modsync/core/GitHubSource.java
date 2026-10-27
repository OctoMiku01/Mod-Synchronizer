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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * The repo cannot be read with this login: it is private, and the GitHub App is not installed in it, the access
     * code does not take it in, or the player has no access of their own. GitHub says "404" for it, as if it were not there.
     */
    public static final class NoAccess extends IOException {
        private final String repo;

        NoAccess(String repo) {
            super("No access to " + repo + " on GitHub: install the GitHub App \"Mod Synchronizer\" in it and add it to the access code, "
                    + "or the player needs read access to it");
            this.repo = repo;
        }

        public String repo() {
            return repo;
        }
    }

    /** The repo can be read, but what the list names is not in it: deleted, or the list is wrong */
    public static final class Missing extends IOException {
        private final String what;
        private final String repo;

        Missing(String what, String repo) {
            super(what + " is not in " + repo + " on GitHub: deleted, or the list is wrong");
            this.what = what;
            this.repo = repo;
        }

        public String what() {
            return what;
        }

        public String repo() {
            return repo;
        }
    }

    @Override
    public Download resolve(Manifest.Entry entry) throws IOException, InterruptedException {
        String repo = repo(entry.project());
        JsonObject release;
        try {
            release = getJson("/repos/" + repo + "/releases/tags/" + encode(entry.version())).getAsJsonObject();
        } catch (Http.StatusException e) {
            throw e.status() == 404 ? notFound(repo, "Release " + entry.version()) : e;
        }
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

    /**
     * The entry as the repo's newest release has it, for an entry that follows the newest one; the entry as it is if
     * the release it names is the newest.
     * <p>
     * A repo can have releases for several Minecraft versions: if the release named has the list's Minecraft version
     * in its tag or file name, only releases that have it too count. The jar is the release asset whose name has the
     * shape of the one named (the same but for the numbers), else the only jar. Pre-releases count only if the one
     * named is one. The release must state the jar's SHA-256 checksum, which GitHub does for releases since mid-2025:
     * that is what the download is checked against.
     *
     * @return null if no release fits, then the entry as it is stays
     */
    public Manifest.@Nullable Entry newest(Manifest.Entry entry, String minecraft) throws IOException, InterruptedException {
        String repo = repo(entry.project());
        JsonArray all;
        try {
            all = getJson("/repos/" + repo + "/releases?per_page=30").getAsJsonArray();
        } catch (Http.StatusException e) {
            throw e.status() == 404 ? notFound(repo, "The releases") : e;
        }
        Pattern version = minecraftVersion(minecraft);
        boolean forVersion = version.matcher(entry.version()).find() || version.matcher(entry.file()).find();
        @Nullable Boolean prerelease = null;
        for (JsonElement element : all) {
            JsonObject release = element.getAsJsonObject();
            if (release.get("tag_name").getAsString().equals(entry.version())) {
                prerelease = release.get("prerelease").getAsBoolean();
            }
        }
        for (JsonElement element : all) {
            JsonObject release = element.getAsJsonObject();
            String tag = release.get("tag_name").getAsString();
            if (tag.equals(entry.version())) {
                // Newest first: nothing newer fits
                return entry;
            }
            if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean() && !Boolean.TRUE.equals(prerelease)) {
                continue;
            }
            @Nullable JsonObject asset = jar(release.getAsJsonArray("assets"), entry.file());
            if (asset == null || forVersion && !version.matcher(tag).find() && !version.matcher(asset.get("name").getAsString()).find()) {
                continue;
            }
            JsonElement digest = asset.get("digest");
            if (digest == null || digest.isJsonNull() || !digest.getAsString().startsWith("sha256:")) {
                continue;
            }
            Map<String, String> hashes = new HashMap<>();
            hashes.put("sha256", digest.getAsString().substring("sha256:".length()).toLowerCase(Locale.ROOT));
            return entry.withRelease(tag, asset.get("name").getAsString(), hashes);
        }
        return null;
    }

    /** "26.2" in "v1.0.0-26.2" or "mod-1.0.0+26.2.jar", but not in "26.2.1" or "126.2" */
    private static Pattern minecraftVersion(String minecraft) {
        return Pattern.compile("(?<![0-9.])" + Pattern.quote(minecraft) + "(?![0-9]|\\.[0-9])");
    }

    /** The asset of a release that is the mod: the jar shaped like the one named, else the only jar besides sources and the like */
    private static @Nullable JsonObject jar(JsonArray assets, String named) {
        String shape = shape(named);
        List<JsonObject> jars = new ArrayList<>();
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (shape(name).equals(shape)) {
                return asset;
            }
            if (name.endsWith(".jar") && !name.matches(".*-(sources|javadoc|dev|api)\\.jar")) {
                jars.add(asset);
            }
        }
        return jars.size() == 1 ? jars.getFirst() : null;
    }

    private static String shape(String name) {
        return name.replaceAll("[0-9]+", "#");
    }

    /** A file from a repo, like the shared list itself; {@code ref} is a branch, tag or commit, the default branch when null */
    public String file(String repo, String path, @Nullable String ref) throws IOException, InterruptedException {
        try {
            return get(contents(repo, path, ref), "application/vnd.github.raw+json");
        } catch (Http.StatusException e) {
            throw e.status() == 404 ? notFound(repo(repo), path) : e;
        }
    }

    /** What a 404 means: GitHub answers it for what is not there and for private repos it does not let this login see */
    private IOException notFound(String repo, String what) throws InterruptedException {
        try {
            get("/repos/" + repo, "application/vnd.github+json");
            return new Missing(what, repo);
        } catch (IOException e) {
            return new NoAccess(repo);
        }
    }

    /** Where a file from a repo is downloaded from, like a config that goes with the list; up to 100 MB */
    public Download fileDownload(String repo, String path, @Nullable String ref) throws IOException {
        Map<String, String> headers = new HashMap<>(auth.headers());
        headers.put("Accept", "application/vnd.github.raw+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return new Download(URI.create(API + contents(repo, path, ref)), headers);
    }

    /** The whole repo as a zip, in one request instead of one per file; {@code ref} as for {@link #file} */
    public Download zipball(String repo, @Nullable String ref) throws IOException {
        Map<String, String> headers = new HashMap<>(auth.headers());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return new Download(URI.create(API + "/repos/" + repo(repo) + "/zipball" + (ref != null ? "/" + encode(ref) : "")), headers);
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
            } catch (Http.StatusException | GitHubAuth.LoginRequired | NoAccess e) {
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
                            new HashMap<>(mod.hashes()), null, mod.modId(), null);
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
            if (e.status() == 401) {
                // A login GitHub no longer takes: withdrawn on github.com, an access code run out, or one of an app that
                // is gone. A new one it is.
                auth.logout();
                throw new GitHubAuth.LoginRequired("GitHub no longer takes the login, a new one is needed for " + path);
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
