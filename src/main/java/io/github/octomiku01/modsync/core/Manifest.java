package io.github.octomiku01.modsync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The shared mod list: which mod comes from where, and the hashes its file must have. Only the list is shared, never
 * the jars: every player downloads them from their source.
 *
 * @param minecraft the Minecraft version the list is for, like "26.2"
 * @param loader    always "fabric" for now
 * @param mods      the mods of the list
 * @param files     other files of the game folder that go with the list, like configs; none when left out
 */
public record Manifest(String minecraft, String loader, List<Entry> mods, @Nullable List<FileEntry> files) {
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
     * @param latest  for GitHub: true to follow the repo's newest release for the list's Minecraft version instead of
     *                the one named, so that a new release reaches every player without a new export; the release
     *                named stays the fallback
     */
    public record Entry(String source, String project, String version, String file, Map<String, String> hashes,
                        @Nullable Side side, @Nullable String modId, @Nullable Boolean latest) {
        public Entry withModId(@Nullable String id) {
            return new Entry(source, project, version, file, hashes, side, id, latest);
        }

        public Entry withSide(@Nullable Side newSide) {
            return new Entry(source, project, version, file, hashes, newSide, modId, latest);
        }

        /** Left out of the list when not following, so that lists stay as they were */
        public Entry withLatest(boolean follow) {
            return new Entry(source, project, version, file, hashes, side, modId, follow ? Boolean.TRUE : null);
        }

        /** The same mod in another release, still following */
        public Entry withRelease(String newVersion, String newFile, Map<String, String> newHashes) {
            return new Entry(source, project, newVersion, newFile, newHashes, side, modId, latest);
        }

        public boolean followsLatest() {
            return Boolean.TRUE.equals(latest);
        }

        /** Stays the same across versions of the mod: an entry with the same key replaces the older file */
        public String key() {
            return source + ":" + project;
        }

        public boolean neededOn(Side here) {
            return side == null || side == Side.BOTH || side == here;
        }
    }

    /**
     * A file of the game folder outside the mods folder, like a config, a resource pack or a shader pack.
     *
     * @param path    where it goes, relative to the game folder, with "/" between folders, like "config/sodium-options.json"
     * @param hashes  as for mods: a download must match them
     * @param source  "modrinth" for a pack Modrinth hosts, else (left out) the file lies next to the list, in its
     *                "files" folder under the same path
     * @param project for Modrinth: the project id
     * @param version for Modrinth: the version id
     * @param file    for Modrinth: the file name of the version
     * @param side    where the file is needed, both when left out; resource and shader packs only on clients, say
     */
    public record FileEntry(String path, Map<String, String> hashes, @Nullable String source, @Nullable String project,
                            @Nullable String version, @Nullable String file, @Nullable Side side) {
        public boolean neededOn(Side here) {
            return side == null || side == Side.BOTH || side == here;
        }

        /** Folders a list may never write into: the mods (they have their own entries), this mod's own, and the player's own data */
        private static final Set<String> FORBIDDEN = Set.of("mods", ".modsync", "saves", "logs", "crash-reports", "screenshots",
                "versions", "libraries", "assets", ".fabric");

        /** Whether the list says where the file is: next to the list, or on Modrinth */
        public boolean nextToList() {
            return source == null;
        }

        /** Only plain relative paths inside the game folder, and none into the folders above */
        public static void check(String path) {
            String[] parts = path.split("/");
            boolean valid = !path.isBlank() && !path.contains("\\") && !path.contains(":") && !path.startsWith("/")
                    && !FORBIDDEN.contains(parts[0].toLowerCase(Locale.ROOT));
            for (String part : parts) {
                valid &= !part.isEmpty() && !part.equals(".") && !part.equals("..");
            }
            if (!valid) {
                throw new JsonParseException("a file of the list may not go to " + path);
            }
        }
    }

    public enum Side {
        @SerializedName("client") CLIENT,
        @SerializedName("server") SERVER,
        @SerializedName("both") BOTH
    }

    /**
     * The list cannot be read: not JSON, or not a list. Says where, and whether Git left the markers of a merge
     * conflict in it, which is the usual cause when the list's owner keeps it in a repo.
     *
     * @param line     the line it goes wrong in, 0 if not known
     * @param conflict whether the list holds conflict markers ("<<<<<<<", "=======", ">>>>>>>")
     */
    public static final class Invalid extends IOException {
        private final int line;
        private final boolean conflict;

        Invalid(String message, int line, boolean conflict, Throwable cause) {
            super(message, cause);
            this.line = line;
            this.conflict = conflict;
        }

        public int line() {
            return line;
        }

        public boolean conflict() {
            return conflict;
        }
    }

    /** Reads the list as it comes from its place, saying where it goes wrong if it cannot be read */
    public static Manifest read(String json) throws Invalid {
        try {
            return parse(json);
        } catch (JsonParseException e) {
            String[] lines = json.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("<<<<<<< ") || lines[i].startsWith(">>>>>>> ")) {
                    throw new Invalid("The list holds the markers of a Git merge conflict from line " + (i + 1)
                            + " on: resolve the conflict in the list's repo and push it again", i + 1, true, e);
                }
            }
            Matcher at = Pattern.compile("line (\\d+)").matcher(String.valueOf(e.getMessage()));
            int line = at.find() ? Integer.parseInt(at.group(1)) : 0;
            throw new Invalid("The list is not valid" + (line > 0 ? " at line " + line : "") + ": " + e.getMessage(), line, false, e);
        }
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
        if (manifest.files() != null) {
            for (FileEntry file : manifest.files()) {
                if (file.path() == null || file.hashes() == null || file.hashes().isEmpty()) {
                    throw new JsonParseException("a file misses its path or hashes: " + file);
                }
                FileEntry.check(file.path());
                if (!file.nextToList() && (file.project() == null || file.version() == null || file.file() == null)) {
                    throw new JsonParseException("a file from " + file.source() + " misses its project, version or file: " + file);
                }
            }
        }
        return manifest;
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}
