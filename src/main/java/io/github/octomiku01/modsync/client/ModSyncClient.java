package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.ModSyncConfig;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.SyncPlan;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Compares the mods folder with the shared list in the background at start, and shows what would change once the
 * title screen is up; asks for a GitHub login first if the list or a mod is in a private repo. The settings open from
 * Mod Menu, or without it from a button on the title screen.
 */
public class ModSyncClient implements ClientModInitializer {
    private static @Nullable ModSync sync;
    private static volatile @Nullable SyncPlan plan;
    private static volatile boolean loginNeeded;
    private static boolean shown;

    static ModSync sync() {
        ModSync current = sync;
        if (current == null) {
            throw new IllegalStateException("Mod Synchronizer is not initialized yet");
        }
        return current;
    }

    @Override
    public void onInitializeClient() {
        ModSyncConfig config = ModSyncConfig.load();
        ModSync current = new ModSync(config);
        sync = current;
        if (!config.manifestUrl.isBlank()) {
            Thread thread = new Thread(() -> {
                try {
                    plan = current.plan();
                } catch (GitHubAuth.LoginRequired e) {
                    loginNeeded = true;
                } catch (IOException | RuntimeException e) {
                    ModSync.LOGGER.warn("Could not compare the mods with the list at {}: {}", config.manifestUrl, e.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Mod Synchronizer check");
            thread.setDaemon(true);
            thread.start();
            ClientTickEvents.END_CLIENT_TICK.register(ModSyncClient::tick);
        }

        if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
                if (screen instanceof TitleScreen) {
                    Screens.getWidgets(screen).add(Button.builder(Component.translatable("modsync.settings.button"),
                            button -> client.gui.setScreen(new ModSyncScreen(screen, current))).bounds(4, 4, 90, 20).build());
                }
            });
        }
    }

    private static void tick(Minecraft client) {
        @Nullable SyncPlan current = plan;
        boolean login = loginNeeded;
        // Not while the game still loads: the loading overlay lies over a title screen that is set again after it
        if (shown || (current == null && !login) || sync == null || !client.isGameLoadFinished() || client.gui == null
                || client.gui.overlay() != null || !(client.gui.screen() instanceof TitleScreen title)) {
            return;
        }
        shown = true;
        if (login) {
            ModSync currentSync = sync;
            SyncScreens.login(client, title, currentSync, () -> SyncScreens.check(client, title, currentSync));
        } else if (current.isEmpty() && current.skipped().isEmpty()) {
            ModSync.LOGGER.info("All mods match the list");
        } else {
            SyncScreens.showPlan(client, title, sync, current);
        }
    }
}
