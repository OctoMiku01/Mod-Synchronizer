package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import io.github.octomiku01.modsync.ModSyncConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Exports the mods folder as a list, with the other files and folders of the game folder that should go with it,
 * like the configs; which ones is remembered for the next export
 */
final class ExportScreen extends Screen {
    private static final int WIDTH = 310;
    private static final int HALF = (WIDTH - 10) / 2;

    private final Screen parent;
    private final ModSync sync;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private @Nullable EditBox paths;
    private boolean shownBefore;
    private boolean rebuild;

    ExportScreen(Screen parent, ModSync sync) {
        super(Component.translatable("modsync.export.title"));
        this.parent = parent;
        this.sync = sync;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(6));
        contents.addChild(new MultiLineTextWidget(Component.translatable("modsync.export.explanation"), font).setMaxWidth(WIDTH));

        EditBox box = contents.addChild(new EditBox(font, WIDTH, 20, Component.translatable("modsync.export.paths")));
        // A folder split up in the file browser can make for hundreds of paths
        box.setMaxLength(1 << 16);
        box.setValue(String.join(", ", sync.config().exportPaths));
        box.setHint(Component.translatable("modsync.export.paths.hint").withStyle(ChatFormatting.DARK_GRAY));
        paths = box;
        contents.addChild(Button.builder(Component.translatable("modsync.export.browse"), button -> {
            remember();
            minecraft.gui.setScreen(new FileBrowserScreen(this, sync.gameFolder(), Set.copyOf(sync.config().exportPaths), chosen -> {
                sync.config().exportPaths = new ArrayList<>(chosen);
                sync.config().save();
            }));
        }).width(WIDTH).build());

        contents.addChild(CycleButton.onOffBuilder(sync.config().exportFollowLatest)
                .withTooltip(value -> Tooltip.create(Component.translatable("modsync.export.followLatest.tooltip")))
                .create(0, 0, WIDTH, 20, Component.translatable("modsync.export.followLatest"), (button, value) -> {
                    sync.config().exportFollowLatest = value;
                    sync.config().save();
                }));

        contents.addChild(new StringWidget(WIDTH, 12, Component.translatable("modsync.export.folder"), font));
        LinearLayout folderRow = contents.addChild(LinearLayout.horizontal().spacing(10));
        EditBox folderBox = folderRow.addChild(new EditBox(font, WIDTH - 90, 20, Component.translatable("modsync.export.folder")));
        folderBox.setMaxLength(1024);
        folderBox.setValue(sync.config().exportFolder);
        folderBox.setHint(Component.translatable("modsync.export.folder.hint").withStyle(ChatFormatting.DARK_GRAY));
        folderBox.setResponder(value -> sync.config().exportFolder = value.strip());
        folderRow.addChild(Button.builder(Component.translatable("modsync.export.folder.pick"), button -> pickFolder(folderBox)).width(80).build());

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(10));
        footer.addChild(Button.builder(Component.translatable("modsync.export.run"), button -> export()).width(HALF).build());
        footer.addChild(Button.builder(CommonComponents.GUI_BACK, button -> onClose()).width(HALF).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (rebuild) {
            rebuild = false;
            rebuildWidgets();
            return;
        }
        layout.arrangeElements();
    }

    /**
     * Minecraft builds a screen only when it is first shown and just arranges it when it is shown again: back from the
     * file browser, the field has to show what was chosen there, so it is built anew
     */
    @Override
    public void added() {
        super.added();
        rebuild = shownBefore;
        shownBefore = true;
    }

    @Override
    public void onClose() {
        remember();
        minecraft.gui.setScreen(parent);
    }

    private void remember() {
        if (paths != null) {
            Set<String> entered = new LinkedHashSet<>();
            Arrays.stream(paths.getValue().split(",")).map(String::strip).filter(path -> !path.isEmpty()).forEach(entered::add);
            ModSyncConfig config = sync.config();
            config.exportPaths = new ArrayList<>(entered);
            config.save();
        }
    }

    /** The system's own folder dialog, off the game thread so that the game does not freeze while it is open */
    private void pickFolder(EditBox folderBox) {
        String start = sync.exportFolder().toAbsolutePath().toString();
        Thread thread = new Thread(() -> {
            @Nullable String picked = TinyFileDialogs.tinyfd_selectFolderDialog(Component.translatable("modsync.export.folder").getString(), start);
            if (picked != null) {
                minecraft.execute(() -> {
                    sync.config().exportFolder = picked;
                    folderBox.setValue(picked);
                });
            }
        }, "Mod Synchronizer folder dialog");
        thread.setDaemon(true);
        thread.start();
    }

    private void export() {
        remember();
        minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("modsync.export.started")));
        Thread thread = new Thread(() -> {
            Component message;
            @Nullable ModSync.Export written = null;
            try {
                written = sync.export(sync.config().exportPaths);
                message = Component.translatable("modsync.export.done", written.folder().toString());
                if (Files.isDirectory(written.folder().resolve(".git"))) {
                    message = message.copy().append("\n").append(Component.translatable("modsync.export.gitHint"));
                }
                if (!written.skipped().isEmpty()) {
                    message = message.copy().append("\n\n").append(Component.translatable("modsync.export.skipped",
                            String.join(", ", written.skipped())).withStyle(ChatFormatting.YELLOW));
                }
            } catch (IOException | RuntimeException e) {
                ModSync.LOGGER.error("Could not export the mod list", e);
                message = Component.translatable("modsync.export.failed", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Component shown = message;
            @Nullable ModSync.Export result = written;
            minecraft.execute(() -> minecraft.gui.setScreen(new AlertScreen(() -> {
                if (result != null) {
                    Util.getPlatform().openPath(result.folder());
                }
                minecraft.gui.setScreen(this);
            }, title, shown, result != null ? Component.translatable("modsync.export.openFolder") : CommonComponents.GUI_BACK, true)));
        }, "Mod Synchronizer export");
        thread.setDaemon(true);
        thread.start();
    }
}
