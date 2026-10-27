package io.github.octomiku01.modsync;

import com.google.gson.JsonParseException;
import io.github.octomiku01.modsync.core.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
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
    /**
     * On a dedicated server, which cannot ask: whether the list's version replaces files the server has its own
     * version of, like configs changed there
     */
    public boolean replaceChangedFiles = false;
    /**
     * Whether an export lets the mods from GitHub releases follow their repo's newest release: a new release then
     * reaches every player at their next start, without a new export
     */
    public boolean exportFollowLatest = true;
    /** Where an export goes, like a local clone of the list's repo; empty: modsync-export in the game folder */
    public String exportFolder = "";
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
            // A dedicated server needs the server mods of the list, not the client ones
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                config.side = Manifest.Side.SERVER;
            }
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
