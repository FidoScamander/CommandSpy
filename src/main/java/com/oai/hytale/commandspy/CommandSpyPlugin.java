package com.oai.hytale.commandspy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;

public final class CommandSpyPlugin extends JavaPlugin {
    static final String MASTER_PERMISSION = "command.spy";
    static final String MESSAGES_PERMISSION = "command.spy.messages";
    static final String COMMANDS_PERMISSION = "command.spy.commands";

    final ConcurrentHashMap<UUID, PlayerRef> onlinePlayers = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, SpyState> states = new ConcurrentHashMap<>();
    final Set<UUID> initializedStates = ConcurrentHashMap.newKeySet();

    private final CopyOnWriteArrayList<LogRecord> subscribedRecords = new CopyOnWriteArrayList<>();
    volatile Config config = Config.defaults();
    private volatile boolean subscriberRunning;
    private volatile Thread subscriberThread;
    private volatile Path sessionLogPath;

    public CommandSpyPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new CommandSpyCommand(this));
        var events = getEventRegistry();
        events.registerGlobal(PlayerConnectEvent.class, this::onConnect);
        events.registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);
    }

    @Override
    protected void start() {
        loadConfig();
        loadState();
        touchFiles();
        createSessionLog();
        startSubscriber();
    }

    @Override
    protected void shutdown() {
        stopSubscriber();
        saveState();
        onlinePlayers.clear();
    }

    private void onConnect(PlayerConnectEvent event) {
        PlayerRef player = event.getPlayerRef();
        if (player == null || player.getUuid() == null) {
            return;
        }
        onlinePlayers.put(player.getUuid(), player);
        ensureState(player.getUuid());
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        PlayerRef player = event.getPlayerRef();
        if (player != null && player.getUuid() != null) {
            onlinePlayers.remove(player.getUuid());
        }
    }

    Path dataDirectory() {
        Path pluginFile = getFile();
        Path base = pluginFile == null ? getDataDirectory() : pluginFile.getParent();
        return (base == null ? Path.of("mods") : base).resolve("CommandSpy");
    }

    Path configPath() {
        return dataDirectory().resolve("config.json");
    }

    Path statePath() {
        return dataDirectory().resolve("spy-state.json");
    }

    private Path logsDirectory() {
        return dataDirectory().resolve("logs");
    }

    private void touchFiles() {
        try {
            Files.createDirectories(dataDirectory());
            Files.createDirectories(logsDirectory());
            if (!Files.exists(statePath())) {
                saveState();
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void createSessionLog() {
        sessionLogPath = CommandSpyLogRetention.initialize(configPath(), logsDirectory());
    }

    private void loadConfig() {
        CommandSpyLogRetention.capture(configPath());
        CommandSpySupport.loadConfig(this);
        CommandSpyLogRetention.restore(configPath());
    }

    private void loadState() {
        CommandSpySupport.loadState(this);
        saveState();
    }

    private synchronized void saveState() {
        CommandSpySupport.saveState(this);
    }

    private SpyState ensureState(UUID uuid) {
        return CommandSpySupport.ensureState(this, uuid);
    }

    private void startSubscriber() {
        if (subscriberRunning) {
            return;
        }
        subscriberRunning = true;
        HytaleLoggerBackend.subscribe(subscribedRecords);
        subscriberThread = new Thread(this::subscriberLoop, "CommandSpy-LogConsumer");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void stopSubscriber() {
        subscriberRunning = false;
        try {
            HytaleLoggerBackend.unsubscribe(subscribedRecords);
        } catch (Throwable ignored) {
        }

        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        subscriberThread = null;

        while (!subscribedRecords.isEmpty()) {
            LogRecord record = subscribedRecords.remove(0);
            if (record != null) {
                handleRecord(record);
            }
        }
    }

    private void subscriberLoop() {
        int index = 0;
        while (subscriberRunning) {
            try {
                while (index < subscribedRecords.size()) {
                    LogRecord record = subscribedRecords.get(index++);
                    if (record != null) {
                        handleRecord(record);
                    }
                }
                if (index > 4096) {
                    for (int i = 0; i < index && !subscribedRecords.isEmpty(); i++) {
                        subscribedRecords.remove(0);
                    }
                    index = 0;
                }
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleRecord(LogRecord record) {
        CommandSpySupport.handleRecord(this, record);
    }

    void appendLog(long millis, String executor, String command) {
        CommandSpyLogRetention.append(sessionLogPath, millis, executor, command, configPath());
    }

    void broadcast(String executor, String command, Category category) {
        if (CommandSpyConsoleSupport.broadcastIfConsole(this, executor, command)) {
            return;
        }

        for (PlayerRef recipient : onlinePlayers.values()) {
            try {
                if (recipient == null || !recipient.isValid() || recipient.getUuid() == null) {
                    continue;
                }
                UUID uuid = recipient.getUuid();
                SpyState state = getState(uuid);
                if (!state.enabled() || !hasCategoryPermission(uuid, category) || !state.filter(category)) {
                    continue;
                }
                recipient.sendMessage(CommandSpyColorSupport.spyMessage(recipient, executor, command));
            } catch (Throwable ignored) {
            }
        }
    }

    boolean hasMaster(UUID uuid) {
        return hasPermission(uuid, MASTER_PERMISSION);
    }

    boolean hasAnyPermission(UUID uuid) {
        return CommandSpySupport.hasAnyPermission(this, uuid);
    }

    boolean hasCategoryPermission(UUID uuid, Category category) {
        return CommandSpySupport.hasCategoryPermission(this, uuid, category);
    }

    boolean hasPermission(UUID uuid, String permission) {
        if (uuid == null || permission == null) {
            return false;
        }
        try {
            PermissionsModule permissions = PermissionsModule.get();
            return permissions != null && permissions.hasPermission(uuid, permission);
        } catch (Throwable ignored) {
            return false;
        }
    }

    SpyState getState(UUID uuid) {
        return ensureState(uuid);
    }

    boolean toggleGeneral(PlayerRef player) {
        if (player == null || player.getUuid() == null) {
            return false;
        }
        UUID uuid = player.getUuid();
        if (!hasAnyPermission(uuid)) {
            send(player, "commandspy.chat.no-permission");
            return false;
        }

        SpyState current = getState(uuid);
        boolean enabled = !current.enabled();
        if (enabled && !hasAnyEnabledFilter(uuid, current)) {
            current = enableAuthorizedFilters(uuid, current);
        }
        states.put(uuid, current.withEnabled(enabled));
        saveState();
        send(player, enabled ? "commandspy.chat.spy-enabled" : "commandspy.chat.spy-disabled");
        return enabled;
    }

    void setGeneral(PlayerRef player, boolean enabled) {
        if (player == null || player.getUuid() == null || !hasAnyPermission(player.getUuid())) {
            return;
        }
        UUID uuid = player.getUuid();
        SpyState current = getState(uuid);
        if (enabled && !hasAnyEnabledFilter(uuid, current)) {
            current = enableAuthorizedFilters(uuid, current);
        }
        states.put(uuid, current.withEnabled(enabled));
        saveState();
    }

    void toggleFilter(PlayerRef player, Category category) {
        if (player == null || player.getUuid() == null || category == null) {
            return;
        }
        UUID uuid = player.getUuid();
        if (!hasCategoryPermission(uuid, category)) {
            send(player, noPermissionKey(category));
            return;
        }

        SpyState current = getState(uuid);
        boolean enabled = !current.filter(category);
        states.put(uuid, current.withFilter(category, enabled));
        saveState();
        send(player, filterMessageKey(category, enabled));
    }

    void setFilter(PlayerRef player, Category category, boolean enabled) {
        if (player == null || player.getUuid() == null || category == null) {
            return;
        }
        UUID uuid = player.getUuid();
        if (!hasCategoryPermission(uuid, category)) {
            return;
        }
        states.put(uuid, getState(uuid).withFilter(category, enabled));
        saveState();
    }

    private boolean hasAnyEnabledFilter(UUID uuid, SpyState state) {
        return CommandSpySupport.hasAnyEnabledFilter(this, uuid, state);
    }

    private SpyState enableAuthorizedFilters(UUID uuid, SpyState state) {
        return CommandSpySupport.enableAuthorizedFilters(this, uuid, state);
    }

    private static String noPermissionKey(Category category) {
        return switch (category) {
            case MESSAGES -> "commandspy.chat.no-permission-messages";
            case COMMANDS -> "commandspy.chat.no-permission-commands";
        };
    }

    private static String filterMessageKey(Category category, boolean enabled) {
        return "commandspy.chat.filter-" + category.key + (enabled ? "-enabled" : "-disabled");
    }

    void reload(PlayerRef player) {
        if (player == null || player.getUuid() == null || !hasMaster(player.getUuid())) {
            if (player != null) {
                send(player, "commandspy.chat.no-permission");
            }
            return;
        }
        loadConfig();
        loadState();
        send(player, "commandspy.chat.reloaded");
    }

    void openGui(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (player == null || player.getUuid() == null || !hasAnyPermission(player.getUuid())) {
            if (player != null) {
                send(player, "commandspy.chat.no-permission");
            }
            return;
        }

        try {
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity == null) {
                send(player, "commandspy.chat.gui-open-failed");
                return;
            }
            entity.getPageManager().openCustomPage(ref, store, new CommandSpyPage(player, this));
        } catch (Exception ignored) {
            send(player, "commandspy.chat.gui-open-failed");
        }
    }

    void send(PlayerRef player, String key) {
        try {
            player.sendMessage(CommandSpyColorSupport.message(player, key));
        } catch (Exception ignored) {
        }
    }

    static Message tr(String key) {
        return Message.translation("server." + key);
    }

    enum Category {
        MESSAGES("messages"),
        COMMANDS("commands");

        final String key;

        Category(String key) {
            this.key = key;
        }
    }

    record Config(boolean defaultSpyEnabled, boolean logPlayerCommands,
                  boolean logPrivateMessages, boolean logConsoleCommands) {
        static Config defaults() {
            return new Config(false, true, true, true);
        }
    }

    record SpyState(boolean enabled, boolean messages, boolean commands) {
        boolean filter(Category category) {
            return category == Category.MESSAGES ? messages : commands;
        }

        SpyState withEnabled(boolean value) {
            return new SpyState(value, messages, commands);
        }

        SpyState withFilter(Category category, boolean value) {
            return category == Category.MESSAGES
                    ? new SpyState(enabled, value, commands)
                    : new SpyState(enabled, messages, value);
        }
    }

}
