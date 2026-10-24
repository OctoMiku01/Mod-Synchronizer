package io.github.octomiku01.modsync.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * What it takes to bring the mods folder in line with the list.
 *
 * @param install the entries whose file is missing
 * @param remove  jars this mod installed earlier that the list no longer has or has in another version, and other
 *                versions of the list's mods, by Fabric mod ID: Fabric does not start with two versions of one mod
 * @param skipped entries that cannot be installed, with the reason
 * @param state   the {@link SyncState} once the plan is carried out
 * @param ignored the mod IDs and file names this installation keeps out of the sync: their jars are never removed
 */
public record SyncPlan(List<Manifest.Entry> install, List<Path> remove, List<Skipped> skipped, Map<String, String> state,
                       Set<String> ignored) {
    public record Skipped(Manifest.Entry entry, Reason reason) {
    }

    public enum Reason {
        /** No source of that name is supported (yet) */
        UNSUPPORTED_SOURCE,
        /** A jar of the same name, but not of the same mod, that this mod did not install is in the way */
        FILE_NAME_TAKEN
    }

    public boolean isEmpty() {
        return install.isEmpty() && remove.isEmpty();
    }

    /**
     * @param ignored mod IDs or file names this installation keeps out of the sync: their
     *                entries are neither installed nor shown, and their jars are neither replaced nor removed
     */
    public static SyncPlan compute(Manifest manifest, List<LocalMods.LocalMod> local, Map<String, String> oldState,
                                   Manifest.Side here, Set<String> sources, Set<String> ignored, Path modsFolder) {
        List<Manifest.Entry> install = new ArrayList<>();
        List<Path> remove = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Map<String, String> state = new TreeMap<>();
        Set<String> wanted = new HashSet<>();

        for (Manifest.Entry entry : manifest.mods()) {
            if (!entry.neededOn(here)) {
                continue;
            }
            wanted.add(entry.key());
            if (ignored.contains(entry.file()) || entry.modId() != null && ignored.contains(entry.modId())) {
                // Whatever jar of it is there stays, and stays tracked if it was
                @Nullable String old = oldState.get(entry.key());
                if (old != null) {
                    state.put(entry.key(), old);
                }
                continue;
            }
            @Nullable LocalMods.LocalMod present = find(local, entry.hashes());
            if (present != null) {
                // Also adopts a jar the player put there themselves: from now on it follows the list
                state.put(entry.key(), present.name());
                removeOtherVersions(local, present.modId(), present, remove);
                continue;
            }
            @Nullable String old = oldState.get(entry.key());
            if (!sources.contains(entry.source())) {
                skipped.add(new Skipped(entry, Reason.UNSUPPORTED_SOURCE));
                if (old != null) {
                    state.put(entry.key(), old);
                }
                continue;
            }
            boolean taken = local.stream().anyMatch(mod -> mod.name().equals(entry.file()) && !mod.name().equals(old)
                    && (entry.modId() == null || !entry.modId().equals(mod.modId())));
            if (taken) {
                skipped.add(new Skipped(entry, Reason.FILE_NAME_TAKEN));
                continue;
            }
            install.add(entry);
            state.put(entry.key(), entry.file());
            if (old != null && exists(local, old)) {
                add(remove, modsFolder.resolve(old));
            }
            removeOtherVersions(local, entry.modId(), null, remove);
        }

        for (Map.Entry<String, String> tracked : oldState.entrySet()) {
            String name = tracked.getValue();
            if (!wanted.contains(tracked.getKey()) && exists(local, name) && !state.containsValue(name)) {
                add(remove, modsFolder.resolve(name));
            }
        }
        // Last, whatever the reason: a jar that is an exception stays, also one tracked under an entry the list dropped
        remove.removeIf(file -> local.stream().anyMatch(mod -> mod.file().equals(file) && isIgnored(mod, ignored)));
        return new SyncPlan(install, remove, skipped, state, ignored);
    }

    public boolean isIgnored(LocalMods.LocalMod mod) {
        return isIgnored(mod, ignored);
    }

    private static boolean isIgnored(LocalMods.LocalMod mod, Set<String> ignored) {
        return ignored.contains(mod.name()) || mod.modId() != null && ignored.contains(mod.modId());
    }

    /** Every jar of the mod but {@code keep}: an older one, or one a mod's own updater put there */
    private static void removeOtherVersions(List<LocalMods.LocalMod> local, @Nullable String modId, @Nullable LocalMods.LocalMod keep,
                                            List<Path> remove) {
        if (modId == null) {
            return;
        }
        for (LocalMods.LocalMod mod : local) {
            if (mod != keep && modId.equals(mod.modId())) {
                add(remove, mod.file());
            }
        }
    }

    private static void add(List<Path> remove, Path file) {
        if (!remove.contains(file)) {
            remove.add(file);
        }
    }

    private static @Nullable LocalMods.LocalMod find(List<LocalMods.LocalMod> local, Map<String, String> hashes) {
        for (LocalMods.LocalMod mod : local) {
            if (Hashes.match(hashes, mod.hashes())) {
                return mod;
            }
        }
        return null;
    }

    private static boolean exists(List<LocalMods.LocalMod> local, String name) {
        return local.stream().anyMatch(mod -> mod.name().equals(name));
    }
}
