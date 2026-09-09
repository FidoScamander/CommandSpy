package com.oai.hytale.commandspy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class CommandSpyCommand extends AbstractPlayerCommand {
    private final CommandSpyPlugin plugin;

    CommandSpyCommand(CommandSpyPlugin plugin) {
        super("commandspy", "Manage personal CommandSpy settings");
        this.plugin = plugin;
        addAliases("cmdspy");
        addSubCommand(new CommandSpySubCommand(plugin, "gui", null));
        addSubCommand(new CommandSpySubCommand(plugin, "messages", CommandSpyPlugin.Category.MESSAGES));
        addSubCommand(new CommandSpySubCommand(plugin, "commands", CommandSpyPlugin.Category.COMMANDS));
        addSubCommand(new CommandSpySubCommand(plugin, "console", null));
        addSubCommand(new CommandSpySubCommand(plugin, "reload", null));
        setPermissionGroups("hytale:Adventurer", "hytale:WorldEditor");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef player, World world) {
        if (player != null && player.getUuid() != null) {
            plugin.toggleGeneral(player);
        }
    }
}

final class CommandSpySubCommand extends AbstractPlayerCommand {
    private final CommandSpyPlugin plugin;
    private final String action;
    private final CommandSpyPlugin.Category category;

    CommandSpySubCommand(CommandSpyPlugin plugin, String action, CommandSpyPlugin.Category category) {
        super(action, description(action));
        this.plugin = plugin;
        this.action = action;
        this.category = category;
        setPermissionGroups("hytale:Adventurer", "hytale:WorldEditor");
    }

    private static String description(String action) {
        return switch (action) {
            case "gui" -> "Open the CommandSpy control panel";
            case "reload" -> "Reload CommandSpy configuration and saved states";
            case "messages" -> "Toggle private-message monitoring";
            case "commands" -> "Toggle player-command monitoring";
            case "console" -> "Toggle console-command monitoring";
            default -> "Manage CommandSpy";
        };
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef player, World world) {
        if (player == null || player.getUuid() == null) {
            return;
        }
        switch (action) {
            case "gui" -> plugin.openGui(player, ref, store);
            case "reload" -> {
                if (plugin.hasMaster(player.getUuid())) {
                    CommandSpyConsoleSupport.reload(plugin);
                }
                plugin.reload(player);
            }
            case "console" -> CommandSpyConsoleSupport.toggle(plugin, player);
            default -> plugin.toggleFilter(player, category);
        }
    }
}
