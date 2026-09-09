package com.oai.hytale.commandspy;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

final class CommandSpyUiSupport {
    private CommandSpyUiSupport() {
    }

    static void configure(UICommandBuilder commands, UIEventBuilder events, PlayerRef player,
                          Object stateObject, CommandSpyPlugin plugin) {
        CommandSpyPlugin.SpyState state = (CommandSpyPlugin.SpyState) stateObject;

        setSecondaryText(commands, "General");
        setSecondaryText(commands, "Messages");
        setSecondaryText(commands, "Commands");
        setSecondaryText(commands, "Console");

        pair(commands, "General", true, state.enabled());
        bind(events, "#CommandSpyMinimalGeneralOnSecondary", "general:on");
        bind(events, "#CommandSpyMinimalGeneralOffSecondary", "general:off");

        boolean messagesAllowed = allowed(player, CommandSpyPlugin.MESSAGES_PERMISSION);
        boolean commandsAllowed = allowed(player, CommandSpyPlugin.COMMANDS_PERMISSION);
        boolean consoleAllowed = allowed(player, CommandSpyConsoleSupport.PERMISSION);

        pair(commands, "Messages", messagesAllowed, state.messages());
        pair(commands, "Commands", commandsAllowed, state.commands());
        pair(commands, "Console", consoleAllowed,
                CommandSpyConsoleSupport.isEnabled(plugin, player.getUuid()));

        if (messagesAllowed) {
            bind(events, "#CommandSpyMinimalMessagesOnSecondary", "messages:on");
            bind(events, "#CommandSpyMinimalMessagesOffSecondary", "messages:off");
        }
        if (commandsAllowed) {
            bind(events, "#CommandSpyMinimalCommandsOnSecondary", "commands:on");
            bind(events, "#CommandSpyMinimalCommandsOffSecondary", "commands:off");
        }
        if (consoleAllowed) {
            bind(events, "#CommandSpyMinimalConsoleOnSecondary", "console:on");
            bind(events, "#CommandSpyMinimalConsoleOffSecondary", "console:off");
        }
    }

    private static void setSecondaryText(UICommandBuilder commands, String name) {
        commands.set("#CommandSpyMinimal" + name + "OnSecondary.Text",
                Message.translation("server.commandspy.ui.on"));
        commands.set("#CommandSpyMinimal" + name + "OffSecondary.Text",
                Message.translation("server.commandspy.ui.off"));
    }

    private static void pair(UICommandBuilder commands, String name, boolean allowed, boolean enabled) {
        String root = "#CommandSpyMinimal" + name;
        commands.set(root + "On.Visible", allowed && enabled);
        commands.set(root + "OnSecondary.Visible", allowed && !enabled);
        commands.set(root + "Off.Visible", allowed && !enabled);
        commands.set(root + "OffSecondary.Visible", allowed && enabled);
    }

    private static boolean allowed(PlayerRef player, String permission) {
        try {
            return player.hasPermission(CommandSpyPlugin.MASTER_PERMISSION) || player.hasPermission(permission);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }
}
