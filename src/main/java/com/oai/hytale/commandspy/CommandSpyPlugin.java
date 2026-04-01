package com.oai.hytale.commandspy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandSpyPlugin extends JavaPlugin {
    private static final String WATCH_PERMISSION = "command.spy";
    private static final Pattern EXECUTED_COMMAND_PATTERN = Pattern.compile("^(.+?) executed command: (.+)$");
    private static final DateTimeFormatter STORAGE_TS_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String DEFAULT_MESSAGE_FORMAT = "&a[SPY] &7%player%: %command%";
    private static final String DEFAULT_ENABLED_MESSAGE = "&a[SPY] &7Command spy enabled.";
    private static final String DEFAULT_DISABLED_MESSAGE = "&c[SPY] &7Command spy disabled.";
    private static final boolean DEFAULT_SPY_ENABLED = false;
    private static final String DEFAULT_CONFIG_JSON = "{\n"
            + "  \"message-format\": \"&a[SPY] &7%player%: %command%\",\n"
            + "  \"spy-enabled-message\": \"&a[SPY] &7Command spy enabled.\",\n"
            + "  \"spy-disabled-message\": \"&c[SPY] &7Command spy disabled.\",\n"
            + "  \"default-spy-enabled\": false\n"
            + "}\n";

    private static final Map<Character, Color> LEGACY_COLORS = createLegacyColors();

    private final ConcurrentHashMap<UUID, PlayerRef> onlinePlayers = new ConcurrentHashMap<>();
    private final Set<UUID> spyEnabled = ConcurrentHashMap.newKeySet();
    private volatile Config config = new Config(
            DEFAULT_MESSAGE_FORMAT,
            DEFAULT_ENABLED_MESSAGE,
            DEFAULT_DISABLED_MESSAGE,
            DEFAULT_SPY_ENABLED
    );
    private volatile Handler loggerHook;
    private final CopyOnWriteArrayList<LogRecord> subscribedRecords = new CopyOnWriteArrayList<>();
    private volatile Thread subscriberThread;
    private volatile boolean subscriberRunning;

    public CommandSpyPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new CommandSpyToggleCommand(this));

        getEventRegistry().registerGlobal(PlayerConnectEvent.class, event -> {
            PlayerRef playerRef = event.getPlayerRef();
            if (playerRef == null || playerRef.getUuid() == null) {
                return;
            }
            onlinePlayers.put(playerRef.getUuid(), playerRef);
        });

        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            PlayerRef playerRef = event.getPlayerRef();
            if (playerRef != null && playerRef.getUuid() != null) {
                onlinePlayers.remove(playerRef.getUuid());
            }
        });
    }

    @Override
    protected void start() {
        loadConfig();
        loadSpyState();
        touchFiles();
        installLoggerHook();
        startBackendSubscriber();
        getLogger().atInfo().log("CommandSpy enabled. Data folder: %s", getPluginDataDirectory());
    }

    @Override
    protected void shutdown() {
        stopBackendSubscriber();
        removeLoggerHook();
        saveSpyState();
        onlinePlayers.clear();
    }

    private Path getPluginDataDirectory() {
        Path pluginFile = getFile();
        Path base = pluginFile != null ? pluginFile.getParent() : getDataDirectory();
        if (base == null) {
            return Path.of("CommandSpy");
        }
        return base.resolve("CommandSpy");
    }

    private Path getConfigPath() {
        return getPluginDataDirectory().resolve("config.json");
    }

    private Path getSpyStatePath() {
        return getPluginDataDirectory().resolve("spy-state.json");
    }

    private Path getCommandLogPath() {
        return getPluginDataDirectory().resolve("commands.log");
    }

    private void ensureDataDir() throws IOException {
        Files.createDirectories(getPluginDataDirectory());
    }

    private void touchFiles() {
        try {
            ensureDataDir();
            if (!Files.exists(getCommandLogPath())) {
                Files.writeString(getCommandLogPath(), "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to initialize data files.");
        }
    }

    private void loadConfig() {
        try {
            ensureDataDir();
            Path configPath = getConfigPath();
            if (!Files.exists(configPath)) {
                Files.writeString(configPath, DEFAULT_CONFIG_JSON, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            this.config = new Config(
                    extractJsonString(raw, "message-format", DEFAULT_MESSAGE_FORMAT),
                    extractJsonString(raw, "spy-enabled-message", DEFAULT_ENABLED_MESSAGE),
                    extractJsonString(raw, "spy-disabled-message", DEFAULT_DISABLED_MESSAGE),
                    extractJsonBoolean(raw, "default-spy-enabled", DEFAULT_SPY_ENABLED)
            );
        } catch (Exception e) {
            this.config = new Config(DEFAULT_MESSAGE_FORMAT, DEFAULT_ENABLED_MESSAGE, DEFAULT_DISABLED_MESSAGE, DEFAULT_SPY_ENABLED);
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to load config, using defaults.");
        }
    }

    private void loadSpyState() {
        try {
            ensureDataDir();
            Path statePath = getSpyStatePath();
            if (!Files.exists(statePath)) {
                Files.writeString(statePath, "[]\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }

            String raw = Files.readString(statePath, StandardCharsets.UTF_8);
            spyEnabled.clear();
            Matcher matcher = Pattern.compile("\"([0-9a-fA-F-]{36})\"").matcher(raw);
            while (matcher.find()) {
                try {
                    spyEnabled.add(UUID.fromString(matcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to load spy-state.json.");
        }
    }

    private void saveSpyState() {
        try {
            ensureDataDir();
            List<String> uuids = new ArrayList<>();
            for (UUID uuid : spyEnabled) {
                uuids.add("  \"" + uuid + "\"");
            }
            uuids.sort(String::compareTo);
            String json = "[\n" + String.join(",\n", uuids) + (uuids.isEmpty() ? "" : "\n") + "]\n";
            Files.writeString(getSpyStatePath(), json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to save spy-state.json.");
        }
    }


    private void startBackendSubscriber() {
        stopBackendSubscriber();
        subscribedRecords.clear();
        subscriberRunning = true;
        try {
            com.hypixel.hytale.logger.backend.HytaleLoggerBackend.subscribe(subscribedRecords);
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to subscribe to Hytale logger backend.");
            return;
        }

        subscriberThread = new Thread(() -> {
            int index = 0;
            while (subscriberRunning) {
                try {
                    while (index < subscribedRecords.size()) {
                        LogRecord record = subscribedRecords.get(index++);
                        if (record != null) {
                            tryHandleLogRecord(record);
                        }
                    }
                    if (index > 4096) {
                        for (int i = 0; i < index && !subscribedRecords.isEmpty(); i++) {
                            subscribedRecords.remove(0);
                        }
                        index = 0;
                    }
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "CommandSpy-LogSubscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void stopBackendSubscriber() {
        subscriberRunning = false;
        try {
            com.hypixel.hytale.logger.backend.HytaleLoggerBackend.unsubscribe(subscribedRecords);
        } catch (Exception ignored) {
        }
        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
        }
        subscriberThread = null;
        subscribedRecords.clear();
    }

    private void installLoggerHook() {
        stopBackendSubscriber();
        removeLoggerHook();

        loggerHook = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || !isLoggable(record)) {
                    return;
                }
                tryHandleLogRecord(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        loggerHook.setLevel(Level.ALL);

        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        if (rootLogger != null) {
            rootLogger.addHandler(loggerHook);
            rootLogger.setLevel(Level.ALL);
        }
    }

    private void removeLoggerHook() {
        if (loggerHook == null) {
            return;
        }
        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        if (rootLogger != null) {
            rootLogger.removeHandler(loggerHook);
        }
        loggerHook = null;
    }

    private void tryHandleLogRecord(LogRecord record) {
        String loggerName = record.getLoggerName();
        String sourceClass = record.getSourceClassName();
        String message = formatLogRecord(record);

        boolean looksLikeCommandManager = (loggerName != null && loggerName.contains("CommandManager"))
                || (sourceClass != null && sourceClass.contains("CommandManager"))
                || message.contains("executed command:");
        if (!looksLikeCommandManager) {
            return;
        }

        String normalized = message.trim();
        int markerIndex = normalized.indexOf("] ");
        if (markerIndex >= 0 && normalized.contains("executed command:")) {
            normalized = normalized.substring(markerIndex + 2).trim();
        }

        Matcher matcher = EXECUTED_COMMAND_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return;
        }

        String playerName = matcher.group(1).trim();
        if (playerName.startsWith("[CommandManager]")) {
            playerName = playerName.substring("[CommandManager]".length()).trim();
        }
        String command = matcher.group(2).trim();
        if (playerName.isEmpty() || command.isEmpty()) {
            return;
        }

        String timestamp = STORAGE_TS_OUT.format(Instant.ofEpochMilli(record.getMillis()).atZone(ZoneId.systemDefault()));
        appendCommandLog(timestamp, playerName, command);
        broadcastToSpies(playerName, command);
    }

    private static String formatLogRecord(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return message;
        }
        try {
            return String.format(message, params);
        } catch (Exception ignored) {
            return message;
        }
    }

    private void appendCommandLog(String timestamp, String playerName, String command) {
        try {
            ensureDataDir();
            String line = "[" + timestamp + "] " + playerName + ": " + command + System.lineSeparator();
            Files.writeString(getCommandLogPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("CommandSpy: failed to write commands.log.");
        }
    }

    private void broadcastToSpies(String playerName, String command) {
        PermissionsModule permissions = PermissionsModule.get();
        if (permissions == null) {
            return;
        }

        Message message = fromLegacy(config.messageFormat()
                .replace("%player%", playerName)
                .replace("%command%", command));

        for (PlayerRef playerRef : onlinePlayers.values()) {
            if (playerRef == null || !playerRef.isValid() || playerRef.getUuid() == null) {
                continue;
            }
            UUID uuid = playerRef.getUuid();
            if (!spyEnabled.contains(uuid)) {
                continue;
            }
            try {
                if (permissions.hasPermission(uuid, WATCH_PERMISSION)) {
                    playerRef.sendMessage(message);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean toggleSpy(UUID uuid) {
        boolean enabled;
        if (spyEnabled.contains(uuid)) {
            spyEnabled.remove(uuid);
            enabled = false;
        } else {
            spyEnabled.add(uuid);
            enabled = true;
        }
        saveSpyState();
        return enabled;
    }

    private static String extractJsonString(String raw, String key, String fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\\\"])*)\"");
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return fallback;
        }
        return unescapeJson(matcher.group(1));
    }

    private static boolean extractJsonBoolean(String raw, String key, boolean fallback) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return fallback;
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private static String unescapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 < value.length()) {
                            String hex = value.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u').append(hex);
                                i += 4;
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(c);
                }
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
            } else {
                sb.append(c);
            }
        }
        if (escaping) {
            sb.append('\\');
        }
        return sb.toString();
    }

    private static Map<Character, Color> createLegacyColors() {
        Map<Character, Color> colors = new HashMap<>();
        colors.put('0', new Color(0x000000));
        colors.put('1', new Color(0x0000AA));
        colors.put('2', new Color(0x00AA00));
        colors.put('3', new Color(0x00AAAA));
        colors.put('4', new Color(0xAA0000));
        colors.put('5', new Color(0xAA00AA));
        colors.put('6', new Color(0xFFAA00));
        colors.put('7', new Color(0xAAAAAA));
        colors.put('8', new Color(0x555555));
        colors.put('9', new Color(0x5555FF));
        colors.put('a', new Color(0x55FF55));
        colors.put('b', new Color(0x55FFFF));
        colors.put('c', new Color(0xFF5555));
        colors.put('d', new Color(0xFF55FF));
        colors.put('e', new Color(0xFFFF55));
        colors.put('f', new Color(0xFFFFFF));
        return colors;
    }

    private static Message fromLegacy(String input) {
        if (input == null || input.isEmpty()) {
            return Message.empty();
        }

        List<Message> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Color currentColor = null;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                Color newColor = LEGACY_COLORS.get(code);
                if (newColor != null || code == 'r') {
                    if (current.length() > 0) {
                        Message part = Message.raw(current.toString());
                        if (currentColor != null) {
                            part.color(currentColor);
                        }
                        parts.add(part);
                        current.setLength(0);
                    }
                    currentColor = code == 'r' ? null : newColor;
                    i++;
                    continue;
                }
            }
            current.append(ch);
        }

        if (current.length() > 0) {
            Message part = Message.raw(current.toString());
            if (currentColor != null) {
                part.color(currentColor);
            }
            parts.add(part);
        }

        if (parts.isEmpty()) {
            return Message.raw(input.replace("&", ""));
        }
        return Message.join(parts.toArray(new Message[0]));
    }

    private static final class CommandSpyToggleCommand extends AbstractPlayerCommand {
        private final CommandSpyPlugin plugin;

        private CommandSpyToggleCommand(CommandSpyPlugin plugin) {
            super("commandspy", "Enable or disable command spy for yourself");
            this.plugin = plugin;
            this.addAliases("cmdspy");
            this.requirePermission(WATCH_PERMISSION);
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            boolean enabled = plugin.toggleSpy(playerRef.getUuid());
            if (enabled) {
                context.sendMessage(fromLegacy(plugin.config.enabledMessage()));
            } else {
                context.sendMessage(fromLegacy(plugin.config.disabledMessage()));
            }
        }
    }

    private record Config(String messageFormat, String enabledMessage, String disabledMessage, boolean defaultSpyEnabled) {
    }
}
