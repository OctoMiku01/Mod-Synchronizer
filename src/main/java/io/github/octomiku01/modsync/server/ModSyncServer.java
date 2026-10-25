package io.github.octomiku01.modsync.server;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.ModSyncConfig;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.Manifest;
import io.github.octomiku01.modsync.core.SyncPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * A dedicated server has no screens: it compares with the list in the background at start, downloads what differs,
 * and tells in the log what changes when the server stops, and that it takes a restart. Files it has its own version
 * of stay, unless replaceChangedFiles is on; a GitHub login shows its code in the console.
 */
public class ModSyncServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ModSyncConfig config = ModSyncConfig.load();
        ModSync sync = new ModSync(config);
        sync.cleanUp();
        List<String> failed = sync.failedApply();
        if (!failed.isEmpty()) {
            ModSync.LOGGER.warn("The last sync could not put {} files in place ({}), trying again when the server stops; see .modsync/apply.log",
                    failed.size(), String.join(", ", failed));
            try {
                sync.retryApply();
            } catch (IOException e) {
                ModSync.LOGGER.error("Could not try the last sync again", e);
            }
            return;
        }
        if (config.manifestUrl.isBlank()) {
            ModSync.LOGGER.info("No shared mod list set: enter it as manifestUrl in config/{}.json to sync this server", ModSync.MOD_ID);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                sync(sync);
            } catch (IOException | RuntimeException e) {
                ModSync.LOGGER.warn("Could not sync with the list at {}: {}", config.manifestUrl, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Mod Synchronizer server sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sync(ModSync sync) throws IOException, InterruptedException {
        SyncPlan plan;
        try {
            plan = sync.plan();
        } catch (GitHubAuth.LoginRequired e) {
            login(sync);
            plan = sync.plan();
        }
        if (sync.config().replaceChangedFiles) {
            plan = plan.withFiles(plan.files().replacingKept());
        }
        for (Manifest.Entry entry : plan.install()) {
            ModSync.LOGGER.info("Mod Synchronizer: + {}", entry.file());
        }
        for (Path old : plan.remove()) {
            ModSync.LOGGER.info("Mod Synchronizer: - {}", old.getFileName());
        }
        for (Manifest.FileEntry file : plan.files().install()) {
            ModSync.LOGGER.info("Mod Synchronizer: + {}", file.path());
        }
        for (String path : plan.files().remove()) {
            ModSync.LOGGER.info("Mod Synchronizer: - {}", path);
        }
        for (SyncPlan.Skipped skipped : plan.skipped()) {
            ModSync.LOGGER.warn("Mod Synchronizer: skipped {} ({})", skipped.entry().file(), skipped.reason());
        }
        for (Manifest.FileEntry file : plan.files().kept()) {
            ModSync.LOGGER.warn("Mod Synchronizer: kept this server's own {} (replaceChangedFiles in config/{}.json takes the list's)",
                    file.path(), ModSync.MOD_ID);
        }
        if (plan.isEmpty()) {
            ModSync.LOGGER.info("Mod Synchronizer: the server's mods match the list");
            return;
        }
        sync.stage(plan, done -> { });
        ModSync.LOGGER.warn("Mod Synchronizer: downloaded {} mods and {} files; they are put in place when the server stops, restart it to use them",
                plan.install().size(), plan.files().install().size());
    }

    /** The device flow on the console: the admin enters the code on github.com from any device */
    private static void login(ModSync sync) throws IOException, InterruptedException {
        GitHubAuth.DeviceCode code = sync.gitHubAuth().start(sync.gitHubClientId());
        ModSync.LOGGER.warn("Mod Synchronizer needs GitHub access to read the mod list: open {} and enter the code {} "
                        + "(or write an access code into {} and restart)", code.verificationUri(), code.userCode(), sync.gitHubAuth().tokenFile());
        sync.gitHubAuth().await(sync.gitHubClientId(), code);
        ModSync.LOGGER.info("Mod Synchronizer: logged in to GitHub");
    }
}
