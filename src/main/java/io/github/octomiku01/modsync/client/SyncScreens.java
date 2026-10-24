package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.core.GitHubAuth;
import io.github.octomiku01.modsync.core.Manifest;
import io.github.octomiku01.modsync.core.SyncPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/** The way through a sync: what changes, the download, then quitting so the helper can swap the jars */
final class SyncScreens {
    /** More lines do not fit on the screen */
    private static final int MAX_LINES = 12;

    private SyncScreens() {
    }

    /** Compares the mods with the list again, after a login, and shows the result */
    static void check(Minecraft client, @Nullable Screen back, ModSync sync) {
        client.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.check.progress")));
        background("Mod Synchronizer check", client, back, sync, () -> {
            SyncPlan plan = sync.plan();
            ModSyncClient.rememberPlan(plan);
            client.execute(() -> {
                if (plan.isEmpty() && plan.skipped().isEmpty()) {
                    client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(back), Component.translatable("modsync.settings.title"),
                            Component.translatable("modsync.plan.none")));
                } else {
                    showPlan(client, back, sync, plan);
                }
            });
        }, () -> check(client, back, sync), "modsync.check.failed");
    }

    static void showPlan(Minecraft client, @Nullable Screen back, ModSync sync, SyncPlan plan) {
        if (plan.isEmpty()) {
            client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(back), Component.translatable("modsync.plan.title"),
                    describe(plan)));
            return;
        }
        client.gui.setScreen(new ConfirmScreen(apply -> {
            if (apply) {
                download(client, back, sync, plan);
            } else {
                client.gui.setScreen(back);
            }
        }, Component.translatable("modsync.plan.title"), describe(plan),
                Component.translatable("modsync.plan.apply"), Component.translatable("modsync.plan.later")));
    }

    /**
     * Logs in to GitHub through the device flow: shows the code, puts it on the clipboard and opens the page to enter
     * it on, then runs {@code then} on the game thread once GitHub has the login.
     */
    static void login(Minecraft client, @Nullable Screen back, ModSync sync, Runnable then) {
        String clientId = sync.gitHubClientId();
        if (clientId.isEmpty()) {
            client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(back), Component.translatable("modsync.login.title"),
                    Component.translatable("modsync.login.noClientId")));
            return;
        }
        client.gui.setScreen(new ConfirmScreen(start -> {
            if (!start) {
                client.gui.setScreen(back);
                return;
            }
            client.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.login.starting")));
            Thread thread = new Thread(() -> {
                try {
                    GitHubAuth.DeviceCode code = sync.gitHubAuth().start(clientId);
                    Thread waiting = Thread.currentThread();
                    client.execute(() -> {
                        client.keyboardHandler.setClipboard(code.userCode());
                        Util.getPlatform().openUri(code.verificationUri());
                        client.gui.setScreen(new AlertScreen(() -> {
                            waiting.interrupt();
                            client.gui.setScreen(back);
                        }, Component.translatable("modsync.login.title"),
                                Component.translatable("modsync.login.enterCode", Component.literal(code.userCode()).withStyle(ChatFormatting.AQUA),
                                        code.verificationUri().toString()),
                                Component.translatable("gui.cancel"), true));
                    });
                    sync.gitHubAuth().await(clientId, code);
                    client.execute(then);
                } catch (IOException | RuntimeException e) {
                    ModSync.LOGGER.warn("GitHub login failed: {}", e.toString());
                    client.execute(() -> client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(back),
                            Component.translatable("modsync.login.failed"), Component.literal(String.valueOf(e.getMessage())))));
                } catch (InterruptedException e) {
                    // Cancelled: the screen has gone back already
                }
            }, "Mod Synchronizer GitHub login");
            thread.setDaemon(true);
            thread.start();
        }, Component.translatable("modsync.login.title"), Component.translatable("modsync.login.why"),
                Component.translatable("modsync.login.start"), Component.translatable("gui.cancel")));
    }

    private static void download(Minecraft client, @Nullable Screen back, ModSync sync, SyncPlan plan) {
        int total = plan.install().size();
        client.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.download.progress", 0, total)));
        background("Mod Synchronizer download", client, back, sync, () -> {
            sync.stage(plan, done -> client.execute(() ->
                    client.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.download.progress", done, total)))));
            client.execute(() -> client.gui.setScreen(new ConfirmScreen(quit -> {
                if (quit) {
                    client.stop();
                } else {
                    client.gui.setScreen(back);
                }
            }, Component.translatable("modsync.done.title"), Component.translatable("modsync.done.message"),
                    Component.translatable("modsync.done.quit"), Component.translatable("modsync.done.later"))));
        }, () -> download(client, back, sync, plan), "modsync.download.failed");
    }

    private interface Work {
        void run() throws IOException, InterruptedException;
    }

    /** Runs the work off the game thread; asks for a GitHub login and then retries it if GitHub wants one */
    private static void background(String name, Minecraft client, @Nullable Screen back, ModSync sync, Work work, Runnable retry,
                                   String failedKey) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (GitHubAuth.LoginRequired e) {
                client.execute(() -> login(client, back, sync, retry));
            } catch (IOException | RuntimeException e) {
                ModSync.LOGGER.error("{} failed", name, e);
                client.execute(() -> client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(back),
                        Component.translatable(failedKey), Component.literal(String.valueOf(e.getMessage())))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static Component describe(SyncPlan plan) {
        List<Component> lines = new ArrayList<>();
        for (Manifest.Entry entry : plan.install()) {
            lines.add(Component.literal("+ " + entry.file()).withStyle(ChatFormatting.GREEN));
        }
        for (Path old : plan.remove()) {
            lines.add(Component.literal("- " + old.getFileName()).withStyle(ChatFormatting.RED));
        }
        for (SyncPlan.Skipped skipped : plan.skipped()) {
            lines.add(Component.literal("! " + skipped.entry().file() + " ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.translatable("modsync.skipped." + skipped.reason().name().toLowerCase(Locale.ROOT), skipped.entry().source())));
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < Math.min(lines.size(), MAX_LINES); i++) {
            text.append(i == 0 ? Component.empty() : Component.literal("\n")).append(lines.get(i));
        }
        if (lines.size() > MAX_LINES) {
            text.append("\n").append(Component.translatable("modsync.plan.more", lines.size() - MAX_LINES));
        }
        return text;
    }
}
