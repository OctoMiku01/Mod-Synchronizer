package io.github.octomiku01.modsync;

import com.google.gson.JsonParseException;
import io.github.octomiku01.modsync.core.ApplyHelper;
import io.github.octomiku01.modsync.core.FilePlan;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.GitHubSource;
import io.github.octomiku01.modsync.core.Hashes;
import io.github.octomiku01.modsync.core.Http;
import io.github.octomiku01.modsync.core.ListLocation;
import io.github.octomiku01.modsync.core.LocalMods;
import io.github.octomiku01.modsync.core.Manifest;
import io.github.octomiku01.modsync.core.ModSource;
import io.github.octomiku01.modsync.core.ModrinthSource;
import io.github.octomiku01.modsync.core.Stager;
import io.github.octomiku01.modsync.core.SyncPlan;
import io.github.octomiku01.modsync.core.SyncState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
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
    private static final String GITHUB_CLIENT_ID = "Iv23liYLk4t5YGqMUlti";
    /** GitHub takes files of up to 100 MB, and warns from 50 MB on */
    private static final long MAX_EXPORT_FILE = 50L * 1024 * 1024;

    private final Http http = new Http(FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
    /** Kept per user, outside the game folder: it is the same login for every instance */
    private final GitHubAuth gitHubAuth = new GitHubAuth(http, Path.of(System.getProperty("user.home"), "." + MOD_ID, "github-token"), this::gitHubClientId);
    private final ModrinthSource modrinth = new ModrinthSource(http);
    private final GitHubSource gitHub = new GitHubSource(http, gitHubAuth);
    private final Map<String, ModSource> sources = Map.of(modrinth.id(), modrinth, gitHub.id(), gitHub);
    private final Path gameFolder = FabricLoader.getInstance().getGameDir();
    private final Path modsFolder = gameFolder.resolve("mods");
    private final Path workFolder = gameFolder.resolve("." + MOD_ID);
    private final Stager stager = new Stager(http, sources, workFolder);
    private final ModSyncConfig config;
    /** Where the list was read from last, which is also where its other files are */
    private volatile @Nullable ListLocation listLocation;

    public ModSync(ModSyncConfig config) {
        this.config = config;
    }

    /** Deletes what earlier syncs left behind, see {@link ApplyHelper#cleanUp} */
    public void cleanUp() {
        ApplyHelper.cleanUp(workFolder);
    }

    /** The files the helper could not put in place when the game last exited; none if it got through */
    public List<String> failedApply() {
        try {
            Path file = workFolder.resolve(ApplyHelper.FAILED);
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Tries the steps left from the last exit again at this one */
    public void retryApply() throws IOException {
        Files.deleteIfExists(workFolder.resolve(ApplyHelper.FAILED));
        scheduleApply(workFolder.resolve("pending.txt"));
    }

    /** Gives up on the steps left from the last exit: the next comparison with the list brings what is still missing */
    public void discardApply() throws IOException {
        Files.deleteIfExists(workFolder.resolve(ApplyHelper.FAILED));
        Files.deleteIfExists(workFolder.resolve("pending.txt"));
    }

    /**
     * Puts the staged files in place once the game is gone: by the helper process, which waits for this one to exit,
     * or on a server that does not run on Windows as it shuts down, since a server in a container takes all its
     * processes with it when it stops, and Linux replaces files that are in use
     */
    private static void scheduleApply(Path steps) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER && !windows) {
            ApplyHelper.applyAtShutdown(steps);
        } else {
            ApplyHelper.launch(steps, classPath());
        }
    }

    private static List<Path> classPath() {
        return FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getOrigin().getPaths();
    }

    public Path gameFolder() {
        return gameFolder;
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

    /** Fetches the list and compares it with the mods folder and the other files that go with it */
    public SyncPlan plan() throws IOException, InterruptedException {
        ListLocation location = ListLocation.parse(config.manifestUrl);
        Manifest manifest = Manifest.parse(location.read(http, gitHub));
        String minecraft = minecraftVersion();
        if (!minecraft.equals(manifest.minecraft()) || !"fabric".equals(manifest.loader())) {
            throw new IOException("The mod list is for " + manifest.loader() + " " + manifest.minecraft() + ", this is fabric " + minecraft);
        }
        listLocation = location;
        Set<String> ignored = Set.copyOf(config.ignoredMods);
        return SyncPlan.compute(manifest, LocalMods.scan(modsFolder), SyncState.load(stager.stateFile()), config.side,
                        sources.keySet(), ignored, modsFolder)
                .withFiles(FilePlan.compute(manifest.files(), gameFolder, SyncState.load(stager.filesStateFile()), config.side, ignored));
    }

    /**
     * Whether the entries the plan skips were shown at start before: they can only be changed in the list, so the
     * start shows them once, and again only once they change
     */
    public boolean skippedShownBefore(SyncPlan plan) {
        try {
            Path file = skippedFile();
            return Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(skipped(plan));
        } catch (IOException e) {
            return false;
        }
    }

    public void markSkippedShown(SyncPlan plan) {
        try {
            Files.createDirectories(skippedFile().getParent());
            Files.writeString(skippedFile(), skipped(plan), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not remember the skipped mods that were shown", e);
        }
    }

    private Path skippedFile() {
        return workFolder.resolve("skipped-shown.txt");
    }

    private static String skipped(SyncPlan plan) {
        List<String> lines = plan.skipped().stream()
                .map(skipped -> skipped.reason() + "\t" + skipped.entry().key() + "\t" + skipped.entry().file())
                .collect(Collectors.toCollection(ArrayList::new));
        plan.files().kept().forEach(file -> lines.add("KEPT\t" + file.path()));
        return lines.stream().sorted().collect(Collectors.joining("\n"));
    }

    /** Downloads the plan's jars and starts the helper that swaps them in once the game has exited */
    public void stage(SyncPlan plan, IntConsumer progress) throws IOException, InterruptedException {
        @Nullable ListLocation location = listLocation;
        if (location == null) {
            throw new IllegalStateException("staged before the list was read");
        }
        Path steps = stager.stage(plan, modsFolder, gameFolder, new Stager.FileResolver() {
            @Override
            public ModSource.Download resolve(Manifest.FileEntry file) throws IOException, InterruptedException {
                return file.nextToList()
                        ? location.fileDownload(http, gitHub, file.path())
                        : modrinth.resolve(new Manifest.Entry(ModrinthSource.ID, file.project(), file.version(), file.file(), file.hashes(), null, null));
            }

            @Override
            public @Nullable ListLocation.Archive archive() throws IOException {
                return location.archive(gitHub);
            }
        }, progress);
        // A new attempt: what the last one could not do is part of this plan, if it is still needed
        Files.deleteIfExists(workFolder.resolve(ApplyHelper.FAILED));
        scheduleApply(steps);
    }

    /**
     * What an export wrote
     *
     * @param folder  the folder with the list and its files, to put into the list's repo as it is
     * @param skipped the paths that were left out, with the reason
     */
    public record Export(Path folder, List<String> skipped) {
    }

    /**
     * Writes the mods folder as a list, together with the given other files and folders of the game folder, to the
     * export folder (see {@link #exportFolder}): {@code modpack.json} and next to it a folder "files" with the
     * files the list brings itself. Jars and packs get their Modrinth entry if Modrinth knows them; jars else their
     * GitHub entry if their fabric.mod.json links a repo that has them as a release asset, or an entry with source
     * "file" to fill in by hand. This mod's own config stays out: it holds the player's own settings.
     *
     * @param paths files and folders relative to the game folder, like "config" or "options.txt"
     */
    public Export export(List<String> paths) throws IOException, InterruptedException {
        List<LocalMods.LocalMod> local = LocalMods.scan(modsFolder);
        Map<String, Manifest.Entry> known = modrinth.identify(local.stream().map(mod -> mod.hashes().get("sha512")).toList());
        Map<String, Manifest.Entry> shared = sharedEntriesOfExceptions();
        List<Manifest.Entry> entries = new ArrayList<>();
        for (LocalMods.LocalMod mod : local) {
            // A mod this installation keeps its own version of goes into the list as the list has it, not as it is here
            Manifest.Entry entry = mod.modId() != null && config.ignoredMods.contains(mod.modId()) ? shared.get(mod.modId()) : null;
            if (entry != null) {
                entries.add(entry);
                continue;
            }
            entry = known.get(mod.hashes().get("sha512"));
            if (entry == null) {
                entry = gitHub.identify(mod);
            }
            if (entry == null) {
                entry = new Manifest.Entry("file", mod.name(), "", mod.name(), new LinkedHashMap<>(mod.hashes()), null, null);
            }
            // Where it runs as the mod itself says: a server then leaves out the client mods, and the other way round
            entries.add(entry.withModId(mod.modId()).withSide(mod.side()));
        }

        Path out = exportFolder();
        prepareExportFolder(out);
        // Git on Windows turns line endings into Unix ones on commit by default, and the files would no longer match
        // their hashes in the list: this tells Git to keep every file in the folder as it is
        Files.writeString(out.resolve(".gitattributes"),
                "# Keep every file byte for byte: Mod Synchronizer checks them against the hashes in the list\n* -text\n",
                StandardCharsets.UTF_8);
        List<String> skipped = new ArrayList<>();
        Map<String, Path> found = new TreeMap<>();
        for (String path : paths) {
            String clean = path.strip().replace('\\', '/').replaceAll("/+$", "");
            try {
                Manifest.FileEntry.check(clean);
            } catch (JsonParseException e) {
                skipped.add(clean + " (" + e.getMessage() + ")");
                continue;
            }
            Path source = gameFolder.resolve(clean);
            if (!Files.exists(source)) {
                skipped.add(clean + " (not there)");
                continue;
            }
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    found.put(gameFolder.relativize(file).toString().replace('\\', '/'), file);
                }
            }
        }
        found.remove("config/" + MOD_ID + ".json");

        Map<String, Map<String, String>> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Path> file : found.entrySet()) {
            if (file.getKey().endsWith(".lock")) {
                // Marks that a mod is running, nothing to share
                skipped.add(file.getKey() + " (lock file)");
            } else if (Files.size(file.getValue()) > MAX_EXPORT_FILE) {
                // GitHub takes files of up to 100 MB, and warns from 50 MB on
                skipped.add(file.getKey() + " (larger than 50 MB)");
            } else {
                try {
                    hashes.put(file.getKey(), Hashes.of(file.getValue()));
                } catch (IOException e) {
                    // A mod keeps it open while the game runs, like a database
                    skipped.add(file.getKey() + " (in use, could not be read)");
                }
            }
        }
        Map<String, Manifest.Entry> knownPacks = modrinth.identify(hashes.entrySet().stream()
                .filter(file -> file.getKey().endsWith(".zip")).map(file -> file.getValue().get("sha512")).toList());
        List<Manifest.FileEntry> files = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> file : hashes.entrySet()) {
            Manifest.Entry pack = knownPacks.get(file.getValue().get("sha512"));
            if (pack != null) {
                files.add(new Manifest.FileEntry(file.getKey(), pack.hashes(), ModrinthSource.ID, pack.project(), pack.version(), pack.file(),
                        fileSide(file.getKey())));
            } else {
                Path copy = out.resolve("files").resolve(file.getKey());
                Files.createDirectories(copy.getParent());
                try {
                    Files.copy(found.get(file.getKey()), copy);
                } catch (IOException e) {
                    skipped.add(file.getKey() + " (in use, could not be read)");
                    continue;
                }
                files.add(new Manifest.FileEntry(file.getKey(), file.getValue(), null, null, null, null, fileSide(file.getKey())));
            }
        }
        Files.writeString(out.resolve("modpack.json"),
                new Manifest(minecraftVersion(), "fabric", entries, files.isEmpty() ? null : files).toJson(), StandardCharsets.UTF_8);
        return new Export(out, skipped);
    }

    /** The shared list's entries for the mods in this installation's exceptions, by mod ID; none if it cannot be read */
    private Map<String, Manifest.Entry> sharedEntriesOfExceptions() throws InterruptedException {
        Map<String, Manifest.Entry> entries = new HashMap<>();
        if (config.ignoredMods.isEmpty() || config.manifestUrl.isBlank()) {
            return entries;
        }
        try {
            for (Manifest.Entry entry : Manifest.parse(ListLocation.parse(config.manifestUrl).read(http, gitHub)).mods()) {
                if (entry.modId() != null && config.ignoredMods.contains(entry.modId())) {
                    entries.put(entry.modId(), entry);
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Could not read the shared list, the exceptions go into the export as they are here: {}", e.toString());
        }
        return entries;
    }

    /** Files only a client has use for: packs, the game's own options and the server list; everything else on both */
    private static Manifest.@Nullable Side fileSide(String path) {
        String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
        if (top.equals("resourcepacks") || top.equals("shaderpacks") || top.equals("screenshots")
                || top.isEmpty() && (path.startsWith("options") && path.endsWith(".txt") || path.startsWith("servers") && path.endsWith(".dat"))) {
            return Manifest.Side.CLIENT;
        }
        return null;
    }

    /**
     * Where the export goes: the folder set in the settings, like a local clone of the list's repo, or else
     * {@code modsync-export} in the game folder
     */
    public Path exportFolder() {
        return config.exportFolder.isBlank() ? gameFolder.resolve(MOD_ID + "-export") : Path.of(config.exportFolder.strip());
    }

    /**
     * Clears what an earlier export wrote and leaves everything else, like a repo's .git and README. Only into an
     * empty folder or one that already holds a list: anything else might be a folder that was picked by mistake.
     */
    private static void prepareExportFolder(Path out) throws IOException {
        if (Files.isDirectory(out) && !Files.exists(out.resolve("modpack.json"))) {
            try (Stream<Path> entries = Files.list(out)) {
                if (entries.anyMatch(entry -> !entry.getFileName().toString().equals(".git"))) {
                    throw new IOException("The export folder " + out + " is neither empty nor holds a list (modpack.json): pick an empty folder or the list's repo");
                }
            }
        }
        Files.createDirectories(out);
        deleteRecursively(out.resolve("files"));
        Files.deleteIfExists(out.resolve("modpack.json"));
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
    }
}
