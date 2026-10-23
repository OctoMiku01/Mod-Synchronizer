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
            boolean failed = false;
            for (String step : steps) {
                try {
                    run(step);
                    out.println("done: " + step);
                } catch (IOException e) {
                    failed = true;
                    out.println("FAILED: " + step + " (" + e + ")");
                }
            }
            if (!failed) {
                Files.deleteIfExists(stepsFile);
            }
            out.println(LocalDateTime.now() + (failed ? " finished with errors" : " finished"));
        }
    }

    private static void run(String step) throws IOException, InterruptedException {
        String[] parts = step.split("\t");
        for (int attempt = 1; ; attempt++) {
            try {
                switch (parts[0]) {
                    case DELETE -> Files.deleteIfExists(Path.of(parts[1]));
                    case MOVE -> Files.move(Path.of(parts[1]), Path.of(parts[2]), StandardCopyOption.REPLACE_EXISTING);
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
