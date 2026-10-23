package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
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

    public Path stateFile() {
        return workFolder.resolve("state.json");
    }

    /**
     * Downloads every jar to install and checks it against its hashes, then writes the steps for the helper.
     *
     * @param progress told how many jars are downloaded so far
     * @return the file with the steps
     */
    public Path stage(SyncPlan plan, Path modsFolder, IntConsumer progress) throws IOException, InterruptedException {
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
                    if (modId != null && !deletions.contains(jar) && modId.equals(LocalMods.modId(jar))) {
                        deletions.add(jar);
                    }
                }
            }
            moves.add(ApplyHelper.MOVE + "\t" + target.toAbsolutePath() + "\t" + modsFolder.resolve(entry.file()).toAbsolutePath());
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

        Path stepsFile = workFolder.resolve("pending.txt");
        Files.write(stepsFile, steps, StandardCharsets.UTF_8);
        return stepsFile;
    }
}
