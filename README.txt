CommandSpy v1.5.1

Lightweight plugin for Hytale Server v4 that listens to command execution through the server logger and shows player commands in chat to admins with the command.spy permission.

INSTALLATION
1. Copy only the file CommandSpy-1.5.1.jar into the server mods folder.
2. Restart the server.
3. Assign the permission command.spy to admins who should use command spy.
4. On first startup, the plugin automatically creates its data folder inside mods/CommandSpy/ with the following files:
   - config.json
   - spy-state.json
   - commands.log

COMMAND
- /commandspy
- alias: /cmdspy

This command toggles spy message reception for the individual admin.

PERMISSION
- command.spy

CHAT FORMAT
By default, admins will receive messages in this format:
&a[SPY] &7%player%: %command%

Available placeholders in config.json:
- %player%
- %command%

CONFIGURATION
Example config.json:
{
  "message-format": "&a[SPY] &7%player%: %command%",
  "spy-enabled-message": "&a[SPY] &7Command spy enabled.",
  "spy-disabled-message": "&c[SPY] &7Command spy disabled.",
  "default-spy-enabled": false
}

Config notes:
- message-format: format of the chat message received by admins
- spy-enabled-message: message shown when an admin enables spy
- spy-disabled-message: message shown when an admin disables spy
- default-spy-enabled: false = admins start with spy OFF by default

PERSISTENT STATE
The spy-state.json file permanently stores which admins have spy enabled.
If an admin enables /commandspy, the state remains saved even after a restart.

COMMAND LOG
The commands.log file is created in the plugin folder and stores all intercepted commands with timestamps.

Example:
[2026-03-30 07:43:57] Fandanko: sethome

TECHNICAL NOTES
- The plugin does not hook into heavy gameplay events.
- It listens to command execution through the server logger and records lines matching this format:
  Newt_Scamander executed command: commandspy
- If you modify config.json, restart the server to apply the changes.
