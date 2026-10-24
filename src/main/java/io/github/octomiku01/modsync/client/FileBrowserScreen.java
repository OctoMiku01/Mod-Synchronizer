package io.github.octomiku01.modsync.client;

import io.github.octomiku01.modsync.ModSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Picks the files and folders of the game folder that go with the list, also in subfolders: a click on a folder's
 * name opens it, the box in front of a name takes it in or leaves it out. A folder that is taken in takes in
 * everything in it, and leaving out something in it leaves out just that; a box with "-" has some of its contents
 * taken in.
 */
final class FileBrowserScreen extends Screen {
    /** Folders a list may not write into, or that are no use to share: not shown at all */
    private static final Set<String> HIDDEN = Set.of("mods", ".modsync", "saves", "logs", "crash-reports", "screenshots",
            "versions", "libraries", "assets", ".fabric", ModSync.MOD_ID + "-export");
    private static final int ROW_WIDTH = 310;

    private final Screen parent;
    private final Path gameFolder;
    private final Set<String> selected;
    private final Consumer<Set<String>> done;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 48, 33);
    private @Nullable FileList list;
    private String folder = "";

    /** @param done told the chosen paths when the player is done */
    FileBrowserScreen(Screen parent, Path gameFolder, Set<String> selected, Consumer<Set<String>> done) {
        super(Component.translatable("modsync.browse.title"));
        this.parent = parent;
        this.gameFolder = gameFolder;
        this.selected = new TreeSet<>(selected);
        this.done = done;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 48, 33);
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4), settings -> settings.alignHorizontallyCenter());
        header.addChild(new StringWidget(title, font), settings -> settings.alignHorizontallyCenter());
        String shown = folder.isEmpty() ? "" : " / " + String.join(" / ", folder.split("/"));
        header.addChild(new StringWidget(ROW_WIDTH, 12, Component.translatable("modsync.browse.gameFolder").append(shown)
                .withStyle(ChatFormatting.GRAY), font), settings -> settings.alignHorizontallyCenter());

        FileList files = layout.addToContents(new FileList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));
        list = files;
        files.show(entries(folder));

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(10));
        Button up = footer.addChild(Button.builder(Component.translatable("modsync.browse.up"), button -> open(parentOf(folder))).width(150).build());
        up.active = !folder.isEmpty();
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(150).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    @Override
    public void onClose() {
        done.accept(selected);
        minecraft.gui.setScreen(parent);
    }

    private void open(String newFolder) {
        folder = newFolder;
        rebuildWidgets();
    }

    /** The folder's contents, folders first, without what a list may not have */
    private List<Path> entries(String inFolder) {
        Path dir = inFolder.isEmpty() ? gameFolder : gameFolder.resolve(inFolder);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> !(inFolder.isEmpty() && HIDDEN.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))))
                    .filter(file -> !relative(file).equals("config/" + ModSync.MOD_ID + ".json"))
                    .sorted(Comparator.comparing((Path file) -> !Files.isDirectory(file))
                            .thenComparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            ModSync.LOGGER.warn("Could not list {}", dir, e);
            return List.of();
        }
    }

    private String relative(Path file) {
        return gameFolder.relativize(file).toString().replace(gameFolder.getFileSystem().getSeparator(), "/");
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private enum State {
        /** Taken in itself */
        SELECTED,
        /** Taken in with a folder it is in */
        IMPLIED,
        /** A folder with some of its contents taken in */
        PARTIAL,
        NONE
    }

    private State state(String path) {
        if (selected.contains(path)) {
            return State.SELECTED;
        }
        for (String parentPath = parentOf(path); !parentPath.isEmpty(); parentPath = parentOf(parentPath)) {
            if (selected.contains(parentPath)) {
                return State.IMPLIED;
            }
        }
        return selected.stream().anyMatch(other -> other.startsWith(path + "/")) ? State.PARTIAL : State.NONE;
    }

    private void toggle(String path) {
        switch (state(path)) {
            case SELECTED -> selected.remove(path);
            case IMPLIED -> leaveOut(path);
            default -> {
                // A folder takes in everything in it: what was chosen inside it is part of it now
                selected.removeIf(other -> other.startsWith(path + "/"));
                selected.add(path);
            }
        }
        if (list != null) {
            list.children().forEach(Row::update);
        }
    }

    /**
     * Leaves out one file or folder of a folder that is taken in as a whole: the folder is split up on the way down to
     * it, into everything next to that way, so that all else stays taken in
     */
    private void leaveOut(String path) {
        String folderTaken = parentOf(path);
        while (!selected.contains(folderTaken)) {
            folderTaken = parentOf(folderTaken);
        }
        selected.remove(folderTaken);
        for (String current = folderTaken; !current.equals(path); ) {
            int slash = path.indexOf('/', current.length() + 1);
            String next = slash < 0 ? path : path.substring(0, slash);
            for (Path child : entries(current)) {
                String childPath = relative(child);
                if (!childPath.equals(next)) {
                    selected.add(childPath);
                }
            }
            current = next;
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private final class FileList extends ContainerObjectSelectionList<Row> {
        FileList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 24);
        }

        void show(List<Path> files) {
            List<Row> rows = new ArrayList<>();
            for (Path file : files) {
                rows.add(new Row(relative(file), Files.isDirectory(file), file));
            }
            replaceEntries(rows);
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }
    }

    private final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final String path;
        private final boolean isFolder;
        private final String size;
        private final Button box;
        private final Button name;

        Row(String path, boolean isFolder, Path file) {
            this.path = path;
            this.isFolder = isFolder;
            String fileSize = "";
            if (!isFolder) {
                try {
                    fileSize = FileBrowserScreen.size(Files.size(file));
                } catch (IOException e) {
                    fileSize = "?";
                }
            }
            this.size = fileSize;
            String fileName = file.getFileName().toString();
            box = Button.builder(Component.empty(), button -> toggle(path)).width(20).build();
            name = Button.builder(Component.literal(isFolder ? fileName + "/" : fileName),
                    button -> {
                        if (isFolder) {
                            open(path);
                        } else {
                            toggle(path);
                        }
                    }).width(ROW_WIDTH - 24 - (isFolder ? 0 : 54)).build();
            update();
        }

        void update() {
            State state = state(path);
            box.setMessage(switch (state) {
                case SELECTED -> Component.literal("✔").withStyle(ChatFormatting.GREEN);
                case IMPLIED -> Component.literal("✔").withStyle(ChatFormatting.DARK_GREEN);
                case PARTIAL -> Component.literal("-").withStyle(ChatFormatting.YELLOW);
                case NONE -> Component.empty();
            });
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            box.setPosition(getContentX(), getContentY());
            box.extractRenderState(graphics, mouseX, mouseY, partialTick);
            name.setPosition(getContentX() + 24, getContentY());
            name.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (!isFolder) {
                graphics.text(font, size, getContentRight() - font.width(size), getContentY() + 6, 0xFFA0A0A0, false);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(box, name);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(box, name);
        }
    }
}
