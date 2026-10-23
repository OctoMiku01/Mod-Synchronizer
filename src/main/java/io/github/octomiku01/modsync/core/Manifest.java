package io.github.octomiku01.modsync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The shared mod list: which mod comes from where, and the hashes its file must have. Only the list is shared, never
 * the jars: every player downloads them from their source.
 *
 * @param minecraft the Minecraft version the list is for, like "26.2"
 * @param loader    always "fabric" for now
 * @param mods      the mods of the list
 */
public record Manifest(String minecraft, String loader, List<Entry> mods) {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * One mod of the list.
     *
     * @param source  where it comes from: "modrinth", "curseforge" or "github"
     * @param project the project on that source: Modrinth project id, CurseForge project id, or "owner/repo"
     * @param version the exact file: Modrinth version id, CurseForge file id, or the GitHub release tag
     * @param file    the file name, for GitHub also the release asset to download
     * @param hashes  algorithm ("sha1", "sha256", "sha512") to lowercase hex; a download must match them
     * @param side    where the mod is needed, both when left out
     * @param modId   the Fabric mod ID in the jar: another version of the same mod in the mods folder is replaced, as
     *                Fabric does not start with two; optional, but without it such a jar is only found after the download
     */
    public record Entry(String source, String project, String version, String file, Map<String, String> hashes,
                        @Nullable Side side, @Nullable String modId) {
        public Entry withModId(@Nullable String id) {
            return new Entry(source, project, version, file, hashes, side, id);
        }

        /** Stays the same across versions of the mod: an entry with the same key replaces the older file */
        public String key() {
            return source + ":" + project;
        }

        public boolean neededOn(Side here) {
            return side == null || side == Side.BOTH || side == here;
        }
    }

    public enum Side {
        @SerializedName("client") CLIENT,
        @SerializedName("server") SERVER,
        @SerializedName("both") BOTH
    }

    public static Manifest parse(String json) {
        Manifest manifest = GSON.fromJson(json, Manifest.class);
        if (manifest == null || manifest.mods() == null) {
            throw new JsonParseException("not a mod list");
        }
        for (Entry entry : manifest.mods()) {
            if (entry.source() == null || entry.project() == null || entry.file() == null || entry.hashes() == null || entry.hashes().isEmpty()) {
                throw new JsonParseException("an entry misses its source, project, file or hashes: " + entry);
            }
        }
        return manifest;
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}
