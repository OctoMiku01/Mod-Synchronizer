package io.github.octomiku01.modsync.core;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which jar in the mods folder belongs to which entry of the list ({@link Manifest.Entry#key()} to file name). Only
 * these jars are ever replaced or removed: mods the player put there themselves stay untouched.
 */
public final class SyncState {
    private SyncState() {
    }

    public static Map<String, String> load(Path file) throws IOException {
        try {
            Map<String, String> state = Manifest.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<TreeMap<String, String>>() { }.getType());
            return state == null ? new TreeMap<>() : state;
        } catch (NoSuchFileException e) {
            return new TreeMap<>();
        } catch (JsonParseException e) {
            throw new IOException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    public static void save(Path file, Map<String, String> state) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Manifest.GSON.toJson(new TreeMap<>(state)), StandardCharsets.UTF_8);
    }
}
