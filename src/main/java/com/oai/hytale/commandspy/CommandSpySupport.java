package com.oai.hytale.commandspy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandSpySupport {
    private static final Pattern STATE = Pattern.compile(
            "\\\"([0-9a-fA-F-]{36})\\\"\\s*:\\s*\\{\\s*\\\"enabled\\\"\\s*:\\s*(true|false)\\s*,\\s*\\\"messages\\\"\\s*:\\s*(true|false)\\s*,\\s*\\\"commands\\\"\\s*:\\s*(true|false)\\s*\\}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile("\\\"([0-9a-fA-F-]{36})\\\"");
    private static final Pattern EXECUTED_COMMAND_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:\\[CommandManager\\]\\s*)?(?!unknown\\s+command)(.+?)\\s+" +
                    "(?:executed|executes|executing|issued|runs?|ran|used|dispatch(?:ed|ing)?)\\s+" +
                    "(?:the\\s+)?command\\s*[:=\\-]?\\s*/?(.+?)\\s*$");

    private static final Set<String> PRIVATE_COMMANDS = Set.of(
            "msg", "m", "message", "pm", "tell", "w", "whisper", "r", "reply");
    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "login", "log", "l", "register", "reg", "unregister", "auth", "password", "passwd",
            "changepassword", "changepass", "cp", "setpassword", "setpass", "resetpassword", "resetpass",
            "2fa", "otp", "pin");
    private static final ConcurrentHashMap<String, Long> RECENT_RECORDS = new ConcurrentHashMap<>();

    private CommandSpySupport() {
    }

    static void loadConfig(CommandSpyPlugin plugin) {
        try {
            Files.createDirectories(plugin.dataDirectory());
            Path path = plugin.configPath();
            CommandSpyPlugin.Config loaded;
            if (!Files.exists(path)) {
                loaded = CommandSpyPlugin.Config.defaults();
            } else {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                boolean defaults = bool(raw, "default-spy-enabled", false);
                boolean playerCommands = bool(raw, "log-player-commands", true);
                boolean privateMessages = bool(raw, "log-private-messages",
                        bool(raw, "spy-private-messages", true));
                boolean consoleCommands = bool(raw, "log-console-commands",
                        bool(raw, "spy-console-commands", true));
                loaded = new CommandSpyPlugin.Config(defaults, playerCommands, privateMessages, consoleCommands);
            }
            plugin.config = loaded;
            Files.writeString(path, configJson(loaded), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            plugin.config = CommandSpyPlugin.Config.defaults();
        }
    }

    static String configJson(CommandSpyPlugin.Config config) {
        return """
                {
                  // Automatically enable CommandSpy for authorized staff on first use/join
                  "default-spy-enabled": %s,

                  // Save regular commands executed by players in the current startup log
                  "log-player-commands": %s,

                  // Save private-message commands in the current startup log under logs/
                  // (/msg, /tell, /w, /whisper, /r, /reply, /mail send)
                  "log-private-messages": %s,

                  // Save commands executed by the console in the current startup log
                  "log-console-commands": %s
                }
                """.formatted(
                config.defaultSpyEnabled(),
                config.logPlayerCommands(),
                config.logPrivateMessages(),
                config.logConsoleCommands());
    }

    private static boolean bool(String raw, String key, boolean fallback) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    static void loadState(CommandSpyPlugin plugin) {
        plugin.states.clear();
        plugin.initializedStates.clear();
        Path path = plugin.statePath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Matcher matcher = STATE.matcher(raw);
            boolean structured = false;
            while (matcher.find()) {
                structured = true;
                try {
                    UUID uuid = UUID.fromString(matcher.group(1));
                    plugin.states.put(uuid, new CommandSpyPlugin.SpyState(
                            Boolean.parseBoolean(matcher.group(2)),
                            Boolean.parseBoolean(matcher.group(3)),
                            Boolean.parseBoolean(matcher.group(4))));
                    plugin.initializedStates.add(uuid);
                } catch (IllegalArgumentException ignored) {
                }
            }

            // Upgrade the old UUID-array format used by early builds.
            if (!structured) {
                Matcher legacy = UUID_PATTERN.matcher(raw);
                while (legacy.find()) {
                    try {
                        UUID uuid = UUID.fromString(legacy.group(1));
                        plugin.states.put(uuid, new CommandSpyPlugin.SpyState(true, true, true));
                        plugin.initializedStates.add(uuid);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized void saveState(CommandSpyPlugin plugin) {
        CommandSpyStatePersistence.saveActive(plugin.states, plugin.statePath());
    }

    static CommandSpyPlugin.SpyState ensureState(CommandSpyPlugin plugin, UUID uuid) {
        CommandSpyPlugin.SpyState existing = plugin.states.get(uuid);
        if (existing != null) {
            return existing;
        }

        boolean defaultEnabled = plugin.config.defaultSpyEnabled() && hasAnyPermission(plugin, uuid);
        CommandSpyPlugin.SpyState initial = new CommandSpyPlugin.SpyState(defaultEnabled, true, true);
        CommandSpyPlugin.SpyState raced = plugin.states.putIfAbsent(uuid, initial);
        plugin.initializedStates.add(uuid);
        if (raced == null) {
            saveState(plugin);
        }
        return raced == null ? initial : raced;
    }

    static boolean hasAnyPermission(CommandSpyPlugin plugin, UUID uuid) {
        return CommandSpyConsoleSupport.hasPermission(plugin, uuid)
                || plugin.hasMaster(uuid)
                || plugin.hasPermission(uuid, CommandSpyPlugin.MESSAGES_PERMISSION)
                || plugin.hasPermission(uuid, CommandSpyPlugin.COMMANDS_PERMISSION);
    }

    static boolean hasCategoryPermission(CommandSpyPlugin plugin, UUID uuid, CommandSpyPlugin.Category category) {
        if (plugin.hasMaster(uuid)) {
            return true;
        }
        return switch (category) {
            case MESSAGES -> plugin.hasPermission(uuid, CommandSpyPlugin.MESSAGES_PERMISSION);
            case COMMANDS -> plugin.hasPermission(uuid, CommandSpyPlugin.COMMANDS_PERMISSION);
        };
    }

    static boolean hasAnyEnabledFilter(CommandSpyPlugin plugin, UUID uuid, CommandSpyPlugin.SpyState state) {
        return CommandSpyConsoleSupport.isAuthorizedAndEnabled(plugin, uuid)
                || (hasCategoryPermission(plugin, uuid, CommandSpyPlugin.Category.MESSAGES) && state.messages())
                || (hasCategoryPermission(plugin, uuid, CommandSpyPlugin.Category.COMMANDS) && state.commands());
    }

    static CommandSpyPlugin.SpyState enableAuthorizedFilters(CommandSpyPlugin plugin, UUID uuid,
                                                              CommandSpyPlugin.SpyState state) {
        CommandSpyConsoleSupport.enableIfAuthorized(plugin, uuid);
        return new CommandSpyPlugin.SpyState(
                state.enabled(),
                state.messages() || hasCategoryPermission(plugin, uuid, CommandSpyPlugin.Category.MESSAGES),
                state.commands() || hasCategoryPermission(plugin, uuid, CommandSpyPlugin.Category.COMMANDS));
    }

    static void handleRecord(CommandSpyPlugin plugin, LogRecord record) {
        if (plugin == null || record == null) {
            return;
        }

        String logger = record.getLoggerName();
        String source = record.getSourceClassName();
        String message = formatRecord(record);
        boolean commandRecord = (logger != null && logger.contains("CommandManager"))
                || (source != null && source.contains("CommandManager"))
                || message.toLowerCase(Locale.ROOT).contains("executed command");
        if (!commandRecord) {
            return;
        }

        String normalized = message.trim();
        int bracket = normalized.indexOf(']');
        if (bracket >= 0 && normalized.toLowerCase(Locale.ROOT).contains("command")) {
            normalized = normalized.substring(bracket + 1).trim();
        }

        Matcher matcher = EXECUTED_COMMAND_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return;
        }

        String executor = matcher.group(1).trim();
        if (executor.startsWith("[CommandManager]")) {
            executor = executor.substring("[CommandManager]".length()).trim();
        }
        String command = matcher.group(2).trim();
        if (executor.isEmpty() || command.isEmpty() || isSensitive(command)) {
            return;
        }

        String dedupeKey = record.getMillis() + "|" + executor.toLowerCase(Locale.ROOT) + "|" + command;
        if (RECENT_RECORDS.putIfAbsent(dedupeKey, System.currentTimeMillis()) != null) {
            return;
        }
        if (RECENT_RECORDS.size() > 1024) {
            long cutoff = System.currentTimeMillis() - 15_000L;
            RECENT_RECORDS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }

        CommandSpyPlugin.Config config = plugin.config;
        if ("console".equalsIgnoreCase(executor)) {
            if (config.logConsoleCommands()) {
                plugin.appendLog(record.getMillis(), executor, command);
            }
            CommandSpyConsoleSupport.broadcastIfConsole(plugin, executor, command);
            return;
        }

        CommandSpyPlugin.Category category = classify(command);
        boolean log = category == CommandSpyPlugin.Category.MESSAGES
                ? config.logPrivateMessages()
                : config.logPlayerCommands();
        if (log) {
            plugin.appendLog(record.getMillis(), executor, command);
        }
        plugin.broadcast(executor, command, category);
    }

    private static String formatRecord(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        try {
            return MessageFormat.format(message, parameters);
        } catch (Exception ignored) {
            return message;
        }
    }

    private static CommandSpyPlugin.Category classify(String command) {
        List<String> parts = split(command);
        if (parts.isEmpty()) {
            return CommandSpyPlugin.Category.COMMANDS;
        }
        String root = parts.get(0);
        if (PRIVATE_COMMANDS.contains(root)) {
            return CommandSpyPlugin.Category.MESSAGES;
        }
        if ("mail".equals(root) && parts.size() > 1 && "send".equals(parts.get(1))) {
            return CommandSpyPlugin.Category.MESSAGES;
        }
        return CommandSpyPlugin.Category.COMMANDS;
    }

    private static boolean isSensitive(String command) {
        List<String> parts = split(command);
        return !parts.isEmpty() && SENSITIVE_COMMANDS.contains(parts.get(0));
    }

    private static List<String> split(String command) {
        String value = command == null ? "" : command.trim();
        if (value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        if (value.isEmpty()) {
            return List.of();
        }

        String[] raw = value.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> result = new ArrayList<>(raw.length);
        for (String part : raw) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }
}
