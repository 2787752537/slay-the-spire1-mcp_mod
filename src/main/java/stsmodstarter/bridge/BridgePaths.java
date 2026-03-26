package stsmodstarter.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BridgePaths {
    public static final String RUNTIME_ENV = "STS_MCP_RUNTIME_DIR";
    public static final String DEFAULT_RUNTIME_DIR = "D:/code/codex_2/Sts_mod/runtime";

    private static final Path RUNTIME_DIR = resolveRuntimeDir();
    private static final Path STATE_FILE = RUNTIME_DIR.resolve("state.json");
    private static final Path COMMAND_FILE = RUNTIME_DIR.resolve("command.json");
    private static final Path RESPONSE_FILE = RUNTIME_DIR.resolve("response.json");

    private BridgePaths() {
    }

    public static Path runtimeDir() {
        return RUNTIME_DIR;
    }

    public static Path stateFile() {
        return STATE_FILE;
    }

    public static Path commandFile() {
        return COMMAND_FILE;
    }

    public static Path responseFile() {
        return RESPONSE_FILE;
    }

    public static void ensureRuntimeDir() {
        try {
            Files.createDirectories(RUNTIME_DIR);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to create runtime dir: " + RUNTIME_DIR, exception);
        }
    }

    private static Path resolveRuntimeDir() {
        String value = System.getenv(RUNTIME_ENV);
        if (value == null || value.trim().isEmpty()) {
            value = DEFAULT_RUNTIME_DIR;
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }
}