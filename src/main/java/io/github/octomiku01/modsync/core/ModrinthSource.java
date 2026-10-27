package io.github.octomiku01.modsync.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Modrinth's public API: needs no key, and finds the project of any jar it hosts by the jar's hash */
public final class ModrinthSource implements ModSource {
    public static final String ID = "modrinth";
    private static final String API = "https://api.modrinth.com/v2";

    private final Http http;

    public ModrinthSource(Http http) {
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Download resolve(Manifest.Entry entry) throws IOException, InterruptedException {
        JsonObject version = JsonParser.parseString(http.get(URI.create(API + "/version/" + entry.version()), Map.of())).getAsJsonObject();
        @Nullable JsonObject file = file(version, entry.file());
        if (file == null) {
            throw new IOException("Modrinth version " + entry.version() + " has no file " + entry.file());
        }
        return new Download(URI.create(file.get("url").getAsString()), Map.of());
    }

    /**
     * The entries for the jars Modrinth knows, by their SHA-512; jars it does not know are left out. Only the hashes
     * leave the computer, not the files.
     */
    public Map<String, Manifest.Entry> identify(Collection<String> sha512) throws IOException, InterruptedException {
        if (sha512.isEmpty()) {
            return Map.of();
        }
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        sha512.forEach(hashes::add);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha512");
        JsonObject found = JsonParser.parseString(http.postJson(URI.create(API + "/version_files"), body.toString())).getAsJsonObject();

        Map<String, Manifest.Entry> entries = new HashMap<>();
        for (Map.Entry<String, JsonElement> match : found.entrySet()) {
            JsonObject version = match.getValue().getAsJsonObject();
            @Nullable JsonObject file = byHash(version, match.getKey());
            if (file == null) {
                continue;
            }
            Map<String, String> fileHashes = new LinkedHashMap<>();
            file.getAsJsonObject("hashes").entrySet().forEach(hash -> fileHashes.put(hash.getKey(), hash.getValue().getAsString()));
            entries.put(match.getKey(), new Manifest.Entry(ID, version.get("project_id").getAsString(), version.get("id").getAsString(),
                    file.get("filename").getAsString(), fileHashes, null, null, null));
        }
        return entries;
    }

    private static @Nullable JsonObject file(JsonObject version, String name) {
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            if (file.get("filename").getAsString().equals(name)) {
                return file;
            }
        }
        return null;
    }

    private static @Nullable JsonObject byHash(JsonObject version, String sha512) {
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            JsonElement hash = file.getAsJsonObject("hashes").get("sha512");
            if (hash != null && hash.getAsString().equalsIgnoreCase(sha512)) {
                return file;
            }
        }
        return null;
    }
}
