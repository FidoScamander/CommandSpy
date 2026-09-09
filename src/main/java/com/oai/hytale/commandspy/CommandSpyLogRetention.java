package com.oai.hytale.commandspy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandSpyLogRetention {
    private static final Object LOCK = new Object();
    private static final int DEFAULT_RETENTION_DAYS = 15;
    private static final long PRUNE_INTERVAL_MS = 3_600_000L;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern RETENTION = Pattern.compile("\\\"log-retention-days\\\"\\s*:\\s*(-?\\d+)");
    private static final Pattern OLD_SESSION_LOG = Pattern.compile(
            "(?:\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_commands(?:-\\d+)?|commands-current)\\.log");

    private static volatile int capturedRetentionDays = DEFAULT_RETENTION_DAYS;
    private static volatile long lastPruneMillis;

    private CommandSpyLogRetention() {
    }

    static void capture(Path configPath) {
        capturedRetentionDays = readRetention(configPath, DEFAULT_RETENTION_DAYS);
    }

    static void restore(Path configPath) {
        int retention = sanitize(capturedRetentionDays);
        try {
            String raw = Files.exists(configPath)
                    ? Files.readString(configPath, StandardCharsets.UTF_8)
                    : "{}\n";
            Matcher matcher = RETENTION.matcher(raw);
            if (matcher.find()) {
                String updated = matcher.replaceFirst("\\\"log-retention-days\\\": " + retention);
                if (!updated.equals(raw)) {
                    Files.writeString(configPath, updated, StandardCharsets.UTF_8);
                }
                return;
            }

            int close = raw.lastIndexOf('}');
            if (close < 0) {
                raw = "{}\n";
                close = 1;
            }
            String before = raw.substring(0, close).stripTrailing();
            boolean hasFields = before.indexOf('{') >= 0
                    && !before.substring(before.indexOf('{') + 1).trim().isEmpty();
            StringBuilder updated = new StringBuilder(before);
            if (hasFields && !before.endsWith(",")) {
                updated.append(',');
            }
            updated.append("\n\n  // Keep audit-log entries for this many days (default: 15)\n")
                    .append("  \"log-retention-days\": ").append(retention).append('\n')
                    .append(raw.substring(close));
            Files.writeString(configPath, updated.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    static Path initialize(Path configPath, Path logsDirectory) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(logsDirectory);
                Path log = logsDirectory.resolve("commands.log");
                migrateOldLogs(logsDirectory, log);
                if (!Files.exists(log)) {
                    Files.createFile(log);
                }
                lastPruneMillis = 0L;
                pruneNow(configPath, log);
                return log;
            } catch (Exception ignored) {
                return logsDirectory.resolve("commands.log");
            }
        }
    }

    static void append(Path logPath, long millis, String executor, String command, Path configPath) {
        synchronized (LOCK) {
            try {
                if (logPath == null) {
                    Path base = configPath == null ? null : configPath.getParent();
                    if (base == null) {
                        return;
                    }
                    Path logs = base.resolve("logs");
                    Files.createDirectories(logs);
                    logPath = logs.resolve("commands.log");
                } else if (logPath.getParent() != null) {
                    Files.createDirectories(logPath.getParent());
                }

                String timestamp = LOG_TIME.format(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
                String line = "[" + timestamp + "] " + executor + ": " + command + System.lineSeparator();
                Files.writeString(logPath, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                pruneIfDue(configPath, logPath);
            } catch (Exception ignored) {
            }
        }
    }

    private static void pruneIfDue(Path configPath, Path logPath) {
        long now = System.currentTimeMillis();
        if (now - lastPruneMillis < PRUNE_INTERVAL_MS) {
            return;
        }
        pruneNow(configPath, logPath);
    }

    private static void pruneNow(Path configPath, Path logPath) {
        lastPruneMillis = System.currentTimeMillis();
        int retention = readRetention(configPath, capturedRetentionDays);
        capturedRetentionDays = retention;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retention);

        try {
            if (!Files.exists(logPath)) {
                return;
            }
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return;
            }

            List<String> kept = new ArrayList<>(lines.size());
            boolean changed = false;
            for (String line : lines) {
                LocalDateTime timestamp = timestamp(line);
                if (timestamp != null && timestamp.isBefore(cutoff)) {
                    changed = true;
                } else {
                    kept.add(line);
                }
            }
            if (changed) {
                rewrite(logPath, kept);
            }
        } catch (Exception ignored) {
        }
    }

    private static LocalDateTime timestamp(String line) {
        if (line == null || line.length() < 21 || line.charAt(0) != '[' || line.charAt(20) != ']') {
            return null;
        }
        try {
            return LocalDateTime.parse(line.substring(1, 20), LOG_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static int readRetention(Path path, int fallback) {
        int safeFallback = sanitize(fallback);
        if (path == null) {
            return safeFallback;
        }
        try {
            if (!Files.exists(path)) {
                return safeFallback;
            }
            Matcher matcher = RETENTION.matcher(Files.readString(path, StandardCharsets.UTF_8));
            if (matcher.find()) {
                return sanitize(Integer.parseInt(matcher.group(1)));
            }
        } catch (Exception ignored) {
        }
        return safeFallback;
    }

    private static int sanitize(int days) {
        return days > 0 ? days : DEFAULT_RETENTION_DAYS;
    }

    private static void migrateOldLogs(Path directory, Path target) throws IOException {
        List<Path> oldLogs = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && OLD_SESSION_LOG.matcher(file.getFileName().toString()).matches()) {
                    oldLogs.add(file);
                }
            }
        }
        if (oldLogs.isEmpty()) {
            return;
        }

        Collections.sort(oldLogs);
        List<String> merged = new ArrayList<>();
        if (Files.exists(target)) {
            merged.addAll(Files.readAllLines(target, StandardCharsets.UTF_8));
        }
        for (Path old : oldLogs) {
            merged.addAll(Files.readAllLines(old, StandardCharsets.UTF_8));
        }
        Collections.sort(merged);
        rewrite(target, merged);

        for (Path old : oldLogs) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException ignored) {
            }
        }
    }

    private static void rewrite(Path path, List<String> lines) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
