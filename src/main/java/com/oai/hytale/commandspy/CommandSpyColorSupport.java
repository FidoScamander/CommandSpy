package com.oai.hytale.commandspy;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

final class CommandSpyColorSupport {
    private static final String DEFAULT_COLOR = "#FFFFFF";

    private CommandSpyColorSupport() {
    }

    static Message message(PlayerRef player, String key) {
        return parse(resolve(player, key));
    }

    static Message spyMessage(PlayerRef player, String executor, String command) {
        String text = resolve(player, "commandspy.chat.spy-format")
                .replace("{player}", executor == null ? "" : executor)
                .replace("{command}", command == null ? "" : command);
        return parse(text);
    }

    private static String resolve(PlayerRef player, String key) {
        String locale = "en-US";
        try {
            if (player != null && player.getLanguage() != null && !player.getLanguage().isBlank()) {
                locale = player.getLanguage();
            }
        } catch (Throwable ignored) {
        }

        try {
            I18nModule i18n = I18nModule.get();
            String value = lookup(i18n, locale, key);
            if (value == null && !"en-US".equalsIgnoreCase(locale)) {
                value = lookup(i18n, "en-US", key);
            }
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return key;
    }

    private static String lookup(I18nModule i18n, String locale, String key) {
        if (i18n == null) {
            return null;
        }
        String value = i18n.getMessage(locale, key);
        if (value == null) {
            value = i18n.getMessage(locale, "server." + key);
        }
        return value;
    }

    private static Message parse(String input) {
        if (input == null) {
            return Message.raw("");
        }

        Message output = Message.empty();
        StringBuilder text = new StringBuilder();
        String color = DEFAULT_COLOR;
        boolean formatted = false;

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '&' && i + 1 < input.length()) {
                String nextColor = null;
                int consumed = 1;
                char code = Character.toLowerCase(input.charAt(i + 1));

                if (code == '#' && i + 7 < input.length()) {
                    String hex = input.substring(i + 2, i + 8);
                    if (hex.matches("[0-9A-Fa-f]{6}")) {
                        nextColor = "#" + hex.toUpperCase();
                        consumed = 7;
                    }
                } else {
                    nextColor = legacyColor(code);
                }

                if (nextColor != null) {
                    if (!text.isEmpty()) {
                        output.insert(Message.raw(text.toString()).color(color));
                        text.setLength(0);
                    }
                    color = nextColor;
                    formatted = true;
                    i += consumed;
                    continue;
                }
            }
            text.append(current);
        }

        if (!formatted) {
            return Message.raw(input);
        }
        if (!text.isEmpty()) {
            output.insert(Message.raw(text.toString()).color(color));
        }
        return output;
    }

    private static String legacyColor(char code) {
        return switch (code) {
            case '0' -> "#000000";
            case '1' -> "#0000AA";
            case '2' -> "#00AA00";
            case '3' -> "#00AAAA";
            case '4' -> "#AA0000";
            case '5' -> "#AA00AA";
            case '6' -> "#FFAA00";
            case '7' -> "#AAAAAA";
            case '8' -> "#555555";
            case '9' -> "#5555FF";
            case 'a' -> "#55FF55";
            case 'b' -> "#55FFFF";
            case 'c' -> "#FF5555";
            case 'd' -> "#FF55FF";
            case 'e' -> "#FFFF55";
            case 'f', 'r' -> DEFAULT_COLOR;
            default -> null;
        };
    }
}
