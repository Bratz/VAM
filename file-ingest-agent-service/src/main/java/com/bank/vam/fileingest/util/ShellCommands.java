package com.bank.vam.fileingest.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the real bash to shell out to — not whatever "bash" happens to resolve to first on
 * PATH. On a Windows dev box with WSL installed, plain "bash" via {@link ProcessBuilder} can
 * resolve to the WSL launcher stub in {@code System32} instead of Git for Windows' bash, and WSL
 * does not inherit the launching process's environment variables at all (confirmed: not even a
 * synthetic test variable came through, let alone JAVA_HOME) unless explicitly whitelisted via
 * WSLENV. Falls back to plain "bash" everywhere else (Linux containers, CI, mac), where this
 * collision doesn't exist.
 */
public final class ShellCommands {

    private static final String[] WINDOWS_GIT_BASH_CANDIDATES = {
            "C:/Program Files/Git/usr/bin/bash.exe",
            "C:/Program Files (x86)/Git/usr/bin/bash.exe"
    };

    private ShellCommands() {
    }

    public static String bashExecutable() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            for (String candidate : WINDOWS_GIT_BASH_CANDIDATES) {
                if (Files.isRegularFile(Path.of(candidate))) {
                    return candidate;
                }
            }
        }
        return "bash";
    }
}
