package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.ModSyncConfig;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.SyncPlan;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Compares the mods folder with the shared list in the background at start, and shows what would change once the
 * title screen is up; asks for a GitHub login first if the list or a mod is in a private repo.
 */
public class ModSyncClient implements ClientModInitializer {
    private static @Nullable ModSync sync;
    private static volatile @Nullable SyncPlan plan;
    private static volatile boolean loginNeeded;
    private static boolean shown;

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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal(ModSync.MOD_ID)
                        .then(ClientCommands.literal("export").executes(context -> {
                            context.getSource().sendFeedback(Component.translatable("modsync.export.started"));
                            export(context.getSource().getClient(), current);
                            return 1;
                        }))
                        .then(ClientCommands.literal("login").executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            // After the command the chat closes: the screen opens on the next tick, not under it
                            client.execute(() -> SyncScreens.login(client, null, current, () -> {
                                client.gui.setScreen(null);
                                client.gui.hud.getChat().addClientSystemMessage(Component.translatable("modsync.login.done"));
                            }));
                            return 1;
                        }))
                        .then(ClientCommands.literal("logout").executes(context -> {
                            try {
                                current.gitHubAuth().logout();
                                context.getSource().sendFeedback(Component.translatable("modsync.logout.done"));
                            } catch (IOException e) {
                                context.getSource().sendError(Component.literal(e.getMessage()));
                            }
                            return 1;
                        }))));
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

    private static void export(Minecraft client, ModSync sync) {
        Thread thread = new Thread(() -> {
            Component message;
            try {
                Path file = sync.export();
                message = Component.translatable("modsync.export.done", file.toString());
            } catch (IOException | RuntimeException e) {
                ModSync.LOGGER.error("Could not export the mod list", e);
                message = Component.translatable("modsync.export.failed", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Component shown = message;
            client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(shown));
        }, "Mod Synchronizer export");
        thread.setDaemon(true);
        thread.start();
    }
}
