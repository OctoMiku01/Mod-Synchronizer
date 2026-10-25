package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * What it takes to bring the list's other files, like configs, in line with the list. A file the player changed
 * since this mod put it there stays: the list only brings files the player has not touched up to date.
 *
 * @param install files that are missing, or that the list has in a newer version the player has not changed
 * @param remove  paths of files this mod put there that the list no longer has, unchanged since
 * @param kept    files changed here (by the player, or by a mod at start) or had here before (like a config a mod
 *                made itself at its first start), for which the list brings another version: they stay, unless the
 *                player chooses to replace them. A file changed here while the list still has the version this mod put
 *                there is no such case: there is nothing new to bring.
 * @param state   path to the hash of the version this mod put there, once the plan is carried out, as
 *                "algorithm:hex"; a file whose hash still matches it was not changed by the player
 */
public record FilePlan(List<Manifest.FileEntry> install, List<String> remove, List<Manifest.FileEntry> kept, Map<String, String> state) {
    public static final FilePlan NONE = new FilePlan(List.of(), List.of(), List.of(), Map.of());

    public boolean isEmpty() {
        return install.isEmpty() && remove.isEmpty();
    }

    /** The same plan, but the list's version replaces the files the player has their own of */
    public FilePlan replacingKept() {
        List<Manifest.FileEntry> all = new ArrayList<>(install);
        all.addAll(kept);
        Map<String, String> newState = new TreeMap<>(state);
        kept.forEach(file -> newState.put(file.path(), stamp(file.hashes())));
        return new FilePlan(all, remove, List.of(), newState);
    }

    /**
     * @param here    which side this installation is: files for the other side are left alone, like packs on a server
     * @param ignored paths (or folders, which take in everything in them) this installation keeps out of the sync
     */
    public static FilePlan compute(@Nullable List<Manifest.FileEntry> files, Path gameFolder, Map<String, String> oldState,
                                   Manifest.Side here, Set<String> ignored) throws IOException {
        List<Manifest.FileEntry> install = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        List<Manifest.FileEntry> kept = new ArrayList<>();
        Map<String, String> state = new TreeMap<>();
        Set<String> listed = new HashSet<>();

        for (Manifest.FileEntry entry : files == null ? List.<Manifest.FileEntry>of() : files) {
            listed.add(entry.path());
            @Nullable String installed = oldState.get(entry.path());
            if (!entry.neededOn(here) || isIgnored(entry.path(), ignored)) {
                if (installed != null) {
                    state.put(entry.path(), installed);
                }
                continue;
            }
            Path local = gameFolder.resolve(entry.path());
            if (!Files.isRegularFile(local)) {
                install.add(entry);
                state.put(entry.path(), stamp(entry.hashes()));
                continue;
            }
            Map<String, String> hashes = Hashes.of(local);
            if (Hashes.match(entry.hashes(), hashes)) {
                state.put(entry.path(), stamp(entry.hashes()));
            } else if (installed != null && unchanged(installed, hashes)) {
                install.add(entry);
                state.put(entry.path(), stamp(entry.hashes()));
            } else if (installed != null && installed.equals(stamp(entry.hashes()))) {
                // Changed since this mod put it there, by the player or by a mod that writes its config anew at start,
                // but the list still has the same version: nothing new to bring, so nothing to decide
                state.put(entry.path(), installed);
            } else {
                // The list brings a new version of a file that was changed here, or had here before the list had it:
                // that one stays, unless the player chooses to replace it
                kept.add(entry);
                if (installed != null) {
                    state.put(entry.path(), installed);
                }
            }
        }

        for (Map.Entry<String, String> tracked : oldState.entrySet()) {
            String path = tracked.getKey();
            Path local = gameFolder.resolve(path);
            if (listed.contains(path) || isIgnored(path, ignored) || !Files.isRegularFile(local)) {
                continue;
            }
            if (unchanged(tracked.getValue(), Hashes.of(local))) {
                remove.add(path);
            }
        }
        return new FilePlan(install, remove, kept, state);
    }

    public static boolean isIgnored(String path, Set<String> ignored) {
        for (String entry : ignored) {
            String folder = entry.endsWith("/") ? entry : entry + "/";
            if (path.equals(entry) || path.startsWith(folder)) {
                return true;
            }
        }
        return false;
    }

    /** The strongest of the hashes, as "algorithm:hex" */
    private static String stamp(Map<String, String> hashes) {
        for (String algorithm : Hashes.ALGORITHMS) {
            String hash = hashes.get(algorithm);
            if (hash != null) {
                return algorithm + ":" + hash.toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalArgumentException("no hash");
    }

    private static boolean unchanged(String stamp, Map<String, String> hashes) {
        int colon = stamp.indexOf(':');
        return colon > 0 && Hashes.match(Map.of(stamp.substring(0, colon), stamp.substring(colon + 1)), hashes);
    }
}
