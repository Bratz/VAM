package com.bank.vam.service.fileingest;

// ponytail: duplicated from file-ingest-agent-service's own ShellCommands — confirmed by hand
// (that session's own live debugging) that plain "bash" via ProcessBuilder can resolve to the
// WSL launcher stub in Windows System32 instead of Git for Windows' real bash, and WSL does not
// inherit the launching process's environment variables at all (not even JAVA_HOME). Falls back
// to plain "bash" everywhere else (Linux containers, CI, mac), where this collision doesn't exist.

import java.nio.file.Files;
import java.nio.file.Path;

final class ShellCommands {

    private static final String[] WINDOWS_GIT_BASH_CANDIDATES = {
            "C:/Program Files/Git/usr/bin/bash.exe",
            "C:/Program Files (x86)/Git/usr/bin/bash.exe"
    };

    private ShellCommands() {
    }

    static String bashExecutable() {
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
