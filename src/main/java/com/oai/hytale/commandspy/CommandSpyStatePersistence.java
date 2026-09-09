package com.oai.hytale.commandspy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandSpyStatePersistence {
    private static final Object LOCK = new Object();
    private static final Pattern ACTIVE_STATE = Pattern.compile(
            "\\\"([0-9a-fA-F-]{36})\\\"\\s*:\\s*\\{[^}]*\\\"enabled\\\"\\s*:\\s*true",
            Pattern.CASE_INSENSITIVE);

    private CommandSpyStatePersistence() {
    }

    static void saveActive(ConcurrentHashMap<UUID, CommandSpyPlugin.SpyState> states, Path path) {
        synchronized (LOCK) {
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }

                List<Map.Entry<UUID, CommandSpyPlugin.SpyState>> active = new ArrayList<>();
                for (Map.Entry<UUID, CommandSpyPlugin.SpyState> entry : states.entrySet()) {
                    CommandSpyPlugin.SpyState state = entry.getValue();
                    if (state != null && state.enabled()) {
                        active.add(entry);
                        continue;
                    }

                    // Disabled staff are intentionally not persisted. Reset hidden filters so
                    // the next activation starts from the normal enabled defaults.
                    if (state != null && (!state.messages() || !state.commands())) {
                        CommandSpyPlugin.SpyState reset = new CommandSpyPlugin.SpyState(false, true, true);
                        states.replace(entry.getKey(), state, reset);
                    }
                }

                active.sort(Comparator.comparing(entry -> entry.getKey().toString()));
                StringBuilder json = new StringBuilder("{\n  \"users\": {\n");
                for (int i = 0; i < active.size(); i++) {
                    Map.Entry<UUID, CommandSpyPlugin.SpyState> entry = active.get(i);
                    CommandSpyPlugin.SpyState state = entry.getValue();
                    json.append("    \"").append(entry.getKey()).append("\": { \"enabled\": true, ")
                            .append("\"messages\": ").append(state.messages()).append(", ")
                            .append("\"commands\": ").append(state.commands()).append(" }");
                    if (i + 1 < active.size()) {
                        json.append(',');
                    }
                    json.append('\n');
                }
                json.append("  }\n}\n");
                atomicWrite(path, json.toString());

                Set<UUID> activeUuids = new HashSet<>();
                for (Map.Entry<UUID, CommandSpyPlugin.SpyState> entry : active) {
                    activeUuids.add(entry.getKey());
                }
                CommandSpyConsoleSupport.STATES.keySet().retainAll(activeUuids);
                Path consolePath = path.resolveSibling("console-state.json");
                writeConsole(CommandSpyConsoleSupport.STATES, consolePath, activeUuids);
            } catch (Exception ignored) {
            }
        }
    }

    static void saveConsole(ConcurrentHashMap<UUID, Boolean> states, Path path) {
        if (path == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Set<UUID> active = readActive(path.resolveSibling("spy-state.json"));
                states.keySet().retainAll(active);
                writeConsole(states, path, active);
            } catch (Exception ignored) {
            }
        }
    }

    private static Set<UUID> readActive(Path path) {
        Set<UUID> active = new HashSet<>();
        try {
            if (!Files.exists(path)) {
                return active;
            }
            Matcher matcher = ACTIVE_STATE.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) {
                try {
                    active.add(UUID.fromString(matcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return active;
    }

    private static void writeConsole(ConcurrentHashMap<UUID, Boolean> states, Path path,
                                     Set<UUID> active) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        List<UUID> uuids = new ArrayList<>(active);
        uuids.sort(Comparator.comparing(UUID::toString));
        StringBuilder json = new StringBuilder("{\n  \"users\": {\n");
        int written = 0;
        for (UUID uuid : uuids) {
            Boolean enabled = states.get(uuid);
            if (enabled == null) {
                continue;
            }
            if (written++ > 0) {
                json.append(",\n");
            }
            json.append("    \"").append(uuid).append("\": ").append(enabled);
        }
        if (written > 0) {
            json.append('\n');
        }
        json.append("  }\n}\n");
        atomicWrite(path, json.toString());
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
