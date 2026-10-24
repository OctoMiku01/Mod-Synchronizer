package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSyncConfig;
import io.github.octomiku01.modsync.core.Manifest;
import io.github.octomiku01.modsync.core.SyncPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The mods this installation keeps out of the sync, as mod IDs separated by commas; suggests the IDs of the mods
 * that differed from the list last time, as those are the ones a player would want to keep their own version of
 */
final class IgnoredModsScreen extends Screen {
    private static final int WIDTH = 310;

    private final Screen parent;
    private final ModSyncConfig config;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private @Nullable EditBox ids;

    IgnoredModsScreen(Screen parent, ModSyncConfig config) {
        super(Component.translatable("modsync.ignored.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(6));
        contents.addChild(new MultiLineTextWidget(Component.translatable("modsync.ignored.explanation"), font).setMaxWidth(WIDTH));

        EditBox box = contents.addChild(new EditBox(font, WIDTH, 20, Component.translatable("modsync.ignored.title")));
        box.setMaxLength(4096);
        box.setValue(String.join(", ", config.ignoredMods));
        box.setHint(Component.translatable("modsync.ignored.hint").withStyle(ChatFormatting.DARK_GRAY));
        ids = box;

        List<String> suggestions = suggestions();
        if (!suggestions.isEmpty()) {
            contents.addChild(new MultiLineTextWidget(Component.translatable("modsync.ignored.suggestions", String.join(", ", suggestions))
                    .withStyle(ChatFormatting.GRAY), font).setMaxWidth(WIDTH));
        }

        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        if (ids != null) {
            Set<String> entered = new LinkedHashSet<>();
            Arrays.stream(ids.getValue().split(",")).map(String::strip).filter(id -> !id.isEmpty()).forEach(entered::add);
            config.ignoredMods = new ArrayList<>(entered);
            config.save();
        }
        minecraft.gui.setScreen(parent);
    }

    /** The IDs of the mods that differed from the list last time and are not ignored yet */
    private List<String> suggestions() {
        @Nullable SyncPlan plan = ModSyncClient.lastPlan();
        if (plan == null) {
            return List.of();
        }
        return Stream.concat(plan.install().stream(), plan.skipped().stream().map(SyncPlan.Skipped::entry))
                .map(IgnoredModsScreen::id)
                .filter(id -> !config.ignoredMods.contains(id))
                .distinct().toList();
    }

    private static String id(Manifest.Entry entry) {
        return entry.modId() != null ? entry.modId() : entry.file();
    }
}
