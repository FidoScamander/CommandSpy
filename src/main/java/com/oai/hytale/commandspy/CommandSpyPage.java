package com.oai.hytale.commandspy;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Locale;
import java.util.UUID;

final class CommandSpyPage extends InteractiveCustomUIPage<PageData> {
    private final PlayerRef owner;
    private final CommandSpyPlugin plugin;

    CommandSpyPage(PlayerRef owner, CommandSpyPlugin plugin) {
        super(owner, CustomPageLifetime.CanDismiss, PageData.CODEC);
        this.owner = owner;
        this.plugin = plugin;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events,
                      Store<EntityStore> store) {
        commands.append("Pages/CommandSpyMinimal.ui");
        setText(commands, "#CommandSpyMinimalTitle.Text", "commandspy.ui.title");
        setText(commands, "#CommandSpyMinimalSubtitle.Text", "commandspy.ui.subtitle");
        setText(commands, "#CommandSpyMinimalGeneralLabel.Text", "commandspy.ui.general");
        setText(commands, "#CommandSpyMinimalMessagesLabel.Text", "commandspy.ui.messages");
        setText(commands, "#CommandSpyMinimalCommandsLabel.Text", "commandspy.ui.commands");
        setText(commands, "#CommandSpyMinimalConsoleLabel.Text", "commandspy.ui.console");
        setText(commands, "#CommandSpyMinimalGeneralOn.Text", "commandspy.ui.on");
        setText(commands, "#CommandSpyMinimalGeneralOff.Text", "commandspy.ui.off");
        setText(commands, "#CommandSpyMinimalMessagesOn.Text", "commandspy.ui.on");
        setText(commands, "#CommandSpyMinimalMessagesOff.Text", "commandspy.ui.off");
        setText(commands, "#CommandSpyMinimalCommandsOn.Text", "commandspy.ui.on");
        setText(commands, "#CommandSpyMinimalCommandsOff.Text", "commandspy.ui.off");
        setText(commands, "#CommandSpyMinimalConsoleOn.Text", "commandspy.ui.on");
        setText(commands, "#CommandSpyMinimalConsoleOff.Text", "commandspy.ui.off");
        setText(commands, "#CommandSpyMinimalPermissionHint.Text", "commandspy.ui.permission-hint");
        setText(commands, "#CommandSpyMinimalCloseHint.Text", "commandspy.ui.close-hint");

        UUID uuid = owner.getUuid();
        CommandSpyPlugin.SpyState state = plugin.getState(uuid);
        bind(events, "#CommandSpyMinimalGeneralOn", "general:on");
        bind(events, "#CommandSpyMinimalGeneralOff", "general:off");
        bindCategory(events, uuid, CommandSpyPlugin.Category.MESSAGES,
                "#CommandSpyMinimalMessagesOn", "messages:on",
                "#CommandSpyMinimalMessagesOff", "messages:off");
        bindCategory(events, uuid, CommandSpyPlugin.Category.COMMANDS,
                "#CommandSpyMinimalCommandsOn", "commands:on",
                "#CommandSpyMinimalCommandsOff", "commands:off");
        if (CommandSpyConsoleSupport.hasPermission(plugin, uuid)) {
            bind(events, "#CommandSpyMinimalConsoleOn", "console:on");
            bind(events, "#CommandSpyMinimalConsoleOff", "console:off");
        }
        CommandSpyUiSupport.configure(commands, events, owner, state, plugin);
    }

    private void bindCategory(UIEventBuilder events, UUID uuid, CommandSpyPlugin.Category category,
                              String onSelector, String onAction, String offSelector, String offAction) {
        if (!plugin.hasCategoryPermission(uuid, category)) {
            return;
        }
        bind(events, onSelector, onAction);
        bind(events, offSelector, offAction);
    }

    private static void setText(UICommandBuilder commands, String selector, String key) {
        commands.set(selector, CommandSpyPlugin.tr(key));
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageData data) {
        if (data == null || data.Action == null) {
            return;
        }
        String[] parts = data.Action.toLowerCase(Locale.ROOT).split(":", 2);
        if (parts.length != 2) {
            return;
        }
        boolean enabled = "on".equals(parts[1]);
        switch (parts[0]) {
            case "general" -> plugin.setGeneral(owner, enabled);
            case "messages" -> plugin.setFilter(owner, CommandSpyPlugin.Category.MESSAGES, enabled);
            case "commands" -> plugin.setFilter(owner, CommandSpyPlugin.Category.COMMANDS, enabled);
            case "console" -> CommandSpyConsoleSupport.set(plugin, owner, enabled);
            default -> { return; }
        }
        plugin.openGui(owner, ref, store);
    }
}

final class PageData {
    public static final BuilderCodec.Builder<PageData> BUILDER = BuilderCodec.builder(PageData.class, PageData::new);
    public static final BuilderCodec<PageData> CODEC;

    public String Action;

    static {
        BUILDER.addField(new KeyedCodec<>("Action", Codec.STRING),
                (data, action) -> data.Action = action,
                data -> data.Action);
        CODEC = BUILDER.build();
    }
}
