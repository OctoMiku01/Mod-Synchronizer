package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A separate Java process that waits for the game to exit and then carries out the steps {@link Stager} wrote: only
 * then are the jars no longer in use. It runs from a copy of this mod's jar, so that it can replace this mod as well.
 * Uses nothing but the JDK: neither Minecraft's nor Fabric's classes are on its class path.
 */
public final class ApplyHelper {
    static final String DELETE = "DELETE";
    static final String MOVE = "MOVE";
    /** Names the files the last attempt could not put in place, one per line; there only while some are left */
    public static final String FAILED = "apply-failed.txt";
    /** A jar can stay locked for a moment after the game has exited, by a virus scanner for example */
    private static final int ATTEMPTS = 60;

    private ApplyHelper() {
    }

    /**
     * Starts the helper for the steps in {@code stepsFile}; it waits for this process to exit.
     *
     * @param classPath where this class is: the mod's jar, or a classes folder in a development environment
     */
    public static void launch(Path stepsFile, List<Path> classPath) throws IOException {
        Path helperFolder = stepsFile.resolveSibling("helper");
        Files.createDirectories(helperFolder);
        List<String> copies = new ArrayList<>();
        for (Path path : classPath) {
            if (Files.isRegularFile(path)) {
                Path copy = helperFolder.resolve(path.getFileName());
                Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                copies.add(copy.toAbsolutePath().toString());
            } else {
                copies.add(path.toAbsolutePath().toString());
            }
        }
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        new ProcessBuilder(java, "-cp", String.join(System.getProperty("path.separator"), copies), ApplyHelper.class.getName(),
                Long.toString(ProcessHandle.current().pid()), stepsFile.toAbsolutePath().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /**
     * Deletes what earlier syncs left in the work folder: the copies the helper ran from, which it cannot delete while
     * it runs, and downloads no pending steps refer to any more. A file still in use is left for the next start.
     */
    public static void cleanUp(Path workFolder) {
        deleteFiles(workFolder.resolve("helper"));
        if (!Files.exists(workFolder.resolve("pending.txt"))) {
            deleteFiles(workFolder.resolve("staging"));
        }
    }

    private static void deleteFiles(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        // Deepest first, so that the folders of the list's files go as well once they are empty
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(file -> !file.equals(folder)).sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // Still in use, by a helper that has not finished yet
                }
            }
        } catch (IOException e) {
            // Gone or unreadable: nothing to clean up
        }
    }

    public static void main(String[] args) throws Exception {
        long gamePid = Long.parseLong(args[0]);
        Path stepsFile = Path.of(args[1]);
        Path log = stepsFile.resolveSibling("apply.log");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            out.println(LocalDateTime.now() + " waiting for the game (pid " + gamePid + ") to exit");
            out.flush();
            Optional<ProcessHandle> game = ProcessHandle.of(gamePid);
            if (game.isPresent()) {
                game.get().onExit().join();
            }
            List<String> steps;
            try (Stream<String> lines = Files.lines(stepsFile, StandardCharsets.UTF_8)) {
                steps = lines.filter(line -> !line.isBlank()).toList();
            }
            List<String> failed = new ArrayList<>();
            for (String step : steps) {
                try {
                    run(step);
                    out.println("done: " + step);
                } catch (IOException e) {
                    String[] parts = step.split("\t");
                    failed.add(Path.of(parts[parts.length - 1]).getFileName().toString());
                    out.println("FAILED: " + step + " (" + e + ")");
                }
            }
            // The game reads this at its next start, to tell the player and offer to try again
            Path failedFile = stepsFile.resolveSibling(FAILED);
            if (failed.isEmpty()) {
                Files.deleteIfExists(stepsFile);
                Files.deleteIfExists(failedFile);
            } else {
                Files.write(failedFile, failed, StandardCharsets.UTF_8);
            }
            out.println(LocalDateTime.now() + (failed.isEmpty() ? " finished" : " finished with errors"));
        }
    }

    private static void run(String step) throws IOException, InterruptedException {
        String[] parts = step.split("\t");
        for (int attempt = 1; ; attempt++) {
            try {
                switch (parts[0]) {
                    case DELETE -> Files.deleteIfExists(Path.of(parts[1]));
                    case MOVE -> {
                        Path target = Path.of(parts[2]);
                        if (!Files.exists(Path.of(parts[1])) && Files.exists(target)) {
                            // Moved in an earlier attempt that did not get through all steps
                            return;
                        }
                        // A file of the list may go to a folder that is not there yet
                        Files.createDirectories(target.getParent());
                        Files.move(Path.of(parts[1]), target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    default -> throw new IOException("unknown step " + parts[0]);
                }
                return;
            } catch (IOException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }
}
