package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.core.SyncPlan;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What differs from the list, with the choice what to do about it: download, download and also replace the files
 * the player has their own of (often configs a mod made itself at its first start), or not now
 */
final class PlanScreen extends Screen {
    private static final int WIDTH = 310;

    private final @Nullable Screen back;
    private final SyncPlan plan;
    private final Component description;
    private final Consumer<SyncPlan> apply;
    private final LinearLayout layout = LinearLayout.vertical().spacing(12);

    /** @param apply carries out the plan the player chose */
    PlanScreen(@Nullable Screen back, SyncPlan plan, Component description, Consumer<SyncPlan> apply) {
        super(Component.translatable("modsync.plan.title"));
        this.back = back;
        this.plan = plan;
        this.description = description;
        this.apply = apply;
    }

    @Override
    protected void init() {
        layout.addChild(new MultiLineTextWidget(title, font).setMaxWidth(WIDTH).setCentered(true), settings -> settings.alignHorizontallyCenter());
        layout.addChild(new MultiLineTextWidget(description, font).setMaxWidth(WIDTH).setCentered(true), settings -> settings.alignHorizontallyCenter());
        LinearLayout buttons = layout.addChild(LinearLayout.vertical().spacing(4), settings -> settings.alignHorizontallyCenter());
        if (!plan.isEmpty()) {
            buttons.addChild(Button.builder(Component.translatable("modsync.plan.apply"), button -> apply.accept(plan)).width(WIDTH).build());
        }
        if (!plan.files().kept().isEmpty()) {
            buttons.addChild(Button.builder(Component.translatable("modsync.plan.applyReplacing", plan.files().kept().size()),
                    button -> apply.accept(plan.withFiles(plan.files().replacingKept()))).width(WIDTH).build());
        }
        buttons.addChild(Button.builder(Component.translatable("modsync.plan.later"), button -> onClose()).width(WIDTH).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, 0, 0, width, height);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(back);
    }
}
