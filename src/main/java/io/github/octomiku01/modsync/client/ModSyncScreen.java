package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.ModSyncConfig;
import io.github.octomiku01.modsync.core.Manifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * The settings: where the list is, which mods this installation needs, the GitHub login; and comparing with the
 * list or exporting the mods folder as one right away. Changes are saved when the screen closes.
 */
public final class ModSyncScreen extends Screen {
    private static final int WIDTH = 310;
    private static final int HALF = (WIDTH - 10) / 2;

    private final @Nullable Screen parent;
    private final ModSync sync;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public ModSyncScreen(@Nullable Screen parent, ModSync sync) {
        super(Component.translatable("modsync.settings.title"));
        this.parent = parent;
        this.sync = sync;
    }

    @Override
    protected void init() {
        ModSyncConfig config = sync.config();
        // A new layout each time: the GitHub part changes after logging in or out
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(4));

        contents.addChild(label("modsync.settings.manifestUrl"));
        EditBox manifestUrl = contents.addChild(new EditBox(font, WIDTH, 20, Component.translatable("modsync.settings.manifestUrl")));
        manifestUrl.setMaxLength(1024);
        manifestUrl.setValue(config.manifestUrl);
        manifestUrl.setHint(Component.translatable("modsync.settings.manifestUrl.hint").withStyle(ChatFormatting.DARK_GRAY));
        manifestUrl.setResponder(value -> config.manifestUrl = value.strip());

        contents.addChild(CycleButton.builder((Manifest.Side side) -> Component.translatable("modsync.settings.side." + side.name().toLowerCase(Locale.ROOT)), config.side)
                .withValues(Manifest.Side.values())
                .create(0, 0, WIDTH, 20, Component.translatable("modsync.settings.side"), (button, side) -> config.side = side));

        LinearLayout actions = contents.addChild(LinearLayout.horizontal().spacing(10));
        actions.addChild(Button.builder(Component.translatable("modsync.settings.check"), button -> {
            config.save();
            if (config.manifestUrl.isEmpty()) {
                minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(this), Component.translatable("modsync.settings.check"),
                        Component.translatable("modsync.settings.noManifest")));
            } else {
                SyncScreens.check(minecraft, this, sync);
            }
        }).width(HALF).build());
        actions.addChild(Button.builder(Component.translatable("modsync.settings.export"), button -> {
            config.save();
            minecraft.gui.setScreen(new ExportScreen(this, sync));
        }).width(HALF).build());

        contents.addChild(label("modsync.settings.github"));
        boolean loggedIn = sync.gitHubAuth().loggedIn();
        contents.addChild(new StringWidget(WIDTH, 12, Component.translatable(loggedIn ? "modsync.settings.github.loggedIn" : "modsync.settings.github.loggedOut")
                .withStyle(loggedIn ? ChatFormatting.GREEN : ChatFormatting.GRAY), font));
        contents.addChild(Button.builder(Component.translatable(loggedIn ? "modsync.settings.github.logout" : "modsync.settings.github.login"), button -> {
            if (sync.gitHubAuth().loggedIn()) {
                try {
                    sync.gitHubAuth().logout();
                } catch (IOException e) {
                    ModSync.LOGGER.warn("Could not log out of GitHub", e);
                }
                rebuildWidgets();
            } else {
                config.save();
                SyncScreens.login(minecraft, this, sync, () -> minecraft.gui.setScreen(this));
            }
        }).width(WIDTH).build());

        // Without a GitHub account: a token with read access to the repos, handed out as an access code
        contents.addChild(label("modsync.settings.accessCode"));
        LinearLayout codeRow = contents.addChild(LinearLayout.horizontal().spacing(10));
        EditBox code = codeRow.addChild(new EditBox(font, WIDTH - 90, 20, Component.translatable("modsync.settings.accessCode")));
        code.setMaxLength(255);
        code.setHint(Component.translatable("modsync.settings.accessCode.hint").withStyle(ChatFormatting.DARK_GRAY));
        // Shown as dots, so that it does not end up readable in a screenshot or stream
        code.addFormatter((text, firstCharacter) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        codeRow.addChild(Button.builder(Component.translatable("modsync.settings.accessCode.use"), button -> useAccessCode(code.getValue().strip()))
                .width(80).build());

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(10));
        footer.addChild(Button.builder(Component.translatable("modsync.settings.ignored"), button -> {
            config.save();
            minecraft.gui.setScreen(new IgnoredModsScreen(this, config));
        }).width(HALF).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(HALF).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        sync.config().save();
        minecraft.gui.setScreen(parent);
    }

    private StringWidget label(String key) {
        return new StringWidget(WIDTH, 12, Component.translatable(key), font);
    }

    private void useAccessCode(String accessCode) {
        if (accessCode.isEmpty()) {
            return;
        }
        minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.settings.accessCode.checking")));
        Thread thread = new Thread(() -> {
            try {
                sync.gitHubAuth().useToken(accessCode);
                minecraft.execute(() -> minecraft.gui.setScreen(this));
            } catch (IOException | RuntimeException e) {
                ModSync.LOGGER.warn("GitHub did not accept the access code: {}", e.toString());
                minecraft.execute(() -> minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(this),
                        Component.translatable("modsync.settings.accessCode"), Component.translatable("modsync.settings.accessCode.invalid"))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Mod Synchronizer access code");
        thread.setDaemon(true);
        thread.start();
    }

}
