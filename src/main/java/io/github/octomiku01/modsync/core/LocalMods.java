package io.github.octomiku01.modsync.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/** The jars in the mods folder with their hashes and Fabric mod IDs */
public final class LocalMods {
    /**
     * @param modId the ID in the jar's fabric.mod.json, null for a jar without one
     * @param side  where the mod runs by its fabric.mod.json ("environment"), null for both or when it does not say
     */
    public record LocalMod(Path file, Map<String, String> hashes, @Nullable String modId, @Nullable Manifest.Side side) {
        public String name() {
            return file.getFileName().toString();
        }
    }

    private LocalMods() {
    }

    public static List<LocalMod> scan(Path modsFolder) throws IOException {
        List<LocalMod> mods = new ArrayList<>();
        for (Path file : jars(modsFolder)) {
            @Nullable JsonObject metadata = fabricModJson(file);
            mods.add(new LocalMod(file, Hashes.of(file), string(metadata, "id"), side(string(metadata, "environment"))));
        }
        return mods;
    }

    /** The jars in the mods folder, by name */
    public static List<Path> jars(Path modsFolder) throws IOException {
        if (!Files.isDirectory(modsFolder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(modsFolder)) {
            return files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::getFileName)).toList();
        }
    }

    public static @Nullable String modId(Path jar) {
        return string(fabricModJson(jar), "id");
    }

    private static @Nullable String string(@Nullable JsonObject metadata, String key) {
        JsonElement value = metadata == null ? null : metadata.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static Manifest.@Nullable Side side(@Nullable String environment) {
        return switch (environment == null ? "*" : environment) {
            case "client" -> Manifest.Side.CLIENT;
            case "server" -> Manifest.Side.SERVER;
            default -> null;
        };
    }

    /** The jar's fabric.mod.json, null if it has none or it cannot be read */
    public static @Nullable JsonObject fabricModJson(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(metadata)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return null;
        }
    }
}
