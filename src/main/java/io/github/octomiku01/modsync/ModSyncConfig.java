package io.github.octomiku01.modsync;

import com.google.gson.JsonParseException;
import io.github.octomiku01.modsync.core.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/modsync.json}, set on the settings screen; written back after loading, so that options added in a
 * newer version show up in it.
 */
public final class ModSyncConfig {
    /**
     * Where the shared list is: a link like a raw.githubusercontent.com one, or {@code github:owner/repo/path/to/list.json}
     * (optionally with {@code @branch} at the end) for a list in a repo, private ones too. Empty: no sync.
     */
    public String manifestUrl = "";
    /** Which mods of the list this installation needs */
    public Manifest.Side side = Manifest.Side.CLIENT;
    /**
     * Mod IDs (or file names) this installation keeps out of the sync, like a mod of
     * which the player has their own version
     */
    public List<String> ignoredMods = new ArrayList<>();
    /** The files and folders of the game folder that an export puts into the list besides the mods, like "config" */
    public List<String> exportPaths = new ArrayList<>();
    /** The client ID of a GitHub App or OAuth app to log in through instead of the built-in one, with device flow on */
    public String githubClientId = "";

    public static ModSyncConfig load() {
        ModSyncConfig config = null;
        try {
            if (Files.exists(file())) {
                config = Manifest.GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), ModSyncConfig.class);
            }
        } catch (IOException | JsonParseException e) {
            ModSync.LOGGER.error("Could not read {}, syncing is off", file(), e);
            return new ModSyncConfig();
        }
        if (config == null) {
            config = new ModSyncConfig();
        }
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Manifest.GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModSync.LOGGER.warn("Could not write {}", file(), e);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(ModSync.MOD_ID + ".json");
    }
}
