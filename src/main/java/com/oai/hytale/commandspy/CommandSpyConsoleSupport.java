package com.oai.hytale.commandspy;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandSpyConsoleSupport {
    static final String PERMISSION = "command.spy.console";

    private static final Pattern ENTRY = Pattern.compile(
            "\\\"([0-9a-fA-F-]{36})\\\"\\s*:\\s*(true|false)",
            Pattern.CASE_INSENSITIVE);
    static final ConcurrentHashMap<UUID, Boolean> STATES = new ConcurrentHashMap<>();
    private static final Object IO_LOCK = new Object();

    private static volatile boolean loaded;
    private static volatile Path statePath;

    private CommandSpyConsoleSupport() {
    }

    static boolean hasPermission(CommandSpyPlugin plugin, UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            if (plugin != null && plugin.hasMaster(uuid)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        PlayerRef player = player(plugin, uuid);
        if (player == null) {
            return false;
        }
        try {
            return player.hasPermission(CommandSpyPlugin.MASTER_PERMISSION) || player.hasPermission(PERMISSION);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAuthorizedAndEnabled(CommandSpyPlugin plugin, UUID uuid) {
        return hasPermission(plugin, uuid) && isEnabled(plugin, uuid);
    }

    static boolean isEnabled(CommandSpyPlugin plugin, UUID uuid) {
        ensureLoaded(plugin);
        return uuid != null && STATES.getOrDefault(uuid, Boolean.TRUE);
    }

    static void enableIfAuthorized(CommandSpyPlugin plugin, UUID uuid) {
        if (!hasPermission(plugin, uuid)) {
            return;
        }
        ensureLoaded(plugin);
        Boolean previous = STATES.put(uuid, Boolean.TRUE);
        if (!Boolean.TRUE.equals(previous)) {
            save();
        }
    }

    static void toggle(CommandSpyPlugin plugin, PlayerRef player) {
        if (player == null || player.getUuid() == null) {
            return;
        }
        UUID uuid = player.getUuid();
        if (!hasPermission(plugin, uuid)) {
            plugin.send(player, "commandspy.chat.no-permission-console");
            return;
        }
        boolean enabled = !isEnabled(plugin, uuid);
        setInternal(plugin, uuid, enabled);
        plugin.send(player, enabled
                ? "commandspy.chat.filter-console-enabled"
                : "commandspy.chat.filter-console-disabled");
    }

    static void set(CommandSpyPlugin plugin, PlayerRef player, boolean enabled) {
        if (player == null || player.getUuid() == null) {
            return;
        }
        UUID uuid = player.getUuid();
        if (hasPermission(plugin, uuid)) {
            setInternal(plugin, uuid, enabled);
        }
    }

    private static void setInternal(CommandSpyPlugin plugin, UUID uuid, boolean enabled) {
        ensureLoaded(plugin);
        Boolean previous = STATES.put(uuid, enabled);
        if (previous == null || previous != enabled) {
            save();
        }
    }

    static boolean broadcastIfConsole(CommandSpyPlugin plugin, String executor, String command) {
        if (!"console".equalsIgnoreCase(executor)) {
            return false;
        }

        ensureLoaded(plugin);
        for (PlayerRef recipient : onlinePlayers(plugin)) {
            try {
                if (recipient == null || !recipient.isValid() || recipient.getUuid() == null) {
                    continue;
                }
                UUID uuid = recipient.getUuid();
                if (!hasPermission(plugin, uuid) || !isEnabled(plugin, uuid)) {
                    continue;
                }
                if (!plugin.getState(uuid).enabled()) {
                    continue;
                }
                recipient.sendMessage(CommandSpyColorSupport.spyMessage(recipient, "console", command));
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    static void reload(CommandSpyPlugin plugin) {
        synchronized (IO_LOCK) {
            loaded = false;
            STATES.clear();
            statePath = null;
        }
        ensureLoaded(plugin);
    }

    private static void ensureLoaded(CommandSpyPlugin plugin) {
        if (loaded) {
            return;
        }
        synchronized (IO_LOCK) {
            if (loaded) {
                return;
            }

            statePath = resolveStatePath(plugin);
            STATES.clear();
            if (statePath != null && Files.isRegularFile(statePath)) {
                try {
                    String raw = Files.readString(statePath, StandardCharsets.UTF_8);
                    Matcher matcher = ENTRY.matcher(raw);
                    while (matcher.find()) {
                        try {
                            STATES.put(UUID.fromString(matcher.group(1)), Boolean.parseBoolean(matcher.group(2)));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            }

            loaded = true;
            if (statePath != null && !Files.exists(statePath)) {
                save();
            }
        }
    }

    private static Path resolveStatePath(CommandSpyPlugin plugin) {
        return plugin == null ? statePath : plugin.dataDirectory().resolve("console-state.json");
    }

    private static void save() {
        CommandSpyStatePersistence.saveConsole(STATES, statePath);
    }

    private static PlayerRef player(CommandSpyPlugin plugin, UUID uuid) {
        return plugin == null ? null : plugin.onlinePlayers.get(uuid);
    }

    private static Iterable<PlayerRef> onlinePlayers(CommandSpyPlugin plugin) {
        return plugin == null ? List.of() : plugin.onlinePlayers.values();
    }
}
