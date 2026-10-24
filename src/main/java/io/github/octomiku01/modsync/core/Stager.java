package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/**
 * Downloads a plan's jars next to the game and writes down how to swap them in. The mods folder itself is only
 * changed by the {@link ApplyHelper} once the game has exited: Fabric has loaded the jars by now, and Windows does
 * not let a jar in use be deleted.
 */
public final class Stager {
    private final Http http;
    private final Map<String, ModSource> sources;
    private final Path workFolder;

    /** @param workFolder the folder for downloads and bookkeeping, like {@code .minecraft/.modsync} */
    public Stager(Http http, Map<String, ModSource> sources, Path workFolder) {
        this.http = http;
        this.sources = sources;
        this.workFolder = workFolder;
    }

    /** From this many files on that lie next to the list, they come in one archive instead of one request each */
    private static final int ARCHIVE_FROM = 10;

    /** Where a file of the list is downloaded from */
    public interface FileResolver {
        ModSource.Download resolve(Manifest.FileEntry file) throws IOException, InterruptedException;

        /** All files next to the list in one download, if their place has such a thing */
        default @Nullable ListLocation.Archive archive() throws IOException {
            return null;
        }
    }

    public Path stateFile() {
        return workFolder.resolve("state.json");
    }

    /** Which version of each of the list's other files this mod put there, see {@link FilePlan#state()} */
    public Path filesStateFile() {
        return workFolder.resolve("files.json");
    }

    /**
     * Downloads every jar and file to install and checks it against its hashes, then writes the steps for the helper.
     *
     * @param progress told how many jars and files are downloaded so far
     * @return the file with the steps
     */
    public Path stage(SyncPlan plan, Path modsFolder, Path gameFolder, FileResolver files, IntConsumer progress)
            throws IOException, InterruptedException {
        Path staging = workFolder.resolve("staging");
        Files.createDirectories(staging);
        // All deletions before all moves: a new jar may have the name of one it replaces
        List<Path> deletions = new ArrayList<>(plan.remove());
        List<String> moves = new ArrayList<>();
        List<Path> jars = LocalMods.jars(modsFolder);
        int done = 0;
        for (Manifest.Entry entry : plan.install()) {
            ModSource source = sources.get(entry.source());
            ModSource.Download download = source.resolve(entry);
            Path target = staging.resolve(entry.file());
            http.download(download.uri(), download.headers(), target);
            Map<String, String> hashes = Hashes.of(target);
            if (!Hashes.match(entry.hashes(), hashes)) {
                Files.delete(target);
                throw new IOException(entry.file() + " does not match the hashes in the list: it was changed, or the list is wrong");
            }
            if (entry.modId() == null) {
                // The list did not say which mod this is, so the plan could not look for other versions of it
                @Nullable String modId = LocalMods.modId(target);
                for (Path jar : jars) {
                    if (modId != null && !deletions.contains(jar) && modId.equals(LocalMods.modId(jar))
                            && !plan.ignored().contains(modId) && !plan.ignored().contains(jar.getFileName().toString())) {
                        deletions.add(jar);
                    }
                }
            }
            moves.add(ApplyHelper.MOVE + "\t" + target.toAbsolutePath() + "\t" + modsFolder.resolve(entry.file()).toAbsolutePath());
            progress.accept(++done);
        }
        for (String path : plan.files().remove()) {
            deletions.add(gameFolder.resolve(path(path)));
        }
        Set<String> unpacked = unpack(plan.files().install(), staging, files);
        for (Manifest.FileEntry file : plan.files().install()) {
            // Under its own path, so that files of the same name in different folders do not meet
            Path target = staging.resolve("files").resolve(path(file.path()));
            Files.createDirectories(target.getParent());
            if (!unpacked.contains(file.path())) {
                ModSource.Download download = files.resolve(file);
                http.download(download.uri(), download.headers(), target);
            }
            if (!Hashes.match(file.hashes(), Hashes.of(target))) {
                Files.delete(target);
                throw new IOException(file.path() + " does not match the hashes in the list: it was changed, or the list is wrong");
            }
            moves.add(ApplyHelper.MOVE + "\t" + target.toAbsolutePath() + "\t" + gameFolder.resolve(path(file.path())).toAbsolutePath());
            progress.accept(++done);
        }
        List<String> steps = new ArrayList<>();
        for (Path old : deletions) {
            steps.add(ApplyHelper.DELETE + "\t" + old.toAbsolutePath());
        }
        steps.addAll(moves);
        // Last: the state only counts once all jars are in place
        Path pendingState = workFolder.resolve("state.pending.json");
        SyncState.save(pendingState, plan.state());
        steps.add(ApplyHelper.MOVE + "\t" + pendingState.toAbsolutePath() + "\t" + stateFile().toAbsolutePath());
        Path pendingFilesState = workFolder.resolve("files.pending.json");
        SyncState.save(pendingFilesState, plan.files().state());
        steps.add(ApplyHelper.MOVE + "\t" + pendingFilesState.toAbsolutePath() + "\t" + filesStateFile().toAbsolutePath());

        Path stepsFile = workFolder.resolve("pending.txt");
        Files.write(stepsFile, steps, StandardCharsets.UTF_8);
        return stepsFile;
    }

    /**
     * With many files next to the list, gets them all in one archive instead of one request each: GitHub allows a
     * token some thousand requests an hour, and an access code shared by several players would run out of them. Puts
     * the files it finds where the single downloads would; the caller checks their hashes all the same. Anything that
     * goes wrong just leaves the files to the single downloads.
     *
     * @return the paths of the files it put in place
     */
    private Set<String> unpack(List<Manifest.FileEntry> install, Path staging, FileResolver files) throws InterruptedException {
        List<Manifest.FileEntry> nextToList = install.stream().filter(Manifest.FileEntry::nextToList).toList();
        Set<String> unpacked = new HashSet<>();
        if (nextToList.size() < ARCHIVE_FROM) {
            return unpacked;
        }
        Path zip = staging.resolve("list.zip");
        try {
            @Nullable ListLocation.Archive archive = files.archive();
            if (archive == null) {
                return unpacked;
            }
            http.download(archive.download().uri(), archive.download().headers(), zip);
            try (ZipFile zipFile = new ZipFile(zip.toFile())) {
                // GitHub puts everything into one folder named after the repo and commit
                String top = zipFile.stream().findFirst().map(entry -> entry.getName().substring(0, entry.getName().indexOf('/') + 1)).orElse("");
                for (Manifest.FileEntry file : nextToList) {
                    @Nullable ZipEntry entry = zipFile.getEntry(top + archive.folder() + "files/" + file.path());
                    if (entry == null || entry.isDirectory()) {
                        continue;
                    }
                    Path target = staging.resolve("files").resolve(path(file.path()));
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    unpacked.add(file.path());
                }
            }
        } catch (IOException | RuntimeException e) {
            // The single downloads take over for whatever is not unpacked
        } finally {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException e) {
                // Cleaned up at the next start
            }
        }
        return unpacked;
    }

    /** A path of the list ("/" between folders, checked when the list was read) as a relative path on this system */
    private static Path path(String listPath) {
        return Path.of("", listPath.split("/"));
    }
}
