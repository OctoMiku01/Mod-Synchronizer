package io.github.octomiku01.modsync;

import com.google.gson.JsonParseException;
import io.github.octomiku01.modsync.core.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/modsync.json}; written back after loading, so that options added in a newer version show up in it and
 * it can be filled in by hand.
 */
public final class ModSyncConfig {
    /**
     * Where the shared list is: a link like a raw.githubusercontent.com one, or {@code github:owner/repo/path/to/list.json}
     * (optionally with {@code @branch} at the end) for a list in a repo, private ones too. Empty: no sync.
     */
    public String manifestUrl = "";
    /** Which mods of the list this installation needs */
    public Manifest.Side side = Manifest.Side.CLIENT;
    /** The client ID of the GitHub OAuth app players log in through, with device flow enabled; empty: the built-in one */
    public String githubClientId = "";

    public static ModSyncConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(ModSync.MOD_ID + ".json");
        ModSyncConfig config = null;
        try {
            if (Files.exists(file)) {
                config = Manifest.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ModSyncConfig.class);
            }
        } catch (IOException | JsonParseException e) {
            ModSync.LOGGER.error("Could not read {}, syncing is off", file, e);
            return new ModSyncConfig();
        }
        if (config == null) {
            config = new ModSyncConfig();
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Manifest.GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModSync.LOGGER.warn("Could not write {}", file, e);
        }
        return config;
    }
}
