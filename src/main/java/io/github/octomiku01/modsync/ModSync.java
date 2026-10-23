package io.github.octomiku01.modsync;

import io.github.octomiku01.modsync.core.ApplyHelper;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.GitHubSource;
import io.github.octomiku01.modsync.core.Http;
import io.github.octomiku01.modsync.core.LocalMods;
import io.github.octomiku01.modsync.core.Manifest;
import io.github.octomiku01.modsync.core.ModSource;
import io.github.octomiku01.modsync.core.ModrinthSource;
import io.github.octomiku01.modsync.core.Stager;
import io.github.octomiku01.modsync.core.SyncPlan;
import io.github.octomiku01.modsync.core.SyncState;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ties the Minecraft-free core to this installation: its folders, its config and its Minecraft version */
public final class ModSync {
    public static final String MOD_ID = "modsync";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mod Synchronizer");
    /**
     * The GitHub App players log in through, with device flow on and only read access to repo contents; it reads just
     * the repos it is installed in. A client ID is public, only the (unused) secret and private key are not.
     */
    private static final String GITHUB_CLIENT_ID = "Iv23liXIgToUgiXmtnxw";
    private static final String GITHUB_PREFIX = "github:";

    private final Http http = new Http(FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
    /** Kept per user, outside the game folder: it is the same login for every instance */
    private final GitHubAuth gitHubAuth = new GitHubAuth(http, Path.of(System.getProperty("user.home"), "." + MOD_ID, "github-token"));
    private final ModrinthSource modrinth = new ModrinthSource(http);
    private final GitHubSource gitHub = new GitHubSource(http, gitHubAuth);
    private final Map<String, ModSource> sources = Map.of(modrinth.id(), modrinth, gitHub.id(), gitHub);
    private final Path gameFolder = FabricLoader.getInstance().getGameDir();
    private final Path modsFolder = gameFolder.resolve("mods");
    private final Stager stager = new Stager(http, sources, gameFolder.resolve("." + MOD_ID));
    private final ModSyncConfig config;

    public ModSync(ModSyncConfig config) {
        this.config = config;
    }

    public ModSyncConfig config() {
        return config;
    }

    public GitHubAuth gitHubAuth() {
        return gitHubAuth;
    }

    public String gitHubClientId() {
        return config.githubClientId.isBlank() ? GITHUB_CLIENT_ID : config.githubClientId.strip();
    }

    /** Fetches the list and compares it with the mods folder */
    public SyncPlan plan() throws IOException, InterruptedException {
        Manifest manifest = Manifest.parse(fetchManifest());
        String minecraft = minecraftVersion();
        if (!minecraft.equals(manifest.minecraft()) || !"fabric".equals(manifest.loader())) {
            throw new IOException("The mod list is for " + manifest.loader() + " " + manifest.minecraft() + ", this is fabric " + minecraft);
        }
        return SyncPlan.compute(manifest, LocalMods.scan(modsFolder), SyncState.load(stager.stateFile()), config.side,
                sources.keySet(), modsFolder);
    }

    /** Downloads the plan's jars and starts the helper that swaps them in once the game has exited */
    public void stage(SyncPlan plan, IntConsumer progress) throws IOException, InterruptedException {
        Path steps = stager.stage(plan, modsFolder, progress);
        List<Path> classPath = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getOrigin().getPaths();
        ApplyHelper.launch(steps, classPath);
    }

    /**
     * Writes the mods folder as a list to {@code modsync-export.json} in the game folder. Jars get their Modrinth
     * entry if Modrinth knows them, else their GitHub entry if their fabric.mod.json links a repo that has them as a
     * release asset; the others an entry with source "file" to fill in by hand.
     *
     * @return the written file
     */
    public Path export() throws IOException, InterruptedException {
        List<LocalMods.LocalMod> local = LocalMods.scan(modsFolder);
        Map<String, Manifest.Entry> known = modrinth.identify(local.stream().map(mod -> mod.hashes().get("sha512")).toList());
        List<Manifest.Entry> entries = new ArrayList<>();
        for (LocalMods.LocalMod mod : local) {
            Manifest.Entry entry = known.get(mod.hashes().get("sha512"));
            if (entry == null) {
                entry = gitHub.identify(mod);
            }
            if (entry == null) {
                entry = new Manifest.Entry("file", mod.name(), "", mod.name(), new LinkedHashMap<>(mod.hashes()), null, null);
            }
            entries.add(entry.withModId(mod.modId()));
        }
        Path file = gameFolder.resolve(MOD_ID + "-export.json");
        Files.writeString(file, new Manifest(minecraftVersion(), "fabric", entries).toJson(), StandardCharsets.UTF_8);
        return file;
    }

    private String fetchManifest() throws IOException, InterruptedException {
        String location = config.manifestUrl.strip();
        if (!location.startsWith(GITHUB_PREFIX)) {
            return http.get(URI.create(location), Map.of());
        }
        // github:owner/repo/path/to/list.json@branch
        String rest = location.substring(GITHUB_PREFIX.length());
        String ref = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            ref = rest.substring(at + 1);
            rest = rest.substring(0, at);
        }
        String[] parts = rest.split("/", 3);
        if (parts.length < 3) {
            throw new IOException("Not a list in a GitHub repo, expected github:owner/repo/path: " + location);
        }
        return gitHub.file(parts[0] + "/" + parts[1], parts[2], ref);
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
    }
}
